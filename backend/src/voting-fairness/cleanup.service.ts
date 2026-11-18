import type { DependentDataOwner } from '../users-groups/dependent-data';
import { fairCreditService } from './fair-credit.service';
import { SettlementModel, TripModel, VoteModel } from './models';
import { NOT_SHARING } from './trip.service';

/**
 * How this component's data follows the meetups, groups and memberships it hangs off. Registered with
 * users-groups in app.ts, which calls these when it deletes a meetup or group or a member departs.
 */

// Votes, trips and settlement snapshots die with their meetup. Settled Fair Credit stays: it is the group's history
async function meetupsDeleted(meetupIds: string[]) {
  const ofMeetups = { meetupId: { $in: meetupIds } };
  await VoteModel.deleteMany(ofMeetups);
  await TripModel.deleteMany(ofMeetups);
  await SettlementModel.deleteMany(ofMeetups);
}

// A member who withdrew their details no longer has a say in those meetups
async function participationWithdrawn(meetupIds: string[], userId: string) {
  await VoteModel.deleteMany({ meetupId: { $in: meetupIds }, userId });
}

// A departed member must stop appearing on the group's live maps
async function memberLeft(meetupIds: string[], userId: string) {
  await TripModel.updateMany({ meetupId: { $in: meetupIds }, userId }, { $set: NOT_SHARING });
}

export const votingFairnessCleanup: DependentDataOwner = {
  meetupsDeleted,
  groupDeleted: fairCreditService.deleteForGroup,
  participationWithdrawn,
  memberLeft,
};
