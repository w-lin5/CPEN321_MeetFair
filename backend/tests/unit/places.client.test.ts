import { searchNearby } from '../../src/integrations/places.client';
import { UpstreamError } from '../../src/common/errors';

const place = {
  id: 'cafe-1', displayName: { text: 'Campus Cafe' }, formattedAddress: 'UBC',
  location: { latitude: 49.26, longitude: -123.25 },
};

describe('Google Places client', () => {
  const originalKey = process.env.GOOGLE_PLACES_API_KEY;
  let fetchMock: jest.SpiedFunction<typeof fetch>;

  beforeEach(() => {
    process.env.GOOGLE_PLACES_API_KEY = 'test-places-secret';
    fetchMock = jest.spyOn(globalThis, 'fetch');
  });
  afterEach(() => {
    jest.restoreAllMocks();
    if (originalKey === undefined) delete process.env.GOOGLE_PLACES_API_KEY;
    else process.env.GOOGLE_PLACES_API_KEY = originalKey;
  });

  function respond(data: unknown, status = 200) {
    fetchMock.mockResolvedValue(new Response(JSON.stringify(data), { status }));
  }

  test('requests only the needed fields and searches cafes around UBC', async () => {
    respond({ places: [place, place] });
    expect(await searchNearby()).toEqual([{
      placeId: 'cafe-1', name: 'Campus Cafe', address: 'UBC', location: { lat: 49.26, lng: -123.25 },
    }]);
    const [url, options] = fetchMock.mock.calls[0]!;
    expect(url).toBe('https://places.googleapis.com/v1/places:searchNearby');
    expect(options?.method).toBe('POST');
    expect(options?.headers).toEqual({
      'Content-Type': 'application/json',
      'X-Goog-Api-Key': 'test-places-secret',
      'X-Goog-FieldMask': 'places.id,places.displayName,places.formattedAddress,places.location',
    });
    expect(JSON.parse(String(options?.body))).toEqual({
      includedTypes: ['cafe'], maxResultCount: 10,
      locationRestriction: { circle: { center: { latitude: 49.2606, longitude: -123.246 }, radius: 1500 } },
    });
    expect(options?.signal).toBeInstanceOf(AbortSignal);
  });

  test.each([{}, { places: [] }])('accepts empty results: %j', async (data) => {
    respond(data);
    await expect(searchNearby()).resolves.toEqual([]);
  });

  test('skips invalid places and allows missing addresses', async () => {
    respond({ places: [null, {}, { ...place, location: { latitude: 200, longitude: 0 } },
      { ...place, formattedAddress: undefined }] });
    expect(await searchNearby()).toEqual([expect.objectContaining({ placeId: 'cafe-1', address: '' })]);
  });

  test.each([403, 429, 500])('sanitizes Google HTTP %i errors', async (status) => {
    respond({ error: 'test-places-secret' }, status);
    await expect(searchNearby()).rejects.toThrow('Unable to retrieve nearby places. Please try again later.');
  });

  test.each([null, [], { places: 'invalid' }])('rejects malformed responses: %j', async (data) => {
    respond(data);
    await expect(searchNearby()).rejects.toBeInstanceOf(UpstreamError);
  });

  test.each(['network failure', 'timeout'])('sanitizes %s', async () => {
    fetchMock.mockRejectedValue(new Error('test-places-secret'));
    await expect(searchNearby()).rejects.toThrow('Unable to retrieve nearby places. Please try again later.');
  });

  test('sanitizes invalid JSON', async () => {
    fetchMock.mockResolvedValue(new Response('invalid JSON'));
    await expect(searchNearby()).rejects.toBeInstanceOf(UpstreamError);
  });

  test('does not call Google without a key', async () => {
    delete process.env.GOOGLE_PLACES_API_KEY;
    await expect(searchNearby()).rejects.toThrow('Missing required env var GOOGLE_PLACES_API_KEY');
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
