import request, { type Test } from 'supertest';

import { createApp } from '../../src/app';
import { Forbidden, NotFound, UpstreamError } from '../../src/common/errors';
import { searchNearby } from '../../src/integrations/places.client';
import { travelSeconds } from '../../src/integrations/routes.client';
import { meetupService } from '../../src/users-groups/meetup.service';
import { bearer } from '../helpers/auth';
import { recommendationService } from '../../src/recommendation/recommendation.service';
import { fairCreditService } from '../../src/voting-fairness/fair-credit.service';

jest.mock('../../src/integrations/places.client');
jest.mock('../../src/integrations/routes.client');
jest.mock('../../src/voting-fairness/fair-credit.service');
jest.mock('../../src/users-groups/meetup.service');
jest.mock('../../src/recommendation/recommendation.service');

const organizerId = '507f1f77bcf86cd799439011';
const meetupId = '507f1f77bcf86cd799439012';
const searchMock = jest.mocked(searchNearby);
const travelMock = jest.mocked(travelSeconds);
const balancesMock = jest.mocked(fairCreditService.getBalances);
const memberMock = jest.mocked(meetupService.getForMember);

beforeEach(() => {
  jest.resetAllMocks();
  memberMock.mockResolvedValue({ organizerId, status: 'SETUP', participants: [{ userId: organizerId, preferences: [], startLocation: { lat: 49.2, lng: -123.1 } }, { userId: 'other', preferences: ['FOOD'], startLocation: { lat: 49.4, lng: -123.3 } }] } as unknown as Awaited<ReturnType<typeof meetupService.getForMember>>);
  searchMock.mockResolvedValue([]);
});

function search(userId = organizerId, id = meetupId): Test {
  return request(createApp()).post(`/api/meetups/${id}/places/search`).set('Authorization', bearer(userId));
}

