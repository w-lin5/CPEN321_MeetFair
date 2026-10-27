import type { LatLng } from '../common/types';
import { UpstreamError } from '../common/errors';
import { tuning } from '../config';
import type { CandidateInput } from '../recommendation/schemas';

const SEARCH = {
  radiusMeters: 1500,
  maxResults: 10,
  timeoutMs: 10000,
} as const;
const FAILURE_MESSAGE = 'Unable to retrieve nearby places. Please try again later.';

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function parsePlace(value: unknown): CandidateInput | undefined {
  if (!isRecord(value)) return undefined;
  const { id, displayName, formattedAddress, location } = value;
  if (typeof id !== 'string' || !id.trim() || id.length > tuning.placeIdMaxLength
    || !isRecord(displayName) || typeof displayName.text !== 'string'
    || !displayName.text.trim() || displayName.text.length > tuning.placeNameMaxLength
    || !isRecord(location)) return undefined;
  const { latitude, longitude } = location;
  if (typeof latitude !== 'number' || !Number.isFinite(latitude) || Math.abs(latitude) > tuning.maxLatitude
    || typeof longitude !== 'number' || !Number.isFinite(longitude) || Math.abs(longitude) > tuning.maxLongitude) {
    return undefined;
  }
  if (formattedAddress !== undefined && (typeof formattedAddress !== 'string'
    || formattedAddress.length > tuning.placeAddressMaxLength)) return undefined;
  return {
    placeId: id,
    name: displayName.text,
    address: typeof formattedAddress === 'string' ? formattedAddress : '',
    location: { lat: latitude, lng: longitude },
  };
}

/** Places matching any included type within 1.5 km of the supplied group center. */
export async function searchNearby(center: LatLng, includedTypes: readonly string[] = ['cafe']): Promise<CandidateInput[]> {
  const apiKey = process.env.GOOGLE_PLACES_API_KEY;
  if (!apiKey) {
    throw new Error('Missing required env var GOOGLE_PLACES_API_KEY');
  }

  try {
    const response = await fetch(
    'https://places.googleapis.com/v1/places:searchNearby',
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Goog-Api-Key': apiKey,
        'X-Goog-FieldMask':
          'places.id,places.displayName,places.formattedAddress,places.location',
      },
      body: JSON.stringify({
        includedTypes,
        maxResultCount: SEARCH.maxResults,
        locationRestriction: {
          circle: {
            center: {
              latitude: center.lat,
              longitude: center.lng,
            },
            radius: SEARCH.radiusMeters,
          },
        },
      }),
      signal: AbortSignal.timeout(SEARCH.timeoutMs),
    },
  );

    if (!response.ok) throw new UpstreamError(FAILURE_MESSAGE);

    const data: unknown = await response.json();
    if (!isRecord(data) || (data.places !== undefined && !Array.isArray(data.places))) {
      throw new UpstreamError(FAILURE_MESSAGE);
    }
    // Google omits places when the search has no matches.
    const places: unknown[] = data.places ?? [];
    const candidates = new Map<string, CandidateInput>();
    for (const value of places) {
      const place = parsePlace(value);
      if (place && !candidates.has(place.placeId)) candidates.set(place.placeId, place);
    }
    return [...candidates.values()];
  } catch {
    // Never expose Google's response, request headers, or the original exception.
    throw new UpstreamError(FAILURE_MESSAGE);
  }
}
