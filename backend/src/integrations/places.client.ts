import { UpstreamError } from '../common/errors';
import type { LatLng, Place } from '../common/types';
import { env, tuning } from '../config';

const SEARCH_URL = 'https://places.googleapis.com/v1/places:searchNearby';
// Only these fields are billed and returned
const FIELD_MASK = 'places.id,places.displayName,places.formattedAddress,places.location,places.types';
const FAILURE_MESSAGE = 'Unable to retrieve nearby places. Please try again later.';

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isTextWithin(value: unknown, maxLength: number): value is string {
  return typeof value === 'string' && value.trim() !== '' && value.length <= maxLength;
}

function isCoordinate(value: unknown, limit: number): value is number {
  return typeof value === 'number' && Number.isFinite(value) && Math.abs(value) <= limit;
}

function parseLocation(value: unknown): LatLng | undefined {
  if (!isRecord(value)) return undefined;
  const { latitude, longitude } = value;
  return isCoordinate(latitude, tuning.maxLatitude) && isCoordinate(longitude, tuning.maxLongitude)
    ? { lat: latitude, lng: longitude }
    : undefined;
}

// Google leaves the address out for some places; one that is present must fit our limit
function parseAddress(value: unknown): string | undefined {
  if (value === undefined) return '';
  return typeof value === 'string' && value.length <= tuning.placeAddressMaxLength ? value : undefined;
}

// A place we can store and show, or undefined when Google's entry is incomplete or oversized
function parsePlace(value: unknown): Place | undefined {
  if (!isRecord(value)) return undefined;
  const { id, displayName, formattedAddress, location, types } = value;
  const name = isRecord(displayName) ? displayName.text : undefined;
  const address = parseAddress(formattedAddress);
  const position = parseLocation(location);
  if (!isTextWithin(id, tuning.placeIdMaxLength) || !isTextWithin(name, tuning.placeNameMaxLength)
    || address === undefined || position === undefined) {
    return undefined;
  }
  return {
    placeId: id,
    name,
    address,
    location: position,
    types: Array.isArray(types) ? types.filter((type): type is string => typeof type === 'string') : [],
  };
}

function searchRequest(center: LatLng, includedTypes: readonly string[]) {
  return {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Goog-Api-Key': env.googleMapsApiKey,
      'X-Goog-FieldMask': FIELD_MASK,
    },
    body: JSON.stringify({
      includedTypes,
      maxResultCount: tuning.placesMaxResults,
      locationRestriction: {
        circle: {
          center: { latitude: center.lat, longitude: center.lng },
          radius: tuning.placesSearchRadiusMeters,
        },
      },
    }),
    signal: AbortSignal.timeout(tuning.googleRequestTimeoutMs),
  };
}

// Usable places from a Nearby Search response, without duplicates
function parsePlaces(data: unknown): Place[] {
  if (!isRecord(data) || (data.places !== undefined && !Array.isArray(data.places))) {
    throw new UpstreamError(FAILURE_MESSAGE);
  }
  // Google omits `places` when the search has no matches
  const entries: unknown[] = data.places ?? [];
  const places = new Map<string, Place>();
  for (const entry of entries) {
    const place = parsePlace(entry);
    if (place && !places.has(place.placeId)) places.set(place.placeId, place);
  }
  return [...places.values()];
}

/** Places of any of the given Google Places types within the search radius of `center` (Places API (New) Nearby Search). */
async function searchNearby(center: LatLng, includedTypes: readonly string[]): Promise<Place[]> {
  try {
    const response = await fetch(SEARCH_URL, searchRequest(center, includedTypes));
    if (!response.ok) throw new UpstreamError(FAILURE_MESSAGE);
    return parsePlaces(await response.json());
  } catch {
    // Never expose Google's response, request headers, or the original exception
    throw new UpstreamError(FAILURE_MESSAGE);
  }
}

// Exported as an object so tests can jest.spyOn(placesClient, 'searchNearby')
export const placesClient = { searchNearby };
