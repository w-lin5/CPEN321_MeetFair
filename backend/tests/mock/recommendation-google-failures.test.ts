import request from 'supertest';
import { createApp } from '../../src/app';
import { RecommendationModel } from '../../src/recommendation/models';
import { setTransport } from '../../src/realtime/publisher';
import { MeetupModel } from '../../src/users-groups/models';
import { VoteModel } from '../../src/voting-fairness/models';
import { bearer } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import { addMember, addParticipants, createGroup, createMeetup, createUserN, futureScheduledAt, PARTICIPATION } from '../helpers/factories';

// Real routes, clients, services and MongoDB. Mock only Google's HTTP boundary.
const app = createApp();
const secret = 'google-failure-test-secret';
const originalKey = process.env.GOOGLE_PLACES_API_KEY;
const places = [1, 2, 3].map(number => ({
  id: `open-venue-${number}`, displayName: { text: `Open venue ${number}` },
  location: { latitude: 49.26, longitude: -123.25 }, types: ['cafe'],
  businessStatus: 'OPERATIONAL', timeZone: { id: 'America/Vancouver' },
  regularOpeningHours: { periods: [{ open: { day: 0, hour: 0 } }] },
}));
beforeAll(startDb);
beforeEach(() => { process.env.GOOGLE_PLACES_API_KEY = secret; });
afterEach(async () => {
  jest.restoreAllMocks();
  setTransport(null);
  if (originalKey === undefined) delete process.env.GOOGLE_PLACES_API_KEY;
  else process.env.GOOGLE_PLACES_API_KEY = originalKey;
  await clearDb();
});
afterAll(stopDb);

async function setup(endpoint: string, scheduledAt = new Date(futureScheduledAt())) {
  const organizer = await createUserN(1);
  const group = await createGroup(organizer.id);
  const meetup = await createMeetup(group.id, organizer.id, { scheduledAt });
  await addParticipants(meetup.id, [organizer.id]);
  const before = await MeetupModel.findById(meetup.id).lean();
  const events: unknown[] = [];
  setTransport((room, event, payload) => events.push({ room, event, payload }));
  return {
    meetupId: meetup.id,
    organizerId: organizer.id,
    groupId: group.id,
    call: () => request(app).post(`/api/meetups/${meetup.id}/${endpoint}`).set('Authorization', bearer(organizer.id)),
    assertUnchanged: async () => {
      expect(await MeetupModel.findById(meetup.id).lean()).toEqual(before);
      expect(await RecommendationModel.countDocuments({ meetupId: meetup.id })).toBe(0);
      expect(await VoteModel.countDocuments({ meetupId: meetup.id })).toBe(0);
      expect(events).toEqual([]);
    },
  };
}

function googleResponses(...responses: Response[]) {
  const fetchMock = jest.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('Unexpected Google request'));
  for (const response of responses) fetchMock.mockResolvedValueOnce(response);
  return fetchMock;
}

const scenarios = [
  { endpoint: 'places/search', provider: 'Places', requests: 1, message: 'Unable to retrieve nearby places. Please try again later.' },
  { endpoint: 'recommendations/from-places', provider: 'Places', requests: 1, message: 'Unable to retrieve nearby places. Please try again later.' },
  { endpoint: 'recommendations/from-places', provider: 'Routes', requests: 2, message: 'Unable to estimate travel times. Please try again later.' },
] as const;

