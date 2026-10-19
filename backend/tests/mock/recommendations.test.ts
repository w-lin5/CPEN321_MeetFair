import request from 'supertest';

import { createApp } from '../../src/app';
import { RecommendationModel } from '../../src/recommendation/models';
import { MeetupModel } from '../../src/users-groups/models';
import { bearer } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import { CANDIDATES, createGroup, createMeetup, createUserN } from '../helpers/factories';

beforeAll(startDb);
afterEach(async () => {
  jest.restoreAllMocks();
  await clearDb();
});
afterAll(stopDb);

async function setup() {
  const organizer = await createUserN(1);
  const group = await createGroup(organizer.id);
  const meetup = await createMeetup(group.id, organizer.id);
  return { organizer, meetup };
}

function generate(meetupId: string, userId: string) {
  return request(createApp())
    .post(`/api/meetups/${meetupId}/recommendations`)
    .set('Authorization', bearer(userId))
    .send({ candidates: CANDIDATES });
}

// Interface POST /api/meetups/:meetupId/recommendations
describe('Mocked: POST /api/meetups/:meetupId/recommendations', () => {
  // Input: organizer of a SETUP meetup
  // Expected status code: 500
  // Expected behavior: the claimed VOTING status is rolled back to SETUP
  // Mocked behavior: RecommendationModel.create rejects
  test('Database failure while saving rolls the meetup back to SETUP', async () => {
    jest.spyOn(console, 'error').mockImplementation(() => undefined);
    const { organizer, meetup } = await setup();
    jest.spyOn(RecommendationModel, 'create').mockRejectedValue(new Error('db down') as never);

    const res = await generate(meetup.id, organizer.id);

    expect(res.status).toBe(500);
    expect((await MeetupModel.findById(meetup.id).orFail()).status).toBe('SETUP');
  });

  // Input: organizer of a SETUP meetup
  // Expected status code: 409
  // Expected behavior: rolled back to SETUP
  // Mocked behavior: RecommendationModel.create rejects with a duplicate-key error
  test('Duplicate recommendation', async () => {
    const { organizer, meetup } = await setup();
    jest.spyOn(RecommendationModel, 'create')
      .mockRejectedValue(Object.assign(new Error('dup'), { code: 11000 }) as never);

    const res = await generate(meetup.id, organizer.id);

    expect(res.status).toBe(409);
    expect((await MeetupModel.findById(meetup.id).orFail()).status).toBe('SETUP');
  });
});
