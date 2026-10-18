import { GroupModel, MeetupModel } from '../../src/users-groups/models';
import { VotingSessionModel } from '../../src/voting/models';
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

// Three distinct places, as the recommender would hand them to POST /meetups/:id/voting-session
export const CANDIDATES = [
  { placeId: 'place-1', name: 'Koerner Library', address: '1958 Main Mall', location: { lat: 49.2667, lng: -123.2551 } },
  { placeId: 'place-2', name: 'Loafe Cafe', address: '6163 University Blvd', location: { lat: 49.2654, lng: -123.2496 } },
  { placeId: 'place-3', name: 'Nest Food Court', address: '6133 University Blvd', location: { lat: 49.2665, lng: -123.2499 } },
];

// A meetup already in VOTING with an open session over CANDIDATES, created directly in the DB
export async function createVotingSession(meetupId: string, createdBy: string) {
  await MeetupModel.updateOne({ _id: meetupId }, { $set: { status: 'VOTING' } });
  return VotingSessionModel.create({ meetupId, createdBy, candidates: CANDIDATES });
}

// Candidate ids of a session, in candidate order
export function candidateIds(session: { candidates: { _id: unknown }[] }): [string, string, string] {
  const [a, b, c, ...rest] = session.candidates.map((candidate) => String(candidate._id));
  if (a === undefined || b === undefined || c === undefined || rest.length > 0) {
    throw new Error('Expected exactly three candidates');
  }
  return [a, b, c];
}
