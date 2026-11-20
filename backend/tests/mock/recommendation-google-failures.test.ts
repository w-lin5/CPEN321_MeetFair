import request from 'supertest';
import { createApp } from '../../src/app';
import { RecommendationModel } from '../../src/recommendation/models';
import { setTransport } from '../../src/realtime/publisher';
import { MeetupModel } from '../../src/users-groups/models';
import { VoteModel } from '../../src/voting-fairness/models';
import { bearer } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import { addParticipants, createGroup, createMeetup, createUserN, futureScheduledAt } from '../helpers/factories';

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

async function setup(endpoint: string) {
  const organizer = await createUserN(1);
  const group = await createGroup(organizer.id);
  const meetup = await createMeetup(group.id, organizer.id, { scheduledAt: new Date(futureScheduledAt()) });
  await addParticipants(meetup.id, [organizer.id]);
  const before = await MeetupModel.findById(meetup.id).lean();
  const events: unknown[] = [];
  setTransport((room, event, payload) => events.push({ room, event, payload }));
  return {
    call: () => request(app).post(`/api/meetups/${meetup.id}/${endpoint}`).set('Authorization', bearer(organizer.id)),
    assertUnchanged: async () => {
      expect(await MeetupModel.findById(meetup.id).lean()).toEqual(before);
      expect(await RecommendationModel.countDocuments({ meetupId: meetup.id })).toBe(0);
      expect(await VoteModel.countDocuments({ meetupId: meetup.id })).toBe(0);
      expect(events).toEqual([]);
    },
  };
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
