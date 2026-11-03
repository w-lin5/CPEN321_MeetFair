import { Types } from 'mongoose';

import { BadRequest, Conflict } from '../common/errors';
import type { CommuteSource } from '../common/types';
import { tuning } from '../config';
import { groupService } from '../users-groups/group.service';
import { MeetupModel } from '../users-groups/models';
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

// Balances of several members in one group; members without a balance yet get the initial credit
async function getBalances(groupId: string, userIds: string[]): Promise<Map<string, number>> {
  const rows = await FairCreditModel.find({ groupId, userId: { $in: userIds } });
  const balances = new Map(rows.map((r): [string, number] => [String(r.userId), r.balance]));
  return new Map(userIds.map((id): [string, number] => [id, balances.get(id) ?? tuning.initialFairCredit]));
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
  const meetupIds = [...new Set(ledger.map((entry) => String(entry.meetupId)))];
  const meetups = meetupIds.length ? await MeetupModel.find({ _id: { $in: meetupIds }, groupId }) : [];
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

function isValidTrip(trip: SettlementTrip): boolean {
  return Number.isFinite(trip.actualSeconds) && trip.actualSeconds >= 0 && trip.actualSeconds <= tuning.maxTripSeconds;
}

/**
 * Settle one meetup: turn each member's (mode-normalized) actual travel into a credit change against
 * the group's average, write the ledger, then apply the changes to the balances. Called by commute
 * tracking once every trip has an actual (TRACKED) or fallback (ESTIMATED) duration. 409 if already settled.
 */
async function settleMeetup(groupId: string, meetupId: string, trips: SettlementTrip[], resume = false) {
  if (!trips.every(isValidTrip)) {
    throw new BadRequest(`Trip durations must be between 0 and ${tuning.maxTripSeconds} seconds`);
  }
  if (!resume && await FairCreditEntryModel.exists({ meetupId })) throw new Conflict('This meetup is already settled');

  const sources = new Map(trips.map((t): [string, CommuteSource] => [t.userId, t.source]));
  const changes = creditChanges(trips);

  const rows = changes.map((change) => ({
    ...change,
    groupId,
    meetupId,
    source: sources.get(change.userId) ?? 'ESTIMATED',
  }));
  if (resume) {
    // Resume uses the persisted, immutable snapshot supplied by the settlement service.
    for (const row of rows) {
      try {
        await FairCreditEntryModel.updateOne({ meetupId, userId: row.userId }, { $setOnInsert: row }, { upsert: true });
      } catch (error) {
        if (!(typeof error === 'object' && error !== null && 'code' in error && error.code === 11000)) throw error;
      }
    }
  } else {
    await FairCreditEntryModel.insertMany(rows);
  }
  const entries = await FairCreditEntryModel.find({ meetupId });

  // Initialize new balances, then apply each ledger entry exactly once.
  for (const entry of entries) {
    const filter = { groupId: new Types.ObjectId(groupId), userId: entry.userId };
    try {
      await FairCreditModel.updateOne(filter, { $setOnInsert: { ...filter, balance: tuning.initialFairCredit } }, { upsert: true });
    } catch (error) {
      if (!(typeof error === 'object' && error !== null && 'code' in error && error.code === 11000)) throw error;
    }
    // The balance and deduplication marker change in the same atomic document update.
    await FairCreditModel.updateOne({ ...filter, appliedMeetups: { $ne: new Types.ObjectId(meetupId) } }, {
      $inc: { balance: entry.delta }, $addToSet: { appliedMeetups: new Types.ObjectId(meetupId) },
    });
  }

  return entries;
}

export const fairCreditService = {
  getBalance,
  getBalances,
  getVoteWeight,
  getProfile,
  updateProfile,
  getGroupFairness,
  settleMeetup,
};
