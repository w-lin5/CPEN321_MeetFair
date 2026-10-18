import { GroupModel, MeetupModel } from '../../src/users-groups/models';
import { createUser } from './auth';

// Creates the nth distinct user (createUser alone always makes the same Google account)
export async function createUserN(n: number) {
  return createUser({ googleSub: `google-sub-${n}`, email: `user${n}@example.com`, name: `User ${n}` });
}

// A group whose only member is its organizer
export async function createGroup(organizerId: string, overrides: Partial<{ name: string; inviteCode: string }> = {}) {
  return GroupModel.create({
    name: 'Study Crew',
    inviteCode: 'ABC234',
    organizerId,
    members: [{ userId: organizerId, role: 'ORGANIZER' }],
    ...overrides,
  });
}

export async function addMember(groupId: string, userId: string) {
  await GroupModel.updateOne(
    { _id: groupId },
    { $push: { members: { userId, role: 'MEMBER', joinedAt: new Date() } } },
  );
}

export async function createMeetup(
  groupId: string,
  organizerId: string,
  overrides: Partial<{ title: string; scheduledAt: Date; status: 'SETUP' | 'VOTING' | 'FINALIZED' | 'SETTLED' }> = {},
) {
  return MeetupModel.create({
    groupId,
    organizerId,
    title: 'Library session',
    scheduledAt: new Date('2026-11-05T22:30:00Z'),
    ...overrides,
  });
}

// A valid PUT /meetups/:id/participation body
export const PARTICIPATION = {
  startLocation: { lat: 49.2606, lng: -123.246, label: 'UBC' },
  travelMode: 'TRANSIT',
  preferences: ['STUDY', 'COFFEE'],
} as const;

export const MISSING_ID = '507f1f77bcf86cd799439011';
