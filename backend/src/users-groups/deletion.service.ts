import { Forbidden, NotFound } from '../common/errors';
import { SERVER_EVENTS, meetupRoom, userRoom } from '../realtime/events';
import { publisher } from '../realtime/publisher';
import { dependentData } from './dependent-data';
import { groupService } from './group.service';
import { GroupModel, MeetupModel } from './models';

interface DeletedMeetup {
  meetupId: string;
  participantIds: string[];
}

function participantIdsOf(meetup: { participants: { userId: unknown }[] }): string[] {
  return meetup.participants.map((participant) => String(participant.userId));
}

// Everyone viewing a deleted meetup must drop the page; the app shows a notice only to its participants
function closeMeetupRooms(meetups: DeletedMeetup[]): void {
  for (const { meetupId, participantIds } of meetups) {
    publisher.publish(meetupRoom(meetupId), SERVER_EVENTS.meetupDeleted, { meetupId, participantIds });
    publisher.closeRoom(meetupRoom(meetupId));
  }
}

// The meetup organizer deletes a meetup in any status, with its recommendations, votes and trips.
// Settled Fair Credit is accounting history, so deleting a meetup never reverses it
async function deleteMeetup(meetupId: string, userId: string): Promise<void> {
  const meetup = await MeetupModel.findById(meetupId);
  if (!meetup) throw new NotFound('Meetup not found');
  const group = await groupService.requireMembership(String(meetup.groupId), userId);
  if (String(meetup.organizerId) !== userId) throw new Forbidden('Only the meetup organizer can delete this meetup');

  // The meetup goes first: every other request finds it through this document, so nothing can use it during cleanup
  const deleted = await MeetupModel.deleteOne({ _id: meetupId, organizerId: userId });
  if (deleted.deletedCount === 0) throw new NotFound('Meetup not found');

  const participantIds = participantIdsOf(meetup);
  closeMeetupRooms([{ meetupId, participantIds }]);
  await dependentData.meetupsDeleted([meetupId]);

  // Group pages refresh their meetup list for every member
  for (const member of group.members) {
    const memberId = String(member.userId);
    publisher.publish(userRoom(memberId), SERVER_EVENTS.groupMeetupDeleted, {
      groupId: String(group._id),
      meetupId,
      notifyParticipant: participantIds.includes(memberId),
    });
  }
}

// The group organizer deletes the group with all its meetups and everything that belongs to them
async function deleteGroup(groupId: string, userId: string): Promise<void> {
  const group = await groupService.requireMembership(groupId, userId);
  if (String(group.organizerId) !== userId) throw new Forbidden('Only the group organizer can delete this group');

  // The group goes first: membership checks then reject every other request for its data
  const deleted = await GroupModel.deleteOne({ _id: groupId, organizerId: userId });
  if (deleted.deletedCount === 0) throw new NotFound('Group not found');

  const meetups = await MeetupModel.find({ groupId }).select('_id participants.userId');
  closeMeetupRooms(meetups.map((meetup) => ({ meetupId: String(meetup._id), participantIds: participantIdsOf(meetup) })));
  await MeetupModel.deleteMany({ groupId });
  await dependentData.meetupsDeleted(meetups.map((meetup) => String(meetup._id)));
  await dependentData.groupDeleted(groupId);

  for (const member of group.members) {
    publisher.publish(userRoom(String(member.userId)), SERVER_EVENTS.groupDeleted, { groupId });
  }
}

export const deletionService = { deleteMeetup, deleteGroup };
