import type { CommuteSource, TravelMode } from '../common/types';
import { tuning } from '../config';

// Pure Fair Credit settlement rule (no DB access)

// One member's commute to a meetup that is being settled
export interface SettledTrip {
  userId: string;
  mode: TravelMode;
  actualSeconds: number;
  // Whether actualSeconds was measured by live tracking or estimated by Google
  source: CommuteSource;
}

export interface CreditChange {
  userId: string;
  source: CommuteSource;
  actualSeconds: number;
  // actualSeconds x the mode's multiplier
  normalizedBurden: number;
  // The group's average normalized burden for this meetup
  expectedBurden: number;
  // normalizedBurden - expectedBurden: positive for members who travelled more than average
  delta: number;
}

function modeMultiplier(mode: TravelMode): number {
  switch (mode) {
    case 'WALK':
      return tuning.walkBurdenMultiplier;
    case 'TRANSIT':
      return tuning.transitBurdenMultiplier;
    case 'DRIVE':
      return tuning.driveBurdenMultiplier;
  }
}

// A commute duration we accept for settlement; anything longer than a day is a broken measurement
export function isValidTripSeconds(seconds: number): boolean {
  return Number.isFinite(seconds) && seconds >= 0 && seconds <= tuning.maxTripSeconds;
}

export function normalizedBurden(actualSeconds: number, mode: TravelMode): number {
  return actualSeconds * modeMultiplier(mode);
}

/**
 * One credit change per trip: whoever carried more than the group's average burden gains credit,
 * whoever carried less loses the same amount, so the changes for one meetup sum to (about) zero.
 * Durations are rounded to whole seconds.
 */
export function creditChanges(trips: SettledTrip[]): CreditChange[] {
  if (trips.length === 0) return [];

  const burdens = trips.map((trip) => ({ trip, burden: normalizedBurden(trip.actualSeconds, trip.mode) }));
  const expected = burdens.reduce((sum, b) => sum + b.burden, 0) / trips.length;

  return burdens.map(({ trip, burden }) => ({
    userId: trip.userId,
    source: trip.source,
    actualSeconds: Math.round(trip.actualSeconds),
    normalizedBurden: Math.round(burden),
    expectedBurden: Math.round(expected),
    delta: Math.round(burden - expected),
  }));
}
