import { tuning } from '../config';

// Pure vote counting (no DB access). Only locked votes carry weight; unlocked ones are just counted

export interface TallyVote {
  placeId: string;
  locked: boolean;
  weight?: number | null | undefined;
}

export interface CandidateTally {
  placeId: string;
  // Current picks, locked or not, so members see choices live before anyone locks
  voteCount: number;
  // Sum of locked weights for this candidate
  weight: number;
  // weight / totalWeight; 0 while nothing is locked
  share: number;
}

export interface Tally {
  candidates: CandidateTally[];
  totalWeight: number;
  lockedCount: number;
}

export type WinnerDecision =
  | { kind: 'winner'; placeId: string }
  | { kind: 'tie'; placeIds: string[] }
  | { kind: 'none' };

export function sameWeight(a: number, b: number): boolean {
  return Math.abs(a - b) < tuning.voteWeightEpsilon;
}

function lockedWeight(vote: TallyVote): number {
  return vote.locked ? (vote.weight ?? 0) : 0;
}

// Per-candidate totals in the recommendation's candidate order
export function tally(placeIds: string[], votes: TallyVote[]): Tally {
  const totalWeight = votes.reduce((sum, v) => sum + lockedWeight(v), 0);

  return {
    candidates: placeIds.map((placeId) => {
      const mine = votes.filter((v) => v.placeId === placeId);
      const weight = mine.reduce((sum, v) => sum + lockedWeight(v), 0);
      return { placeId, voteCount: mine.length, weight, share: totalWeight > 0 ? weight / totalWeight : 0 };
    }),
    totalWeight,
    lockedCount: votes.filter((v) => v.locked).length,
  };
}

/**
 * Winner = highest share of locked weight. Ties are broken by the highest single voter weight
 * (favouring the member who compromised most); a tie that still remains is left to the organizer.
 */
export function decideWinner(placeIds: string[], votes: TallyVote[]): WinnerDecision {
  const locked = votes.filter((v) => v.locked);
  if (locked.length === 0) return { kind: 'none' };

  const totals = tally(placeIds, locked).candidates.map((c) => ({
    placeId: c.placeId,
    weight: c.weight,
    topVoterWeight: locked
      .filter((v) => v.placeId === c.placeId)
      .reduce((max, v) => Math.max(max, lockedWeight(v)), 0),
  }));
  const best = Math.max(...totals.map((t) => t.weight));
  const leaders = totals.filter((t) => sameWeight(t.weight, best));
  const bestVoter = Math.max(...leaders.map((t) => t.topVoterWeight));
  const tied = leaders.filter((t) => sameWeight(t.topVoterWeight, bestVoter)).map((t) => t.placeId);

  const [only] = tied;
  return tied.length === 1 && only !== undefined ? { kind: 'winner', placeId: only } : { kind: 'tie', placeIds: tied };
}
