import request from 'supertest';

import { createApp } from '../../src/app';
import { NotFound } from '../../src/common/errors';
import { recommendationService } from '../../src/recommendation/recommendation.service';
import { MeetupModel } from '../../src/users-groups/models';
import { fairCreditService } from '../../src/voting-fairness/fair-credit.service';
import { VoteModel } from '../../src/voting-fairness/models';
import { bearer } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import { PLACE_1, PLACE_2, addMember, createGroup, createUserN, createVotingMeetup } from '../helpers/factories';

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
  const ada = await createUserN(2);
  const group = await createGroup(organizer.id);
  await addMember(group.id, ada.id);
  const meetup = await createVotingMeetup(group.id, organizer.id, [organizer.id, ada.id]);
  return { organizer, ada, meetup };
}

const app = () => request(createApp());

// Interface PUT /api/meetups/:meetupId/vote
describe('Mocked: PUT /api/meetups/:meetupId/vote', () => {
  // Input: valid vote from a participant
  // Expected status code: 500
  // Expected output: { error: "Internal Server Error" }
  // Mocked behavior: VoteModel.findOneAndUpdate throws
  test('Database failure while saving the vote', async () => {
    quietConsole();
    const { ada, meetup } = await setup();
    jest.spyOn(VoteModel, 'findOneAndUpdate').mockImplementation(() => {
      throw new Error('db down');
    });

    const res = await app().put(`/api/meetups/${meetup.id}/vote`).set('Authorization', bearer(ada.id)).send({ placeId: PLACE_1 });

    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'Internal Server Error' });
  });
});

// Interface POST /api/meetups/:meetupId/vote/lock
describe('Mocked: POST /api/meetups/:meetupId/vote/lock', () => {
  // Input: participant with an unlocked vote
  // Expected status code: 200
  // Expected behavior: the weight is snapshotted from Fair Credit in the meetup's group
  // Mocked behavior: fairCreditService.getVoteWeight resolves 2.5
  test('Stores the Fair Credit weight', async () => {
    const { ada, meetup } = await setup();
    const credit = jest.spyOn(fairCreditService, 'getVoteWeight').mockResolvedValue(2.5);
    await app().put(`/api/meetups/${meetup.id}/vote`).set('Authorization', bearer(ada.id)).send({ placeId: PLACE_2 });

    const res = await app().post(`/api/meetups/${meetup.id}/vote/lock`).set('Authorization', bearer(ada.id));

    expect(res.status).toBe(200);
    expect(res.body.weight).toBe(2.5);
    expect(credit).toHaveBeenCalledWith(ada.id, String(meetup.groupId));
  });
});

// Interface GET /api/meetups/:meetupId/votes
describe('Mocked: GET /api/meetups/:meetupId/votes', () => {
  // Input: member of a meetup in VOTING
  // Expected status code: 500
  // Expected output: { error: "Internal Server Error" } (no internals leaked)
  // Mocked behavior: VoteModel.find throws while the tally is built
  test('Database failure while loading the tally', async () => {
    quietConsole();
    const { ada, meetup } = await setup();
    jest.spyOn(VoteModel, 'find').mockImplementation(() => {
      throw new Error('db down');
    });

    const res = await app().get(`/api/meetups/${meetup.id}/votes`).set('Authorization', bearer(ada.id));

    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'Internal Server Error' });
  });

  // Input: member of a meetup in VOTING
  // Expected status code: 404
  // Expected output: { error: "No recommendations for this meetup yet" }
  // Mocked behavior: recommendationService.getByMeetupId rejects with NotFound (candidates missing)
  test('Candidates cannot be loaded', async () => {
    const { ada, meetup } = await setup();
    jest.spyOn(recommendationService, 'getByMeetupId')
      .mockRejectedValue(new NotFound('No recommendations for this meetup yet'));

    const res = await app().get(`/api/meetups/${meetup.id}/votes`).set('Authorization', bearer(ada.id));

    expect(res.status).toBe(404);
    expect(res.body).toEqual({ error: 'No recommendations for this meetup yet' });
  });
});

// Interface POST /api/meetups/:meetupId/finalize
describe('Mocked: POST /api/meetups/:meetupId/finalize', () => {
  async function withLockedVote() {
    const ctx = await setup();
    await VoteModel.create({ meetupId: ctx.meetup.id, userId: ctx.ada.id, placeId: PLACE_1, locked: true, weight: 1 });
    return ctx;
  }

  // Input: organizer finalizes with a clear winner
  // Expected status code: 409
  // Expected behavior: a concurrent finalize already moved the meetup, so this one reports a conflict
  // Mocked behavior: MeetupModel.findOneAndUpdate resolves null
  test('Concurrent finalize', async () => {
    const { organizer, meetup } = await withLockedVote();
    jest.spyOn(MeetupModel, 'findOneAndUpdate').mockResolvedValue(null as never);

    expect((await app().post(`/api/meetups/${meetup.id}/finalize`).set('Authorization', bearer(organizer.id))).status)
      .toBe(409);
  });

  // Input: organizer finalizes with a clear winner
  // Expected status code: 500
  // Expected behavior: the meetup stays in VOTING without a winner
  // Mocked behavior: MeetupModel.findOneAndUpdate rejects
  test('Database failure while finalizing', async () => {
    quietConsole();
    const { organizer, meetup } = await withLockedVote();
    jest.spyOn(MeetupModel, 'findOneAndUpdate').mockRejectedValue(new Error('db down') as never);

    const res = await app().post(`/api/meetups/${meetup.id}/finalize`).set('Authorization', bearer(organizer.id));

    expect(res.status).toBe(500);
    jest.restoreAllMocks();
    const saved = await MeetupModel.findById(meetup.id).orFail();
    expect(saved.status).toBe('VOTING');
    expect(saved.winner).toBeUndefined();
  });

  // Input: organizer finalizes with a clear winner
  // Expected status code: 404
  // Expected output: { error: "Winning candidate not found" }; the meetup stays in VOTING
  // Mocked behavior: recommendationService.getByMeetupId resolves a candidate without a location
  test('Winning candidate has no location', async () => {
    const { organizer, meetup } = await withLockedVote();
    jest.spyOn(recommendationService, 'getByMeetupId').mockResolvedValue({
      candidates: [{ placeId: PLACE_1, name: 'Koerner Library', address: '' }],
    } as never);

    const res = await app().post(`/api/meetups/${meetup.id}/finalize`).set('Authorization', bearer(organizer.id));

    expect(res.status).toBe(404);
    expect(res.body).toEqual({ error: 'Winning candidate not found' });
    expect((await MeetupModel.findById(meetup.id).orFail()).status).toBe('VOTING');
  });
});

