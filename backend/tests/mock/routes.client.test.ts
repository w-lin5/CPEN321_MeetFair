import { UpstreamError } from '../../src/common/errors';
import { env } from '../../src/config';
import { routesClient } from '../../src/integrations/routes.client';

const { travelSeconds } = routesClient;

const UBC = { lat: 49.26, lng: -123.25 };
const DOWNTOWN = { lat: 49.28, lng: -123.12 };

// Interface routesClient.travelSeconds (integrations/routes.client), the only caller of Google Routes
describe('Mocked: routesClient.travelSeconds', () => {
  let fetchMock: jest.SpiedFunction<typeof fetch>;

  beforeEach(() => {
    fetchMock = jest.spyOn(global, 'fetch');
  });
  afterEach(() => {
    jest.restoreAllMocks();
  });

  function respond(...bodies: unknown[]) {
    for (const body of bodies) fetchMock.mockResolvedValueOnce(new Response(JSON.stringify(body)));
  }

  // The JSON body of the nth request sent to Google
  function requestBody(call: number): { travelMode: string; origins: unknown[]; destinations: unknown[] } {
    const body: { travelMode: string; origins: unknown[]; destinations: unknown[] } =
      JSON.parse(String(fetchMock.mock.calls.at(call)?.[1]?.body));
    return body;
  }

  // Mocked behavior: fetch returns one matrix per mode; Google omits zero indexes and reports one missing route
  // Input: a WALK, a DRIVE and a second WALK origin to UBC and downtown
  // Expected behavior: one POST to computeRouteMatrix per mode with the API key, field mask and that mode's origins
  // Expected output: seconds per origin (in the caller's order) and destination; null where there is no route
  test('requests one matrix per travel mode and maps results back to the caller\'s origins', async () => {
    respond(
      [{ duration: '600s', condition: 'ROUTE_EXISTS' }, { destinationIndex: 1, duration: '900s', condition: 'ROUTE_EXISTS' },
        { originIndex: 1, duration: '300s', condition: 'ROUTE_EXISTS' },
        { originIndex: 1, destinationIndex: 1, condition: 'ROUTE_NOT_FOUND' }],
      [{ duration: '120.5s', condition: 'ROUTE_EXISTS' }, { destinationIndex: 1, duration: '240s', condition: 'ROUTE_EXISTS' }],
    );
    const result = await travelSeconds(
      [{ location: UBC, mode: 'WALK' }, { location: DOWNTOWN, mode: 'DRIVE' }, { location: DOWNTOWN, mode: 'WALK' }],
      [UBC, DOWNTOWN],
    );
    expect(result).toEqual([[600, 900], [120.5, 240], [300, null]]);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    const [url, options] = fetchMock.mock.calls[0] ?? [];
    expect(url).toBe('https://routes.googleapis.com/distanceMatrix/v2:computeRouteMatrix');
    expect(options?.method).toBe('POST');
    expect(options?.headers).toEqual({
      'Content-Type': 'application/json',
      'X-Goog-Api-Key': env.googleMapsApiKey,
      'X-Goog-FieldMask': 'originIndex,destinationIndex,duration,condition',
    });
    expect(requestBody(0)).toMatchObject({
      travelMode: 'WALK',
      origins: [
        { waypoint: { location: { latLng: { latitude: 49.26, longitude: -123.25 } } } },
        { waypoint: { location: { latLng: { latitude: 49.28, longitude: -123.12 } } } },
      ],
    });
    expect(requestBody(0).destinations).toHaveLength(2);
    expect(requestBody(1).travelMode).toBe('DRIVE');
  });

  // Mocked behavior: fetch returns [] for every request
  // Input: 15 TRANSIT origins to 10 destinations
  // Expected behavior: split into requests of at most 100 elements (10 + 5 origins)
  // Expected output: every entry null
  test('splits large matrices to stay within Google\'s element limit', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(new Response('[]')));
    const result = await travelSeconds(
      Array.from({ length: 15 }, () => ({ location: UBC, mode: 'TRANSIT' as const })),
      Array.from({ length: 10 }, () => DOWNTOWN),
    );
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect([requestBody(0).origins, requestBody(1).origins].map((origins) => origins.length)).toEqual([10, 5]);
    expect(result.flat().every((seconds) => seconds === null)).toBe(true);
  });

  // Mocked behavior: fetch returns malformed elements (null, bad durations, out-of-range or negative indexes)
  // Input: one WALK origin to UBC
  // Expected behavior: malformed elements are ignored
  // Expected output: [[null]]
  test('ignores malformed elements', async () => {
    respond([null, { duration: 'soon', condition: 'ROUTE_EXISTS' }, { duration: 5, condition: 'ROUTE_EXISTS' },
      { duration: 's', condition: 'ROUTE_EXISTS' }, { duration: '-5s', condition: 'ROUTE_EXISTS' },
      { originIndex: 3, duration: '60s', condition: 'ROUTE_EXISTS' }, { originIndex: -1, duration: '60s', condition: 'ROUTE_EXISTS' },
      { destinationIndex: 3, duration: '60s', condition: 'ROUTE_EXISTS' },
      { destinationIndex: 0.5, duration: '60s', condition: 'ROUTE_EXISTS' }]);
    expect(await travelSeconds([{ location: UBC, mode: 'WALK' }], [UBC])).toEqual([[null]]);
  });

  // Input: no origins; no destinations
  // Expected behavior: Google is not called
  // Expected output: []; [[]]
  test('does not call Google for an empty matrix', async () => {
    expect(await travelSeconds([], [UBC])).toEqual([]);
    expect(await travelSeconds([{ location: UBC, mode: 'WALK' }], [])).toEqual([[]]);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  // Mocked behavior: fetch returns HTTP 403 with the API key in the body, an object instead of an array,
  //   or rejects with the API key as its message
  // Input: one WALK origin to UBC
  // Expected behavior: rejects with UpstreamError without exposing Google's response
  // Expected output: error "Unable to estimate travel times. Please try again later."
  test.each([
    ['HTTP error', () => fetchMock.mockResolvedValue(new Response('google-internal-detail', { status: 403 }))],
    ['non-array body', () => fetchMock.mockResolvedValue(new Response('{"error":"google-internal-detail"}'))],
    ['network failure', () => fetchMock.mockRejectedValue(new Error('google-internal-detail'))],
  ])('sanitizes %s', async (_name, arrange) => {
    arrange();
    const promise = travelSeconds([{ location: UBC, mode: 'WALK' }], [UBC]);
    await expect(promise).rejects.toBeInstanceOf(UpstreamError);
    await expect(promise).rejects.toThrow('Unable to estimate travel times. Please try again later.');
  });
});
