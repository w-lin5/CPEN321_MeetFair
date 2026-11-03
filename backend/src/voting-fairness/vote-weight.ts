import { tuning } from '../config';

/**
 * Vote weight from Fair Credit (seconds): 1 + 0.1 per 30 minutes of credit, never below a normal vote and
 * capped, so members who compromised more get slightly more say. Pure; numbers live in tuning.
 */
export function voteWeight(fairCreditSeconds: number): number {
  const raw = tuning.voteWeightBase + tuning.voteWeightPerInterval * (fairCreditSeconds / tuning.fairCreditWeightIntervalSeconds);
  return Math.min(tuning.maxVoteWeight, Math.max(tuning.minVoteWeight, raw));
}
