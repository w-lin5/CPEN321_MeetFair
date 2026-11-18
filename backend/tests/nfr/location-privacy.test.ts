import request, { type Test } from 'supertest';

import { createApp } from '../../src/app';
import { TripModel } from '../../src/live-location/models';
import { setTransport } from '../../src/realtime/publisher';
import { MeetupModel } from '../../src/users-groups/models';
import { bearer } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import { addMember, createFinalizedMeetup, createGroup, createUserN } from '../helpers/factories';

// Requirements 3.7.2 / 4.7.2. No Google service or device location is needed.
const app = createApp();
const FIRST = { lat: 49.261, lng: -123.246, accuracyM: 8 };
const LATEST = { lat: 49.267, lng: -123.252, accuracyM: 5 };
beforeAll(startDb);
afterEach(async () => {
  setTransport(null);
  await clearDb();
});
afterAll(stopDb);

async function setup() {
  const organizer = await createUserN(1);
  const participant = await createUserN(2);
  const observer = await createUserN(3);
  const outsider = await createUserN(4);
  const group = await createGroup(organizer.id);
  await addMember(group.id, participant.id);
  await addMember(group.id, observer.id);
  const meetup = await createFinalizedMeetup(group.id, organizer.id, [organizer.id, participant.id]);
  await MeetupModel.updateOne({ _id: meetup.id }, { $set: { scheduledAt: new Date(Date.now() - 60_000) } });
  const action = (name: string, id = participant.id): Test => request(app)
    .post(`/api/meetups/${meetup.id}/trip/${name}`).set('Authorization', bearer(id));
  const position = (point = FIRST, id = participant.id): Test => request(app)
    .put(`/api/meetups/${meetup.id}/trip/location`).set('Authorization', bearer(id)).send(point);
  const list = (id = observer.id): Test => request(app)
    .get(`/api/meetups/${meetup.id}/trips`).set('Authorization', bearer(id));
  const stored = () => TripModel.collection.findOne({ meetupId: meetup._id, userId: participant._id });
  return { organizer, participant, observer, outsider, group, meetup, action, position, list, stored };
}

describe('NFR LOC-1: sharing requires an explicit start by an eligible participant', () => {
  test('opening the trip list or sending a position does not implicitly start a trip', async () => {
    const { observer, action, position, list } = await setup();
    const before = await list();
    expect(before.status).toBe(200);
    expect(before.body).toEqual([]);
    const unsolicited = await position();
    expect(unsolicited.status).toBe(409);
    expect(unsolicited.body).toEqual({ error: 'Location sharing is not active' });
    const notParticipating = await action('start', observer.id);
    expect(notParticipating.status).toBe(403);
    expect(notParticipating.body).toEqual({ error: 'Only members who submitted details can share a trip' });
    expect(await TripModel.countDocuments()).toBe(0);
  });
});

describe('NFR LOC-2: every position read/write checks the session and group membership', () => {
  test.each(['missing token', 'invalid token', 'outsider'] as const)('rejects %s without exposing or changing coordinates', async kind => {
    const { outsider, meetup, action, position, stored } = await setup();
    expect((await action('start')).status).toBe(200);
    expect((await position()).status).toBe(200);
    const snapshot = JSON.stringify(await stored());
    const calls = [request(app).get(`/api/meetups/${meetup.id}/trips`),
      request(app).put(`/api/meetups/${meetup.id}/trip/location`).send(LATEST)];
    const error = kind === 'missing token' ? 'Missing bearer token'
      : kind === 'invalid token' ? 'Invalid session token' : 'Not a member of this group';
    for (const call of calls) {
      if (kind !== 'missing token') call.set('Authorization', kind === 'outsider' ? bearer(outsider.id) : 'Bearer invalid-token');
      const denied = await call;
      expect(denied.status).toBe(kind === 'outsider' ? 403 : 401);
      expect(denied.body).toEqual({ error });
    }
    expect(JSON.stringify(await stored())).toBe(snapshot);
  });

  test('a supplied userId cannot overwrite another participant location', async () => {
    const { organizer, participant, meetup, action, list, stored } = await setup();
    expect((await action('start', organizer.id)).status).toBe(200);
    expect((await action('start')).status).toBe(200);
    const result = await request(app).put(`/api/meetups/${meetup.id}/trip/location`)
      .set('Authorization', bearer(participant.id)).send({ ...FIRST, userId: organizer.id });
    expect(result.status).toBe(200);
    expect(result.body.userId).toBe(participant.id);
    expect(await stored()).toMatchObject({ location: FIRST });
    expect(await TripModel.findOne({ meetupId: meetup.id, userId: organizer.id }).lean()).toMatchObject({ location: null });
    const visible = await list();
    expect(visible.status).toBe(200);
    expect(visible.body).toEqual(expect.arrayContaining([expect.objectContaining({ userId: participant.id, location: expect.objectContaining(FIRST) })]));
  });

  test('leaving revokes later reads/writes, clears the position and removes it from member lists', async () => {
    const { participant, group, action, position, list, stored } = await setup();
    await action('start');
    await position();
    const leave = await request(app).post(`/api/groups/${group.id}/leave`).set('Authorization', bearer(participant.id));
    expect(leave.status).toBe(204);
    expect(await stored()).toMatchObject({ sharing: false, location: null });
    for (const call of [list(participant.id), position(LATEST)]) {
      const denied = await call;
      expect(denied.status).toBe(403);
      expect(denied.body).toEqual({ error: 'Not a member of this group' });
    }
    expect(await stored()).toMatchObject({ sharing: false, location: null });
    const visible = await list();
    expect(visible.status).toBe(200);
    expect(visible.body).toEqual([]);
  });
});

