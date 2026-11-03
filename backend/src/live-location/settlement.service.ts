import { BadRequest, Conflict, Forbidden } from '../common/errors';
import { tuning } from '../config';
import { travelSeconds } from '../integrations/routes.client';
import { RecommendationModel } from '../recommendation/models';
import { meetupRoom, SERVER_EVENTS } from '../realtime/events';
import { publisher } from '../realtime/publisher';
import { meetupService } from '../users-groups/meetup.service';
import { MeetupModel } from '../users-groups/models';
import { fairCreditService, type SettlementTrip } from '../voting-fairness/fair-credit.service';
import { SettlementModel } from '../voting-fairness/models';
import { TripModel } from './models';
import { withMeetupLock } from './meetup-lock';

export function settleMeetup(meetupId: string, userId: string) {
  return withMeetupLock(meetupId, () => settle(meetupId, userId));
}

async function settle(meetupId: string, userId: string) {
  const meetup = await meetupService.getForMember(meetupId, userId);
  if (String(meetup.organizerId) !== userId) throw new Forbidden('Only the meetup organizer can settle Fair Credit');
  // A repeated successful request is harmless (including a lost HTTP response).
  if (meetup.status === 'SETTLED') return meetup;
  if (meetup.status !== 'FINALIZED' || !meetup.winner) throw new Conflict('The meetup must have a finalized destination');
  if (meetup.scheduledAt.getTime() > Date.now()) throw new Conflict('Fair Credit can be settled after the meetup time arrives');
  if (meetup.participants.length === 0) throw new BadRequest('There are no participants to settle');

  let snapshot = await SettlementModel.findOne({ meetupId });
  if (!snapshot) {
    const trips = await TripModel.find({ meetupId });
    if (trips.some(trip => trip.status === 'ACTIVE')) {
      throw new Conflict('Wait for all started trips to arrive before settling Fair Credit');
    }
    const recommendation = await RecommendationModel.findOne({ meetupId });
    const winner = recommendation?.candidates.find(candidate => candidate.placeId === meetup.winner?.placeId);
    const inputs: SettlementTrip[] = [];
    const missing = [];
    for (const member of meetup.participants) {
      const id = String(member.userId);
      const tracked = trips.find(trip => String(trip.userId) === id && trip.status === 'ARRIVED');
      if (tracked?.actualSeconds != null) {
        inputs.push({ userId: id, mode: member.travelMode, actualSeconds: tracked.actualSeconds, source: 'TRACKED' });
      } else {
        const estimate = winner?.travel.find(trip => String(trip.userId) === id && trip.mode === member.travelMode);
        if (estimate) inputs.push({ userId: id, mode: member.travelMode, actualSeconds: estimate.seconds, source: 'ESTIMATED' });
        else missing.push(member);
      }
    }
    // Older recommendations may not have stored travel estimates.
    if (missing.length) {
      const winnerLocation = meetup.winner.location;
      if (!winnerLocation) throw new BadRequest('The destination has no valid location');
      const estimates = await travelSeconds(missing.map(member => ({ location: member.startLocation!, mode: member.travelMode })), [winnerLocation]);
      missing.forEach((member, index) => {
        const seconds = estimates[index]?.[0];
        if (seconds == null) throw new Conflict('A participant has no usable travel estimate; ask them to complete a tracked trip');
        inputs.push({ userId: String(member.userId), mode: member.travelMode, actualSeconds: seconds, source: 'ESTIMATED' });
      });
    }
    if (inputs.some(trip => !Number.isFinite(trip.actualSeconds) || trip.actualSeconds < 0 || trip.actualSeconds > tuning.maxTripSeconds)) {
      throw new BadRequest('A commute duration is invalid or exceeds one day');
    }
    try {
      snapshot = await SettlementModel.findOneAndUpdate({ meetupId }, {
        $setOnInsert: { meetupId, groupId: meetup.groupId, trips: inputs },
      }, { upsert: true, returnDocument: 'after' });
    } catch (error) {
      if (!(typeof error === 'object' && error !== null && 'code' in error && error.code === 11000)) throw error;
      snapshot = await SettlementModel.findOne({ meetupId });
    }
  }
  if (!snapshot) throw new Conflict('Please retry settlement');
  // A trip may have started while estimates were being collected. Do not settle it mid-trip.
  if (await TripModel.exists({ meetupId, status: 'ACTIVE' })) {
    throw new Conflict('Wait for all started trips to arrive before settling Fair Credit');
  }
  await fairCreditService.settleMeetup(String(snapshot.groupId), meetupId, snapshot.trips, true);
  const settled = await MeetupModel.findOneAndUpdate({ _id: meetupId, status: 'FINALIZED' }, {
    $set: { status: 'SETTLED' },
  }, { returnDocument: 'after' });
  const result = settled ?? await meetupService.getForMember(meetupId, userId);
  publisher.publish(meetupRoom(meetupId), SERVER_EVENTS.meetupStatus, { meetupId, status: result.status });
  return result;
}
