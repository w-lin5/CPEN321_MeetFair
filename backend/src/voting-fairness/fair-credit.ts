import type { TravelMode } from '../common/types';
import { tuning } from '../config';

// Pure Fair Credit settlement rule (no DB access)

export interface SettledTrip {
  userId: string;
  mode: TravelMode;
  actualSeconds: number;
}

export interface CreditChange {
  userId: string;
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
    actualSeconds: Math.round(trip.actualSeconds),
    normalizedBurden: Math.round(burden),
    expectedBurden: Math.round(expected),
    delta: Math.round(burden - expected),
  }));
}
