import { Forbidden, NotFound } from '../common/errors';
import { RecommendationModel } from '../recommendation/models';
import { SERVER_EVENTS, meetupRoom, userRoom } from '../realtime/events';
import { publisher } from '../realtime/publisher';
import { FairCreditEntryModel, FairCreditModel, VoteModel } from '../voting-fairness/models';
import { groupService } from './group.service';
import { GroupModel, MeetupModel } from './models';

function notifyDeleted(meetups: { meetupId: string; participantIds: string[] }[]): void {
  for (const { meetupId, participantIds } of meetups) {
    // Everyone viewing a deleted page must invalidate it; only participants should see a notice.
    publisher.publish(meetupRoom(meetupId), SERVER_EVENTS.meetupDeleted, { meetupId, participantIds });
    publisher.closeRoom(meetupRoom(meetupId));
  }
}

async function deleteMeetup(meetupId: string, userId: string): Promise<void> {
  const meetup = await MeetupModel.findById(meetupId);
  if (!meetup) throw new NotFound('Meetup not found');
  const group = await groupService.requireMembership(String(meetup.groupId), userId);
  if (String(meetup.organizerId) !== userId) throw new Forbidden('Only the meetup organizer can delete this meetup');
  // Remove the parent first so new requests cannot access the meetup during cleanup.
  const result = await MeetupModel.deleteOne({ _id: meetupId, organizerId: userId });
  if (result.deletedCount === 0) throw new NotFound('Meetup not found');
  const participantIds = meetup.participants.map(participant => String(participant.userId));
  notifyDeleted([{ meetupId, participantIds }]);
  await RecommendationModel.deleteMany({ meetupId });
  await VoteModel.deleteMany({ meetupId });
  for (const member of group.members) {
    publisher.publish(userRoom(String(member.userId)), SERVER_EVENTS.groupMeetupDeleted, {
      groupId: String(group._id), meetupId, notifyParticipant: participantIds.includes(String(member.userId)),
    });
  }
  // Settled credit entries and balances are accounting history; deletion never reverses them.
}

async function deleteGroup(groupId: string, userId: string): Promise<void> {
  const group = await groupService.requireMembership(groupId, userId);
  if (String(group.organizerId) !== userId) throw new Forbidden('Only the group organizer can delete this group');
  const result = await GroupModel.deleteOne({ _id: groupId, organizerId: userId });
  if (result.deletedCount === 0) throw new NotFound('Group not found');
  const meetups = await MeetupModel.find({ groupId }).select('_id participants.userId');
  const meetupIds = meetups.map(meetup => String(meetup._id));
  notifyDeleted(meetups.map(meetup => ({
    meetupId: String(meetup._id), participantIds: meetup.participants.map(participant => String(participant.userId)),
  })));
  await MeetupModel.deleteMany({ groupId });
  await RecommendationModel.deleteMany({ meetupId: { $in: meetupIds } });
  await VoteModel.deleteMany({ meetupId: { $in: meetupIds } });
  await FairCreditEntryModel.deleteMany({ groupId });
  await FairCreditModel.deleteMany({ groupId });
  for (const member of group.members) {
    publisher.publish(userRoom(String(member.userId)), SERVER_EVENTS.groupDeleted, { groupId });
  }
}

export const deletionService = { deleteMeetup, deleteGroup };
