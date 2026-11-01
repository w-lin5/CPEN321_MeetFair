import type { TravelMode } from '../common/types';
import { tuning } from '../config';
import { normalizedBurden } from '../voting-fairness/fair-credit';
import { voteWeight } from '../voting-fairness/vote-weight';
import { preferenceMismatch } from './preference-types';
import type { CandidateInput, CandidateScores, ScoredCandidate } from './schemas';

// Pure recommendation scoring (no DB or Google access); see documentation/Recommendation_Algorithm_Plan.md

export interface ScoringMember {
  userId: string;
  mode: TravelMode;
  // Fair Credit balance in the meetup's group (seconds)
  fairCredit: number;
  preferences: readonly string[];
}

// One member's side of a candidate: normalized burden B_i (minutes), weight w_i and preference mismatch P_i
export interface MemberTrip {
  burden: number;
  weight: number;
  mismatch: number;
}

function sum(values: number[]): number {
  return values.reduce((total, value) => total + value, 0);
}

/**
 * Score for one place from each member's trip:
 *   W = sum(w_i B_i) / sum(w_i)          Fair Credit-weighted travel
 *   M = max(B_i)                         longest single trip
 *   I = mean(|B_i - mean(B)|)            travel imbalance (mean absolute deviation)
 *   P = mu sum(w_i P_i) / sum(w_i)       Fair Credit-weighted preference penalty
 *   total = W + beta M + gamma I + P
 */
export function scorePlace(trips: MemberTrip[]): CandidateScores {
  const burdens = trips.map(trip => trip.burden);
  const totalWeight = sum(trips.map(trip => trip.weight));
  const weightedTravel = sum(trips.map(trip => trip.weight * trip.burden)) / totalWeight;
  const maxTravel = Math.max(...burdens);
  const mean = sum(burdens) / burdens.length;
  const imbalance = sum(burdens.map(burden => Math.abs(burden - mean))) / burdens.length;
  const preference = tuning.recommendationPreferencePenaltyMinutes
    * sum(trips.map(trip => trip.weight * trip.mismatch)) / totalWeight;
  const total = weightedTravel
    + tuning.recommendationMaxTravelWeight * maxTravel
    + tuning.recommendationImbalanceWeight * imbalance
    + preference;
  return { weightedTravel, maxTravel, imbalance, preference, total };
}

function isValidSeconds(value: number | null | undefined): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0;
}

/**
 * Every place every member can reach, best (lowest total) first; ties are broken by placeId so the same
 * inputs always give the same order. seconds[i][j] is member i's travel time to place j, or null when
 * Google found no route (such places are dropped).
 */
export function rankCandidates(
  members: ScoringMember[],
  places: CandidateInput[],
  seconds: (number | null)[][],
): ScoredCandidate[] {
  if (members.length === 0) return [];
  const rows = members.map((member, i) => ({ member, weight: voteWeight(member.fairCredit), times: seconds.at(i) ?? [] }));

  const scored: ScoredCandidate[] = [];
  places.forEach((place, j) => {
    const reached = rows.flatMap(row => {
      const time = row.times.at(j);
      return isValidSeconds(time) ? [{ ...row, time, normalized: normalizedBurden(time, row.member.mode) }] : [];
    });
    if (reached.length < rows.length) return;
    const types = place.types ?? [];
    const travel = reached.map(({ member, time, normalized }) => ({
      userId: member.userId,
      mode: member.mode,
      seconds: time,
      normalizedSeconds: normalized,
    }));
    const scores = scorePlace(reached.map(({ member, weight, normalized }) => ({
      burden: normalized / tuning.secondsPerMinute,
      weight,
      mismatch: preferenceMismatch(member.preferences, types),
    })));
    scored.push({ ...place, types, scores, travel });
  });

  return scored.sort((a, b) => {
    const difference = a.scores.total - b.scores.total;
    if (Math.abs(difference) > tuning.recommendationScoreEpsilon) return difference;
    if (a.placeId === b.placeId) return 0;
    return a.placeId < b.placeId ? -1 : 1;
  });
}
