import request from 'supertest';

import { createApp } from '../../src/app';
import { setTransport } from '../../src/realtime/publisher';
import { MeetupModel } from '../../src/users-groups/models';
import { VoteModel } from '../../src/voting-fairness/models';
import { bearer } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import {
  MISSING_ID,
  PLACE_1,
  PLACE_2,
  PLACE_3,
  addMember,
  createGroup,
  createMeetup,
  createUserN,
  createVotingMeetup,
} from '../helpers/factories';

interface Published {
  room: string;
  event: string;
  payload: unknown;
}

let published: Published[] = [];

beforeAll(startDb);
beforeEach(() => {
  published = [];
  setTransport((room, event, payload) => published.push({ room, event, payload: JSON.parse(JSON.stringify(payload)) }));
});
afterEach(async () => {
  setTransport(null);
  await clearDb();
});
afterAll(stopDb);

// Organizer, two other participants and a member who never entered details, in a meetup in VOTING
async function setup() {
  const organizer = await createUserN(1);
  const ada = await createUserN(2);
  const grace = await createUserN(3);
  const bystander = await createUserN(4);
  const group = await createGroup(organizer.id);
  for (const user of [ada, grace, bystander]) await addMember(group.id, user.id);
  const meetup = await createVotingMeetup(group.id, organizer.id, [organizer.id, ada.id, grace.id]);
  return { organizer, ada, grace, bystander, group, meetup };
}

const app = () => request(createApp());

function vote(meetupId: string, userId: string, placeId: unknown) {
  return app().put(`/api/meetups/${meetupId}/vote`).set('Authorization', bearer(userId)).send({ placeId });
}

function lock(meetupId: string, userId: string) {
  return app().post(`/api/meetups/${meetupId}/vote/lock`).set('Authorization', bearer(userId));
}

function finalize(meetupId: string, userId: string, body?: object) {
  const req = app().post(`/api/meetups/${meetupId}/finalize`).set('Authorization', bearer(userId));
  return body === undefined ? req : req.send(body);
}

// A locked vote with an explicit weight, written directly (weights from Fair Credit are still a placeholder)
async function lockedVote(meetupId: string, userId: string, placeId: string, weight: number) {
  await VoteModel.create({ meetupId, userId, placeId, locked: true, weight });
}

// Interface PUT /api/meetups/:meetupId/vote
describe('Unmocked: PUT /api/meetups/:meetupId/vote', () => {
  // Input: participant picks a candidate
  // Expected status code: 200
  // Expected behavior: one unlocked vote is stored and vote:updated is published with the new tally
  // Expected output: the vote
  test('Creates a vote', async () => {
    const { ada, meetup } = await setup();

    const res = await vote(meetup.id, ada.id, PLACE_2);

    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ meetupId: meetup.id, userId: ada.id, placeId: PLACE_2, locked: false, weight: null });
    expect(published).toHaveLength(1);
    expect(published[0]).toMatchObject({
      room: `meetup:${meetup.id}`,
      event: 'vote:updated',
      payload: { meetupId: meetup.id, lockedCount: 0, participantCount: 3, totalWeight: 0 },
    });
  });

  // Input: participant changes their pick, then sends the same pick again
  // Expected status code: 200 each time
  // Expected behavior: the single vote is updated; the unchanged resend publishes nothing
  test('Changing a vote updates the existing vote', async () => {
    const { ada, meetup } = await setup();
    await vote(meetup.id, ada.id, PLACE_1);

    const changed = await vote(meetup.id, ada.id, PLACE_3);
    const resent = await vote(meetup.id, ada.id, PLACE_3);

    expect(changed.status).toBe(200);
    expect(resent.status).toBe(200);
    expect(await VoteModel.countDocuments({ meetupId: meetup.id })).toBe(1);
    expect((await VoteModel.findOne({ meetupId: meetup.id }).orFail()).placeId).toBe(PLACE_3);
    expect(published).toHaveLength(2);
  });

  // Input: placeId that is not a candidate; missing placeId; malformed meetup id
  // Expected status code: 400
  test.each([
    ['unknown place', 'place-9', undefined],
    ['missing placeId', undefined, undefined],
    ['malformed meetup id', PLACE_1, 'not-an-id'],
  ])('Rejects %s', async (_label, placeId, meetupIdOverride) => {
    const { ada, meetup } = await setup();

    expect((await vote(meetupIdOverride ?? meetup.id, ada.id, placeId)).status).toBe(400);
    expect(published).toHaveLength(0);
  });

  // Input: participant whose vote is already locked
  // Expected status code: 409
  // Expected behavior: the locked vote is unchanged
  test('Locked vote cannot change', async () => {
    const { ada, meetup } = await setup();
    await vote(meetup.id, ada.id, PLACE_1);
    const locked = await lock(meetup.id, ada.id);
    expect(locked.body).toMatchObject({ locked: true });

    const res = await vote(meetup.id, ada.id, PLACE_2);

    expect(res.status).toBe(409);
    expect((await VoteModel.findOne({ meetupId: meetup.id }).orFail()).placeId).toBe(PLACE_1);
  });

  // Input: meetup still in SETUP
  // Expected status code: 409
  test('Voting not open', async () => {
    const { organizer, group } = await setup();
    const meetup = await createMeetup(group.id, organizer.id);

    expect((await vote(meetup.id, organizer.id, PLACE_1)).status).toBe(409);
  });

  // Input: group member who is not a participant; outsider; missing meetup
  // Expected status code: 403; 403; 404
  test('Non-participant, outsider and missing meetup', async () => {
    const { bystander, meetup } = await setup();
    const outsider = await createUserN(9);

    expect((await vote(meetup.id, bystander.id, PLACE_1)).status).toBe(403);
    expect((await vote(meetup.id, outsider.id, PLACE_1)).status).toBe(403);
    expect((await vote(MISSING_ID, outsider.id, PLACE_1)).status).toBe(404);
  });

  // Input: no Authorization header
  // Expected status code: 401
  test('Unauthenticated', async () => {
    expect((await app().put(`/api/meetups/${MISSING_ID}/vote`).send({ placeId: PLACE_1 })).status).toBe(401);
  });
});