describe('NFR LOC-3: minimize retention and stop sharing when paused or arrived', () => {
  test('new points replace the old coordinate instead of retaining a route history', async () => {
    const { action, position, stored, meetup } = await setup();
    await action('start');
    await position(FIRST);
    const original = await stored();
    await position(LATEST);
    const updated = await stored();
    expect(updated?._id).toEqual(original?._id);
    expect(updated).toMatchObject({ location: LATEST });
    expect(await TripModel.countDocuments({ meetupId: meetup.id })).toBe(1);
    expect(JSON.stringify(updated)).not.toContain(String(FIRST.lat));
    expect(JSON.stringify(updated)).not.toContain(String(FIRST.lng));
  });

  test('pause clears stored/returned coordinates and rejects updates until explicitly resumed', async () => {
    const { action, position, list, stored } = await setup();
    const start = await action('start');
    await position();
    expect((await action('stop')).status).toBe(204);
    expect(await stored()).toMatchObject({ status: 'ACTIVE', sharing: false, location: null });
    expect((await list()).body[0]).toMatchObject({ sharing: false, location: null });
    const denied = await position(LATEST);
    expect(denied.status).toBe(409);
    expect(denied.body).toEqual({ error: 'Location sharing is not active' });
    expect(await stored()).toMatchObject({ location: null });
    const resumed = await action('start');
    expect(resumed.status).toBe(200);
    expect(resumed.body).toMatchObject({ sharing: true, location: null, startedAt: start.body.startedAt });
    expect((await position(LATEST)).status).toBe(200);
  });

  test('arrival clears coordinates permanently while retaining commute timing', async () => {
    const { action, position, list, stored } = await setup();
    const start = await action('start');
    await position();
    const arrived = await action('arrive');
    expect(arrived.status).toBe(200);
    expect(arrived.body).toMatchObject({ status: 'ARRIVED', sharing: false, location: null, startedAt: start.body.startedAt });
    expect(arrived.body.actualSeconds).toEqual(expect.any(Number));
    expect(arrived.body.arrivedAt).toEqual(expect.any(String));
    expect(await stored()).toMatchObject({ status: 'ARRIVED', sharing: false, location: null });
    expect((await list()).body[0]).toMatchObject({ location: null });
    const snapshot = JSON.stringify(await stored());
    const latePosition = await position(LATEST);
    expect(latePosition.status).toBe(409);
    expect(latePosition.body).toEqual({ error: 'Location sharing is not active' });
    const restart = await action('start');
    expect(restart.status).toBe(409);
    expect(restart.body).toEqual({ error: 'You have already arrived for this meetup' });
    expect(JSON.stringify(await stored())).toBe(snapshot);
  });
});

describe('NFR LOC-4: realtime invalidations never contain coordinates', () => {
  test('start, position, pause, resume and arrival publish only the meetup identifier', async () => {
    const { meetup, action, position } = await setup();
    const events: { room: string; event: string; payload: unknown }[] = [];
    setTransport((room, event, payload) => events.push({ room, event, payload }));
    await action('start');
    await position(FIRST);
    await action('stop');
    await action('start');
    await position(LATEST);
    await action('arrive');
    expect(events).toHaveLength(6);
    for (const event of events) expect(event).toEqual({
      room: `meetup:${meetup.id}`, event: 'trip:updated', payload: { meetupId: meetup.id },
    });
  });
});
