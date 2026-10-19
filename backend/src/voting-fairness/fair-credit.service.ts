import { Types } from 'mongoose';

import { BadRequest, Conflict } from '../common/errors';
import type { CommuteSource } from '../common/types';
import { tuning } from '../config';
import { groupService } from '../users-groups/group.service';
import { profileService } from '../users-groups/profile.service';
import { userService } from '../users-groups/user.service';
import { type SettledTrip, creditChanges } from './fair-credit';
import { FairCreditEntryModel, FairCreditModel } from './models';
import { voteWeight } from './vote-weight';

/**
 * Fair Credit per member per group (seconds of travel burden above the group average, summed over
 * settled meetups). Balances live in fairCredits; every change is explained by a fairCreditEntries row.
 * Other features read and write Fair Credit through this service instead of the models directly.
 */

export interface SettlementTrip extends SettledTrip {
  source: CommuteSource;
}

// Balances for one user across groups; groups without a balance yet are absent (initial credit)
async function balancesForUser(userId: string, groupIds: string[]): Promise<Map<string, number>> {
  const rows = await FairCreditModel.find({ userId, groupId: { $in: groupIds } });
  return new Map(rows.map((r): [string, number] => [String(r.groupId), r.balance]));
}

async function getBalance(userId: string, groupId: string): Promise<number> {
  const row = await FairCreditModel.findOne({ userId, groupId });
  return row?.balance ?? tuning.initialFairCredit;
}

// Vote weight from the member's Fair Credit in the meetup's group, snapshotted when a vote is locked
async function getVoteWeight(userId: string, groupId: string): Promise<number> {
  return voteWeight(await getBalance(userId, groupId));
}

// Adds each group's Fair Credit to a users-groups profile
async function withFairCredit(userId: string, profile: Awaited<ReturnType<typeof profileService.getProfile>>) {
  const balances = await balancesForUser(userId, profile.groups.map((g) => g.groupId));

  return {
    ...profile,
    groups: profile.groups.map((group) => ({
      ...group,
      fairCredit: balances.get(group.groupId) ?? tuning.initialFairCredit,
    })),
  };
}

// Full profile: account info plus every group the user is in, each with their Fair Credit there
async function getProfile(userId: string) {
  return withFairCredit(userId, await profileService.getProfile(userId));
}

// null clears the custom name so the Google name is shown again
async function updateProfile(userId: string, input: { displayName: string | null }) {
  return withFairCredit(userId, await profileService.updateProfile(userId, input));
}

// Every member's balance (with display name) and the group's ledger, newest first; members only
async function getGroupFairness(groupId: string, userId: string) {
  const group = await groupService.requireMembership(groupId, userId);
  const memberIds = group.members.map((m) => m.userId);
  const [users, credits, ledger] = await Promise.all([
    userService.findByIds(memberIds),
    FairCreditModel.find({ groupId }),
    FairCreditEntryModel.find({ groupId }).sort({ createdAt: -1 }),
  ]);
  const names = new Map(users.map((u): [string, string] => [u.id, userService.displayNameOf(u)]));
  const balances = new Map(credits.map((c): [string, number] => [String(c.userId), c.balance]));

  return {
    groupId,
    members: memberIds.map((id) => ({
      userId: String(id),
      name: names.get(String(id)) ?? null,
      balance: balances.get(String(id)) ?? tuning.initialFairCredit,
    })),
    ledger,
  };
}

function isValidTrip(trip: SettlementTrip): boolean {
  return Number.isFinite(trip.actualSeconds) && trip.actualSeconds >= 0 && trip.actualSeconds <= tuning.maxTripSeconds;
}

/**
 * Settle one meetup: turn each member's (mode-normalized) actual travel into a credit change against
 * the group's average, write the ledger, then apply the changes to the balances. Called by commute
 * tracking once every trip has an actual (TRACKED) or fallback (ESTIMATED) duration. 409 if already settled.
 */
async function settleMeetup(groupId: string, meetupId: string, trips: SettlementTrip[]) {
  if (!trips.every(isValidTrip)) {
    throw new BadRequest(`Trip durations must be between 0 and ${tuning.maxTripSeconds} seconds`);
  }
  if (await FairCreditEntryModel.exists({ meetupId })) throw new Conflict('This meetup is already settled');

  const sources = new Map(trips.map((t): [string, CommuteSource] => [t.userId, t.source]));
  const changes = creditChanges(trips);

  // The unique (meetupId, userId) index makes a concurrent second settlement fail here, before any balance moves
  const entries = await FairCreditEntryModel.insertMany(changes.map((change) => ({
    ...change,
    groupId,
    meetupId,
    source: sources.get(change.userId) ?? 'ESTIMATED',
  })));

  // Pipeline update so a member's first balance starts from tuning.initialFairCredit, not 0
  await FairCreditModel.bulkWrite(changes.map((change) => ({
    updateOne: {
      filter: { groupId: new Types.ObjectId(groupId), userId: new Types.ObjectId(change.userId) },
      update: [{ $set: { balance: { $add: [{ $ifNull: ['$balance', tuning.initialFairCredit] }, change.delta] } } }],
      upsert: true,
    },
  })));

  return entries;
}

export const fairCreditService = {
  getBalance,
  getVoteWeight,
  getProfile,
  updateProfile,
  getGroupFairness,
  settleMeetup,
};
