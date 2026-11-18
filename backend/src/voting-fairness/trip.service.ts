import { Conflict, Forbidden } from '../common/errors';
import { tuning } from '../config';
import { isDuplicateKeyError } from '../db';
import { SERVER_EVENTS, meetupRoom } from '../realtime/events';
import { publisher } from '../realtime/publisher';
import { groupService } from '../users-groups/group.service';
import { meetupService } from '../users-groups/meetup.service';
import { userService } from '../users-groups/user.service';
import { withMeetupLock } from './meetup-lock';
import { SettlementModel, TripModel } from './models';
import type { TripPosition } from './schemas';

/**
 * Live commute tracking: a participant's trip to a finalized meetup, from "I'm leaving" to "I arrived".
 * The server times the trip; the device may share its location along the way. Only the latest position
 * is stored, and it is forgotten as soon as sharing stops.
 */

// What stopping location sharing writes: the flag goes off and the last position is forgotten
export const NOT_SHARING = { sharing: false, location: null };

// Carries no coordinates: clients reload trips over REST, which rechecks group membership
function publishTripsUpdated(meetupId: string): void {
  publisher.publish(meetupRoom(meetupId), SERVER_EVENTS.tripUpdated, { meetupId });
}

// The caller's participation entry, if they may travel to this meetup now: it is finalized, its
// scheduled time has come and Fair Credit is not being settled yet (403 / 409 otherwise)
async function requireTraveller(meetupId: string, userId: string) {
  const meetup = await meetupService.getForMember(meetupId, userId);
  const participant = meetup.participants.find((p) => String(p.userId) === userId);
  if (!participant) throw new Forbidden('Only members who submitted details can share a trip');
  if (meetup.status !== 'FINALIZED' || !meetup.winner) {
    throw new Conflict('The meetup must have a finalized destination');
  }
  if (meetup.scheduledAt.getTime() > Date.now()) {
    throw new Conflict('You can start your trip when the meetup time arrives');
  }
  if (await SettlementModel.exists({ meetupId })) throw new Conflict('Fair Credit settlement has started');
  return participant;
}

// Starts the caller's trip, or resumes sharing on their active one; the start time is only ever set once
async function startOrResume(meetupId: string, userId: string) {
  const participant = await requireTraveller(meetupId, userId);
  if (await TripModel.exists({ meetupId, userId, status: 'ARRIVED' })) {
    throw new Conflict('You have already arrived for this meetup');
  }

  try {
    await TripModel.updateOne(
      { meetupId, userId, status: 'ACTIVE' },
      {
        $set: { sharing: true },
        $setOnInsert: { startedAt: new Date(), travelMode: participant.travelMode, status: 'ACTIVE' },
      },
      { upsert: true },
    );
  } catch (err) {
    // The unique index on sharing trips: the member already shares their location for another meetup
    if (isDuplicateKeyError(err)) {
      throw new Conflict('Another trip is already sharing, or this trip has already ended');
    }
    throw err;
  }

  try {
    await requireTraveller(meetupId, userId);
  } catch (err) {
    // The member left (or the meetup was deleted) while the trip was saved; sharing must not outlive their access
    await TripModel.updateOne({ meetupId, userId }, { $set: NOT_SHARING });
    throw err;
  }

  publishTripsUpdated(meetupId);
  return TripModel.findOne({ meetupId, userId }).orFail();
}

// Serialized with settlement, so a trip cannot start while Fair Credit is being settled
async function start(meetupId: string, userId: string) {
  return withMeetupLock(meetupId, () => startOrResume(meetupId, userId));
}

// Saves the caller's latest position while they share an active trip
async function updateLocation(meetupId: string, userId: string, position: TripPosition) {
  await requireTraveller(meetupId, userId);
  const trip = await TripModel.findOneAndUpdate(
    { meetupId, userId, status: 'ACTIVE', sharing: true },
    { $set: { location: { ...position, updatedAt: new Date() } } },
    { returnDocument: 'after' },
  );
  if (!trip) throw new Conflict('Location sharing is not active');

  publishTripsUpdated(meetupId);
  return trip;
}

// Ends the caller's trip and records how long it took; repeating it changes nothing
async function arrive(meetupId: string, userId: string) {
  await requireTraveller(meetupId, userId);
  const trip = await TripModel.findOne({ meetupId, userId });
  if (!trip) throw new Conflict('Start your trip before marking arrival');
  if (trip.status === 'ARRIVED') return trip;

  const arrivedAt = new Date();
  const elapsedMs = arrivedAt.getTime() - trip.startedAt.getTime();
  await TripModel.updateOne(
    { _id: trip._id, status: 'ACTIVE' },
    {
      $set: {
        ...NOT_SHARING,
        status: 'ARRIVED',
        arrivedAt,
        actualSeconds: Math.max(0, Math.floor(elapsedMs / tuning.msPerSecond)),
      },
    },
  );

  publishTripsUpdated(meetupId);
  return TripModel.findById(trip._id).orFail();
}

// Pauses the caller's location sharing; the commute timer keeps running until they arrive
async function stopSharing(meetupId: string, userId: string) {
  await meetupService.getForMember(meetupId, userId);
  await TripModel.updateOne({ meetupId, userId, status: 'ACTIVE' }, { $set: NOT_SHARING });
  publishTripsUpdated(meetupId);
}

// The meetup's trips with each traveller's name and photo; a position only for members sharing right now
async function list(meetupId: string, userId: string) {
  const meetup = await meetupService.getForMember(meetupId, userId);
  const group = await groupService.requireMembership(String(meetup.groupId), userId);
  // Trips of people who have since left the group are not shown
  const trips = await TripModel.find({ meetupId, userId: { $in: group.members.map((m) => m.userId) } });
  const users = await userService.findByIds(trips.map((trip) => trip.userId));
  const usersById = new Map(users.map((user) => [String(user._id), user]));

  return trips.map((trip) => {
    const user = usersById.get(String(trip.userId));
    return {
      ...trip.toJSON(),
      userId: String(trip.userId),
      name: user ? userService.displayNameOf(user) : 'Member',
      photoUrl: user?.photoUrl ?? null,
      location: trip.sharing && trip.status === 'ACTIVE' ? trip.location : null,
    };
  });
}

export const tripService = { start, updateLocation, arrive, stopSharing, list };