describe.each(scenarios)('Mocked Google failures: $endpoint / $provider', scenario => {
  // Input: organizer requests preview/generation for a valid SETUP meetup.
  // Mock: Google Places or Routes responds with 403, 429 or 500 and sensitive details.
  // Expected: exact safe 502 error, no persisted changes, votes or success events.
  test.each([403, 429, 500])('sanitizes Google HTTP %i and preserves all meetup state', async status => {
    const fixture = await setup(scenario.endpoint);
    const fetchMock = jest.spyOn(globalThis, 'fetch').mockImplementation(async url => {
      if (scenario.provider === 'Routes' && String(url).includes('places.googleapis.com')) {
        return new Response(JSON.stringify({ places }));
      }
      return new Response(JSON.stringify({ error: { message: secret } }), { status });
    });
    const result = await fixture.call();
    expect(result.status).toBe(502);
    expect(result.body).toEqual({ error: scenario.message });
    expect(JSON.stringify(result.body)).not.toContain(secret);
    expect(fetchMock).toHaveBeenCalledTimes(scenario.requests);
    if (scenario.provider === 'Routes') expect(String(fetchMock.mock.calls[1]?.[0])).toContain('routes.googleapis.com');
    await fixture.assertUnchanged();
  });

  // Mock only the deadline signal; actual client fetch observes its abort event.
  // Trigger the timeout deterministically after fetch starts, with no 10-second sleep,
  // frozen wall clock or fake MongoDB/network timers.
  // Expected: the 10-second deadline is wired into fetch, safe 502, unchanged DB/events.
  test('handles an aborted Google deadline without starting voting or leaking details', async () => {
    const fixture = await setup(scenario.endpoint);
    const controller = new AbortController();
    const deadline = jest.spyOn(AbortSignal, 'timeout').mockReturnValue(controller.signal);
    let entered!: () => void;
    const fetching = new Promise<void>(resolve => { entered = resolve; });
    const fetchMock = jest.spyOn(globalThis, 'fetch').mockImplementation(async (url, options) => {
      if (scenario.provider === 'Routes' && String(url).includes('places.googleapis.com')) {
        return new Response(JSON.stringify({ places }));
      }
      return new Promise<Response>((_resolve, reject) => {
        const signal = options?.signal;
        if (!signal) { reject(new Error('Missing timeout signal')); return; }
        signal.addEventListener('abort', () => reject(signal.reason), { once: true });
        entered();
      });
    });
    const pending = fixture.call().then(result => result);
    try {
      await fetching;
      expect(deadline).toHaveBeenCalledWith(10_000);
      controller.abort(new DOMException(secret, 'TimeoutError'));
      const result = await pending;
      expect(result.status).toBe(502);
      expect(result.body).toEqual({ error: scenario.message });
      expect(JSON.stringify(result.body)).not.toContain(secret);
      expect(fetchMock).toHaveBeenCalledTimes(scenario.requests);
      await fixture.assertUnchanged();
    } finally {
      controller.abort();
      await pending;
    }
  });
});

