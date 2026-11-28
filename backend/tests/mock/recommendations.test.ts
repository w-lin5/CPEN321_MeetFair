import request, { type Test } from 'supertest';
import * as placesClient from '../../src/integrations/places.client';
import * as routesClient from '../../src/integrations/routes.client';

import { createApp } from '../../src/app';
import { setTransport } from '../../src/realtime/publisher';
import { RecommendationModel } from '../../src/recommendation/models';
import { MeetupModel } from '../../src/users-groups/models';
import { FairCreditModel } from '../../src/voting-fairness/models';
import { bearer } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import { CANDIDATES, addMember, createGroup, createMeetup, createUserN } from '../helpers/factories';

beforeAll(startDb);
beforeEach(() => {
  jest.spyOn(placesClient, 'searchNearby').mockResolvedValue(CANDIDATES);
  // Every participant reaches every place in 10 minutes
  jest.spyOn(routesClient, 'travelSeconds').mockImplementation(async (origins, destinations) =>
    origins.map(() => destinations.map(() => 600)));
});
afterEach(async () => {
  jest.restoreAllMocks();
  setTransport(null);
  await clearDb();
});
afterAll(stopDb);

async function setup() {
  const organizer = await createUserN(1);
  const group = await createGroup(organizer.id);
  const meetup = await createMeetup(group.id, organizer.id);
  await MeetupModel.updateOne({ _id: meetup.id }, { $set: { participants: [{
    userId: organizer.id, startLocation: { lat: 49.26, lng: -123.25 }, travelMode: 'WALK', preferences: [],
  }] } });
  return { organizer, meetup };
}

function generate(meetupId: string, userId: string): Test {
  return request(createApp())
    .post(`/api/meetups/${meetupId}/recommendations/from-places`)
    .set('Authorization', bearer(userId));
}

// Interface POST /api/meetups/:meetupId/recommendations/from-places
describe('Mocked: POST /api/meetups/:meetupId/recommendations/from-places', () => {
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
    expect(res.body).toEqual({ error: 'Internal Server Error' });
    expect(await RecommendationModel.countDocuments({ meetupId: meetup.id })).toBe(0);
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
    expect(res.body).toEqual({ error: 'This meetup already has recommendations' });
    expect(await RecommendationModel.countDocuments({ meetupId: meetup.id })).toBe(0);
    expect((await MeetupModel.findById(meetup.id).orFail()).status).toBe('SETUP');
  });
});

// Interface GET /api/meetups/:meetupId/recommendations
describe('Mocked: GET /api/meetups/:meetupId/recommendations', () => {
  // Input: member of a meetup whose recommendations were generated
  // Expected status code: 500
  // Expected output: { error: "Internal Server Error" } (no internals leaked)
  // Mocked behavior: RecommendationModel.findOne rejects
  test('Database failure while loading recommendations', async () => {
    jest.spyOn(console, 'error').mockImplementation(() => undefined);
    const { organizer, meetup } = await setup();
    expect((await generate(meetup.id, organizer.id)).status).toBe(201);
    jest.spyOn(RecommendationModel, 'findOne').mockRejectedValue(new Error('db down') as never);

    const res = await request(createApp())
      .get(`/api/meetups/${meetup.id}/recommendations`)
      .set('Authorization', bearer(organizer.id));

    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'Internal Server Error' });
  });
});


// Places between three members: one central, one next to each member
const SPREAD_PLACES = ['center', 'near-a', 'near-b', 'near-c'].map((placeId, i) => ({
  placeId, name: placeId, address: '', location: { lat: 49.2 + i / 100, lng: -123.2 }, types: ['cafe'],
}));
// Minutes from members a, b, c (rows) to center, near-a, near-b, near-c (columns)
const SPREAD_MINUTES = [[15, 5, 30, 30], [15, 30, 5, 30], [15, 30, 30, 5]];

async function setupThreeMembers() {
  const [a, b, c] = await Promise.all([createUserN(1), createUserN(2), createUserN(3)]);
  const group = await createGroup(a.id);
  await addMember(group.id, b.id);
  await addMember(group.id, c.id);
  const meetup = await createMeetup(group.id, a.id);
  await MeetupModel.updateOne({ _id: meetup.id }, { $set: { participants: [
    { userId: a.id, startLocation: { lat: 49.2, lng: -123.2 }, travelMode: 'WALK', preferences: ['COFFEE'] },
    { userId: b.id, startLocation: { lat: 49.3, lng: -123.2 }, travelMode: 'TRANSIT', preferences: ['COFFEE'] },
    { userId: c.id, startLocation: { lat: 49.25, lng: -123.1 }, travelMode: 'DRIVE', preferences: ['COFFEE'] },
  ] } });
  jest.spyOn(placesClient, 'searchNearby').mockResolvedValue(SPREAD_PLACES);
  jest.spyOn(routesClient, 'travelSeconds').mockResolvedValue(SPREAD_MINUTES.map(row => row.map(m => m * 60)));
  return { a: a, b: b, c: c, group, meetup };
}

