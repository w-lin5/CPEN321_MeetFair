import request from 'supertest';

import { createApp } from '../../src/app';
import { MeetupModel } from '../../src/users-groups/models';
import { bearer } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import {
  MISSING_ID,
  PARTICIPATION,
  addMember,
  createGroup,
  createMeetup,
  createUserN,
} from '../helpers/factories';

beforeAll(startDb);
afterEach(clearDb);
afterAll(stopDb);

// Creates an organizer, a second group member and a group containing both
async function setupGroup() {
  const organizer = await createUserN(1);
  const member = await createUserN(2);
  const group = await createGroup(organizer.id);
  await addMember(group.id, member.id);
  return { organizer, member, group };
}

// Interface POST /api/groups/:groupId/meetups
describe('Unmocked: POST /api/groups/:groupId/meetups', () => {
  // Input: { title, scheduledAt } from a group member (not the group organizer)
  // Expected status code: 201
  // Expected behavior: meetup stored in SETUP with no participants; the caller is its organizer
  // Expected output: meetup with id, groupId, organizerId, title, scheduledAt (ISO), status SETUP, participants []
  test('Creates a meetup in SETUP', async () => {
    const { member, group } = await setupGroup();

    const res = await request(createApp())
      .post(`/api/groups/${group.id}/meetups`)
      .set('Authorization', bearer(member.id))
      .send({ title: '  Library session ', scheduledAt: '2026-11-05T22:30:00Z' });

    expect(res.status).toBe(201);
    expect(res.body).toMatchObject({
      groupId: group.id,
      organizerId: member.id,
      title: 'Library session',
      scheduledAt: '2026-11-05T22:30:00.000Z',
      status: 'SETUP',
      participants: [],
    });
    expect(await MeetupModel.countDocuments()).toBe(1);
  });

  // Input: title missing / blank / over the length limit, or scheduledAt missing / not an ISO date
  // Expected status code: 400
  // Expected behavior: nothing is stored
  test.each([
    [{ scheduledAt: '2026-11-05T22:30:00Z' }],
    [{ title: '   ', scheduledAt: '2026-11-05T22:30:00Z' }],
    [{ title: 'x'.repeat(101), scheduledAt: '2026-11-05T22:30:00Z' }],
    [{ title: 'Library' }],
    [{ title: 'Library', scheduledAt: 'tomorrow' }],
    [{ title: 'Library', scheduledAt: '2026-13-45T99:99' }],
  ])('Invalid body %j', async (body) => {
    const { organizer, group } = await setupGroup();

    const res = await request(createApp())
      .post(`/api/groups/${group.id}/meetups`)
      .set('Authorization', bearer(organizer.id))
      .send(body);

    expect(res.status).toBe(400);
    expect(await MeetupModel.countDocuments()).toBe(0);
  });

  // Input: caller is not in the group
  // Expected status code: 403
  // Expected behavior: nothing is stored
  test('Non-member', async () => {
    const { group } = await setupGroup();
    const outsider = await createUserN(3);

    const res = await request(createApp())
      .post(`/api/groups/${group.id}/meetups`)
      .set('Authorization', bearer(outsider.id))
      .send({ title: 'Library', scheduledAt: '2026-11-05T22:30:00Z' });

    expect(res.status).toBe(403);
    expect(await MeetupModel.countDocuments()).toBe(0);
  });

  // Input: group id that does not exist / is malformed
  // Expected status code: 404 / 400
  test.each([[MISSING_ID, 404], ['abc', 400]])('Group id %s', async (groupId, status) => {
    const user = await createUserN(1);

    const res = await request(createApp())
      .post(`/api/groups/${groupId}/meetups`)
      .set('Authorization', bearer(user.id))
      .send({ title: 'Library', scheduledAt: '2026-11-05T22:30:00Z' });

    expect(res.status).toBe(status);
  });

  // Input: no Authorization header
  // Expected status code: 401
  test('No token', async () => {
    const res = await request(createApp())
      .post(`/api/groups/${MISSING_ID}/meetups`)
      .send({ title: 'Library', scheduledAt: '2026-11-05T22:30:00Z' });

    expect(res.status).toBe(401);
  });
});

