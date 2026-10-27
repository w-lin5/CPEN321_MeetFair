import { randomInt } from 'node:crypto';

import { BadRequest, Conflict, Forbidden, NotFound } from '../common/errors';
import { tuning } from '../config';
import { GroupModel, MeetupModel } from './models';
import { publisher } from '../realtime/publisher';
import { SERVER_EVENTS, meetupRoom } from '../realtime/events';
import { VoteModel } from '../voting-fairness/models';
import { userService } from './user.service';

function generateInviteCode(): string {
  const alphabet = tuning.inviteCodeAlphabet;
  return Array.from({ length: tuning.inviteCodeLength }, () => alphabet.charAt(randomInt(alphabet.length))).join('');
}

function isDuplicateKey(err: unknown): boolean {
  return typeof err === 'object' && err !== null && 'code' in err && err.code === tuning.mongoDuplicateKeyCode;
}

// Create a group with the caller as organizer, retrying if a generated invite code collides
async function create(userId: string, name: string) {
  for (let attempt = 0; attempt < tuning.inviteCodeMaxAttempts; attempt++) {
    try {
      return await GroupModel.create({
        name,
        inviteCode: generateInviteCode(),
        organizerId: userId,
        members: [{ userId, role: 'ORGANIZER' }],
      });
    } catch (err) {
      if (!isDuplicateKey(err)) throw err;
    }
  }
  throw new Error('Could not generate a unique invite code');
}

async function listForUser(userId: string) {
  return GroupModel.find({ 'members.userId': userId }).sort({ createdAt: -1 });
}

// Load a group, ensuring it exists and the user is a member (404 / 403 otherwise)
async function requireMembership(groupId: string, userId: string) {
  const group = await GroupModel.findById(groupId);

  if (!group) throw new NotFound('Group not found');
  
  if (!group.members.some((m) => String(m.userId) === userId)) {
    throw new Forbidden('Not a member of this group');
  }
  
  return group;
}

// Group with each member's display name resolved
async function getDetail(groupId: string, userId: string) {
  const group = await requireMembership(groupId, userId);
  const users = await userService.findByIds(group.members.map((m) => m.userId));
  const names = new Map(users.map((u): [string, string] => [u.id, userService.displayNameOf(u)]));

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

async function removeMember(groupId: string, organizerId: string, memberId: string) {
  const group = await requireMembership(groupId, organizerId);
  if (String(group.organizerId) !== organizerId) throw new Forbidden('Only the group organizer can remove members');
  if (memberId === organizerId) throw new BadRequest('The group organizer cannot remove themselves');
  if (!group.members.some(member => String(member.userId) === memberId)) throw new NotFound('Member not found in this group');
  const participatingInActiveVote = await MeetupModel.exists({
    groupId,
    'participants.userId': memberId,
    status: { $in: ['VOTING', 'FINALIZED'] },
  });
  if (participatingInActiveVote) {
    throw new Conflict('This member has entered details for a meetup whose voting has started. Settle that meetup before removing them.');
  }
  const meetups = await MeetupModel.find({ groupId }).select('_id');
  const meetupIds = meetups.map(meetup => meetup._id);
  const result = await GroupModel.updateOne(
    { _id: groupId, organizerId, 'members.userId': memberId },
    { $pull: { members: { userId: memberId } } },
  );
  if (result.modifiedCount === 0) throw new Conflict('Group membership changed. Please refresh and try again.');
  publisher.revokeUser(memberId);
  // Membership is revoked first; remove all submitted inputs and votes in this group only.
  await MeetupModel.updateMany({ groupId }, { $pull: { participants: { userId: memberId } } });
  await VoteModel.deleteMany({ meetupId: { $in: meetupIds }, userId: memberId });
  // A removed meetup organizer must not leave an active meetup without anyone able to manage it.
  await MeetupModel.updateMany(
    { groupId, organizerId: memberId, status: { $ne: 'SETTLED' } },
    { $set: { organizerId } },
  );
  for (const meetupId of meetupIds) {
    publisher.publish(meetupRoom(String(meetupId)), SERVER_EVENTS.meetupUpdated, { meetupId: String(meetupId) });
  }
}

async function leave(groupId: string, userId: string) {
  const group = await requireMembership(groupId, userId);
  if (String(group.organizerId) === userId) throw new BadRequest('The group organizer cannot leave. Delete the group instead.');
  if (await MeetupModel.exists({ groupId, status: 'VOTING', 'participants.userId': userId })) {
    throw new Conflict('You cannot leave while a meetup you joined is still voting.');
  }
  const setupMeetups = await MeetupModel.find({ groupId, status: 'SETUP' }).select('_id');
  const setupIds = setupMeetups.map(meetup => meetup._id);
  await MeetupModel.updateMany({ groupId, status: 'SETUP' }, { $pull: { participants: { userId } } });
  await VoteModel.deleteMany({ meetupId: { $in: setupIds }, userId });
  await MeetupModel.updateMany(
    { groupId, organizerId: userId, status: { $ne: 'SETTLED' } },
    { $set: { organizerId: group.organizerId } },
  );
  const result = await GroupModel.updateOne({ _id: groupId, 'members.userId': userId }, { $pull: { members: { userId } } });
  if (result.modifiedCount === 0) throw new Conflict('Group membership changed. Please refresh and try again.');
  publisher.revokeUser(userId);
  // Silent refresh only: leaving is not a deletion notification.
  for (const meetupId of setupIds) {
    publisher.publish(meetupRoom(String(meetupId)), SERVER_EVENTS.meetupUpdated, { meetupId: String(meetupId) });
  }
}

export const groupService = { create, listForUser, requireMembership, getDetail, join, removeMember, leave };
