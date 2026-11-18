import mongoose from 'mongoose';
import request, { type Test } from 'supertest';

import { createApp } from '../../src/app';
import { UpstreamError } from '../../src/common/errors';
import { signSessionToken } from '../../src/common/session';
import { calendarClient } from '../../src/integrations/calendar.client';
import * as places from '../../src/integrations/places.client';
import * as routes from '../../src/integrations/routes.client';
import { RecommendationModel } from '../../src/recommendation/models';
import { MeetupModel } from '../../src/users-groups/models';
import { VoteModel } from '../../src/voting-fairness/models';
import { bearer } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import {
  CANDIDATES, PARTICIPATION, PLACE_1, addMember, addParticipants,
  createFinalizedMeetup, createGroup, createMeetup, createUserN, createVotingMeetup,
} from '../helpers/factories';

// Requirements 3.7.1 / 4.7.1: real middleware, services and isolated MongoDB.
// Only external Google operations are mocked; deployment TLS is tested separately.
const app = createApp();
beforeAll(startDb);
afterEach(async () => {
  jest.restoreAllMocks();
  await clearDb();
});
afterAll(stopDb);

async function setup() {
  const owner = await createUserN(1);
  const organizer = await createUserN(2);
  const member = await createUserN(3);
  const outsider = await createUserN(4);
  const observer = await createUserN(5);
  const group = await createGroup(owner.id);
  for (const user of [organizer, member, observer]) await addMember(group.id, user.id);
  const meetup = await createVotingMeetup(group.id, organizer.id, [organizer.id, member.id]);
  return { owner, organizer, member, outsider, observer, group, meetup };
}

function expiredToken(userId: string): string {
  const now = Date.now();
  const clock = jest.spyOn(Date, 'now').mockReturnValue(now - 8 * 24 * 60 * 60 * 1000);
  try { return signSessionToken(userId); } finally { clock.mockRestore(); }
}

function protectedRequests(groupId: string, meetupId: string, includeProfile = true): Test[] {
  return [
    ...(includeProfile ? [request(app).get('/api/users/me/profile')] : []),
    request(app).get(`/api/groups/${groupId}`),
    request(app).get(`/api/groups/${groupId}/fairness`),
    request(app).get(`/api/meetups/${meetupId}`),
    request(app).get(`/api/meetups/${meetupId}/recommendations`),
    request(app).get(`/api/meetups/${meetupId}/votes`),
    request(app).get(`/api/meetups/${meetupId}/trips`),
    request(app).put(`/api/meetups/${meetupId}/participation`).send(PARTICIPATION),
    request(app).put(`/api/meetups/${meetupId}/vote`).send({ placeId: PLACE_1 }),
    request(app).post(`/api/meetups/${meetupId}/recommendations/from-places`),
    request(app).post(`/api/meetups/${meetupId}/finalize`).send({ placeId: PLACE_1 }),
    request(app).post(`/api/meetups/${meetupId}/calendar-event`).send({ accessToken: 'nfr-unused-token' }),
  ];
}

describe('NFR SEC-2: verify session tokens before protected reads or changes', () => {
  test.each(['missing', 'tampered', 'expired'] as const)('rejects %s tokens without changing stored data', async kind => {
    const { member, group, meetup } = await setup();
    const before = JSON.stringify(await MeetupModel.findById(meetup.id).lean());
    const token = kind === 'expired' ? expiredToken(member.id) : `${signSessionToken(member.id)}x`;
    const error = kind === 'missing' ? 'Missing bearer token'
      : kind === 'expired' ? 'Invalid or expired session token' : 'Invalid session token';
    for (const call of protectedRequests(group.id, meetup.id)) {
      if (kind !== 'missing') call.set('Authorization', `Bearer ${token}`);
      const response = await call;
      expect(response.status).toBe(401);
      expect(response.body).toEqual({ error });
    }
    expect(JSON.stringify(await MeetupModel.findById(meetup.id).lean())).toBe(before);
    expect(await VoteModel.countDocuments()).toBe(0);
    expect(await RecommendationModel.countDocuments()).toBe(1);
  });

  test('uses the verified identity rather than a userId supplied in the body', async () => {
    const { member, organizer, meetup } = await setup();
    const result = await request(app).put(`/api/meetups/${meetup.id}/vote`)
      .set('Authorization', bearer(member.id)).send({ placeId: PLACE_1, userId: organizer.id });
    expect(result.status).toBe(200);
    expect(result.body.userId).toBe(member.id);
    expect(await VoteModel.countDocuments({ userId: organizer.id })).toBe(0);
  });
});