// Interface GET /api/groups/:groupId/meetups
describe('Unmocked: GET /api/groups/:groupId/meetups', () => {
  // Input: a group with two meetups and another group with one
  // Expected status code: 200
  // Expected output: only this group's meetups, latest scheduledAt first
  test('Lists the group\'s meetups, latest first', async () => {
    const { organizer, group } = await setupGroup();
    const otherGroup = await createGroup(organizer.id, { name: 'Other', inviteCode: 'ZZZ999' });
    const early = await createMeetup(group.id, organizer.id, { scheduledAt: new Date('2026-11-01T10:00:00Z') });
    const late = await createMeetup(group.id, organizer.id, { scheduledAt: new Date('2026-12-01T10:00:00Z') });
    await createMeetup(otherGroup.id, organizer.id);

    const res = await request(createApp())
      .get(`/api/groups/${group.id}/meetups`)
      .set('Authorization', bearer(organizer.id));

    expect(res.status).toBe(200);
    expect(res.body.map((m: { id: string }) => m.id)).toEqual([late.id, early.id]);
  });

  // Input: group without meetups
  // Expected status code: 200
  // Expected output: []
  test('Empty list', async () => {
    const { organizer, group } = await setupGroup();

    const res = await request(createApp())
      .get(`/api/groups/${group.id}/meetups`)
      .set('Authorization', bearer(organizer.id));

    expect(res.status).toBe(200);
    expect(res.body).toEqual([]);
  });

  // Input: caller is not in the group / group does not exist
  // Expected status code: 403 / 404
  test('Non-member and unknown group', async () => {
    const { group } = await setupGroup();
    const outsider = await createUserN(3);
    const app = createApp();

    const forbidden = await request(app).get(`/api/groups/${group.id}/meetups`).set('Authorization', bearer(outsider.id));
    const missing = await request(app).get(`/api/groups/${MISSING_ID}/meetups`).set('Authorization', bearer(outsider.id));

    expect(forbidden.status).toBe(403);
    expect(missing.status).toBe(404);
  });
});

// Interface GET /api/meetups/:meetupId
describe('Unmocked: GET /api/meetups/:meetupId', () => {
  // Input: id of a meetup in the caller's group
  // Expected status code: 200
  // Expected output: the meetup
  test('Returns the meetup to any group member', async () => {
    const { organizer, member, group } = await setupGroup();
    const meetup = await createMeetup(group.id, organizer.id);

    const res = await request(createApp()).get(`/api/meetups/${meetup.id}`).set('Authorization', bearer(member.id));

    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ id: meetup.id, groupId: group.id, title: 'Library session', status: 'SETUP' });
  });

  // Input: caller belongs to a different group
  // Expected status code: 403
  test('Member of another group', async () => {
    const { organizer, group } = await setupGroup();
    const meetup = await createMeetup(group.id, organizer.id);
    const outsider = await createUserN(3);
    await createGroup(outsider.id, { name: 'Elsewhere', inviteCode: 'ZZZ999' });

    const res = await request(createApp()).get(`/api/meetups/${meetup.id}`).set('Authorization', bearer(outsider.id));

    expect(res.status).toBe(403);
  });

  // Input: meetup id that does not exist / is malformed
  // Expected status code: 404 / 400
  test.each([[MISSING_ID, 404], ['abc', 400]])('Meetup id %s', async (meetupId, status) => {
    const user = await createUserN(1);

    const res = await request(createApp()).get(`/api/meetups/${meetupId}`).set('Authorization', bearer(user.id));

    expect(res.status).toBe(status);
  });

  // Input: no Authorization header
  // Expected status code: 401
  test('No token', async () => {
    const res = await request(createApp()).get(`/api/meetups/${MISSING_ID}`);

    expect(res.status).toBe(401);
  });
});

