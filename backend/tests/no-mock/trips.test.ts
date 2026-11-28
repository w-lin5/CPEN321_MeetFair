import request, { type Test } from 'supertest';
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
  const call = (action: string, id = member.id): Test => request(app).post(`/api/meetups/${meetup.id}/trip/${action}`).set('Authorization', auth(id));
  return { organizer, member, viewer, outsider, group, meetup, auth, call };
}

// Interface POST /api/meetups/:meetupId/trip/start
describe('Unmocked: POST /api/meetups/:meetupId/trip/start', () => {
  // Input: group member without participation starts a trip; participant starts before the meetup time; participant starts
  //   after the meetup is moved back to VOTING
  // Expected status code: 403; 409; 409
  // Expected behavior: every start request is rejected
  // Expected output: None
  test('requires participation, finalized destination and scheduled time', async () => {
    const { meetup, viewer, call } = await setup();
    expect((await call('start', viewer.id)).status).toBe(403);
    await MeetupModel.updateOne({ _id: meetup.id }, { $set: { scheduledAt: new Date(Date.now() + 60000) } });
    expect((await call('start')).status).toBe(409);
    await MeetupModel.updateOne({ _id: meetup.id }, { $set: { scheduledAt: new Date(Date.now() - 60000), status: 'VOTING' } });
    expect((await call('start')).status).toBe(409);
  });

  // Input: member shares a trip for one meetup, starts a second meetup's trip, stops the first, starts the second, then restarts the first
  // Expected status code: 409; 200; 409
  // Expected behavior: only one trip per member can share at a time; the stopped first trip stays not sharing
  // Expected output: None
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
});

// Interface POST /api/meetups/:meetupId/trip/arrive
describe('Unmocked: POST /api/meetups/:meetupId/trip/arrive', () => {
  // Input: two concurrent trip starts; a location update with a spoofed userId; a group member lists trips; arrival with a
  //   client-sent actualSeconds after the trip started 125 s ago; a second arrival; a start and a location update after arrival
  // Expected status code: 200, 200 for the starts; 200 for the location; 200 for the arrival; 409 for the late start and location
  // Expected behavior: a single trip is stored with one server-set startedAt; the location is saved for the caller, not the spoofed
  //   user; the duration is computed by the server (client value ignored); arrival is idempotent and clears the location
  // Expected output: trip list shows the member sharing their location; arrived trip
  //   { status: "ARRIVED", sharing: false, location: null, source: "TRACKED", actualSeconds: 125-129 } with an unchanged arrivedAt on repeat
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

  // Input: participant marks arrival without starting a trip
  // Expected status code: 409
  // Expected behavior: no trip is created
  // Expected output: None
  test('requires a started trip', async () => {
    const { call } = await setup();
    expect((await call('arrive')).status).toBe(409);
  });
});

describe('Unmocked: PUT /api/meetups/:meetupId/trip/location', () => {
  // Input: a sharing member submits a second point, then an out-of-range latitude.
  // Expected: 200 with the latest point, then 400 with a validation error and no write.
  test('replaces the previous point and preserves it when the next update is invalid', async () => {
    const { meetup, member, auth, call } = await setup();
    const sendPoint = (lat: number) => request(app).put(`/api/meetups/${meetup.id}/trip/location`)
      .set('Authorization', auth(member.id)).send({ lat, lng: -123, accuracyM: 8 });
    expect((await call('start')).status).toBe(200);
    expect((await sendPoint(49)).status).toBe(200);
    const latest = await sendPoint(49.1);
    expect(latest.status).toBe(200);
    expect(latest.body).toMatchObject({ userId: member.id, location: { lat: 49.1, lng: -123, accuracyM: 8 } });
    const invalid = await sendPoint(100);
    expect(invalid.status).toBe(400);
    expect(invalid.body).toMatchObject({ error: 'Invalid request', details: [expect.stringContaining('lat')] });
    expect(await TripModel.countDocuments({ meetupId: meetup.id })).toBe(1);
    expect(await TripModel.findOne({ meetupId: meetup.id }).lean()).toMatchObject({ location: { lat: 49.1, lng: -123, accuracyM: 8 } });
  });
});

// Interface POST /api/meetups/:meetupId/trip/stop
describe('Unmocked: POST /api/meetups/:meetupId/trip/stop', () => {
  // Input: sharing participant with a stored point pauses, a group member lists trips, then the participant resumes.
  // Expected: 204 for stop, 200 with a paused trip and no visible point for list, 200 for resume.
  // DB location is cleared and the original start time is retained.
  test('pause clears the shared point from storage and member views while preserving the timer', async () => {
    const { meetup, member, viewer, auth, call } = await setup();
    const started = await call('start');
    expect((await request(app).put(`/api/meetups/${meetup.id}/trip/location`).set('Authorization', auth(member.id))
      .send({ lat: 49, lng: -123, accuracyM: 8 })).status).toBe(200);
    expect((await call('stop')).status).toBe(204);
    expect(await TripModel.findOne({ meetupId: meetup.id }).lean()).toMatchObject({ status: 'ACTIVE', sharing: false, location: null });
    const listed = await request(app).get(`/api/meetups/${meetup.id}/trips`).set('Authorization', auth(viewer.id));
    expect(listed.status).toBe(200);
    expect(listed.body).toEqual([expect.objectContaining({ userId: member.id, status: 'ACTIVE', sharing: false, location: null })]);
    const resumed = await call('start');
    expect(resumed.status).toBe(200);
    expect(resumed.body.startedAt).toBe(started.body.startedAt);
  });
});

