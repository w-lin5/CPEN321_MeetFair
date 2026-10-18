import request from 'supertest';

import { createApp } from '../../src/app';
import { setTransport } from '../../src/realtime/broadcaster';
import { MeetupModel } from '../../src/users-groups/models';
import { VoteModel, VotingSessionModel } from '../../src/voting/models';
import { bearer } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import {
  CANDIDATES,
  MISSING_ID,
  addMember,
  candidateIds,
  createGroup,
  createMeetup,
  createUserN,
  createVotingSession,
} from '../helpers/factories';

beforeAll(startDb);
afterEach(async () => {
  setTransport(null);
  await clearDb();
});
afterAll(stopDb);

// Organizer, two members, an outsider; one meetup in SETUP owned by the organizer
async function setup() {
  const organizer = await createUserN(1);
  const member = await createUserN(2);
  const member2 = await createUserN(3);
  const outsider = await createUserN(4);
  const group = await createGroup(organizer.id);
  await addMember(group.id, member.id);
  await addMember(group.id, member2.id);
  const meetup = await createMeetup(group.id, organizer.id);
  return { organizer, member, member2, outsider, group, meetup };
}

// setup() plus an open voting session on the meetup
async function setupVoting() {
  const ctx = await setup();
  const session = await createVotingSession(ctx.meetup.id, ctx.organizer.id);
  return { ...ctx, session, ids: candidateIds(session) };
}

function vote(sessionId: string, userId: string, candidateId: unknown) {
  return request(createApp())
    .post(`/api/voting-sessions/${sessionId}/vote`)
    .set('Authorization', bearer(userId))
    .send({ candidateId });
}

function close(sessionId: string, userId: string, body: object = {}) {
  return request(createApp())
    .patch(`/api/voting-sessions/${sessionId}/close`)
    .set('Authorization', bearer(userId))
    .send(body);
}

