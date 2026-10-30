import request from 'supertest';
import { createApp } from '../../src/app';
import { signSessionToken } from '../../src/common/session';
import { TripModel } from '../../src/live-location/models';
import { MeetupModel } from '../../src/users-groups/models';
import { clearDb, startDb, stopDb } from '../helpers/db';
import { addMember, createFinalizedMeetup, createGroup, createUserN } from '../helpers/factories';

const app = createApp();
beforeAll(startDb);
afterEach(clearDb);
afterAll(stopDb);
async function setup() {
  const organizer = await createUserN(1);
  const member = await createUserN(2);
  const viewer = await createUserN(3);
  const outsider = await createUserN(4);
  const group = await createGroup(organizer.id);
  await addMember(group.id, member.id);
  await addMember(group.id, viewer.id);
  const meetup = await createFinalizedMeetup(group.id, organizer.id, [organizer.id, member.id]);
  await MeetupModel.updateOne({ _id: meetup.id }, { $set: { scheduledAt: new Date(Date.now() - 60000) } });
  const auth = (id: string) => `Bearer ${signSessionToken(id)}`;
  const call = (action: string, id = member.id) => request(app).post(`/api/meetups/${meetup.id}/trip/${action}`).set('Authorization', auth(id));
  return { organizer, member, viewer, outsider, group, meetup, auth, call };
}

test('server times one trip, accepts latest positions, and clears location on idempotent arrival', async () => {
  const { meetup, member, viewer, auth, call } = await setup();
  const starts = await Promise.all([call('start'), call('start')]);
  expect(starts.map(r => Number(r.status))).toEqual([200, 200]);
  expect(await TripModel.countDocuments({ meetupId: meetup.id })).toBe(1);
  const startedAt = starts[0]?.body.startedAt as string;
  expect(starts[1]?.body.startedAt).toBe(startedAt);
  const position = await request(app).put(`/api/meetups/${meetup.id}/trip/location`).set('Authorization', auth(member.id))
    .send({ lat: 49.2, lng: -123.2, accuracyM: 8, userId: viewer.id });
  expect(position.status).toBe(200);
  expect(position.body.userId).toBe(member.id);
  const listed = await request(app).get(`/api/meetups/${meetup.id}/trips`).set('Authorization', auth(viewer.id));
  expect(listed.body[0]).toMatchObject({ userId: member.id, name: 'User 2', sharing: true, location: { lat: 49.2, lng: -123.2 } });
  await TripModel.updateOne({ meetupId: meetup.id }, { $set: { startedAt: new Date(Date.now() - 125000) } });
  const arrived = await call('arrive').send({ actualSeconds: 99999 });
  expect(arrived.status).toBe(200);
  expect(arrived.body).toMatchObject({ status: 'ARRIVED', sharing: false, location: null, source: 'TRACKED' });
  expect(arrived.body.actualSeconds).toBeGreaterThanOrEqual(125);
  expect(arrived.body.actualSeconds).toBeLessThan(130);
  expect((await call('arrive')).body.arrivedAt).toBe(arrived.body.arrivedAt);
  expect((await call('start')).status).toBe(409);
  expect((await request(app).put(`/api/meetups/${meetup.id}/trip/location`).set('Authorization', auth(member.id))
    .send({ lat: 49, lng: -123, accuracyM: 8 })).status).toBe(409);
});

test('requires authentication, participation, finalized destination and scheduled time', async () => {
  const { meetup, viewer, outsider, auth, call } = await setup();
  expect((await request(app).get(`/api/meetups/${meetup.id}/trips`)).status).toBe(401);
  expect((await request(app).get(`/api/meetups/${meetup.id}/trips`).set('Authorization', auth(outsider.id))).status).toBe(403);
  expect((await call('start', viewer.id)).status).toBe(403);
  expect((await call('arrive')).status).toBe(409);
  await MeetupModel.updateOne({ _id: meetup.id }, { $set: { scheduledAt: new Date(Date.now() + 60000) } });
  expect((await call('start')).status).toBe(409);
  await MeetupModel.updateOne({ _id: meetup.id }, { $set: { scheduledAt: new Date(Date.now() - 60000), status: 'VOTING' } });
  expect((await call('start')).status).toBe(409);
});

test('pause and resume do not reset the clock; invalid coordinates are rejected', async () => {
  const { meetup, member, auth, call } = await setup();
  const started = await call('start');
  expect((await request(app).put(`/api/meetups/${meetup.id}/trip/location`).set('Authorization', auth(member.id))
    .send({ lat: 100, lng: -123, accuracyM: 8 })).status).toBe(400);
  expect((await call('stop')).status).toBe(204);
  expect(await TripModel.findOne({ meetupId: meetup.id }).lean()).toMatchObject({ sharing: false, location: null });
  const resumed = await call('start');
  expect(resumed.status).toBe(200);
  expect(resumed.body.startedAt).toBe(started.body.startedAt);
});

test('leaving the group clears shared location and denies later access', async () => {
  const { group, meetup, member, auth, call } = await setup();
  await call('start');
  await request(app).put(`/api/meetups/${meetup.id}/trip/location`).set('Authorization', auth(member.id))
    .send({ lat: 49, lng: -123, accuracyM: 8 });
  expect((await request(app).post(`/api/groups/${group.id}/leave`).set('Authorization', auth(member.id))).status).toBe(204);
  expect(await TripModel.findOne({ meetupId: meetup.id }).lean()).toMatchObject({ sharing: false, location: null });
  expect((await request(app).get(`/api/meetups/${meetup.id}/trips`).set('Authorization', auth(member.id))).status).toBe(403);
});

test('deleting a meetup also deletes trip locations and durations', async () => {
  const { meetup, organizer, auth, call } = await setup();
  await call('start');
  expect((await request(app).delete(`/api/meetups/${meetup.id}`).set('Authorization', auth(organizer.id))).status).toBe(204);
  expect(await TripModel.countDocuments({ meetupId: meetup.id })).toBe(0);
});

test('one member cannot share two trips at the same time', async () => {
  const { meetup, group, organizer, member, auth, call } = await setup();
  await call('start');
  const second = await createFinalizedMeetup(group.id, organizer.id, [member.id]);
  await MeetupModel.updateOne({ _id: second.id }, { $set: { scheduledAt: new Date(Date.now() - 60000) } });
  expect((await request(app).post(`/api/meetups/${second.id}/trip/start`).set('Authorization', auth(member.id))).status).toBe(409);
  await call('stop');
  expect((await request(app).post(`/api/meetups/${second.id}/trip/start`).set('Authorization', auth(member.id))).status).toBe(200);
  expect((await call('start')).status).toBe(409);
  expect(await TripModel.findOne({ meetupId: meetup.id }).lean()).toMatchObject({ sharing: false });
});