// Interface POST /api/meetups/:meetupId/places/search
describe('Mocked: POST /api/meetups/:meetupId/places/search', () => {
  // Mocked behavior: getForMember returns the default SETUP meetup; searchNearby returns one place
  // Input: organizer searches
  // Expected status code: 200
  // Expected behavior: Google is called once around the participants' average location (49.3, -123.2); the meetup is not transitioned
  // Expected output: { candidates: [the place], message: null }
  test('returns candidates without saving or transitioning the meetup', async () => {
    const candidates = [{ placeId: 'cafe-1', name: 'Cafe', address: 'UBC', location: { lat: 49.26, lng: -123.25 } }];
    searchMock.mockResolvedValue(candidates);
    const res = await search();
    expect(res.status).toBe(200);
    expect(res.body).toEqual({ candidates, message: null });
    expect(memberMock).toHaveBeenCalledWith(meetupId, organizerId);
    expect(searchMock).toHaveBeenCalledTimes(1);
    expect(searchMock.mock.calls[0]?.[0].lat).toBeCloseTo(49.3);
    expect(searchMock.mock.calls[0]?.[0].lng).toBeCloseTo(-123.2);
    expect(meetupService.transitionStatus).not.toHaveBeenCalled();
  });

  // Mocked behavior: getForMember returns a SETUP meetup with the organizer and one other participant; searchNearby returns []
  // Input: organizer searches
  // Expected status code: 200
  // Expected behavior: the empty result is reported, not treated as an error
  // Expected output: { candidates: [], message: "No nearby places found." }
  test('returns a clear empty-result message', async () => {
    const res = await search();
    expect(res.status).toBe(200);
    expect(res.body).toEqual({ candidates: [], message: 'No nearby places found.' });
  });

  // Mocked behavior: getForMember returns a member (COFFEE, 40/-74) and the organizer (OUTDOORS + FOOD, 42/-72)
  // Input: organizer searches
  // Expected status code: 200
  // Expected behavior: Google is called with center (41, -73) and every participant's types ["cafe", "park", "restaurant"]
  // Expected output: None
  test('searches every participant\'s preferences around every submitted location', async () => {
    memberMock.mockResolvedValue({ organizerId, status: 'SETUP', participants: [
      { userId: 'other', preferences: ['COFFEE'], startLocation: { lat: 40, lng: -74 } },
      { userId: organizerId, preferences: ['OUTDOORS', 'FOOD'], startLocation: { lat: 42, lng: -72 } },
    ] } as unknown as Awaited<ReturnType<typeof meetupService.getForMember>>);
    expect((await search()).status).toBe(200);
    expect(searchMock).toHaveBeenCalledWith({ lat: 41, lng: -73 }, ['cafe', 'park', 'restaurant']);
  });

  // Mocked behavior: getForMember returns a meetup where only a non-organizer member submitted details
  // Input: organizer searches
  // Expected status code: 400
  // Expected behavior: Google is not called
  // Expected output: error mentioning that the organizer must submit
  test('requires organizer participation even when another member submitted', async () => {
    memberMock.mockResolvedValue({ organizerId, status: 'SETUP', participants: [
      { userId: 'other', preferences: ['FOOD'], startLocation: { lat: 40, lng: -74 } },
    ] } as unknown as Awaited<ReturnType<typeof meetupService.getForMember>>);
    const res = await search();
    expect(res.status).toBe(400);
    expect(res.body.error).toContain('organizer must submit');
    expect(searchMock).not.toHaveBeenCalled();
  });

  // Mocked behavior: getForMember returns a meetup with only the organizer at (0, 0) and no preferences
  // Input: organizer searches
  // Expected status code: 200
  // Expected behavior: Google is called at exactly (0, 0) with the default ["cafe"] type
  // Expected output: None
  test('a single participant searches around their exact chosen location', async () => {
    memberMock.mockResolvedValue({ organizerId, status: 'SETUP', participants: [
      { userId: organizerId, preferences: [], startLocation: { lat: 0, lng: 0 } },
    ] } as unknown as Awaited<ReturnType<typeof meetupService.getForMember>>);
    expect((await search()).status).toBe(200);
    expect(searchMock).toHaveBeenCalledWith({ lat: 0, lng: 0 }, ['cafe']);
  });

  // Mocked behavior: getForMember returns a SETUP meetup with the organizer and one other participant; searchNearby returns []
  // Input: request without a token
  // Expected status code: 401
  // Expected behavior: Google is not called
  // Expected output: None
  test('requires authentication', async () => {
    const res = await request(createApp()).post(`/api/meetups/${meetupId}/places/search`);
    expect(res.status).toBe(401);
    expect(searchMock).not.toHaveBeenCalled();
  });

  // Mocked behavior: getForMember returns a SETUP meetup with the organizer and one other participant; searchNearby returns []
  // Input: malformed meetup id
  // Expected status code: 400
  // Expected behavior: Google is not called
  // Expected output: None
  test('rejects an invalid meetup id before calling Google', async () => {
    expect((await search(organizerId, 'invalid')).status).toBe(400);
    expect(searchMock).not.toHaveBeenCalled();
  });

  // Mocked behavior: getForMember returns a SETUP meetup with the organizer and one other participant; searchNearby returns []
  // Input: a user who is not the meetup organizer searches
  // Expected status code: 403
  // Expected behavior: Google is not called
  // Expected output: None
  test('requires the organizer', async () => {
    expect((await search('507f1f77bcf86cd799439013')).status).toBe(403);
    expect(searchMock).not.toHaveBeenCalled();
  });

  // Mocked behavior: getForMember rejects with Forbidden, then NotFound
  // Input: organizer searches
  // Expected status code: 403; 404
  // Expected behavior: Google is not called
  // Expected output: None
  test.each([new Forbidden(), new NotFound()])('respects membership and existence checks: %s', async (error) => {
    memberMock.mockRejectedValue(error);
    expect((await search()).status).toBe(error.status);
    expect(searchMock).not.toHaveBeenCalled();
  });

  // Mocked behavior: getForMember returns a meetup in VOTING, FINALIZED or SETTLED
  // Input: organizer searches
  // Expected status code: 409
  // Expected behavior: Google is not called
  // Expected output: None
  test.each(['VOTING', 'FINALIZED', 'SETTLED'])('rejects status %s', async (status) => {
    memberMock.mockResolvedValue({ organizerId, status } as unknown as Awaited<ReturnType<typeof meetupService.getForMember>>);
    expect((await search()).status).toBe(409);
    expect(searchMock).not.toHaveBeenCalled();
  });

  // Mocked behavior: searchNearby rejects with UpstreamError
  // Input: organizer searches
  // Expected status code: 502
  // Expected behavior: the meetup is not transitioned
  // Expected output: { error: "Unable to retrieve nearby places. Please try again later." }
  test('returns a safe upstream error without transitioning the meetup', async () => {
    searchMock.mockRejectedValue(new UpstreamError('Unable to retrieve nearby places. Please try again later.'));
    const res = await search();
    expect(res.status).toBe(502);
    expect(res.body.error).toBe('Unable to retrieve nearby places. Please try again later.');
    expect(meetupService.transitionStatus).not.toHaveBeenCalled();
  });
});