describe('Mocked: POST /api/meetups/:meetupId/places/search (provider data)', () => {
  // Input: valid meetup preview. Mock: Google omits places for an empty result.
  // Expected: 200 with an empty list and an explanation, without opening voting or saving candidates.
  test('treats an omitted places field as an empty search', async () => {
    const fixture = await setup('places/search');
    googleResponses(Response.json({}));
    const result = await fixture.call();
    expect(result.status).toBe(200);
    expect(result.body).toEqual({ candidates: [], message: 'No nearby places could be confirmed open at the meetup time.' });
    await fixture.assertUnchanged();
  });

  // Input: a meetup two days from now at noon UTC. Mock: weekly hours, a dated closure,
  // incomplete opening-hours records, and invalid candidate data from Google Places.
  // Expected: 200 with only the two confirmed-open, valid candidates and no state changes.
  test('filters a mixed provider response using the scheduled time and current-hours override', async () => {
    const scheduledAt = new Date(Date.now() + 2 * 86_400_000);
    scheduledAt.setUTCHours(12, 0, 0, 0);
    const fixture = await setup('places/search', scheduledAt);
    const [open, closed, malformed] = places;
    const day = scheduledAt.getUTCDay();
    const regularOpeningHours = { periods: [{ open: { day, hour: 9 }, close: { day, hour: 17 } }] };
    const valid = { ...open!, timeZone: { id: 'UTC' }, regularOpeningHours, formattedAddress: '123 Test Street' };
    const date = { year: scheduledAt.getUTCFullYear(), month: scheduledAt.getUTCMonth() + 1, day: scheduledAt.getUTCDate() };
    const special = { ...closed!, id: 'exceptional-open', timeZone: valid.timeZone, regularOpeningHours: { periods: [] },
      currentOpeningHours: { periods: [{ open: { date, hour: 11 }, close: { date, hour: 13 } }] } };
    googleResponses(Response.json({ places: [
      valid,
      { ...closed!, timeZone: valid.timeZone, regularOpeningHours, currentOpeningHours: { periods: [] } },
      special,
      { ...malformed!, id: 'invalid-hours', regularOpeningHours: { periods: [null, { open: null }] } },
      { ...malformed!, id: 'invalid-address', formattedAddress: 42 },
      { ...malformed!, id: 'oversized-address', formattedAddress: 'x'.repeat(301) },
      valid,
    ] }));
    const result = await fixture.call();
    expect(result.status).toBe(200);
    expect(result.body).toEqual({ candidates: [
      { placeId: valid.id, name: valid.displayName.text, address: '123 Test Street', location: { lat: 49.26, lng: -123.25 }, types: ['cafe'] },
      { placeId: special.id, name: special.displayName.text, address: '', location: { lat: 49.26, lng: -123.25 }, types: ['cafe'] },
    ], message: null });
    await fixture.assertUnchanged();
  });

  // Input: a Sunday meetup at 00:30 in the venue timezone. Mock: Saturday-night
  // schedules with two closing times, plus a venue with an unsupported timezone.
  // Expected: 200 with the venue open until 01:00 only, and unchanged meetup state.
  test('keeps an overnight venue open across the week boundary until its closing time', async () => {
    const scheduledAt = new Date(Date.now() + 86_400_000);
    scheduledAt.setUTCDate(scheduledAt.getUTCDate() + (7 - scheduledAt.getUTCDay()) % 7);
    scheduledAt.setUTCHours(0, 30, 0, 0);
    const fixture = await setup('places/search', scheduledAt);
    const [overnight, closesAtMeetup, invalidZone] = places;
    const open = { day: 6, hour: 22 };
    const hours = (hour: number, minute: number) => ({ periods: [{ open, close: { day: 0, hour, minute } }] });
    googleResponses(Response.json({ places: [
      { ...overnight!, timeZone: { id: 'UTC' }, regularOpeningHours: hours(1, 0) },
      { ...closesAtMeetup!, timeZone: { id: 'UTC' }, regularOpeningHours: hours(0, 30) },
      { ...invalidZone!, timeZone: { id: 'Unknown/Timezone' }, regularOpeningHours: hours(1, 0) },
    ] }));
    const result = await fixture.call();
    expect(result.status).toBe(200);
    expect(result.body).toEqual({ candidates: [{ placeId: overnight!.id, name: overnight!.displayName.text, address: '',
      location: { lat: 49.26, lng: -123.25 }, types: ['cafe'] }], message: null });
    await fixture.assertUnchanged();
  });
});

