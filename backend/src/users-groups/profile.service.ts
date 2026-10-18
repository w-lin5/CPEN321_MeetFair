import { Types } from 'mongoose';

import { BadRequest, NotFound } from '../common/errors';
import type { CommuteSource } from '../common/types';
import { tuning } from '../config';
import { groupService } from './group.service';
import { FairnessEntryModel, UserModel } from './models';
import { userService } from './user.service';

/**
 * The user's persistent profile: account info, the groups they belong to and their fairness debt.
 * Other features read and write profile data through this service instead of the models directly.
 */

export interface CommuteInput {
  userId: string;
  groupId: string;
  meetupId: string;
  commuteMinutes: number;
  groupAverageMinutes: number;
  source: CommuteSource;
}

function isValidMinutes(value: number): boolean {
  return Number.isFinite(value) && value >= 0 && value <= tuning.maxCommuteMinutes;
}

// Fairness debt per group for one user; groups without entries are absent (debt 0)
async function debtByGroup(userId: string, groupIds: Types.ObjectId[]): Promise<Map<string, number>> {
  const rows = await FairnessEntryModel.aggregate<{ _id: Types.ObjectId; debt: number }>([
    { $match: { userId: new Types.ObjectId(userId), groupId: { $in: groupIds } } },
    { $group: { _id: '$groupId', debt: { $sum: '$extraMinutes' } } },
  ]);

  return new Map(rows.map((r): [string, number] => [String(r._id), r.debt]));
}

// Account fields shown to the user; displayName is already resolved to the name others should see
function account(user: Awaited<ReturnType<typeof userService.getById>>) {
  return {
    id: user.id,
    email: user.email,
    googleName: user.name,
    displayName: userService.displayNameOf(user),
    hasCustomDisplayName: user.displayName !== null && user.displayName !== undefined,
    photoUrl: user.photoUrl ?? null,
  };
}

// Full profile: account info plus every group the user is in, each with its fairness debt
async function getProfile(userId: string) {
  const user = await userService.getById(userId);
  const groups = await groupService.listForUser(userId);
  const debts = await debtByGroup(userId, groups.map((g) => g._id));

  return {
    ...account(user),
    groups: groups.map((group) => {
      const membership = group.members.find((m) => String(m.userId) === userId);
      return {
        groupId: group.id,
        name: group.name,
        role: membership?.role ?? 'MEMBER',
        joinedAt: membership?.joinedAt ?? null,
        fairnessDebt: debts.get(group.id) ?? 0,
      };
    }),
  };
}

// null clears the custom name so the Google name is shown again
async function updateProfile(userId: string, input: { displayName: string | null }) {
  const updated = await UserModel.findByIdAndUpdate(
    userId,
    { $set: { displayName: input.displayName } },
    { returnDocument: 'after' },
  );
  if (!updated) throw new NotFound('User not found');

  return getProfile(userId);
}

// Fairness debt in one group, in minutes; used as the basis for vote weight (Fair Credit)
async function getFairnessDebt(userId: string, groupId: string): Promise<number> {
  const debts = await debtByGroup(userId, [new Types.ObjectId(groupId)]);
  return debts.get(groupId) ?? 0;
}

// The caller's debt and past commutes in a group they belong to, newest first
async function getFairnessHistory(userId: string, groupId: string) {
  await groupService.requireMembership(groupId, userId);
  const history = await FairnessEntryModel.find({ userId, groupId }).sort({ createdAt: -1 });

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
  const existing = await FairnessEntryModel.findOne(key);
  if (existing?.source === 'TRACKED' && input.source === 'ESTIMATED') return existing;

  return FairnessEntryModel.findOneAndUpdate(
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

export const profileService = {
  getProfile,
  updateProfile,
  getFairnessDebt,
  getFairnessHistory,
  recordCommute,
};
