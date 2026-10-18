import { tuning } from '../config';

// Pure vote counting: no DB access, so it can be unit tested on plain objects

interface TallyCandidate {
  _id: unknown;
}

interface TallyVote {
  candidateId: unknown;
  weight: number;
}

export interface CandidateTally {
  candidateId: string;
  voteCount: number;
  totalWeight: number;
  // Highest single voter weight; breaks ties in favour of the member with the most fairness debt
  topVoterWeight: number;
}

export function sameWeight(a: number, b: number): boolean {
  return Math.abs(a - b) < tuning.voteWeightEpsilon;
}

// Raw count and weighted total per candidate, in the session's candidate order
export function tally(candidates: TallyCandidate[], votes: TallyVote[]): CandidateTally[] {
  return candidates.map((candidate) => {
    const candidateId = String(candidate._id);
    const candidateVotes = votes.filter((v) => String(v.candidateId) === candidateId);

    return {
      candidateId,
      voteCount: candidateVotes.length,
      totalWeight: candidateVotes.reduce((sum, v) => sum + v.weight, 0),
      topVoterWeight: candidateVotes.reduce((max, v) => Math.max(max, v.weight), 0),
    };
  });
}

// Candidates still tied after comparing weighted totals, then top individual voter weight
export function tiedLeaders(candidates: TallyCandidate[], votes: TallyVote[]): string[] {
  const totals = tally(candidates, votes);
  const best = Math.max(...totals.map((t) => t.totalWeight));
  const leaders = totals.filter((t) => sameWeight(t.totalWeight, best));
  const bestVoter = Math.max(...leaders.map((t) => t.topVoterWeight));

  return leaders.filter((t) => sameWeight(t.topVoterWeight, bestVoter)).map((t) => t.candidateId);
}
