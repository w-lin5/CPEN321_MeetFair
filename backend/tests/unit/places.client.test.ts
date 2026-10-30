import { searchNearby } from '../../src/integrations/places.client';
import { UpstreamError } from '../../src/common/errors';

const place = {
  id: 'cafe-1', displayName: { text: 'Campus Cafe' }, formattedAddress: 'UBC',
  location: { latitude: 49.26, longitude: -123.25 },
};

// Interface searchNearby (integrations/places.client)
describe('Mocked: searchNearby', () => {
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

  // Mocked behavior: fetch returns 200 with the same place twice
  // Input: center (40.75, -73.98), default types
  // Expected behavior: one POST to places:searchNearby with the API key, the 4-field mask, includedTypes ["cafe"],
  //   10 results within 1500 m of the center, and an abort signal
  // Expected output: the place once, as { placeId, name, address, location }
  test('requests only the needed fields and searches cafes around the supplied center', async () => {
    respond({ places: [place, place] });
    expect(await searchNearby({ lat: 40.75, lng: -73.98 })).toEqual([{
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
      locationRestriction: { circle: { center: { latitude: 40.75, longitude: -73.98 }, radius: 1500 } },
    });
    expect(options?.signal).toBeInstanceOf(AbortSignal);
  });

  // Mocked behavior: fetch returns 200 with {} or { places: [] }
  // Input: center (40.75, -73.98)
  // Expected behavior: no error
  // Expected output: []
  test.each([{}, { places: [] }])('accepts empty results: %j', async (data) => {
    respond(data);
    await expect(searchNearby({ lat: 40.75, lng: -73.98 })).resolves.toEqual([]);
  });

  // Mocked behavior: fetch returns 200 with { places: [] }
  // Input: center (40, -74) with types ["park", "library"]
  // Expected behavior: the request's includedTypes are exactly ["park", "library"]
  // Expected output: None
  test('sends preference types without adding cafes to the request', async () => {
    respond({ places: [] });
    await searchNearby({ lat: 40, lng: -74 }, ['park', 'library']);
    const options = fetchMock.mock.calls[0]![1];
    expect(JSON.parse(String(options?.body)).includedTypes).toEqual(['park', 'library']);
  });

  // Mocked behavior: fetch returns null, {}, a place with latitude 200, and a valid place without an address
  // Input: center (40.75, -73.98)
  // Expected behavior: invalid entries are dropped
  // Expected output: only the valid place, with address ""
  test('skips invalid places and allows missing addresses', async () => {
    respond({ places: [null, {}, { ...place, location: { latitude: 200, longitude: 0 } },
      { ...place, formattedAddress: undefined }] });
    expect(await searchNearby({ lat: 40.75, lng: -73.98 })).toEqual([expect.objectContaining({ placeId: 'cafe-1', address: '' })]);
  });

  // Mocked behavior: fetch returns HTTP 403, 429 or 500 with a body containing the API key
  // Input: center (40.75, -73.98)
  // Expected behavior: rejects without exposing Google's response
  // Expected output: error "Unable to retrieve nearby places. Please try again later."
  test.each([403, 429, 500])('sanitizes Google HTTP %i errors', async (status) => {
    respond({ error: 'test-places-secret' }, status);
    await expect(searchNearby({ lat: 40.75, lng: -73.98 })).rejects.toThrow('Unable to retrieve nearby places. Please try again later.');
  });

  // Mocked behavior: fetch returns 200 with null, [] or { places: "invalid" }
  // Input: center (40.75, -73.98)
  // Expected behavior: rejects with UpstreamError
  // Expected output: None
  test.each([null, [], { places: 'invalid' }])('rejects malformed responses: %j', async (data) => {
    respond(data);
    await expect(searchNearby({ lat: 40.75, lng: -73.98 })).rejects.toBeInstanceOf(UpstreamError);
  });

  // Mocked behavior: fetch rejects with an error whose message is the API key (stands in for a network failure or timeout)
  // Input: center (40.75, -73.98)
  // Expected behavior: rejects without exposing the original error
  // Expected output: error "Unable to retrieve nearby places. Please try again later."
  test.each(['network failure', 'timeout'])('sanitizes %s', async () => {
    fetchMock.mockRejectedValue(new Error('test-places-secret'));
    await expect(searchNearby({ lat: 40.75, lng: -73.98 })).rejects.toThrow('Unable to retrieve nearby places. Please try again later.');
  });

  // Mocked behavior: fetch returns 200 with a non-JSON body
  // Input: center (40.75, -73.98)
  // Expected behavior: rejects with UpstreamError
  // Expected output: None
  test('sanitizes invalid JSON', async () => {
    fetchMock.mockResolvedValue(new Response('invalid JSON'));
    await expect(searchNearby({ lat: 40.75, lng: -73.98 })).rejects.toBeInstanceOf(UpstreamError);
  });

  // Mocked behavior: GOOGLE_PLACES_API_KEY is unset
  // Input: center (40.75, -73.98)
  // Expected behavior: fetch is not called
  // Expected output: error "Missing required env var GOOGLE_PLACES_API_KEY"
  test('does not call Google without a key', async () => {
    delete process.env.GOOGLE_PLACES_API_KEY;
    await expect(searchNearby({ lat: 40.75, lng: -73.98 })).rejects.toThrow('Missing required env var GOOGLE_PLACES_API_KEY');
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
