import request from 'supertest';

import { createApp } from '../../src/app';
import { MeetupModel } from '../../src/users-groups/models';
import { VoteModel, VotingSessionModel } from '../../src/voting-fairness/models';
import { fairCreditService } from '../../src/voting-fairness/fair-credit.service';
import { bearer } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import {
  CANDIDATES,
  addMember,
  candidateIds,
  createGroup,
  createMeetup,
  createUserN,
  createVotingSession,
} from '../helpers/factories';

beforeAll(startDb);
afterEach(async () => {
  jest.restoreAllMocks();
  await clearDb();
});
afterAll(stopDb);

// Silences the error handler's logging for tests that expect a 500
function quietConsole() {
  jest.spyOn(console, 'error').mockImplementation(() => undefined);
}

async function setup() {
  const organizer = await createUserN(1);
  const member = await createUserN(2);
  const group = await createGroup(organizer.id);
  await addMember(group.id, member.id);
  const meetup = await createMeetup(group.id, organizer.id);
  return { organizer, member, meetup };
}

async function setupVoting() {
  const ctx = await setup();
  const session = await createVotingSession(ctx.meetup.id, ctx.organizer.id);
  return { ...ctx, session, ids: candidateIds(session) };
}

// Interface POST /api/meetups/:meetupId/voting-session
describe('Mocked: POST /api/meetups/:meetupId/voting-session', () => {
  // Input: valid body from the organizer
  // Expected status code: 500
  // Expected behavior: the meetup is moved back to SETUP so voting can be retried
  // Mocked behavior: VotingSessionModel.create rejects
  test('Database failure while saving rolls the meetup back to SETUP', async () => {
    quietConsole();
    const { organizer, meetup } = await setup();
    jest.spyOn(VotingSessionModel, 'create').mockRejectedValue(new Error('db down') as never);

    const res = await request(createApp())
      .post(`/api/meetups/${meetup.id}/voting-session`)
      .set('Authorization', bearer(organizer.id))
      .send({ candidates: CANDIDATES });

    expect(res.status).toBe(500);
    expect((await MeetupModel.findById(meetup.id))?.status).toBe('SETUP');
  });

  // Input: valid body from the organizer
  // Expected status code: 409
  // Expected behavior: meetup rolled back to SETUP
  // Mocked behavior: VotingSessionModel.create rejects with a duplicate-key error (a stale session exists)
  test('Duplicate session', async () => {
    const { organizer, meetup } = await setup();
    jest.spyOn(VotingSessionModel, 'create').mockRejectedValue(Object.assign(new Error('dup'), { code: 11000 }) as never);

    const res = await request(createApp())
      .post(`/api/meetups/${meetup.id}/voting-session`)
      .set('Authorization', bearer(organizer.id))
      .send({ candidates: CANDIDATES });

    expect(res.status).toBe(409);
    expect(res.body).toEqual({ error: 'This meetup already has a voting session' });
    expect((await MeetupModel.findById(meetup.id))?.status).toBe('SETUP');
  });
});

// Interface POST /api/voting-sessions/:sessionId/vote
describe('Mocked: POST /api/voting-sessions/:sessionId/vote', () => {
  // Input: valid vote
  // Expected status code: 500
  // Mocked behavior: VoteModel.findOneAndUpdate throws
  test('Database failure while saving the vote', async () => {
    quietConsole();
    const { member, session, ids } = await setupVoting();
    jest.spyOn(VoteModel, 'findOneAndUpdate').mockImplementation(() => {
      throw new Error('db down');
    });

    const res = await request(createApp())
      .post(`/api/voting-sessions/${session.id}/vote`)
      .set('Authorization', bearer(member.id))
      .send({ candidateId: ids[0] });

    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'Internal Server Error' });
  });

  // Input: valid vote
  // Expected status code: 400
  // Expected behavior: nothing stored
  // Mocked behavior: Fair Credit returns a negative weight
  test.each([-1, Number.NaN, Number.POSITIVE_INFINITY])('Invalid Fair Credit weight %p', async (weight) => {
    const { member, session, ids } = await setupVoting();
    jest.spyOn(fairCreditService, 'getFairCredit').mockResolvedValue(weight);

    const res = await request(createApp())
      .post(`/api/voting-sessions/${session.id}/vote`)
      .set('Authorization', bearer(member.id))
      .send({ candidateId: ids[0] });

    expect(res.status).toBe(400);
    expect(await VoteModel.countDocuments()).toBe(0);
  });

  // Input: valid vote
  // Expected behavior: the stored weight is whatever Fair Credit returns
  // Mocked behavior: Fair Credit returns 2.5
  test('Stores the Fair Credit weight', async () => {
    const { member, session, ids } = await setupVoting();
    jest.spyOn(fairCreditService, 'getFairCredit').mockResolvedValue(2.5);

    const res = await request(createApp())
      .post(`/api/voting-sessions/${session.id}/vote`)
      .set('Authorization', bearer(member.id))
      .send({ candidateId: ids[0] });

    expect(res.body.weight).toBe(2.5);
  });
});

// Interface PATCH /api/voting-sessions/:sessionId/close
describe('Mocked: PATCH /api/voting-sessions/:sessionId/close', () => {
  // Input: creator closes after a vote
  // Expected status code: 500
  // Expected behavior: session is reopened with no winner so the organizer can retry
  // Mocked behavior: finalizing the meetup (MeetupModel.updateOne) rejects
  test('Finalize failure reopens the session', async () => {
    quietConsole();
    const { organizer, member, session, ids } = await setupVoting();
    await VoteModel.create({ sessionId: session.id, userId: member.id, candidateId: ids[0], weight: 1 });
    jest.spyOn(MeetupModel, 'updateOne').mockRejectedValue(new Error('db down') as never);

    const res = await request(createApp())
      .patch(`/api/voting-sessions/${session.id}/close`)
      .set('Authorization', bearer(organizer.id))
      .send({});

    expect(res.status).toBe(500);
    const stored = await VotingSessionModel.findById(session.id);
    expect(stored?.status).toBe('open');
    expect(stored?.winnerCandidateId).toBeNull();
  });

  // Input: creator closes
  // Expected status code: 409
  // Mocked behavior: another request closed the session between the check and the update
  test('Concurrent close', async () => {
    const { organizer, member, session, ids } = await setupVoting();
    await VoteModel.create({ sessionId: session.id, userId: member.id, candidateId: ids[0], weight: 1 });
    jest.spyOn(VotingSessionModel, 'findOneAndUpdate').mockResolvedValue(null as never);

    const res = await request(createApp())
      .patch(`/api/voting-sessions/${session.id}/close`)
      .set('Authorization', bearer(organizer.id))
      .send({});

    expect(res.status).toBe(409);
  });
});