// Interface POST /api/meetups/:meetupId/voting-session
describe('Unmocked: POST /api/meetups/:meetupId/voting-session', () => {
  // Input: three candidates from the meetup organizer while the meetup is in SETUP
  // Expected status code: 201
  // Expected behavior: session stored as open; meetup moves to VOTING
  // Expected output: session with id, meetupId, createdBy, status "open", candidates each with an id, name and address
  test('Organizer starts voting', async () => {
    const { organizer, meetup } = await setup();

    const res = await request(createApp())
      .post(`/api/meetups/${meetup.id}/voting-session`)
      .set('Authorization', bearer(organizer.id))
      .send({ candidates: CANDIDATES });

    expect(res.status).toBe(201);
    expect(res.body).toMatchObject({
      meetupId: meetup.id,
      createdBy: organizer.id,
      status: 'open',
      winnerCandidateId: null,
    });
    expect(res.body.candidates).toHaveLength(3);
    expect(res.body.candidates[0]).toMatchObject({
      id: expect.stringMatching(/^[0-9a-f]{24}$/),
      placeId: 'place-1',
      name: 'Koerner Library',
      address: '1958 Main Mall',
      location: { lat: 49.2667, lng: -123.2551 },
    });
    expect((await MeetupModel.findById(meetup.id))?.status).toBe('VOTING');
  });

  // Input: valid body from a group member who is not the meetup organizer
  // Expected status code: 403
  // Expected behavior: nothing stored; meetup stays in SETUP
  test('Non-organizer member', async () => {
    const { member, meetup } = await setup();

    const res = await request(createApp())
      .post(`/api/meetups/${meetup.id}/voting-session`)
      .set('Authorization', bearer(member.id))
      .send({ candidates: CANDIDATES });

    expect(res.status).toBe(403);
    expect(await VotingSessionModel.countDocuments()).toBe(0);
    expect((await MeetupModel.findById(meetup.id))?.status).toBe('SETUP');
  });

  // Input: valid body from a user outside the group
  // Expected status code: 403
  test('Outsider', async () => {
    const { outsider, meetup } = await setup();

    const res = await request(createApp())
      .post(`/api/meetups/${meetup.id}/voting-session`)
      .set('Authorization', bearer(outsider.id))
      .send({ candidates: CANDIDATES });

    expect(res.status).toBe(403);
  });

  // Input: valid body, but voting already started for this meetup
  // Expected status code: 409
  // Expected behavior: still only one session
  test('Meetup no longer in SETUP', async () => {
    const { organizer, meetup } = await setupVoting();

    const res = await request(createApp())
      .post(`/api/meetups/${meetup.id}/voting-session`)
      .set('Authorization', bearer(organizer.id))
      .send({ candidates: CANDIDATES });

    expect(res.status).toBe(409);
    expect(await VotingSessionModel.countDocuments()).toBe(1);
  });

  // Input: meetup id that does not exist
  // Expected status code: 404
  test('Missing meetup', async () => {
    const { organizer } = await setup();

    const res = await request(createApp())
      .post(`/api/meetups/${MISSING_ID}/voting-session`)
      .set('Authorization', bearer(organizer.id))
      .send({ candidates: CANDIDATES });

    expect(res.status).toBe(404);
  });

  // Input: wrong candidate count, duplicate place, missing name, out-of-range coordinates, non-object items
  // Expected status code: 400
  // Expected behavior: nothing stored; meetup stays in SETUP
  test.each([
    ['no candidates', {}],
    ['two candidates', { candidates: CANDIDATES.slice(0, 2) }],
    ['four candidates', { candidates: [...CANDIDATES, { ...CANDIDATES[0], placeId: 'place-4' }] }],
    ['duplicate placeId', { candidates: [CANDIDATES[0], CANDIDATES[0], CANDIDATES[1]] }],
    ['missing name', { candidates: [{ ...CANDIDATES[0], name: '' }, CANDIDATES[1], CANDIDATES[2]] }],
    ['bad latitude', { candidates: [{ ...CANDIDATES[0], location: { lat: 91, lng: 0 } }, CANDIDATES[1], CANDIDATES[2]] }],
    ['non-object item', { candidates: ['a', 'b', 'c'] }],
  ])('Invalid body: %s', async (_label, body) => {
    const { organizer, meetup } = await setup();

    const res = await request(createApp())
      .post(`/api/meetups/${meetup.id}/voting-session`)
      .set('Authorization', bearer(organizer.id))
      .send(body);

    expect(res.status).toBe(400);
    expect(await VotingSessionModel.countDocuments()).toBe(0);
    expect((await MeetupModel.findById(meetup.id))?.status).toBe('SETUP');
  });

  // Input: no Authorization header
  // Expected status code: 401
  test('Unauthenticated', async () => {
    const { meetup } = await setup();

    const res = await request(createApp())
      .post(`/api/meetups/${meetup.id}/voting-session`)
      .send({ candidates: CANDIDATES });

    expect(res.status).toBe(401);
  });
});

// Interface GET /api/meetups/:meetupId/voting-session
describe('Unmocked: GET /api/meetups/:meetupId/voting-session', () => {
  // Input: group member, after voting started and after they voted
  // Expected status code: 200
  // Expected output: the session with myCandidateId set to the caller's choice
  test('Member loads the session for a meetup', async () => {
    const { member, meetup, session, ids } = await setupVoting();
    await vote(session.id, member.id, ids[1]);

    const res = await request(createApp())
      .get(`/api/meetups/${meetup.id}/voting-session`)
      .set('Authorization', bearer(member.id));

    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ id: session.id, status: 'open', myCandidateId: ids[1] });
  });

  // Input: meetup still in SETUP
  // Expected status code: 404
  test('Voting not started', async () => {
    const { member, meetup } = await setup();

    const res = await request(createApp())
      .get(`/api/meetups/${meetup.id}/voting-session`)
      .set('Authorization', bearer(member.id));

    expect(res.status).toBe(404);
  });

  // Input: user outside the group
  // Expected status code: 403
  test('Outsider', async () => {
    const { outsider, meetup } = await setupVoting();

    const res = await request(createApp())
      .get(`/api/meetups/${meetup.id}/voting-session`)
      .set('Authorization', bearer(outsider.id));

    expect(res.status).toBe(403);
  });
});