// Interface POST /api/meetups/:meetupId/recommendations/from-places
describe('Mocked: POST /api/meetups/:meetupId/recommendations/from-places', () => {
  // Mocked behavior: getForMember returns a SETUP meetup with no participants, a latitude of 91, or a NaN longitude
  // Input: organizer generates recommendations
  // Expected status code: 400
  // Expected behavior: Google, recommendationService.generate and transitionStatus are not called
  // Expected output: None
  test.each([
    { participants: [] },
    { participants: [{ startLocation: { lat: 91, lng: 0 } }] },
    { participants: [{ startLocation: { lat: 0, lng: Number.NaN } }] },
  ])('rejects missing or invalid member locations before calling Google: %j', async ({ participants }) => {
    memberMock.mockResolvedValue({ organizerId, status: 'SETUP', participants } as unknown as Awaited<ReturnType<typeof meetupService.getForMember>>);
    const res = await request(createApp())
      .post(`/api/meetups/${meetupId}/recommendations/from-places`)
      .set('Authorization', bearer(organizerId));
    expect(res.status).toBe(400);
    expect(searchMock).not.toHaveBeenCalled();
    expect(recommendationService.generate).not.toHaveBeenCalled();
    expect(meetupService.transitionStatus).not.toHaveBeenCalled();
  });

  // Mocked behavior: searchNearby returns places a-d; both participants (organizer WALK, other DRIVE, no Fair Credit)
  //   reach a, b, c, d in 40/10/20/30 minutes; recommendationService.generate echoes its candidates
  // Input: organizer generates recommendations
  // Expected status code: 201
  // Expected behavior: travel times are requested per participant (location and mode) to every place, Fair Credit
  //   is read for the meetup's group, and generate receives the 3 lowest-scoring places
  // Expected output: candidates b, c, d, best first, each with its score breakdown and per-member travel
  test('ranks Google places by score and generates the best three', async () => {
    const groupId = '507f1f77bcf86cd799439014';
    memberMock.mockResolvedValue({ organizerId, groupId, status: 'SETUP', participants: [
      { userId: organizerId, travelMode: 'WALK', preferences: [], startLocation: { lat: 49.2, lng: -123.1 } },
      { userId: 'other', travelMode: 'DRIVE', preferences: [], startLocation: { lat: 49.4, lng: -123.3 } },
    ] } as unknown as Awaited<ReturnType<typeof meetupService.getForMember>>);
    const candidates = ['a', 'b', 'c', 'd'].map(placeId => ({
      placeId, name: placeId, address: 'UBC', location: { lat: 49.26, lng: -123.25 }, types: ['cafe'],
    }));
    searchMock.mockResolvedValue(candidates);
    const row = [40, 10, 20, 30].map(minutes => minutes * 60);
    travelMock.mockResolvedValue([row, row]);
    balancesMock.mockResolvedValue(new Map([[organizerId, 0], ['other', 0]]));
    const generateMock = jest.mocked(recommendationService.generate);
    generateMock.mockImplementation(async (id, _user, ranked) =>
      ({ meetupId: id, candidates: ranked }) as unknown as Awaited<ReturnType<typeof recommendationService.generate>>);

    const res = await request(createApp())
      .post(`/api/meetups/${meetupId}/recommendations/from-places`)
      .set('Authorization', bearer(organizerId));

    expect(res.status).toBe(201);
    expect(res.body.candidates.map((c: { placeId: string }) => c.placeId)).toEqual(['b', 'c', 'd']);
    expect(res.body.candidates[0].scores).toEqual({ weightedTravel: 10, maxTravel: 10, imbalance: 0, preference: 0, total: 15 });
    expect(res.body.candidates[0].travel).toEqual([
      { userId: organizerId, mode: 'WALK', seconds: 600, normalizedSeconds: 600 },
      { userId: 'other', mode: 'DRIVE', seconds: 600, normalizedSeconds: 600 },
    ]);
    expect(travelMock).toHaveBeenCalledWith(
      [expect.objectContaining({ location: { lat: 49.2, lng: -123.1 }, mode: 'WALK' }),
        expect.objectContaining({ location: { lat: 49.4, lng: -123.3 }, mode: 'DRIVE' })],
      candidates.map(c => c.location),
    );
    expect(balancesMock).toHaveBeenCalledWith(groupId, [organizerId, 'other']);
    expect(generateMock).toHaveBeenCalledWith(meetupId, organizerId, expect.any(Array));
  });

  // Mocked behavior: searchNearby returns 4 places but Google finds a route to only 2 of them
  // Input: organizer generates recommendations
  // Expected status code: 400
  // Expected behavior: generate is not called
  // Expected output: error mentioning "At least 3"
  test('does not start voting when fewer than three places are reachable', async () => {
    searchMock.mockResolvedValue(['a', 'b', 'c', 'd'].map(placeId => ({
      placeId, name: placeId, address: '', location: { lat: 49.26, lng: -123.25 },
    })));
    travelMock.mockResolvedValue([[600, null, 600, null], [600, 600, null, null]]);
    balancesMock.mockResolvedValue(new Map());
    const res = await request(createApp())
      .post(`/api/meetups/${meetupId}/recommendations/from-places`)
      .set('Authorization', bearer(organizerId));
    expect(res.status).toBe(400);
    expect(res.body.error).toContain('At least 3');
    expect(recommendationService.generate).not.toHaveBeenCalled();
  });

  // Mocked behavior: searchNearby returns 3 places; travelSeconds rejects with UpstreamError
  // Input: organizer generates recommendations
  // Expected status code: 502
  // Expected behavior: generate is not called, so the meetup stays in SETUP
  // Expected output: None
  test('does not generate when travel times are unavailable', async () => {
    searchMock.mockResolvedValue(['a', 'b', 'c'].map(placeId => ({
      placeId, name: placeId, address: '', location: { lat: 49.26, lng: -123.25 },
    })));
    travelMock.mockRejectedValue(new UpstreamError('Unable to estimate travel times. Please try again later.'));
    balancesMock.mockResolvedValue(new Map());
    const res = await request(createApp())
      .post(`/api/meetups/${meetupId}/recommendations/from-places`)
      .set('Authorization', bearer(organizerId));
    expect(res.status).toBe(502);
    expect(recommendationService.generate).not.toHaveBeenCalled();
  });

  // Mocked behavior: searchNearby returns 0, 1 or 2 places
  // Input: organizer generates recommendations
  // Expected status code: 400
  // Expected behavior: generate and transitionStatus are not called
  // Expected output: error mentioning "At least 3"
  test.each([0, 1, 2])('does not start voting with only %i places', async count => {
    searchMock.mockResolvedValue(Array.from({ length: count }, (_, i) => ({
      placeId: String(i), name: 'Cafe', address: '', location: { lat: 49.26, lng: -123.25 },
    })));
    const res = await request(createApp())
      .post(`/api/meetups/${meetupId}/recommendations/from-places`)
      .set('Authorization', bearer(organizerId));
    expect(res.status).toBe(400);
    expect(res.body.error).toContain('At least 3');
    expect(recommendationService.generate).not.toHaveBeenCalled();
    expect(meetupService.transitionStatus).not.toHaveBeenCalled();
  });

  // Mocked behavior: searchNearby rejects with UpstreamError
  // Input: organizer generates recommendations
  // Expected status code: 502
  // Expected behavior: generate is not called
  // Expected output: None
  test('does not generate when Google fails', async () => {
    searchMock.mockRejectedValue(new UpstreamError('Place search unavailable'));
    const res = await request(createApp())
      .post(`/api/meetups/${meetupId}/recommendations/from-places`)
      .set('Authorization', bearer(organizerId));
    expect(res.status).toBe(502);
    expect(recommendationService.generate).not.toHaveBeenCalled();
  });
});

// Interface POST /api/meetups/:meetupId/recommendations (removed manual submission)
describe('Mocked: POST /api/meetups/:meetupId/recommendations', () => {
  // Mocked behavior: getForMember returns a SETUP meetup with the organizer and one other participant; searchNearby returns []
  // Input: organizer posts a manual candidate list
  // Expected status code: 404
  // Expected behavior: neither Google nor recommendationService.generate is called
  // Expected output: None
  test('manual candidate submission endpoint has been removed', async () => {
    const res = await request(createApp())
      .post(`/api/meetups/${meetupId}/recommendations`)
      .set('Authorization', bearer(organizerId))
      .send({ candidates: [] });
    expect(res.status).toBe(404);
    expect(searchMock).not.toHaveBeenCalled();
    expect(recommendationService.generate).not.toHaveBeenCalled();
  });
});
