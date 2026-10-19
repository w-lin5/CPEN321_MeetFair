import { tuning } from '../config';

/**
 * Vote weight from Fair Credit (seconds): 1 + 0.1 per hour of credit, never below a normal vote and
 * capped, so members who compromised more get slightly more say. Pure; numbers live in tuning.
 */
export function voteWeight(fairCreditSeconds: number): number {
  const raw = tuning.voteWeightBase + tuning.voteWeightPerHour * (fairCreditSeconds / tuning.secondsPerHour);
  return Math.min(tuning.maxVoteWeight, Math.max(tuning.minVoteWeight, raw));
}