// Interface GET /api/voting-sessions/:sessionId
describe('Unmocked: GET /api/voting-sessions/:sessionId', () => {
  // Input: group member who has not voted
  // Expected status code: 200
  // Expected output: session with candidates and myCandidateId null
  test('Member loads the session', async () => {
    const { member, session } = await setupVoting();

    const res = await request(createApp())
      .get(`/api/voting-sessions/${session.id}`)
      .set('Authorization', bearer(member.id));

    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ id: session.id, myCandidateId: null });
    expect(res.body.candidates.map((c: { name: string }) => c.name)).toEqual(CANDIDATES.map((c) => c.name));
  });

  // Input: outsider / missing session / malformed id
  // Expected status code: 403 / 404 / 400
  test('Outsider, missing and malformed ids', async () => {
    const { outsider, member, session } = await setupVoting();
    const app = createApp();

    const forbidden = await request(app).get(`/api/voting-sessions/${session.id}`).set('Authorization', bearer(outsider.id));
    const missing = await request(app).get(`/api/voting-sessions/${MISSING_ID}`).set('Authorization', bearer(member.id));
    const malformed = await request(app).get('/api/voting-sessions/not-an-id').set('Authorization', bearer(member.id));

    expect(forbidden.status).toBe(403);
    expect(missing.status).toBe(404);
    expect(malformed.status).toBe(400);
  });
});

// Interface POST /api/voting-sessions/:sessionId/vote
describe('Unmocked: POST /api/voting-sessions/:sessionId/vote', () => {
  // Input: member votes for a candidate in an open session
  // Expected status code: 200
  // Expected behavior: one vote stored with the placeholder Fair Credit weight
  // Expected output: the vote
  test('Creates a vote', async () => {
    const { member, session, ids } = await setupVoting();

    const res = await vote(session.id, member.id, ids[0]);

    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ sessionId: session.id, userId: member.id, candidateId: ids[0], weight: 1 });
    expect(await VoteModel.countDocuments()).toBe(1);
  });

  // Input: same member votes twice for different candidates
  // Expected status code: 200
  // Expected behavior: the existing vote is updated, never duplicated
  test('Changing a vote updates the existing vote', async () => {
    const { member, session, ids } = await setupVoting();

    const first = await vote(session.id, member.id, ids[0]);
    const second = await vote(session.id, member.id, ids[2]);

    expect(second.status).toBe(200);
    expect(second.body.id).toBe(first.body.id);
    expect(second.body.candidateId).toBe(ids[2]);
    expect(await VoteModel.countDocuments()).toBe(1);
  });

  // Input: candidate id that is valid but not in this session / not an ObjectId / missing
  // Expected status code: 400
  // Expected behavior: no vote stored
  test.each([
    ['foreign candidate', MISSING_ID],
    ['malformed candidate', 'abc'],
    ['missing candidate', undefined],
  ])('Rejects %s', async (_label, candidateId) => {
    const { member, session } = await setupVoting();

    const res = await vote(session.id, member.id, candidateId);

    expect(res.status).toBe(400);
    expect(await VoteModel.countDocuments()).toBe(0);
  });

  // Input: vote on a closed session
  // Expected status code: 409
  test('Closed session', async () => {
    const { member, session, ids } = await setupVoting();
    await VotingSessionModel.updateOne({ _id: session.id }, { $set: { status: 'closed' } });

    const res = await vote(session.id, member.id, ids[0]);

    expect(res.status).toBe(409);
    expect(res.body).toEqual({ error: 'Voting session is closed' });
  });

  // Input: outsider / missing session
  // Expected status code: 403 / 404
  test('Outsider and missing session', async () => {
    const { outsider, member, session, ids } = await setupVoting();

    expect((await vote(session.id, outsider.id, ids[0])).status).toBe(403);
    expect((await vote(MISSING_ID, member.id, ids[0])).status).toBe(404);
    expect(await VoteModel.countDocuments()).toBe(0);
  });
});

