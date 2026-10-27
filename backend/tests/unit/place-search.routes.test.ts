import request from 'supertest';

import { createApp } from '../../src/app';
import { Forbidden, NotFound, UpstreamError } from '../../src/common/errors';
import { searchNearby } from '../../src/integrations/places.client';
import { meetupService } from '../../src/users-groups/meetup.service';
import { bearer } from '../helpers/auth';
import { recommendationService } from '../../src/recommendation/recommendation.service';

jest.mock('../../src/integrations/places.client');
jest.mock('../../src/users-groups/meetup.service');
jest.mock('../../src/recommendation/recommendation.service');

const organizerId = '507f1f77bcf86cd799439011';
const meetupId = '507f1f77bcf86cd799439012';
const searchMock = jest.mocked(searchNearby);
const memberMock = jest.mocked(meetupService.getForMember);

describe('POST /api/meetups/:meetupId/places/search', () => {
  beforeEach(() => {
    jest.resetAllMocks();
    memberMock.mockResolvedValue({ organizerId, status: 'SETUP' } as unknown as Awaited<ReturnType<typeof meetupService.getForMember>>);
    searchMock.mockResolvedValue([]);
  });

  function search(userId = organizerId, id = meetupId) {
    return request(createApp()).post(`/api/meetups/${id}/places/search`).set('Authorization', bearer(userId));
  }

  test('returns candidates without saving or transitioning the meetup', async () => {
    const candidates = [{ placeId: 'cafe-1', name: 'Cafe', address: 'UBC', location: { lat: 49.26, lng: -123.25 } }];
    searchMock.mockResolvedValue(candidates);
    const res = await search();
    expect(res.status).toBe(200);
    expect(res.body).toEqual({ candidates, message: null });
    expect(memberMock).toHaveBeenCalledWith(meetupId, organizerId);
    expect(searchMock).toHaveBeenCalledTimes(1);
    expect(meetupService.transitionStatus).not.toHaveBeenCalled();
  });

  test('returns a clear empty-result message', async () => {
    const res = await search();
    expect(res.status).toBe(200);
    expect(res.body).toEqual({ candidates: [], message: 'No nearby places found.' });
  });

  test('requires authentication', async () => {
    const res = await request(createApp()).post(`/api/meetups/${meetupId}/places/search`);
    expect(res.status).toBe(401);
    expect(searchMock).not.toHaveBeenCalled();
  });

  test('rejects an invalid meetup id before calling Google', async () => {
    expect((await search(organizerId, 'invalid')).status).toBe(400);
    expect(searchMock).not.toHaveBeenCalled();
  });

  test('requires the organizer', async () => {
    expect((await search('507f1f77bcf86cd799439013')).status).toBe(403);
    expect(searchMock).not.toHaveBeenCalled();
  });

  test.each([new Forbidden(), new NotFound()])('respects membership and existence checks: %s', async (error) => {
    memberMock.mockRejectedValue(error);
    expect((await search()).status).toBe(error.status);
    expect(searchMock).not.toHaveBeenCalled();
  });

  test.each(['VOTING', 'FINALIZED', 'SETTLED'])('rejects status %s', async (status) => {
    memberMock.mockResolvedValue({ organizerId, status } as unknown as Awaited<ReturnType<typeof meetupService.getForMember>>);
    expect((await search()).status).toBe(409);
    expect(searchMock).not.toHaveBeenCalled();
  });

  test('returns a safe upstream error without transitioning the meetup', async () => {
    searchMock.mockRejectedValue(new UpstreamError('Unable to retrieve nearby places. Please try again later.'));
    const res = await search();
    expect(res.status).toBe(502);
    expect(res.body.error).toBe('Unable to retrieve nearby places. Please try again later.');
    expect(meetupService.transitionStatus).not.toHaveBeenCalled();
  });

  test('generates voting candidates from the first three Google places', async () => {
    const candidates = ['a', 'b', 'c', 'd'].map(placeId => ({
      placeId, name: placeId, address: 'UBC', location: { lat: 49.26, lng: -123.25 },
    }));
    searchMock.mockResolvedValue(candidates);
    const generateMock = jest.mocked(recommendationService.generate);
    generateMock.mockResolvedValue({ meetupId, candidates: candidates.slice(0, 3) } as unknown as Awaited<ReturnType<typeof recommendationService.generate>>);
    const res = await request(createApp())
      .post(`/api/meetups/${meetupId}/recommendations/from-places`)
      .set('Authorization', bearer(organizerId));
    expect(res.status).toBe(201);
    expect(res.body.candidates).toEqual(candidates.slice(0, 3));
    expect(generateMock).toHaveBeenCalledWith(meetupId, organizerId, candidates.slice(0, 3));
  });

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

  test('does not generate when Google fails', async () => {
    searchMock.mockRejectedValue(new UpstreamError('Place search unavailable'));
    const res = await request(createApp())
      .post(`/api/meetups/${meetupId}/recommendations/from-places`)
      .set('Authorization', bearer(organizerId));
    expect(res.status).toBe(502);
    expect(recommendationService.generate).not.toHaveBeenCalled();
  });
});
