import { randomInt } from 'node:crypto';

import { Conflict, Forbidden, NotFound } from '../common/errors';
import { tuning } from '../config';
import { GroupModel } from './models';
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
  const names = new Map(users.map((u): [string, string] => [u.id, u.name]));

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

export const groupService = { create, listForUser, requireMembership, getDetail, join };
