import request, { type Test } from 'supertest';

import { createApp } from '../../src/app';
import { Forbidden } from '../../src/common/errors';
import { tuning } from '../../src/config';
import { meetupService } from '../../src/users-groups/meetup.service';
import { MeetupModel } from '../../src/users-groups/models';
import { TripModel } from '../../src/voting-fairness/models';
import { bearer } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import { createFinalizedMeetup, createGroup, createUserN } from '../helpers/factories';

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

// A finalized meetup whose time has come, so its organizer may start a trip
async function setup() {
  const organizer = await createUserN(1);
  const group = await createGroup(organizer.id);
  const meetup = await createFinalizedMeetup(group.id, organizer.id, [organizer.id]);
  await MeetupModel.updateOne(
    { _id: meetup.id },
    { $set: { scheduledAt: new Date(Date.now() - tuning.secondsPerMinute * tuning.msPerSecond) } },
  );
  return { organizer, meetup };
}

function trip(action: string, meetupId: string, userId: string): Test {
  return request(createApp()).post(`/api/meetups/${meetupId}/trip/${action}`).set('Authorization', bearer(userId));
}

// Interface POST /api/meetups/:meetupId/trip/start
describe('Mocked: POST /api/meetups/:meetupId/trip/start', () => {
  // Mocked behavior: saving the trip rejects
  // Input: participant starts their trip
  // Expected status code: 500
  // Expected behavior: no trip is stored
  // Expected output: { error: "Internal Server Error" }
  test('Database failure while saving the trip', async () => {
    quietConsole();
    const { organizer, meetup } = await setup();
    jest.spyOn(TripModel, 'updateOne').mockRejectedValue(new Error('db down') as never);

    const res = await trip('start', meetup.id, organizer.id);

    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'Internal Server Error' });
    expect(await TripModel.countDocuments()).toBe(0);
  });

  // Mocked behavior: the second access check fails, as if the participant left the group while the trip was saved
  // Input: participant starts their trip
  // Expected status code: 403
  // Expected behavior: the trip that was just saved no longer shares a location
  // Expected output: { error: "Not a member of this group" }
  test('Access lost while the trip is being saved', async () => {
    const { organizer, meetup } = await setup();
    const loaded = await meetupService.getById(meetup.id);
    const access = jest.spyOn(meetupService, 'getForMember')
      .mockResolvedValueOnce(loaded)
      .mockRejectedValueOnce(new Forbidden('Not a member of this group'));

    const res = await trip('start', meetup.id, organizer.id);

    expect(res.status).toBe(403);
    expect(res.body).toEqual({ error: 'Not a member of this group' });
    expect(access).toHaveBeenCalledTimes(2);
    expect(await TripModel.findOne({ meetupId: meetup.id }).lean()).toMatchObject({ sharing: false, location: null });
  });
});

// Interface POST /api/meetups/:meetupId/trip/arrive
describe('Mocked: POST /api/meetups/:meetupId/trip/arrive', () => {
  // Mocked behavior: saving the arrival rejects
  // Input: participant with an active trip taps "I arrived"
  // Expected status code: 500
  // Expected behavior: the trip stays ACTIVE, so the participant can retry
  // Expected output: { error: "Internal Server Error" }
  test('Database failure while saving the arrival', async () => {
    quietConsole();
    const { organizer, meetup } = await setup();
    expect((await trip('start', meetup.id, organizer.id)).status).toBe(200);
    jest.spyOn(TripModel, 'updateOne').mockRejectedValue(new Error('db down') as never);

    const res = await trip('arrive', meetup.id, organizer.id);

    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'Internal Server Error' });
    expect(await TripModel.findOne({ meetupId: meetup.id }).lean()).toMatchObject({ status: 'ACTIVE', arrivedAt: null });
  });
});

// Interface GET /api/meetups/:meetupId/trips
describe('Mocked: GET /api/meetups/:meetupId/trips', () => {
  // Mocked behavior: loading the trips rejects
  // Input: group member lists the meetup's trips
  // Expected status code: 500
  // Expected behavior: no internals are leaked
  // Expected output: { error: "Internal Server Error" }
  test('Database failure while loading trips', async () => {
    quietConsole();
    const { organizer, meetup } = await setup();
    jest.spyOn(TripModel, 'find').mockRejectedValue(new Error('db down') as never);

    const res = await request(createApp())
      .get(`/api/meetups/${meetup.id}/trips`)
      .set('Authorization', bearer(organizer.id));

    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'Internal Server Error' });
  });
});
