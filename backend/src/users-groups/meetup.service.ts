import { NotFound, Conflict } from '../common/errors';
import { groupService } from './group.service';
import { MeetupModel } from './models';
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

// Load a meetup, ensuring it exists and the user belongs to its group (404 / 403 otherwise)
async function getForMember(meetupId: string, userId: string) {
  const meetup = await MeetupModel.findById(meetupId);
  if (!meetup) throw new NotFound('Meetup not found');

  await groupService.requireMembership(String(meetup.groupId), userId);

  return meetup;
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

export const meetupService = { create, listForGroup, getForMember, submitParticipation };
