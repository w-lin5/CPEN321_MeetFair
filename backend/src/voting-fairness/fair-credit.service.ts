import { Types } from 'mongoose';

import { BadRequest } from '../common/errors';
import { tuning } from '../config';
import { groupService } from '../users-groups/group.service';
import { meetupService } from '../users-groups/meetup.service';
import { profileService } from '../users-groups/profile.service';
import { userService } from '../users-groups/user.service';
import { type SettledTrip, creditChanges, isValidTripSeconds } from './fair-credit';
import { FairCreditEntryModel, FairCreditModel } from './models';
import { voteWeight } from './vote-weight';

/**
 * Fair Credit per member per group (seconds of travel burden above the group average, summed over
 * settled meetups). Balances live in fairCredits; every change is explained by a fairCreditEntries row.
 * Other features read and write Fair Credit through this service instead of the models directly.
 */

// Balances for one user across groups; groups without a balance yet are absent (initial credit)
async function balancesForUser(userId: string, groupIds: string[]): Promise<Map<string, number>> {
  const rows = await FairCreditModel.find({ userId, groupId: { $in: groupIds } });
  return new Map(rows.map((r): [string, number] => [String(r.groupId), r.balance]));
}

async function getBalance(userId: string, groupId: string): Promise<number> {
  const row = await FairCreditModel.findOne({ userId, groupId });
  return row?.balance ?? tuning.initialFairCredit;
}

// Balances of several members in one group; members without a balance yet are absent (initial credit)
async function getBalances(groupId: string, userIds: string[]): Promise<Map<string, number>> {
  const rows = await FairCreditModel.find({ groupId, userId: { $in: userIds } });
  return new Map(rows.map((r): [string, number] => [String(r.userId), r.balance]));
}

// Vote weight from the member's Fair Credit in the meetup's group, snapshotted when a vote is locked
async function getVoteWeight(userId: string, groupId: string): Promise<number> {
  return voteWeight(await getBalance(userId, groupId));
}

// Adds each group's Fair Credit to a users-groups profile
async function withFairCredit(userId: string, profile: Awaited<ReturnType<typeof profileService.getProfile>>) {
  const groupIds: string[] = profile.groups.map((g) => String(g.groupId));
  const balances = await balancesForUser(userId, groupIds);

  return {
    ...profile,
    groups: profile.groups.map((group) => ({
      ...group,
      fairCredit: balances.get(String(group.groupId)) ?? tuning.initialFairCredit,
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

// Every member's balance and the group's ledger with meetup context, newest first; members only
async function getGroupFairness(groupId: string, userId: string) {
  const group = await groupService.requireMembership(groupId, userId);
  const memberIds = group.members.map((m) => m.userId);
  const [users, credits, ledger] = await Promise.all([
    userService.findByIds(memberIds),
    FairCreditModel.find({ groupId }),
    FairCreditEntryModel.find({ groupId }).sort({ createdAt: -1 }),
  ]);
  const names = new Map(users.map((u): [string, string] => [String(u.id), userService.displayNameOf(u)]));
  const balances = new Map(credits.map((c): [string, number] => [String(c.userId), c.balance]));
  // Ledger rows show which meetup they came from; a deleted meetup leaves its rows without a title
  const meetups = await meetupService.findByIds([...new Set(ledger.map((entry) => String(entry.meetupId)))]);
  const meetupDetails = new Map(meetups.map((meetup) => [String(meetup.id), meetup]));

  return {
    groupId,
    members: memberIds.map((id) => ({
      userId: String(id),
      name: names.get(String(id)) ?? null,
      balance: balances.get(String(id)) ?? tuning.initialFairCredit,
    })),
    ledger: ledger.map((entry) => {
      const meetup = meetupDetails.get(String(entry.meetupId));
      return {
        ...entry.toJSON(),
        meetupTitle: meetup?.title ?? null,
        placeName: meetup?.winner?.name ?? null,
      };
    }),
  };
}

/**
 * Settle one meetup: turn each member's (mode-normalized) actual travel into a credit change against
 * the group's average, write the ledger, then apply the changes to the balances.
 * Safe to repeat: the ledger keeps the first entry per (meetup, member) and every balance remembers
 * which meetups it already includes, so a settlement that failed halfway is completed by running it again.
 */
async function settleMeetup(groupId: string, meetupId: string, trips: SettledTrip[]) {
  if (!trips.every((trip) => isValidTripSeconds(trip.actualSeconds))) {
    throw new BadRequest(`Trip durations must be between 0 and ${tuning.maxTripSeconds} seconds`);
  }
  if (trips.length === 0) return [];

  const group = new Types.ObjectId(groupId);
  const meetup = new Types.ObjectId(meetupId);

  await FairCreditEntryModel.bulkWrite(creditChanges(trips).map((change) => ({
    updateOne: {
      filter: { meetupId: meetup, userId: new Types.ObjectId(change.userId) },
      update: { $setOnInsert: { ...change, groupId: group } },
      upsert: true,
    },
  })));
  const entries = await FairCreditEntryModel.find({ meetupId });

  // Make sure every member has a balance, then add each ledger entry to it exactly once
  await FairCreditModel.bulkWrite(entries.map((entry) => ({
    updateOne: {
      filter: { groupId: group, userId: entry.userId },
      update: { $setOnInsert: { balance: tuning.initialFairCredit } },
      upsert: true,
    },
  })));
  await FairCreditModel.bulkWrite(entries.map((entry) => ({
    updateOne: {
      // The balance and its "already applied" marker change in the same atomic update
      filter: { groupId: group, userId: entry.userId, appliedMeetups: { $ne: meetup } },
      update: { $inc: { balance: entry.delta }, $addToSet: { appliedMeetups: meetup } },
    },
  })));

  return entries;
}

// Registered as dependent data of users-groups (app.ts): Fair Credit dies with its group
async function deleteForGroup(groupId: string) {
  await FairCreditEntryModel.deleteMany({ groupId });
  await FairCreditModel.deleteMany({ groupId });
}

export const fairCreditService = {
  getBalance,
  getBalances,
  getVoteWeight,
  getProfile,
  updateProfile,
  getGroupFairness,
  settleMeetup,
  deleteForGroup,
};