// Interface GET /api/voting-sessions/:sessionId/votes
describe('Unmocked: GET /api/voting-sessions/:sessionId/votes', () => {
  // Input: two members voted for the first candidate, the organizer for the third
  // Expected status code: 200
  // Expected output: per-candidate raw count and weighted total, voter and eligible voter counts
  test('Returns totals for every candidate', async () => {
    const { organizer, member, member2, session, ids } = await setupVoting();
    await vote(session.id, member.id, ids[0]);
    await vote(session.id, member2.id, ids[0]);
    await vote(session.id, organizer.id, ids[2]);

    const res = await request(createApp())
      .get(`/api/voting-sessions/${session.id}/votes`)
      .set('Authorization', bearer(member.id));

    expect(res.status).toBe(200);
    expect(res.body).toEqual({
      sessionId: session.id,
      status: 'open',
      winnerCandidateId: null,
      voterCount: 3,
      eligibleVoterCount: 3,
      totals: [
        { candidateId: ids[0], voteCount: 2, totalWeight: 2 },
        { candidateId: ids[1], voteCount: 0, totalWeight: 0 },
        { candidateId: ids[2], voteCount: 1, totalWeight: 1 },
      ],
    });
  });

  // Input: votes with different stored weights
  // Expected output: totalWeight sums weights while voteCount counts voters
  test('Weighted totals use each vote weight', async () => {
    const { organizer, member, session, ids } = await setupVoting();
    await VoteModel.create({ sessionId: session.id, userId: member.id, candidateId: ids[1], weight: 2.5 });
    await VoteModel.create({ sessionId: session.id, userId: organizer.id, candidateId: ids[1], weight: 0.5 });

    const res = await request(createApp())
      .get(`/api/voting-sessions/${session.id}/votes`)
      .set('Authorization', bearer(member.id));

    expect(res.body.totals[1]).toEqual({ candidateId: ids[1], voteCount: 2, totalWeight: 3 });
  });

  // Input: outsider
  // Expected status code: 403
  test('Outsider', async () => {
    const { outsider, session } = await setupVoting();

    const res = await request(createApp())
      .get(`/api/voting-sessions/${session.id}/votes`)
      .set('Authorization', bearer(outsider.id));

    expect(res.status).toBe(403);
  });
});