// Interface POST /api/meetups/:meetupId/vote/lock
describe('Unmocked: POST /api/meetups/:meetupId/vote/lock', () => {
  // Input: participant with an unlocked vote
  // Expected status code: 200
  // Expected behavior: the vote is locked with its Fair Credit weight and the tally counts it
  // Expected output: the locked vote
  test('Locks the vote with its weight', async () => {
    const { ada, meetup } = await setup();
    await vote(meetup.id, ada.id, PLACE_2);
    published = [];

    const res = await lock(meetup.id, ada.id);

    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ placeId: PLACE_2, locked: true, weight: 1 });
    expect(published[0]?.payload).toMatchObject({ lockedCount: 1, totalWeight: 1 });
  });

  // Input: participant without a vote; then locking twice
  // Expected status code: 404; 409
  test('No vote, then already locked', async () => {
    const { ada, meetup } = await setup();

    expect((await lock(meetup.id, ada.id)).status).toBe(404);
    await vote(meetup.id, ada.id, PLACE_1);
    expect((await lock(meetup.id, ada.id)).status).toBe(200);
    expect((await lock(meetup.id, ada.id)).status).toBe(409);
  });

  // Input: meetup still in SETUP; non-participant
  // Expected status code: 409; 403
  test('Voting not open and non-participant', async () => {
    const { organizer, bystander, group, meetup } = await setup();
    const setupMeetup = await createMeetup(group.id, organizer.id);

    expect((await lock(setupMeetup.id, organizer.id)).status).toBe(409);
    expect((await lock(meetup.id, bystander.id)).status).toBe(403);
  });
});

// Interface GET /api/meetups/:meetupId/votes
describe('Unmocked: GET /api/meetups/:meetupId/votes', () => {
  // Input: one locked and one unlocked vote, requested by a member who has not voted
  // Expected status code: 200
  // Expected output: tally per candidate (locked weight, share, all picks) and the caller's vote (null)
  test('Returns the tally and the caller vote', async () => {
    const { ada, grace, bystander, meetup } = await setup();
    await lockedVote(meetup.id, ada.id, PLACE_1, 2);
    await vote(meetup.id, grace.id, PLACE_1);

    const res = await app().get(`/api/meetups/${meetup.id}/votes`).set('Authorization', bearer(bystander.id));

    expect(res.status).toBe(200);
    expect(res.body).toEqual({
      meetupId: meetup.id,
      candidates: [
        { placeId: PLACE_1, voteCount: 2, weight: 2, share: 1 },
        { placeId: PLACE_2, voteCount: 0, weight: 0, share: 0 },
        { placeId: PLACE_3, voteCount: 0, weight: 0, share: 0 },
      ],
      totalWeight: 2,
      lockedCount: 1,
      participantCount: 3,
      myVote: null,
    });
  });

  // Input: a locked vote stored without a weight snapshot, requested by a member
  // Expected status code: 200
  // Expected output: the vote counts as a pick and as locked, but adds no weight
  test('Locked vote without a weight adds no weight', async () => {
    const { ada, bystander, meetup } = await setup();
    await VoteModel.create({ meetupId: meetup.id, userId: ada.id, placeId: PLACE_2, locked: true });

    const res = await app().get(`/api/meetups/${meetup.id}/votes`).set('Authorization', bearer(bystander.id));

    expect(res.status).toBe(200);
    expect(res.body.candidates[1]).toEqual({ placeId: PLACE_2, voteCount: 1, weight: 0, share: 0 });
    expect(res.body).toMatchObject({ totalWeight: 0, lockedCount: 1 });
  });

  // Input: participant who voted
  // Expected output: myVote is their vote
  test('Includes the caller vote', async () => {
    const { ada, meetup } = await setup();
    await vote(meetup.id, ada.id, PLACE_3);

    const res = await app().get(`/api/meetups/${meetup.id}/votes`).set('Authorization', bearer(ada.id));

    expect(res.body.myVote).toMatchObject({ placeId: PLACE_3, locked: false });
  });

  // Input: outsider; meetup without recommendations
  // Expected status code: 403; 404
  test('Outsider and no recommendations', async () => {
    const { organizer, group, meetup } = await setup();
    const outsider = await createUserN(9);
    const setupMeetup = await createMeetup(group.id, organizer.id);

    expect((await app().get(`/api/meetups/${meetup.id}/votes`).set('Authorization', bearer(outsider.id))).status).toBe(403);
    expect((await app().get(`/api/meetups/${setupMeetup.id}/votes`).set('Authorization', bearer(organizer.id))).status)
      .toBe(404);
  });
});

