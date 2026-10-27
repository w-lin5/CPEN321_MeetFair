import { RecommendationModel } from '../../src/recommendation/models';
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
  preferences: ['FOOD', 'COFFEE'],
} as const;

export const MISSING_ID = '507f1f77bcf86cd799439011';

// Three distinct places, as a recommendation's candidates (POST /meetups/:id/recommendations body)
export const CANDIDATES = [
  { placeId: 'place-1', name: 'Koerner Library', address: '1958 Main Mall', location: { lat: 49.2667, lng: -123.2551 } },
  { placeId: 'place-2', name: 'Loafe Cafe', address: '6163 University Blvd', location: { lat: 49.2654, lng: -123.2496 } },
  { placeId: 'place-3', name: 'Nest Food Court', address: '6133 University Blvd', location: { lat: 49.2665, lng: -123.2499 } },
];

export const [PLACE_1, PLACE_2, PLACE_3] = ['place-1', 'place-2', 'place-3'] as const;

// Every listed user enters PARTICIPATION for the meetup
export async function addParticipants(meetupId: string, userIds: string[]) {
  await MeetupModel.updateOne(
    { _id: meetupId },
    { $set: { participants: userIds.map((userId) => ({ userId, ...PARTICIPATION })) } },
  );
}

// A meetup in VOTING over CANDIDATES whose participants are `participantIds`, created directly in the DB
export async function createVotingMeetup(groupId: string, organizerId: string, participantIds: string[]) {
  const meetup = await createMeetup(groupId, organizerId, { status: 'VOTING' });
  await addParticipants(meetup.id, participantIds);
  await RecommendationModel.create({ meetupId: meetup.id, candidates: CANDIDATES });
  return MeetupModel.findById(meetup._id).orFail();
}

// A FINALIZED meetup at CANDIDATES[0] whose participants entered PARTICIPATION
export async function createFinalizedMeetup(groupId: string, organizerId: string, participantIds: string[]) {
  const meetup = await createMeetup(groupId, organizerId, { status: 'FINALIZED' });
  await addParticipants(meetup.id, participantIds);
  await MeetupModel.updateOne({ _id: meetup._id }, { $set: { winner: CANDIDATES[0], finalizedAt: new Date() } });
  return MeetupModel.findById(meetup._id).orFail();
}