// Interface PATCH /api/voting-sessions/:sessionId/close
describe('Unmocked: PATCH /api/voting-sessions/:sessionId/close', () => {
  // Input: creator closes after a clear weighted majority
  // Expected status code: 200
  // Expected behavior: session closed with the winner recorded; meetup moves to FINALIZED
  test('Closes with the weighted winner and finalizes the meetup', async () => {
    const { organizer, member, member2, meetup, session, ids } = await setupVoting();
    await vote(session.id, member.id, ids[1]);
    await vote(session.id, member2.id, ids[1]);
    await vote(session.id, organizer.id, ids[0]);

    const res = await close(session.id, organizer.id);

    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ status: 'closed', winnerCandidateId: ids[1], myCandidateId: ids[0] });
    expect((await MeetupModel.findById(meetup.id))?.status).toBe('FINALIZED');
  });

  // Input: tie on weighted total; one side has a voter with a higher individual weight
  // Expected behavior: that side wins without asking the organizer
  test('Tie broken by highest individual weight', async () => {
    const { organizer, member, member2, session, ids } = await setupVoting();
    await VoteModel.create({ sessionId: session.id, userId: member.id, candidateId: ids[0], weight: 1 });
    await VoteModel.create({ sessionId: session.id, userId: member2.id, candidateId: ids[0], weight: 1 });
    await VoteModel.create({ sessionId: session.id, userId: organizer.id, candidateId: ids[2], weight: 2 });

    const res = await close(session.id, organizer.id);

    expect(res.status).toBe(200);
    expect(res.body.winnerCandidateId).toBe(ids[2]);
  });

  // Input: an unbreakable tie, first without a pick, then with a pick among the tied candidates
  // Expected status code: 409, then 200
  // Expected behavior: the organizer's pick decides
  test('Unbreakable tie needs a manual pick', async () => {
    const { organizer, member, session, ids } = await setupVoting();
    await vote(session.id, member.id, ids[0]);
    await vote(session.id, organizer.id, ids[1]);

    const blocked = await close(session.id, organizer.id);
    const outsideTie = await close(session.id, organizer.id, { winnerCandidateId: ids[2] });
    const picked = await close(session.id, organizer.id, { winnerCandidateId: ids[1] });

    expect(blocked.status).toBe(409);
    expect(blocked.body.error).toContain('Tie');
    expect(outsideTie.status).toBe(409);
    expect(picked.status).toBe(200);
    expect(picked.body.winnerCandidateId).toBe(ids[1]);
  });

  // Input: no votes at all, first without a pick, then with one
  // Expected status code: 409, then 200
  test('Zero votes require a manual pick', async () => {
    const { organizer, session, ids } = await setupVoting();

    const blocked = await close(session.id, organizer.id);
    const picked = await close(session.id, organizer.id, { winnerCandidateId: ids[2] });

    expect(blocked.status).toBe(409);
    expect(blocked.body.error).toContain('zero votes');
    expect(picked.status).toBe(200);
    expect(picked.body.winnerCandidateId).toBe(ids[2]);
  });

  // Input: a pick that would overrule a clear winner / a pick from another session / malformed pick
  // Expected status code: 409 / 400 / 400
  // Expected behavior: session stays open
  test('Rejects invalid manual picks', async () => {
    const { organizer, member, session, ids } = await setupVoting();
    await vote(session.id, member.id, ids[0]);

    expect((await close(session.id, organizer.id, { winnerCandidateId: ids[1] })).status).toBe(409);
    expect((await close(session.id, organizer.id, { winnerCandidateId: MISSING_ID })).status).toBe(400);
    expect((await close(session.id, organizer.id, { winnerCandidateId: 'x' })).status).toBe(400);
    expect((await VotingSessionModel.findById(session.id))?.status).toBe('open');
  });

  // Input: a member who did not create the session
  // Expected status code: 403
  test('Only the creator can close', async () => {
    const { member, session, ids } = await setupVoting();
    await vote(session.id, member.id, ids[0]);

    const res = await close(session.id, member.id);

    expect(res.status).toBe(403);
    expect((await VotingSessionModel.findById(session.id))?.status).toBe('open');
  });

  // Input: closing twice, then voting
  // Expected status code: 409 for both
  test('Already closed', async () => {
    const { organizer, member, session, ids } = await setupVoting();
    await vote(session.id, member.id, ids[0]);
    await close(session.id, organizer.id);

    expect((await close(session.id, organizer.id)).status).toBe(409);
    expect((await vote(session.id, member.id, ids[1])).status).toBe(409);
  });
});

// Interface: live updates published by the voting service
describe('Unmocked: voting broadcasts', () => {
  // Input: create a vote, repeat the same vote, change it, then close the session
  // Expected behavior: results are published only for the create and the change (not the repeat);
  //                    closing publishes one "closed" event carrying the winner
  test('Publishes only when a vote is created or changed', async () => {
    const { organizer, member, session, ids } = await setupVoting();
    // Round-trip through JSON like Socket.IO does on the wire
    const published: { room: string; event: string; payload: unknown }[] = [];
    setTransport((room, event, payload) => published.push({ room, event, payload: JSON.parse(JSON.stringify(payload)) }));

    await vote(session.id, member.id, ids[0]);
    await vote(session.id, member.id, ids[0]);
    await vote(session.id, member.id, ids[1]);
    await close(session.id, organizer.id);

    expect(published.map((p) => p.event)).toEqual(['voting:results', 'voting:results', 'voting:closed']);
    expect(published.every((p) => p.room === `voting:${session.id}`)).toBe(true);
    expect(published[1]?.payload).toMatchObject({
      voterCount: 1,
      totals: [
        { candidateId: ids[0], voteCount: 0 },
        { candidateId: ids[1], voteCount: 1 },
        { candidateId: ids[2], voteCount: 0 },
      ],
    });
    expect(published[2]?.payload).toMatchObject({
      session: { status: 'closed', winnerCandidateId: ids[1] },
      results: { status: 'closed', winnerCandidateId: ids[1] },
    });
  });

  // Input: rejected votes (closed session, foreign candidate)
  // Expected behavior: nothing is published
  test('Rejected votes publish nothing', async () => {
    const { member, session } = await setupVoting();
    const publish = jest.fn();
    setTransport(publish);

    await vote(session.id, member.id, MISSING_ID);

    expect(publish).not.toHaveBeenCalled();
  });
});