describe('Mocked: POST /api/meetups/:meetupId/recommendations/from-places (Google Routes data)', () => {
  // Input: three confirmed-open venues. Mock: two valid routes and malformed or unreachable
  // entries for the third, including bad indices and invalid durations from Google.
  // Expected: 400 with no persisted recommendations, votes, status changes or events.
  test('rejects generation when matrix entries leave fewer than three reachable places', async () => {
    const fixture = await setup('recommendations/from-places');
    const fetchMock = googleResponses(Response.json({ places }), Response.json([
        { condition: 'ROUTE_EXISTS', duration: '360s' },
        { condition: 'ROUTE_EXISTS', destinationIndex: 1, duration: '300s' },
        null,
        { condition: 'ROUTE_NOT_FOUND', destinationIndex: 2, duration: '120s' },
        { condition: 'ROUTE_EXISTS', originIndex: -1, destinationIndex: 2, duration: '120s' },
        { condition: 'ROUTE_EXISTS', originIndex: 0.5, destinationIndex: 2, duration: '120s' },
        { condition: 'ROUTE_EXISTS', destinationIndex: 3, duration: '120s' },
        { condition: 'ROUTE_EXISTS', destinationIndex: 2, duration: 120 },
        { condition: 'ROUTE_EXISTS', destinationIndex: 2, duration: '120' },
        { condition: 'ROUTE_EXISTS', destinationIndex: 2, duration: 'NaNs' },
        { condition: 'ROUTE_EXISTS', destinationIndex: 2, duration: '-30s' },
        { condition: 'ROUTE_EXISTS', destinationIndex: 2, duration: 's' },
      ]));
    const result = await fixture.call();
    expect(result.status).toBe(400);
    expect(result.body).toEqual({ error: 'Not enough nearby places found. At least 3 are needed to start voting.' });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    await fixture.assertUnchanged();
  });

  // Input: three confirmed-open venues. Mock: Google returns a non-array body with HTTP 200.
  // Expected: safe 502 and no persisted state or success events.
  test('handles an unexpected Routes response shape', async () => {
    const fixture = await setup('recommendations/from-places');
    googleResponses(Response.json({ places }), Response.json({ message: secret }));
    const result = await fixture.call();
    expect(result.status).toBe(502);
    expect(result.body).toEqual({ error: 'Unable to estimate travel times. Please try again later.' });
    await fixture.assertUnchanged();
  });

  // Input: eleven transit riders, one walker and ten venues. Mock: Google returns one
  // matrix per requested batch, with omitted zero indices and fractional durations.
  // Expected: 201 and VOTING. Every selected venue has the correct estimate for all 12 members.
  test('keeps travel estimates aligned across matrix batches and travel modes', async () => {
    const fixture = await setup('recommendations/from-places');
    const memberIds = [fixture.organizerId];
    for (let i = 2; i <= 12; i++) {
      const user = await createUserN(i);
      await addMember(fixture.groupId, user.id);
      memberIds.push(user.id);
    }
    const startLat = (index: number) => 49 + index / 100;
    const modeAt = (index: number) => index === 1 ? 'WALK' : 'TRANSIT';
    await MeetupModel.updateOne({ _id: fixture.meetupId }, { $set: { participants: memberIds.map((userId, index) => ({
      ...PARTICIPATION, userId, travelMode: modeAt(index), startLocation: { ...PARTICIPATION.startLocation, lat: startLat(index) },
    })) } });
    const venues = Array.from({ length: 10 }, (_, index) => ({ ...places[0]!, id: `venue-${index}` }));
    const requests: { mode: string; origins: number; destinations: number }[] = [];
    type Waypoint = { waypoint: { location: { latLng: { latitude: number } } } };
    const secondsFor = (member: number, venue: number) => 120.5 + member + venue * 60;
    jest.spyOn(globalThis, 'fetch').mockImplementation(async (url, options) => {
      const host = new URL(String(url)).hostname;
      if (host === 'places.googleapis.com') return Response.json({ places: venues });
      if (host !== 'routes.googleapis.com') throw new Error('Unexpected Google request');
      const matrix = JSON.parse(String(options?.body)) as { origins: Waypoint[]; destinations: Waypoint[]; travelMode: string };
      requests.push({ mode: matrix.travelMode, origins: matrix.origins.length, destinations: matrix.destinations.length });
      return Response.json(matrix.origins.flatMap((origin, i) => matrix.destinations.map((_, j) => ({
        condition: 'ROUTE_EXISTS', originIndex: i || undefined, destinationIndex: j || undefined,
        duration: `${secondsFor(Math.round((origin.waypoint.location.latLng.latitude - 49) * 100), j)}s`,
      }))));
    });
    const result = await fixture.call();
    expect(result.status).toBe(201);
    expect(requests).toHaveLength(3);
    expect(requests).toEqual(expect.arrayContaining([
      { mode: 'TRANSIT', origins: 10, destinations: 10 },
      { mode: 'TRANSIT', origins: 1, destinations: 10 },
      { mode: 'WALK', origins: 1, destinations: 10 },
    ]));
    const saved = await RecommendationModel.findOne({ meetupId: fixture.meetupId }).orFail();
    const expectedIds = ['venue-0', 'venue-1', 'venue-2'];
    expect(result.body.candidates.map((candidate: { placeId: string }) => candidate.placeId)).toEqual(expectedIds);
    expect(saved.candidates.map(candidate => candidate.placeId)).toEqual(expectedIds);
    saved.candidates.forEach((candidate, venue) => {
      expect(candidate.travel?.map(row => ({ userId: String(row.userId), mode: row.mode, seconds: row.seconds })))
        .toEqual(memberIds.map((userId, member) => ({ userId, mode: modeAt(member), seconds: secondsFor(member, venue) })));
    });
    expect((await MeetupModel.findById(fixture.meetupId).orFail()).status).toBe('VOTING');
  });
});
