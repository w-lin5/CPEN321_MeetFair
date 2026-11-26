import request from 'supertest';
import { createApp } from '../../src/app';
import { TripModel } from '../../src/live-location/models';
import { MeetupModel, UserModel } from '../../src/users-groups/models';
import { SettlementModel } from '../../src/voting-fairness/models';
import { setTransport } from '../../src/realtime/publisher';
import { bearer } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import { addMember, createFinalizedMeetup, createGroup, createUserN } from '../helpers/factories';

const app = createApp();
beforeAll(startDb);
afterEach(async () => { jest.restoreAllMocks(); setTransport(null); await clearDb(); });
afterAll(stopDb);
async function setup() {
  const organizer = await createUserN(1);
  const viewer = await createUserN(2);
  const group = await createGroup(organizer.id);
  await addMember(group.id, viewer.id);
  const meetup = await createFinalizedMeetup(group.id, organizer.id, [organizer.id]);
  await MeetupModel.updateOne({ _id: meetup.id }, { $set: { scheduledAt: new Date(Date.now() - 60_000) } });
  const events: unknown[] = [];
  setTransport((_room, event, payload) => events.push({ event, payload }));
  return { organizer, viewer, group, meetup, events,
    start: () => request(app).post(`/api/meetups/${meetup.id}/trip/start`).set('Authorization', bearer(organizer.id)),
  };
}

test('a frozen settlement prevents a new trip while preserving existing inputs', async () => {
  const fixture = await setup();
  const snapshot = await SettlementModel.create({ meetupId: fixture.meetup.id, groupId: fixture.group.id,
    trips: [{ userId: fixture.organizer.id, mode: 'TRANSIT', actualSeconds: 600, source: 'ESTIMATED' }] });
  const result = await fixture.start();
  expect(result.status).toBe(409);
  expect(result.body).toEqual({ error: 'Fair Credit settlement has started' });
  expect(await TripModel.countDocuments()).toBe(0);
  expect((await SettlementModel.findById(snapshot.id).orFail()).trips[0]?.actualSeconds).toBe(600);
  expect(fixture.events).toEqual([]);
});

test('a finalized legacy meetup without a destination cannot start sharing', async () => {
  const fixture = await setup();
  await MeetupModel.updateOne({ _id: fixture.meetup.id }, { $unset: { winner: 1 } });
  const result = await fixture.start();
  expect(result.status).toBe(409);
  expect(result.body).toEqual({ error: 'The meetup must have a finalized destination' });
  expect(await TripModel.countDocuments()).toBe(0);
});

test.each([new Error('storage down'), null, 'network failure', {}, { code: 123 }])('a non-duplicate start write failure never creates or announces a trip: %p', async failure => {
  const fixture = await setup();
  jest.spyOn(console, 'error').mockImplementation(() => undefined);
  jest.spyOn(TripModel, 'updateOne').mockRejectedValueOnce(failure);
  const result = await fixture.start();
  expect(result.status).toBe(500);
  expect(result.body).toEqual({ error: 'Internal Server Error' });
  expect(await TripModel.countDocuments()).toBe(0);
  expect(fixture.events).toEqual([]);
});

test('a committed start followed by a duplicate-key response returns the same active trip', async () => {
  const fixture = await setup();
  const original = TripModel.updateOne.bind(TripModel);
  jest.spyOn(TripModel, 'updateOne').mockImplementationOnce((...args: Parameters<typeof original>) => {
    const query = original(...args);
    const execute = query.exec.bind(query);
    jest.spyOn(query, 'exec').mockImplementationOnce(async () => {
      await execute(); throw Object.assign(new Error('duplicate active trip'), { code: 11000 });
    });
    return query;
  });
  const first = await fixture.start();
  const retry = await fixture.start();
  expect(first.status).toBe(200);
  expect(retry.status).toBe(200);
  expect(retry.body).toMatchObject({ id: first.body.id, startedAt: first.body.startedAt, status: 'ACTIVE', sharing: true });
  expect(await TripModel.countDocuments()).toBe(1);
});

test('a missing user profile uses a safe member label without hiding an authorized trip', async () => {
  const fixture = await setup();
  expect((await fixture.start()).status).toBe(200);
  await UserModel.deleteOne({ _id: fixture.organizer.id });
  const result = await request(app).get(`/api/meetups/${fixture.meetup.id}/trips`).set('Authorization', bearer(fixture.viewer.id));
  expect(result.status).toBe(200);
  expect(result.body).toHaveLength(1);
  expect(result.body[0]).toMatchObject({ userId: fixture.organizer.id, name: 'Member', photoUrl: null, status: 'ACTIVE' });
});
