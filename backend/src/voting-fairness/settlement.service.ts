import { BadRequest, Conflict, Forbidden } from '../common/errors';
import type { LatLng } from '../common/types';
import { routesClient } from '../integrations/routes.client';
import { SERVER_EVENTS, meetupRoom } from '../realtime/events';
import { publisher } from '../realtime/publisher';
import { recommendationService } from '../recommendation/recommendation.service';
import { type Traveller, meetupService } from '../users-groups/meetup.service';
import { type SettledTrip, isValidTripSeconds } from './fair-credit';
import { fairCreditService } from './fair-credit.service';
import { withMeetupLock } from './meetup-lock';
import { SettlementModel, TripModel } from './models';

/**
 * Settling a finalized meetup turns every participant's commute into Fair Credit. The commute durations
 * are frozen in a settlement snapshot first, so a settlement that is interrupted (or requested twice)
 * continues with exactly the same numbers and never counts a meetup twice.
 */

type MeetupDoc = Awaited<ReturnType<typeof meetupService.getForMember>>;

// The destination of a meetup whose Fair Credit can be settled now (409 / 400 otherwise)
function requireDestination(meetup: MeetupDoc): { placeId: string; location: LatLng } {
  const { winner } = meetup;
  if (meetup.status !== 'FINALIZED' || !winner?.location) {
    throw new Conflict('The meetup must have a finalized destination');
  }
  if (meetup.scheduledAt.getTime() > Date.now()) {
    throw new Conflict('Fair Credit can be settled after the meetup time arrives');
  }
  if (meetup.participants.length === 0) throw new BadRequest('There are no participants to settle');
  return { placeId: winner.placeId, location: { lat: winner.location.lat, lng: winner.location.lng } };
}

// Routes estimates for travellers whose recommendation stored none (recommendations from before estimates were kept)
async function estimateCommutes(travellers: Traveller[], destination: LatLng): Promise<SettledTrip[]> {
  if (travellers.length === 0) return [];
  const estimates = await routesClient.travelSeconds(travellers, [destination]);
  return travellers.map(({ userId, mode }, index) => {
    const seconds = estimates.at(index)?.at(0);
    if (typeof seconds !== 'number' || !isValidTripSeconds(seconds)) {
      throw new Conflict('A participant has no usable travel estimate; ask them to complete a tracked trip');
    }
    return { userId, mode, actualSeconds: seconds, source: 'ESTIMATED' };
  });
}

/**
 * Every participant's commute for the settlement: the tracked time if they completed a trip, otherwise
 * the estimate stored with the winning candidate, otherwise a fresh Routes estimate.
 */
async function collectCommutes(meetup: MeetupDoc, destination: { placeId: string; location: LatLng }) {
  const meetupId = String(meetup._id);
  const [arrivals, estimates] = await Promise.all([
    TripModel.find({ meetupId, status: 'ARRIVED' }),
    recommendationService.travelEstimates(meetupId, destination.placeId),
  ]);
  const trackedSeconds = new Map(arrivals.map((trip) => [String(trip.userId), trip.actualSeconds]));
  const estimateByUser = new Map(estimates.map((estimate) => [String(estimate.userId), estimate]));

  const commutes: SettledTrip[] = [];
  const withoutEstimate: Traveller[] = [];
  for (const traveller of meetupService.travellers(meetup)) {
    const { userId, mode } = traveller;
    const tracked = trackedSeconds.get(userId);
    const estimate = estimateByUser.get(userId);
    // A tracked time beyond the limit (e.g. "I arrived" tapped a day late) is not a usable measurement
    if (typeof tracked === 'number' && isValidTripSeconds(tracked)) {
      commutes.push({ userId, mode, actualSeconds: tracked, source: 'TRACKED' });
    } else if (estimate?.mode === mode && isValidTripSeconds(estimate.seconds)) {
      commutes.push({ userId, mode, actualSeconds: estimate.seconds, source: 'ESTIMATED' });
    } else {
      withoutEstimate.push(traveller);
    }
  }
  return [...commutes, ...(await estimateCommutes(withoutEstimate, destination.location))];
}

// Freezes the settlement's inputs; from here on no new trip can start for the meetup (trip.service)
async function createSnapshot(meetup: MeetupDoc) {
  const meetupId = String(meetup._id);
  const destination = requireDestination(meetup);
  if (await TripModel.exists({ meetupId, status: 'ACTIVE' })) {
    throw new Conflict('Wait for all started trips to arrive before settling Fair Credit');
  }
  const trips = await collectCommutes(meetup, destination);
  return SettlementModel.create({ meetupId, groupId: meetup.groupId, trips });
}

async function settleLocked(meetupId: string, userId: string) {
  const meetup = await meetupService.getForMember(meetupId, userId);
  if (String(meetup.organizerId) !== userId) {
    throw new Forbidden('Only the meetup organizer can settle Fair Credit');
  }
  // Repeating a successful request is harmless (the first response may have been lost)
  if (meetup.status === 'SETTLED') return meetup;

  const snapshot = (await SettlementModel.findOne({ meetupId })) ?? (await createSnapshot(meetup));
  await fairCreditService.settleMeetup(String(snapshot.groupId), meetupId, snapshot.trips);
  await meetupService.transitionStatus(meetupId, 'FINALIZED', 'SETTLED');

  const settled = await meetupService.getById(meetupId);
  publisher.publish(meetupRoom(meetupId), SERVER_EVENTS.meetupStatus, { meetupId, status: settled.status });
  return settled;
}

// Organizer only: settles the meetup's Fair Credit and moves it to SETTLED
async function settle(meetupId: string, userId: string) {
  // Serialized with trip starts, so no trip can begin between the active-trip check and the snapshot
  return withMeetupLock(meetupId, () => settleLocked(meetupId, userId));
}

export const settlementService = { settle };
