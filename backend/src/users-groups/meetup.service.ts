import { NotFound, Conflict } from '../common/errors';
import type { MeetupStatus } from '../common/types';
import { groupService } from './group.service';
import { MeetupModel, type MeetupWinner } from './models';
import type { ParticipationInput } from './schemas';

// Create a meetup in SETUP (enum); the caller must be a group member and becomes its organizer
async function create(userId: string, groupId: string, input: { title: string; scheduledAt: string }) {
  await groupService.requireMembership(groupId, userId);
  return MeetupModel.create({
    groupId,
    organizerId: userId,
    title: input.title,
    scheduledAt: new Date(input.scheduledAt),
  });
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

// Load a meetup, ensuring it exists and the user belongs to its group (404 / 403 otherwise)
async function getForMember(meetupId: string, userId: string) {
  const meetup = await getById(meetupId);

  await groupService.requireMembership(String(meetup.groupId), userId);

  return meetup;
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

// Add or replace the caller's participation entry (i.e., details and preferences); only allowed while the meetup is in SETUP
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

  return MeetupModel.findById(meetupId).orFail();
}

export const meetupService = {
  create,
  listForGroup,
  getById,
  getForMember,
  submitParticipation,
  transitionStatus,
  finalize,
  recordCalendarEvent,
};