describe('NFR SEC-3: enforce membership and meetup organizer permissions', () => {
  test('outsiders cannot read group/meetup data or change participation and votes', async () => {
    const { outsider, group, meetup } = await setup();
    const before = JSON.stringify(await MeetupModel.findById(meetup.id).lean());
    for (const call of protectedRequests(group.id, meetup.id, false)) {
      const result = await call.set('Authorization', bearer(outsider.id));
      expect(result.status).toBe(403);
      expect(result.body).toEqual({ error: 'Not a member of this group' });
    }
    expect(JSON.stringify(await MeetupModel.findById(meetup.id).lean())).toBe(before);
    expect(await VoteModel.countDocuments()).toBe(0);
  });

  test('members can read, but only participants can vote', async () => {
    const { member, observer, group, meetup } = await setup();
    const detail = await request(app).get(`/api/groups/${group.id}`).set('Authorization', bearer(member.id));
    expect(detail.status).toBe(200);
    expect(detail.body.id).toBe(group.id);
    const votes = await request(app).get(`/api/meetups/${meetup.id}/votes`).set('Authorization', bearer(observer.id));
    expect(votes.status).toBe(200);
    const denied = await request(app).put(`/api/meetups/${meetup.id}/vote`)
      .set('Authorization', bearer(observer.id)).send({ placeId: PLACE_1 });
    expect(denied.status).toBe(403);
    expect(denied.body).toEqual({ error: 'Only participants can do this' });
    expect(await VoteModel.countDocuments()).toBe(0);
  });

  test('group ownership does not grant another meetup organizer permissions', async () => {
    const { owner, organizer, member, group, meetup } = await setup();
    const pending = await createMeetup(group.id, organizer.id);
    await addParticipants(pending.id, [organizer.id, member.id]);
    const search = jest.spyOn(places, 'searchNearby').mockResolvedValue(CANDIDATES);
    const matrix = jest.spyOn(routes, 'travelSeconds').mockResolvedValue([[600, 900, 1200], [900, 600, 1200]]);
    for (const user of [owner, member]) {
      const start = await request(app).post(`/api/meetups/${pending.id}/recommendations/from-places`)
        .set('Authorization', bearer(user.id)).send({ organizerId: user.id });
      expect(start.status).toBe(403);
      expect(start.body).toEqual({ error: 'Only the meetup organizer can search for places' });
      const finalize = await request(app).post(`/api/meetups/${meetup.id}/finalize`)
        .set('Authorization', bearer(user.id)).send({ placeId: PLACE_1, organizerId: user.id });
      expect(finalize.status).toBe(403);
      expect(finalize.body).toEqual({ error: 'Only the meetup organizer can finalize' });
    }
    expect(search).not.toHaveBeenCalled();
    expect(matrix).not.toHaveBeenCalled();
    expect((await MeetupModel.findById(pending.id).orFail()).status).toBe('SETUP');
    expect((await MeetupModel.findById(meetup.id).orFail()).status).toBe('VOTING');

    const started = await request(app).post(`/api/meetups/${pending.id}/recommendations/from-places`)
      .set('Authorization', bearer(organizer.id));
    expect(started.status).toBe(201);
    expect((await MeetupModel.findById(pending.id).orFail()).status).toBe('VOTING');
    const finalized = await request(app).post(`/api/meetups/${meetup.id}/finalize`)
      .set('Authorization', bearer(organizer.id)).send({ placeId: PLACE_1 });
    expect(finalized.status).toBe(200);
    expect(finalized.body).toMatchObject({ status: 'FINALIZED', winner: { placeId: PLACE_1 } });
  });
});

describe('NFR SEC-4: Google access tokens must not be stored in MongoDB', () => {
  test.each(['success', 'provider failure'] as const)('does not persist the Calendar token after %s', async outcome => {
    const { organizer, group } = await setup();
    const meetup = await createFinalizedMeetup(group.id, organizer.id, [organizer.id]);
    // Unique recognizable sentinel, never a real credential.
    const token = `nfr-calendar-token-${meetup.id}`;
    const google = jest.spyOn(calendarClient, 'createEvent');
    if (outcome === 'success') google.mockResolvedValue({ externalId: 'nfr-event', htmlLink: null, duplicate: false });
    else google.mockRejectedValue(new UpstreamError('Failed to create event in Google Calendar.'));
    const result = await request(app).post(`/api/meetups/${meetup.id}/calendar-event`)
      .set('Authorization', bearer(organizer.id)).send({ accessToken: token });
    expect(result.status).toBe(outcome === 'success' ? 201 : 502);
    expect(result.body).toEqual(outcome === 'success'
      ? { eventId: 'nfr-event', htmlLink: null }
      : { error: 'Failed to create event in Google Calendar.' });
    expect(google).toHaveBeenCalledWith(token, expect.objectContaining({ title: 'Library session' }));
    expect(JSON.stringify(result.body)).not.toContain(token);
    // Raw collection reads bypass Mongoose projections/serialization that could hide a stored token.
    const db = mongoose.connection.db;
    if (!db) throw new Error('Test database is not connected');
    for (const { name } of await db.listCollections().toArray()) {
      const rows = await db.collection(name).find({}).toArray();
      expect(JSON.stringify(rows)).not.toContain(token);
      expect(JSON.stringify(rows)).not.toMatch(/"(?:accessToken|access_token)"\s*:/);
    }
    const stored = await MeetupModel.findById(meetup.id).orFail();
    if (outcome === 'success') expect(stored.participants[0]?.calendarEventId).toBe('nfr-event');
    else expect(stored.participants[0]?.calendarEventId).toBeUndefined();
  });
});
