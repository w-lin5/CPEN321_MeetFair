import { BadRequest, Conflict, NotFound } from '../common/errors';
import type { LatLng, MeetupStatus, TravelMode } from '../common/types';
import { SERVER_EVENTS, meetupRoom } from '../realtime/events';
import { publisher } from '../realtime/publisher';
import { dependentData } from './dependent-data';
import { groupService } from './group.service';
import { type MeetupFields, MeetupModel, type MeetupWinner } from './models';
import type { ParticipationInput } from './schemas';

// A participant as the travel computations need them: who, from where, how, and what they prefer
export interface Traveller {
  userId: string;
  location: LatLng;
  mode: TravelMode;
  preferences: string[];
}

// Create a meetup in SETUP; the caller must be a group member and becomes its organizer
async function create(userId: string, groupId: string, input: { title: string; scheduledAt: string }) {
  await groupService.requireMembership(groupId, userId);
  const scheduledAt = new Date(input.scheduledAt);
  if (!Number.isFinite(scheduledAt.getTime()) || scheduledAt.getTime() <= Date.now()) {
    throw new BadRequest('Meeting time must be in the future.');
  }
  return MeetupModel.create({ groupId, organizerId: userId, title: input.title, scheduledAt });
}

async function listForGroup(groupId: string, userId: string) {
  await groupService.requireMembership(groupId, userId);
  return MeetupModel.find({ groupId }).sort({ scheduledAt: -1 });
}

// Load a meetup without an access check (404 if missing); callers check membership themselves
async function getById(meetupId: string) {
  const meetup = await MeetupModel.findById(meetupId);
  if (!meetup) throw new NotFound('Meetup not found');
  return meetup;
}

// Meetups by id without an access check, for callers that already checked the group; missing ids are skipped
async function findByIds(meetupIds: string[]) {
  return MeetupModel.find({ _id: { $in: meetupIds } });
}

// Load a meetup, ensuring it exists and the user belongs to its group (404 / 403 otherwise)
async function getForMember(meetupId: string, userId: string) {
  const meetup = await getById(meetupId);
  await groupService.requireMembership(String(meetup.groupId), userId);
  return meetup;
}

// Every participant with the start location and travel mode they submitted
function travellers(meetup: Pick<MeetupFields, 'participants'>): Traveller[] {
  return meetup.participants.map((participant) => {
    const start = participant.startLocation;
    // The schema requires coordinates; this only guards against a document written around it
    if (!start || !Number.isFinite(start.lat) || !Number.isFinite(start.lng)) {
      throw new Conflict('A member has an invalid starting location. Please submit it again.');
    }
    return {
      userId: String(participant.userId),
      location: { lat: start.lat, lng: start.lng },
      mode: participant.travelMode,
      preferences: participant.preferences,
    };
  });
}

// Atomic status change: only moves the meetup if it is still in `from`; returns whether it moved
async function transitionStatus(meetupId: string, from: MeetupStatus, to: MeetupStatus): Promise<boolean> {
  const moved = await MeetupModel.updateOne({ _id: meetupId, status: from }, { $set: { status: to } });
  return moved.modifiedCount > 0;
}

// VOTING -> FINALIZED with the winning place, atomically; null if the meetup was no longer in VOTING
async function finalize(meetupId: string, winner: MeetupWinner) {
  return MeetupModel.findOneAndUpdate(
    { _id: meetupId, status: 'VOTING' },
    { $set: { status: 'FINALIZED', winner, finalizedAt: new Date() } },
    { returnDocument: 'after' },
  );
}

// Remember the Google Calendar event created for one participant; returns false if one was already recorded
async function recordCalendarEvent(meetupId: string, userId: string, eventId: string): Promise<boolean> {
  const recorded = await MeetupModel.updateOne(
    { _id: meetupId, participants: { $elemMatch: { userId, calendarEventId: { $exists: false } } } },
    { $set: { 'participants.$.calendarEventId': eventId } },
  );
  return recorded.modifiedCount > 0;
}

// Add or replace the caller's participation entry (start location, travel mode, preferences) while in SETUP
async function submitParticipation(meetupId: string, userId: string, input: ParticipationInput) {
  await getForMember(meetupId, userId);
  const entry = { userId, ...input };

  // matchedCount (not modifiedCount): resubmitting identical data must still count as a replace
  const replaced = await MeetupModel.updateOne(
    { _id: meetupId, status: 'SETUP', 'participants.userId': userId },
    { $set: { 'participants.$': entry } },
  );
  if (replaced.matchedCount === 0) {
    const added = await MeetupModel.updateOne(
      { _id: meetupId, status: 'SETUP', 'participants.userId': { $ne: userId } },
      { $push: { participants: entry } },
    );
    if (added.modifiedCount === 0) throw new Conflict('Meetup is no longer accepting participation');
  }

  const updated = await MeetupModel.findById(meetupId).orFail();
  publisher.publish(meetupRoom(meetupId), SERVER_EVENTS.meetupUpdated, { meetupId });
  return updated;
}

// Withdraw the caller's participation entry; only possible before voting starts
async function withdrawParticipation(meetupId: string, userId: string) {
  await getForMember(meetupId, userId);
  // Conditional on SETUP so a vote that starts concurrently never loses a participant
  const withdrawn = await MeetupModel.updateOne(
    { _id: meetupId, status: 'SETUP' },
    { $pull: { participants: { userId } } },
  );
  if (withdrawn.matchedCount === 0) throw new Conflict('You can only leave a meetup before voting starts.');

  await dependentData.participationWithdrawn([meetupId], userId);
  publisher.publish(meetupRoom(meetupId), SERVER_EVENTS.meetupUpdated, { meetupId });
}

export const meetupService = {
  create,
  listForGroup,
  getById,
  findByIds,
  getForMember,
  travellers,
  transitionStatus,
  finalize,
  recordCalendarEvent,
  submitParticipation,
  withdrawParticipation,
};
