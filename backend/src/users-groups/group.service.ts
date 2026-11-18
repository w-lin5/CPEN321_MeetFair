import { randomInt } from 'node:crypto';

import type { Types } from 'mongoose';

import { BadRequest, Conflict, Forbidden, NotFound } from '../common/errors';
import { tuning } from '../config';
import { isDuplicateKeyError } from '../db';
import { SERVER_EVENTS, meetupRoom } from '../realtime/events';
import { publisher } from '../realtime/publisher';
import { dependentData } from './dependent-data';
import { GroupModel, MeetupModel } from './models';
import { userService } from './user.service';

type GroupDoc = Awaited<ReturnType<typeof requireMembership>>;

const MEMBERSHIP_CHANGED = 'Group membership changed. Please refresh and try again.';

function generateInviteCode(): string {
  const alphabet = tuning.inviteCodeAlphabet;
  return Array.from({ length: tuning.inviteCodeLength }, () => alphabet.charAt(randomInt(alphabet.length))).join('');
}

// Insert the group with a fresh invite code, retrying on a collision; null if every attempt collided
async function insertWithUniqueInviteCode(userId: string, name: string) {
  for (let attempt = 0; attempt < tuning.inviteCodeMaxAttempts; attempt++) {
    try {
      return await GroupModel.create({
        name,
        inviteCode: generateInviteCode(),
        organizerId: userId,
        members: [{ userId, role: 'ORGANIZER' }],
      });
    } catch (err) {
      if (!isDuplicateKeyError(err)) throw err;
    }
  }
  return null;
}

// Create a group with the caller as organizer
async function create(userId: string, name: string) {
  const group = await insertWithUniqueInviteCode(userId, name);
  if (group === null) throw new Error('Could not generate a unique invite code');
  return group;
}

async function listForUser(userId: string) {
  return GroupModel.find({ 'members.userId': userId }).sort({ createdAt: -1 });
}

function isMember(group: { members: { userId: unknown }[] }, userId: string): boolean {
  return group.members.some((member) => String(member.userId) === userId);
}

// Load a group, ensuring it exists and the user is a member (404 / 403 otherwise)
async function requireMembership(groupId: string, userId: string) {
  const group = await GroupModel.findById(groupId);
  if (!group) throw new NotFound('Group not found');
  if (!isMember(group, userId)) throw new Forbidden('Not a member of this group');
  return group;
}

// Group with each member's display name resolved
async function getDetail(groupId: string, userId: string) {
  const group = await requireMembership(groupId, userId);
  const users = await userService.findByIds(group.members.map((m) => m.userId));
  const names = new Map(users.map((u): [string, string] => [String(u.id), userService.displayNameOf(u)]));

  return {
    ...group.toJSON(),
    members: group.members.map((m) => ({
      userId: String(m.userId),
      name: names.get(String(m.userId)) ?? null,
      role: m.role,
      joinedAt: m.joinedAt,
    })),
  };
}

// Atomic join: the filter fails if the user is already a member, so concurrent joins cannot duplicate
async function join(userId: string, inviteCode: string) {
  const group = await GroupModel.findOne({ inviteCode });
  if (!group) throw new NotFound('Invalid invite code');

  const result = await GroupModel.updateOne(
    { _id: group._id, 'members.userId': { $ne: userId } },
    { $push: { members: { userId, role: 'MEMBER', joinedAt: new Date() } } },
  );
  if (result.modifiedCount === 0) throw new Conflict('Already a member of this group');

  return GroupModel.findById(group._id).orFail();
}

// Ids of the group's meetups, optionally only those in one status
async function meetupIdsOf(groupId: string, status?: 'SETUP'): Promise<string[]> {
  const meetups = await MeetupModel.find(status ? { groupId, status } : { groupId }).select('_id');
  return meetups.map((meetup) => String(meetup._id));
}

// A departing meetup organizer must not leave an unfinished meetup without anyone able to manage it
async function handOverMeetups(groupId: string, fromUserId: string, toUserId: string | Types.ObjectId): Promise<void> {
  await MeetupModel.updateMany(
    { groupId, organizerId: fromUserId, status: { $ne: 'SETTLED' } },
    { $set: { organizerId: toUserId } },
  );
}

// Open meetup pages reload silently: a departure is not announced like a deletion
function publishMeetupsUpdated(meetupIds: string[]): void {
  for (const meetupId of meetupIds) {
    publisher.publish(meetupRoom(meetupId), SERVER_EVENTS.meetupUpdated, { meetupId });
  }
}

function requireRemovable(group: GroupDoc, organizerId: string, memberId: string): void {
  if (String(group.organizerId) !== organizerId) throw new Forbidden('Only the group organizer can remove members');
  if (memberId === organizerId) throw new BadRequest('The group organizer cannot remove themselves');
  if (!isMember(group, memberId)) throw new NotFound('Member not found in this group');
}

// The group organizer removes another member together with everything they entered in the group's meetups
async function removeMember(groupId: string, organizerId: string, memberId: string) {
  requireRemovable(await requireMembership(groupId, organizerId), organizerId, memberId);
  // Their details already count towards an open vote or a finalized destination
  const inActiveMeetup = await MeetupModel.exists({
    groupId,
    'participants.userId': memberId,
    status: { $in: ['VOTING', 'FINALIZED'] },
  });
  if (inActiveMeetup) {
    throw new Conflict(
      'This member has entered details for a meetup whose voting has started. Settle that meetup before removing them.',
    );
  }

  const meetupIds = await meetupIdsOf(groupId);
  const removed = await GroupModel.updateOne(
    { _id: groupId, organizerId, 'members.userId': memberId },
    { $pull: { members: { userId: memberId } } },
  );
  if (removed.modifiedCount === 0) throw new Conflict(MEMBERSHIP_CHANGED);

  // Membership is revoked first; then everything the member entered in this group goes
  publisher.revokeUser(memberId);
  await MeetupModel.updateMany({ groupId }, { $pull: { participants: { userId: memberId } } });
  await dependentData.participationWithdrawn(meetupIds, memberId);
  await dependentData.memberLeft(meetupIds, memberId);
  await handOverMeetups(groupId, memberId, organizerId);
  publishMeetupsUpdated(meetupIds);
}

// A member leaves the group; their details are withdrawn from meetups that are still in SETUP
async function leave(groupId: string, userId: string) {
  const group = await requireMembership(groupId, userId);
  if (String(group.organizerId) === userId) {
    throw new BadRequest('The group organizer cannot leave. Delete the group instead.');
  }
  if (await MeetupModel.exists({ groupId, status: 'VOTING', 'participants.userId': userId })) {
    throw new Conflict('You cannot leave while a meetup you joined is still voting.');
  }

  const [meetupIds, setupMeetupIds] = await Promise.all([meetupIdsOf(groupId), meetupIdsOf(groupId, 'SETUP')]);
  await dependentData.memberLeft(meetupIds, userId);
  await MeetupModel.updateMany({ groupId, status: 'SETUP' }, { $pull: { participants: { userId } } });
  await dependentData.participationWithdrawn(setupMeetupIds, userId);
  await handOverMeetups(groupId, userId, group.organizerId);

  const left = await GroupModel.updateOne({ _id: groupId, 'members.userId': userId }, { $pull: { members: { userId } } });
  if (left.modifiedCount === 0) throw new Conflict(MEMBERSHIP_CHANGED);
  publisher.revokeUser(userId);
  publishMeetupsUpdated(setupMeetupIds);
}

export const groupService = { create, listForUser, requireMembership, getDetail, join, removeMember, leave };