// Interface GET /api/meetups/:meetupId/trips
describe('Unmocked: GET /api/meetups/:meetupId/trips', () => {
  // Input: request without a token; user outside the group
  // Expected status code: 401; 403
  // Expected behavior: trip locations are not returned
  // Expected output: None
  test('requires authentication and group membership', async () => {
    const { meetup, outsider, auth } = await setup();
    expect((await request(app).get(`/api/meetups/${meetup.id}/trips`)).status).toBe(401);
    expect((await request(app).get(`/api/meetups/${meetup.id}/trips`).set('Authorization', auth(outsider.id))).status).toBe(403);
  });
});

// Interface POST /api/groups/:groupId/leave (trip side effects)
describe('Unmocked: POST /api/groups/:groupId/leave', () => {
  // Input: participant sharing their location leaves the group, then lists trips
  // Expected status code: 204; then 403
  // Expected behavior: the unfinished trip is cancelled and its location is cleared
  // Expected output: None
  test('leaving the group clears shared location and denies later access', async () => {
    const { group, meetup, organizer, member, auth, call } = await setup();
    expect((await call('start', organizer.id)).status).toBe(200);
    const otherGroup = await createGroup(member.id, { inviteCode: 'XYZ234' });
    const otherMeetup = await createFinalizedMeetup(otherGroup.id, member.id, [member.id]);
    const otherTrip = await TripModel.create({ meetupId: otherMeetup.id, userId: member.id, travelMode: 'TRANSIT',
      status: 'ACTIVE', sharing: false, startedAt: new Date() });
    await call('start');
    await request(app).put(`/api/meetups/${meetup.id}/trip/location`).set('Authorization', auth(member.id))
      .send({ lat: 49, lng: -123, accuracyM: 8 });
    expect((await request(app).post(`/api/groups/${group.id}/leave`).set('Authorization', auth(member.id))).status).toBe(204);
    expect(await TripModel.findOne({ meetupId: meetup.id, userId: member.id }).lean())
      .toMatchObject({ status: 'CANCELLED', sharing: false, location: null, actualSeconds: null });
    expect(await TripModel.findOne({ meetupId: meetup.id, userId: organizer.id }).lean())
      .toMatchObject({ status: 'ACTIVE', sharing: true });
    expect(await TripModel.findById(otherTrip.id).lean()).toMatchObject({ status: 'ACTIVE', sharing: false });
    expect((await request(app).get(`/api/meetups/${meetup.id}/trips`).set('Authorization', auth(member.id))).status).toBe(403);
  });

  test('rejoining does not resume or mark arrival on a cancelled trip', async () => {
    const { group, member, auth, call } = await setup();
    expect((await call('start')).status).toBe(200);
    expect((await request(app).post(`/api/groups/${group.id}/leave`).set('Authorization', auth(member.id))).status).toBe(204);
    expect((await request(app).post('/api/groups/join').set('Authorization', auth(member.id)).send({ inviteCode: group.inviteCode })).status).toBe(200);
    for (const action of ['start', 'arrive']) {
      const response = await call(action);
      expect(response.status).toBe(409);
      expect(response.body.error).toBe('This trip was cancelled when you left the group');
    }
  });

  test('a start racing after leave cleanup cannot leave an orphan active trip', async () => {
    const { group, meetup, member, auth, call } = await setup();
    const original = TripModel.updateOne.bind(TripModel);
    const update = jest.spyOn(TripModel, 'updateOne').mockImplementationOnce((...args: Parameters<typeof original>) => {
      // Delay the insertion until the leave request has revoked membership and cleaned trips.
      return (async () => {
        expect((await request(app).post(`/api/groups/${group.id}/leave`).set('Authorization', auth(member.id))).status).toBe(204);
        return original(...args);
      })() as ReturnType<typeof original>;
    });
    try {
      expect((await call('start')).status).toBe(403);
      expect(await TripModel.findOne({ meetupId: meetup.id, userId: member.id }).lean())
        .toMatchObject({ status: 'CANCELLED', sharing: false, location: null, actualSeconds: null });
    } finally {
      update.mockRestore();
    }
  });
});

// Interface DELETE /api/meetups/:meetupId (trip side effects)
describe('Unmocked: DELETE /api/meetups/:meetupId', () => {
  // Input: organizer deletes a meetup with a started trip
  // Expected status code: 204
  // Expected behavior: the meetup's trips are deleted
  // Expected output: None
  test('deleting a meetup also deletes trip locations and durations', async () => {
    const { meetup, organizer, auth, call } = await setup();
    await call('start');
    expect((await request(app).delete(`/api/meetups/${meetup.id}`).set('Authorization', auth(organizer.id))).status).toBe(204);
    expect(await TripModel.countDocuments({ meetupId: meetup.id })).toBe(0);
  });
});