// Interface POST /api/meetups/:meetupId/finalize
describe('Unmocked: POST /api/meetups/:meetupId/finalize', () => {
  // Input: locked votes where one candidate has the highest weight; an unlocked pick elsewhere
  // Expected status code: 200
  // Expected behavior: the meetup is FINALIZED with that winner, unlocked picks are ignored,
  //                    meetup:status is published with the winner
  // Expected output: the meetup with its winner
  test('Finalizes with the weighted winner', async () => {
    const { organizer, ada, grace, meetup } = await setup();
    await lockedVote(meetup.id, ada.id, PLACE_2, 1.5);
    await lockedVote(meetup.id, organizer.id, PLACE_1, 1);
    await vote(meetup.id, grace.id, PLACE_1);
    published = [];

    const res = await finalize(meetup.id, organizer.id);

    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ status: 'FINALIZED', winner: { placeId: PLACE_2, name: 'Loafe Cafe' } });
    expect(published).toEqual([{
      room: `meetup:${meetup.id}`,
      event: 'meetup:status',
      payload: { meetupId: meetup.id, status: 'FINALIZED', winner: expect.objectContaining({ placeId: PLACE_2 }) },
    }]);
  });

  // Input: equal totals; one side has the single heaviest voter
  // Expected status code: 200
  // Expected output: that side wins
  test('Tie broken by highest individual weight', async () => {
    const { organizer, ada, grace, meetup } = await setup();
    await lockedVote(meetup.id, ada.id, PLACE_1, 1);
    await lockedVote(meetup.id, grace.id, PLACE_1, 1);
    await lockedVote(meetup.id, organizer.id, PLACE_3, 2);

    const res = await finalize(meetup.id, organizer.id);

    expect(res.body.winner.placeId).toBe(PLACE_3);
  });

  // Input: unbreakable tie, then the organizer picks a non-tied and a tied candidate
  // Expected status code: 409, 409, then 200
  test('Unbreakable tie needs a manual pick', async () => {
    const { organizer, ada, meetup } = await setup();
    await lockedVote(meetup.id, ada.id, PLACE_1, 1);
    await lockedVote(meetup.id, organizer.id, PLACE_2, 1);

    expect((await finalize(meetup.id, organizer.id)).status).toBe(409);
    expect((await finalize(meetup.id, organizer.id, { placeId: PLACE_3 })).status).toBe(409);
    const picked = await finalize(meetup.id, organizer.id, { placeId: PLACE_2 });
    expect(picked.status).toBe(200);
    expect(picked.body.winner.placeId).toBe(PLACE_2);
  });

  // Input: no locked votes, without and with a manual pick
  // Expected status code: 409, then 200
  test('No locked votes require a manual pick', async () => {
    const { organizer, ada, meetup } = await setup();
    await vote(meetup.id, ada.id, PLACE_1);

    expect((await finalize(meetup.id, organizer.id)).status).toBe(409);
    expect((await finalize(meetup.id, organizer.id, { placeId: PLACE_3 })).body.winner.placeId).toBe(PLACE_3);
  });

  // Input: clear winner, but the organizer names a different one; then a place that is not a candidate
  // Expected status code: 409; 400
  test('Rejects invalid manual picks', async () => {
    const { organizer, ada, meetup } = await setup();
    await lockedVote(meetup.id, ada.id, PLACE_1, 1);

    expect((await finalize(meetup.id, organizer.id, { placeId: PLACE_2 })).status).toBe(409);
    expect((await finalize(meetup.id, organizer.id, { placeId: 'place-9' })).status).toBe(400);
  });

  // Input: participant who is not the organizer; then finalizing twice
  // Expected status code: 403; 200 then 409
  test('Only the organizer, only once', async () => {
    const { organizer, ada, meetup } = await setup();
    await lockedVote(meetup.id, ada.id, PLACE_1, 1);

    expect((await finalize(meetup.id, ada.id)).status).toBe(403);
    expect((await finalize(meetup.id, organizer.id)).status).toBe(200);
    expect((await finalize(meetup.id, organizer.id)).status).toBe(409);
    expect((await MeetupModel.findById(meetup.id).orFail()).winner?.placeId).toBe(PLACE_1);
  });
});
