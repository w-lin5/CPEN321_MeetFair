import { UpstreamError } from '../common/errors';
import type { LatLng, TravelMode } from '../common/types';
import { env, tuning } from '../config';

const MATRIX_URL = 'https://routes.googleapis.com/distanceMatrix/v2:computeRouteMatrix';
const FIELD_MASK = 'originIndex,destinationIndex,duration,condition';
const FAILURE_MESSAGE = 'Unable to estimate travel times. Please try again later.';

export interface RouteOrigin {
  location: LatLng;
  mode: TravelMode;
}

// Travel times of one origin to every destination; null where Google finds no route
type TravelRow = (number | null)[];

interface MatrixRequest {
  mode: TravelMode;
  // This request's origins, each with the result row its travel times go into
  origins: { location: LatLng; row: TravelRow }[];
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function waypoint(location: LatLng) {
  return { waypoint: { location: { latLng: { latitude: location.lat, longitude: location.lng } } } };
}

// Google encodes durations as e.g. "754s"
function parseDuration(value: unknown): number | null {
  if (typeof value !== 'string' || !value.endsWith('s')) return null;
  const seconds = Number(value.slice(0, -1));
  return value.length > 1 && Number.isFinite(seconds) && seconds >= 0 ? seconds : null;
}

// A valid index into a list of the given length; Google omits zero-valued indexes
function parseIndex(value: unknown, length: number): number | null {
  const index = value ?? 0;
  return typeof index === 'number' && Number.isInteger(index) && index >= 0 && index < length ? index : null;
}

// One request per travel mode, split so that origins x destinations stays within Google's limit
function planRequests(origins: RouteOrigin[], rows: TravelRow[], destinationCount: number): MatrixRequest[] {
  const originsPerRequest = Math.max(1, Math.floor(tuning.routeMatrixMaxElements / destinationCount));
  const byMode = new Map<TravelMode, MatrixRequest['origins']>();
  origins.forEach(({ location, mode }, index) => {
    byMode.set(mode, [...(byMode.get(mode) ?? []), { location, row: rows.at(index) ?? [] }]);
  });

  const requests: MatrixRequest[] = [];
  for (const [mode, modeOrigins] of byMode) {
    for (let start = 0; start < modeOrigins.length; start += originsPerRequest) {
      requests.push({ mode, origins: modeOrigins.slice(start, start + originsPerRequest) });
    }
  }
  return requests;
}

async function computeMatrix(request: MatrixRequest, destinations: LatLng[]): Promise<unknown[]> {
  const response = await fetch(MATRIX_URL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Goog-Api-Key': env.googleMapsApiKey,
      'X-Goog-FieldMask': FIELD_MASK,
    },
    body: JSON.stringify({
      origins: request.origins.map((origin) => waypoint(origin.location)),
      destinations: destinations.map(waypoint),
      travelMode: request.mode,
    }),
    signal: AbortSignal.timeout(tuning.googleRequestTimeoutMs),
  });
  if (!response.ok) throw new UpstreamError(FAILURE_MESSAGE);

  const data: unknown = await response.json();
  if (!Array.isArray(data)) throw new UpstreamError(FAILURE_MESSAGE);
  // Array.isArray narrows to any[]; keep the elements unknown so callers must check them
  const elements: unknown[] = data;
  return elements;
}

// Writes one matrix element's travel time into its origin's row; unusable elements are skipped
function storeElement(element: unknown, request: MatrixRequest, destinationCount: number): void {
  if (!isRecord(element) || element.condition !== 'ROUTE_EXISTS') return;
  const originIndex = parseIndex(element.originIndex, request.origins.length);
  const origin = originIndex === null ? undefined : request.origins.at(originIndex);
  const destinationIndex = parseIndex(element.destinationIndex, destinationCount);
  const seconds = parseDuration(element.duration);
  if (origin && destinationIndex !== null && seconds !== null) {
    origin.row.splice(destinationIndex, 1, seconds);
  }
}

/**
 * Travel time in seconds from each origin (in its own travel mode) to each destination:
 * result[origin][destination], or null where Google finds no route (Routes API computeRouteMatrix).
 */
async function travelSeconds(origins: RouteOrigin[], destinations: LatLng[]): Promise<TravelRow[]> {
  const result = origins.map(() => destinations.map((): number | null => null));
  if (origins.length === 0 || destinations.length === 0) return result;

  try {
    const requests = planRequests(origins, result, destinations.length);
    const responses = await Promise.all(requests.map((request) => computeMatrix(request, destinations)));
    requests.forEach((request, requestIndex) => {
      for (const element of responses.at(requestIndex) ?? []) {
        storeElement(element, request, destinations.length);
      }
    });
    return result;
  } catch {
    // Never expose Google's response, request headers, or the original exception
    throw new UpstreamError(FAILURE_MESSAGE);
  }
}

// Exported as an object so tests can jest.spyOn(routesClient, 'travelSeconds')
export const routesClient = { travelSeconds };
