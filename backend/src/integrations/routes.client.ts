import type { LatLng, TravelMode } from '../common/types';
import { UpstreamError } from '../common/errors';

const MATRIX = {
  // Google's per-request element limit for TRANSIT (the strictest of our modes)
  maxElements: 100,
  timeoutMs: 10000,
} as const;
const FAILURE_MESSAGE = 'Unable to estimate travel times. Please try again later.';

export interface RouteOrigin {
  location: LatLng;
  mode: TravelMode;
}

interface MatrixRequest {
  mode: TravelMode;
  // This request's origins, each with the result row its travel times go into
  origins: { location: LatLng; row: (number | null)[] }[];
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
function planRequests(origins: RouteOrigin[], rows: (number | null)[][], destinationCount: number): MatrixRequest[] {
  const perRequest = Math.max(1, Math.floor(MATRIX.maxElements / destinationCount));
  const byMode = new Map<TravelMode, MatrixRequest['origins']>();
  origins.forEach(({ location, mode }, index) => {
    byMode.set(mode, [...(byMode.get(mode) ?? []), { location, row: rows.at(index) ?? [] }]);
  });
  const requests: MatrixRequest[] = [];
  for (const [mode, modeOrigins] of byMode) {
    for (let start = 0; start < modeOrigins.length; start += perRequest) {
      requests.push({ mode, origins: modeOrigins.slice(start, start + perRequest) });
    }
  }
  return requests;
}

async function computeMatrix(apiKey: string, request: MatrixRequest, destinations: LatLng[]) {
  const response = await fetch('https://routes.googleapis.com/distanceMatrix/v2:computeRouteMatrix', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Goog-Api-Key': apiKey,
      'X-Goog-FieldMask': 'originIndex,destinationIndex,duration,condition',
    },
    body: JSON.stringify({
      origins: request.origins.map(origin => waypoint(origin.location)),
      destinations: destinations.map(waypoint),
      travelMode: request.mode,
    }),
    signal: AbortSignal.timeout(MATRIX.timeoutMs),
  });
  if (!response.ok) throw new UpstreamError(FAILURE_MESSAGE);
  const data: unknown = await response.json();
  if (!Array.isArray(data)) throw new UpstreamError(FAILURE_MESSAGE);
  return data;
}

/**
 * Travel time in seconds from each origin (in its own travel mode) to each destination:
 * result[origin][destination], or null where Google finds no route.
 */
export async function travelSeconds(origins: RouteOrigin[], destinations: LatLng[]): Promise<(number | null)[][]> {
  const apiKey = process.env.GOOGLE_PLACES_API_KEY;
  if (!apiKey) {
    throw new Error('Missing required env var GOOGLE_PLACES_API_KEY');
  }
  const result = origins.map(() => destinations.map((): number | null => null));
  if (origins.length === 0 || destinations.length === 0) return result;

  try {
    const requests = planRequests(origins, result, destinations.length);
    const responses = await Promise.all(requests.map(request => computeMatrix(apiKey, request, destinations)));
    requests.forEach((request, requestIndex) => {
      for (const element of responses.at(requestIndex) ?? []) {
        if (!isRecord(element) || element.condition !== 'ROUTE_EXISTS') continue;
        const originIndex = parseIndex(element.originIndex, request.origins.length);
        const origin = originIndex === null ? undefined : request.origins.at(originIndex);
        const destinationIndex = parseIndex(element.destinationIndex, destinations.length);
        const seconds = parseDuration(element.duration);
        if (!origin || destinationIndex === null || seconds === null) continue;
        origin.row.splice(destinationIndex, 1, seconds);
      }
    });
    return result;
  } catch {
    // Never expose Google's response, request headers, or the original exception.
    throw new UpstreamError(FAILURE_MESSAGE);
  }
}
