import { Conflict, Forbidden } from '../common/errors';
import { meetupService } from '../users-groups/meetup.service';
import { groupService } from '../users-groups/group.service';
import { userService } from '../users-groups/user.service';
import { publisher } from '../realtime/publisher';
import { meetupRoom, SERVER_EVENTS } from '../realtime/events';
import { TripModel } from './models';

function changed(meetupId: string) {
  // Invalidation only; REST rechecks membership before returning sensitive coordinates.
  publisher.publish(meetupRoom(meetupId), SERVER_EVENTS.tripUpdated, { meetupId });
}

async function participant(meetupId: string, userId: string) {
  const meetup = await meetupService.getForMember(meetupId, userId);
  const entry = meetup.participants.find(p => String(p.userId) === userId);
  if (!entry) throw new Forbidden('Only members who submitted details can share a trip');
  if (meetup.status !== 'FINALIZED' || !meetup.winner) throw new Conflict('The meetup must have a finalized destination');
  if (meetup.scheduledAt.getTime() > Date.now()) throw new Conflict('You can start your trip when the meetup time arrives');
  return entry;
}

async function start(meetupId: string, userId: string) {
  const entry = await participant(meetupId, userId);
  const existing = await TripModel.findOne({ meetupId, userId });
  if (existing?.status === 'ARRIVED') throw new Conflict('You have already arrived for this meetup');
  try {
    // First insert sets server time once; retries and resuming sharing preserve it.
    await TripModel.updateOne({ meetupId, userId, status: 'ACTIVE' }, {
      $set: { sharing: true },
      $setOnInsert: { startedAt: new Date(), travelMode: entry.travelMode, status: 'ACTIVE' },
    }, { upsert: true });
  } catch (error) {
    if (typeof error === 'object' && error !== null && 'code' in error && error.code === 11000) {
      const same = await TripModel.findOne({ meetupId, userId, status: 'ACTIVE', sharing: true });
      if (!same) throw new Conflict('Another trip is already sharing, or this trip has already ended');
    } else throw error;
  }
  try {
    await participant(meetupId, userId);
  } catch (error) {
    // An in-flight leave/delete must not leave a sharing flag active after the access recheck.
    await TripModel.updateOne({ meetupId, userId }, { $set: { sharing: false, location: null } });
    throw error;
  }
  changed(meetupId);
  return TripModel.findOne({ meetupId, userId }).orFail();
}

async function location(meetupId: string, userId: string, point: { lat: number; lng: number; accuracyM: number }) {
  await participant(meetupId, userId);
  const trip = await TripModel.findOneAndUpdate({ meetupId, userId, status: 'ACTIVE', sharing: true },
    { $set: { location: { ...point, updatedAt: new Date() } } }, { returnDocument: 'after' });
  if (!trip) throw new Conflict('Location sharing is not active');
  changed(meetupId);
  return trip;
}

async function arrive(meetupId: string, userId: string) {
  await participant(meetupId, userId);
  const trip = await TripModel.findOne({ meetupId, userId });
  if (!trip) throw new Conflict('Start your trip before marking arrival');
  if (trip.status === 'ARRIVED') return trip;
  const arrivedAt = new Date();
  await TripModel.updateOne({ _id: trip._id, status: 'ACTIVE' }, { $set: {
    status: 'ARRIVED', sharing: false, location: null, arrivedAt,
    actualSeconds: Math.max(0, Math.floor((arrivedAt.getTime() - trip.startedAt.getTime()) / 1000)),
  } });
  changed(meetupId);
  return TripModel.findById(trip._id).orFail();
}

async function stop(meetupId: string, userId: string) {
  await meetupService.getForMember(meetupId, userId);
  await TripModel.updateOne({ meetupId, userId, status: 'ACTIVE' }, { $set: { sharing: false, location: null } });
  changed(meetupId);
}

async function list(meetupId: string, userId: string) {
  const meetup = await meetupService.getForMember(meetupId, userId);
  const group = await groupService.requireMembership(String(meetup.groupId), userId);
  const memberIds = group.members.map(m => String(m.userId));
  const trips = await TripModel.find({ meetupId, userId: { $in: memberIds } });
  const users = await userService.findByIds(trips.map(t => t.userId));
  const people = new Map(users.map(u => [String(u._id), u]));
  return trips.map(trip => {
    const person = people.get(String(trip.userId));
    return { ...trip.toJSON(), userId: String(trip.userId), name: person ? userService.displayNameOf(person) : 'Member',
      photoUrl: person?.photoUrl ?? null, location: trip.sharing && trip.status === 'ACTIVE' ? trip.location : null };
  });
}

export const tripService = { start, location, arrive, stop, list };