// Interface PUT /api/meetups/:meetupId/participation
describe('Unmocked: PUT /api/meetups/:meetupId/participation', () => {
  // Input: a valid participation body from a group member
  // Expected status code: 200
  // Expected behavior: the caller's entry is added
  // Expected output: meetup whose participants contain the entry
  test('Adds the caller\'s entry', async () => {
    const { member, group } = await setupGroup();
    const meetup = await createMeetup(group.id, member.id);

    const res = await request(createApp())
      .put(`/api/meetups/${meetup.id}/participation`)
      .set('Authorization', bearer(member.id))
      .send(PARTICIPATION);

    expect(res.status).toBe(200);
    expect(res.body.participants).toHaveLength(1);
    expect(res.body.participants[0]).toMatchObject({
      userId: member.id,
      startLocation: { lat: 49.2606, lng: -123.246, label: 'UBC' },
      travelMode: 'TRANSIT',
      preferences: ['FOOD', 'COFFEE'],
    });
  });

  // Input: the same member submits twice with different data
  // Expected status code: 200 both times
  // Expected behavior: the second submission replaces the first; no duplicate entry
  test('Resubmitting replaces the entry', async () => {
    const { member, group } = await setupGroup();
    const meetup = await createMeetup(group.id, member.id);
    const app = createApp();
    await request(app).put(`/api/meetups/${meetup.id}/participation`).set('Authorization', bearer(member.id)).send(PARTICIPATION);

    const res = await request(app)
      .put(`/api/meetups/${meetup.id}/participation`)
      .set('Authorization', bearer(member.id))
      .send({ startLocation: { lat: 49.28, lng: -123.12 }, travelMode: 'WALK', preferences: [] });

    expect(res.status).toBe(200);
    expect(res.body.participants).toHaveLength(1);
    expect(res.body.participants[0]).toMatchObject({
      startLocation: { lat: 49.28, lng: -123.12, label: '' },
      travelMode: 'WALK',
      preferences: [],
    });
  });

  // Input: the same member submits identical data twice
  // Expected status code: 200 both times
  // Expected behavior: an unchanged entry is still treated as a replace, not as a conflict
  test('Identical resubmission is accepted', async () => {
    const { member, group } = await setupGroup();
    const meetup = await createMeetup(group.id, member.id);
    const app = createApp();
    const submit = () =>
      request(app).put(`/api/meetups/${meetup.id}/participation`).set('Authorization', bearer(member.id)).send(PARTICIPATION);

    const first = await submit();
    const second = await submit();

    expect(first.status).toBe(200);
    expect(second.status).toBe(200);
    expect(second.body.participants).toHaveLength(1);
  });

  // Input: two members submit entries
  // Expected status code: 200 both times
  // Expected behavior: each keeps their own entry
  test('Entries are per member', async () => {
    const { organizer, member, group } = await setupGroup();
    const meetup = await createMeetup(group.id, organizer.id);
    const app = createApp();
    await request(app).put(`/api/meetups/${meetup.id}/participation`).set('Authorization', bearer(organizer.id)).send(PARTICIPATION);

    const res = await request(app)
      .put(`/api/meetups/${meetup.id}/participation`)
      .set('Authorization', bearer(member.id))
      .send({ ...PARTICIPATION, travelMode: 'DRIVE' });

    expect(res.status).toBe(200);
    expect(res.body.participants.map((p: { userId: string }) => p.userId)).toEqual([organizer.id, member.id]);
  });

  // Input: invalid bodies (latitude / longitude out of range, bad travel mode, bad preference, too many
  //        preferences, over-long label, missing or non-object startLocation, missing travelMode)
  // Expected status code: 400
  // Expected behavior: nothing is stored
  test.each([
    [{ ...PARTICIPATION, startLocation: { lat: 91, lng: 0 } }],
    [{ ...PARTICIPATION, startLocation: { lat: 0, lng: -181 } }],
    [{ ...PARTICIPATION, startLocation: { lat: '49', lng: 0 } }],
    [{ ...PARTICIPATION, startLocation: { lat: 0, lng: 0, label: 'x'.repeat(121) } }],
    [{ ...PARTICIPATION, startLocation: 'UBC' }],
    [{ travelMode: 'WALK', preferences: [] }],
    [{ ...PARTICIPATION, travelMode: 'TELEPORT' }],
    [{ startLocation: PARTICIPATION.startLocation, preferences: [] }],
    [{ ...PARTICIPATION, preferences: ['KARAOKE'] }],
    [{ ...PARTICIPATION, preferences: ['FOOD', 'FOOD', 'FOOD', 'FOOD', 'FOOD', 'FOOD'] }],
    [{ ...PARTICIPATION, preferences: 'FOOD' }],
  ])('Invalid body %j', async (body) => {
    const { member, group } = await setupGroup();
    const meetup = await createMeetup(group.id, member.id);

    const res = await request(createApp())
      .put(`/api/meetups/${meetup.id}/participation`)
      .set('Authorization', bearer(member.id))
      .send(body);

    expect(res.status).toBe(400);
    expect((await MeetupModel.findById(meetup.id).orFail()).participants).toHaveLength(0);
  });

  // Input: meetup already in VOTING, from a member without / with an existing entry
  // Expected status code: 409
  // Expected behavior: entries are frozen once SETUP ends; the stored entry is unchanged
  // Expected output: { error: "Meetup is no longer accepting participation" }
  test('Not in SETUP', async () => {
    const { organizer, member, group } = await setupGroup();
    const meetup = await createMeetup(group.id, organizer.id);
    const app = createApp();
    await request(app).put(`/api/meetups/${meetup.id}/participation`).set('Authorization', bearer(member.id)).send(PARTICIPATION);
    await MeetupModel.updateOne({ _id: meetup.id }, { status: 'VOTING' });

    const newEntry = await request(app)
      .put(`/api/meetups/${meetup.id}/participation`)
      .set('Authorization', bearer(organizer.id))
      .send(PARTICIPATION);
    const replace = await request(app)
      .put(`/api/meetups/${meetup.id}/participation`)
      .set('Authorization', bearer(member.id))
      .send({ ...PARTICIPATION, travelMode: 'DRIVE' });

    expect(newEntry.status).toBe(409);
    expect(replace.status).toBe(409);
    expect(replace.body).toEqual({ error: 'Meetup is no longer accepting participation' });
    const stored = await MeetupModel.findById(meetup.id).orFail();
    expect(stored.participants).toHaveLength(1);
    expect(stored.participants[0]?.travelMode).toBe('TRANSIT');
  });

  // Input: caller is not in the meetup's group
  // Expected status code: 403
  test('Non-member', async () => {
    const { organizer, group } = await setupGroup();
    const meetup = await createMeetup(group.id, organizer.id);
    const outsider = await createUserN(3);

    const res = await request(createApp())
      .put(`/api/meetups/${meetup.id}/participation`)
      .set('Authorization', bearer(outsider.id))
      .send(PARTICIPATION);

    expect(res.status).toBe(403);
  });

  // Input: meetup id that does not exist
  // Expected status code: 404
  test('Unknown meetup', async () => {
    const user = await createUserN(1);

    const res = await request(createApp())
      .put(`/api/meetups/${MISSING_ID}/participation`)
      .set('Authorization', bearer(user.id))
      .send(PARTICIPATION);

    expect(res.status).toBe(404);
  });

  // Input: no Authorization header
  // Expected status code: 401
  test('No token', async () => {
    const res = await request(createApp()).put(`/api/meetups/${MISSING_ID}/participation`).send(PARTICIPATION);

    expect(res.status).toBe(401);
  });
});
