import { Types } from 'mongoose';

import { BadRequest } from '../common/errors';
import type { CommuteSource } from '../common/types';
import { tuning } from '../config';
import { groupService } from '../users-groups/group.service';
import { profileService } from '../users-groups/profile.service';
import { FairCreditEntryModel } from './models';

/**
 * Fairness debt per member per group, built from one entry per finalized meetup.
 * Other features read and write fairness data through this service instead of the model directly.
 */

export interface CommuteInput {
  userId: string;
  groupId: string;
  meetupId: string;
  commuteMinutes: number;
  groupAverageMinutes: number;
  source: CommuteSource;
}

// Vote weight from Fair Credit; [TBD] still a placeholder until the vote-weight formula is decided
async function getFairCredit(
  userId: string,
  sessionId: string,
): Promise<number> {
  void userId;
  void sessionId;

  return 1; // Placeholder implementation, replace with actual logic to calculate fair credit
}

function isValidMinutes(value: number): boolean {
  return Number.isFinite(value) && value >= 0 && value <= tuning.maxCommuteMinutes;
}

// Fairness debt per group for one user; groups without entries are absent (debt 0)
async function debtByGroup(userId: string, groupIds: Types.ObjectId[]): Promise<Map<string, number>> {
  const rows = await FairCreditEntryModel.aggregate<{ _id: Types.ObjectId; debt: number }>([
    { $match: { userId: new Types.ObjectId(userId), groupId: { $in: groupIds } } },
    { $group: { _id: '$groupId', debt: { $sum: '$extraMinutes' } } },
  ]);

  return new Map(rows.map((r): [string, number] => [String(r._id), r.debt]));
}

// Adds each group's fairness debt to a users-groups profile
async function withFairnessDebt(userId: string, profile: Awaited<ReturnType<typeof profileService.getProfile>>) {
  const debts = await debtByGroup(userId, profile.groups.map((g) => new Types.ObjectId(g.groupId)));

  return {
    ...profile,
    groups: profile.groups.map((group) => ({ ...group, fairnessDebt: debts.get(group.groupId) ?? 0 })),
  };
}

// Full profile: account info plus every group the user is in, each with its fairness debt
async function getProfile(userId: string) {
  return withFairnessDebt(userId, await profileService.getProfile(userId));
}

// null clears the custom name so the Google name is shown again
async function updateProfile(userId: string, input: { displayName: string | null }) {
  return withFairnessDebt(userId, await profileService.updateProfile(userId, input));
}

// Fairness debt in one group, in minutes; used as the basis for vote weight (Fair Credit)
async function getFairnessDebt(userId: string, groupId: string): Promise<number> {
  const debts = await debtByGroup(userId, [new Types.ObjectId(groupId)]);
  return debts.get(groupId) ?? 0;
}

// The caller's debt and past commutes in a group they belong to, newest first
async function getFairnessHistory(userId: string, groupId: string) {
  await groupService.requireMembership(groupId, userId);
  const history = await FairCreditEntryModel.find({ userId, groupId }).sort({ createdAt: -1 });

  return {
    groupId,
    fairnessDebt: history.reduce((sum, entry) => sum + entry.extraMinutes, 0),
    history,
  };
}

/**
 * Record (or replace) a user's commute for a finalized meetup. Called by commute tracking, or with
 * ESTIMATED when tracking never started; an estimate never overwrites a tracked commute.
 */
async function recordCommute(input: CommuteInput) {
  if (!isValidMinutes(input.commuteMinutes) || !isValidMinutes(input.groupAverageMinutes)) {
    throw new BadRequest(`Commute minutes must be between 0 and ${tuning.maxCommuteMinutes}`);
  }

  const key = { userId: input.userId, meetupId: input.meetupId };
  const existing = await FairCreditEntryModel.findOne(key);
  if (existing?.source === 'TRACKED' && input.source === 'ESTIMATED') return existing;

  return FairCreditEntryModel.findOneAndUpdate(
    key,
    {
      $set: {
        groupId: input.groupId,
        commuteMinutes: input.commuteMinutes,
        groupAverageMinutes: input.groupAverageMinutes,
        extraMinutes: input.commuteMinutes - input.groupAverageMinutes,
        source: input.source,
      },
    },
    { upsert: true, returnDocument: 'after', setDefaultsOnInsert: true },
  ).orFail();
}

export const fairCreditService = {
  getFairCredit,
  getProfile,
  updateProfile,
  getFairnessDebt,
  getFairnessHistory,
  recordCommute,
};