// Interface POST /api/meetups/:meetupId/recommendations/from-places, GET /api/meetups/:meetupId/recommendations
describe('Mocked: fairness-ranked recommendations end to end', () => {
  // Input: two organizer requests begin while SETUP; both wait at the Routes boundary.
  // Mock: release both successful route responses together after both requests arrive.
  // Expected: one 201, one exact 409; one stored recommendation and one pair of success events.
  test('concurrent HTTP generations persist and announce exactly one winner', async () => {
    const { a, meetup } = await setupThreeMembers();
    const events: { room: string; event: string; payload: unknown }[] = [];
    setTransport((room, event, payload) => events.push({ room, event, payload }));
    let entered = 0;
    let bothEntered!: () => void;
    let release!: () => void;
    const waiting = new Promise<void>(resolve => { bothEntered = resolve; });
    const gate = new Promise<void>(resolve => { release = resolve; });
    jest.spyOn(routesClient, 'travelSeconds').mockImplementation(async (origins, destinations) => {
      entered += 1;
      if (entered === 2) bothEntered();
      await gate;
      return origins.map(() => destinations.map(() => 600));
    });
    const requests = [generate(meetup.id, a.id).then(result => result), generate(meetup.id, a.id).then(result => result)];
    try { await waiting; } finally { release(); }
    const results = await Promise.all(requests);
    expect(results.map(result => result.status).sort()).toEqual([201, 409]);
    const rejected = results.find(result => result.status === 409)!;
    expect(rejected.body).toEqual({ error: 'Recommendations can only be generated while the meetup is in SETUP' });
    const accepted = results.find(result => result.status === 201)!;
    const stored = await RecommendationModel.findOne({ meetupId: meetup.id }).orFail();
    expect(await RecommendationModel.countDocuments({ meetupId: meetup.id })).toBe(1);
    expect(stored.id).toBe(accepted.body.id);
    expect(stored.candidates.map(candidate => candidate.placeId)).toEqual(accepted.body.candidates.map((candidate: { placeId: string }) => candidate.placeId));
    expect((await MeetupModel.findById(meetup.id).orFail()).status).toBe('VOTING');
    expect(events).toEqual([
      { room: `meetup:${meetup.id}`, event: 'recommendations:ready', payload: { meetupId: meetup.id } },
      { room: `meetup:${meetup.id}`, event: 'meetup:status', payload: { meetupId: meetup.id, status: 'VOTING' } },
    ]);
  });

  // Mocked behavior: Google returns 4 places; travel times as in SPREAD_MINUTES; nobody has Fair Credit
  // Input: organizer a generates, then member b loads the recommendations
  // Expected status code: 201; 200
  // Expected behavior: the meetup moves to VOTING; "recommendations:ready" and "meetup:status" are published
  // Expected output: exactly 3 candidates (center, near-a, near-b), best first, each with scores and every member's travel
  test('stores the top 3 with their score breakdown and starts voting', async () => {
    const { a, b, meetup } = await setupThreeMembers();
    const events: string[] = [];
    setTransport((_room, event) => events.push(event));

    const res = await generate(meetup.id, a.id);

    expect(res.status).toBe(201);
    expect(res.body.candidates.map((c: { placeId: string }) => c.placeId)).toEqual(['center', 'near-a', 'near-b']);
    expect(res.body.candidates[0].scores).toEqual({ weightedTravel: 15, maxTravel: 15, imbalance: 0, preference: 0, total: 22.5 });
    expect(res.body.candidates[0].travel.map((t: { mode: string }) => t.mode)).toEqual(['WALK', 'TRANSIT', 'DRIVE']);
    expect((await MeetupModel.findById(meetup.id).orFail()).status).toBe('VOTING');
    expect(events).toEqual(['recommendations:ready', 'meetup:status']);

    const loaded = await request(createApp())
      .get(`/api/meetups/${meetup.id}/recommendations`)
      .set('Authorization', bearer(b.id));
    expect(loaded.status).toBe(200);
    expect(loaded.body.candidates[1].scores.total).toBeCloseTo(res.body.candidates[1].scores.total);
  });

  // Mocked behavior: as above, but member c has 5 hours of Fair Credit in the group
  // Input: organizer a generates
  // Expected status code: 201
  // Expected output: near-c (shortest for c) moves up to second place
  test('a member with more Fair Credit gets a place closer to them', async () => {
    const { a, c, group, meetup } = await setupThreeMembers();
    await FairCreditModel.create({ groupId: group.id, userId: c.id, balance: 5 * 60 * 60 });

    const res = await generate(meetup.id, a.id);

    expect(res.status).toBe(201);
    expect(res.body.candidates.map((x: { placeId: string }) => x.placeId)).toEqual(['center', 'near-c', 'near-a']);
  });

  // Mocked behavior: as above
  // Input: member b generates; then organizer a generates twice
  // Expected status code: 403; 201 then 409
  // Expected behavior: the first stored recommendation is kept unchanged
  // Expected output: None
  test('only the organizer can generate, and only once', async () => {
    const { a, b, meetup } = await setupThreeMembers();
    const denied = await generate(meetup.id, b.id);
    expect(denied.status).toBe(403);
    expect(denied.body).toEqual({ error: 'Only the meetup organizer can search for places' });
    expect(await RecommendationModel.countDocuments({ meetupId: meetup.id })).toBe(0);
    expect((await MeetupModel.findById(meetup.id).orFail()).status).toBe('SETUP');

    const first = await generate(meetup.id, a.id);
    expect(first.status).toBe(201);
    const duplicate = await generate(meetup.id, a.id);
    expect(duplicate.status).toBe(409);
    expect(duplicate.body).toEqual({ error: 'Places can only be searched while the meetup is in SETUP' });
    expect(await RecommendationModel.countDocuments({ meetupId: meetup.id })).toBe(1);
    expect((await RecommendationModel.findOne({ meetupId: meetup.id }).orFail()).generatedAt.toISOString())
      .toBe(first.body.generatedAt);
  });
});
