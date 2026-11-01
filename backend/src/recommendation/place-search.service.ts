import { BadRequest, Conflict, Forbidden } from '../common/errors';
import { tuning } from '../config';
import { searchNearby } from '../integrations/places.client';
import { travelSeconds } from '../integrations/routes.client';
import { meetupService } from '../users-groups/meetup.service';
import { fairCreditService } from '../voting-fairness/fair-credit.service';
import { recommendationService } from './recommendation.service';
import { placeTypesForPreferences } from './preference-types';
import { rankCandidates } from './scoring';

// Checks access and inputs, then finds nearby places matching any participant's preferences
async function findPlaces(meetupId: string, userId: string) {
  const meetup = await meetupService.getForMember(meetupId, userId);
  if (String(meetup.organizerId) !== userId) {
    throw new Forbidden('Only the meetup organizer can search for places');
  }
  if (meetup.status !== 'SETUP') {
    throw new Conflict('Places can only be searched while the meetup is in SETUP');
  }
  if (meetup.participants.length === 0) {
    throw new BadRequest('At least one member must submit their starting location before searching for places.');
  }
  const members = meetup.participants.map(participant => {
    const location = participant.startLocation;
    if (!location || !Number.isFinite(location.lat) || Math.abs(location.lat) > tuning.maxLatitude
      || !Number.isFinite(location.lng) || Math.abs(location.lng) > tuning.maxLongitude) {
      throw new BadRequest('A member has an invalid starting location. Please submit it again.');
    }
    return { userId: String(participant.userId), location, mode: participant.travelMode, preferences: participant.preferences };
  });
  const locations = members.map(member => member.location);
  // Simple geographic average for local meetups; candidates are then ranked by travel-time fairness.
  const center = {
    lat: locations.reduce((sum, location) => sum + location.lat, 0) / locations.length,
    lng: locations.reduce((sum, location) => sum + location.lng, 0) / locations.length,
  };
  const organizer = meetup.participants.find(participant => String(participant.userId) === String(meetup.organizerId));
  if (!organizer) {
    throw new BadRequest('The organizer must submit their starting location and preferences before searching for places.');
  }
  // Every participant's categories, so the scoring has places that can satisfy each of them
  const includedTypes = placeTypesForPreferences(meetup.participants.flatMap(participant => participant.preferences));
  const candidates = await searchNearby(center, includedTypes);
  return { meetup, members, candidates };
}

// Preview only; generate below scores the places, saves the top ones and starts voting.
async function search(meetupId: string, userId: string) {
  const { candidates } = await findPlaces(meetupId, userId);
  return {
    candidates,
    message: candidates.length === 0 ? 'No nearby places found.' : null,
  };
}

function notEnoughPlaces(): BadRequest {
  return new BadRequest(`Not enough nearby places found. At least ${tuning.recommendationCount} are needed to start voting.`);
}

async function generate(meetupId: string, userId: string) {
  const { meetup, members, candidates } = await findPlaces(meetupId, userId);
  if (candidates.length < tuning.recommendationCount) throw notEnoughPlaces();

  const [balances, seconds] = await Promise.all([
    fairCreditService.getBalances(String(meetup.groupId), members.map(member => member.userId)),
    travelSeconds(members, candidates.map(candidate => candidate.location)),
  ]);
  const scoringMembers = members.map(({ userId, mode, preferences }) => ({
    userId,
    mode,
    preferences,
    fairCredit: balances.get(userId) ?? tuning.initialFairCredit,
  }));
  const ranked = rankCandidates(scoringMembers, candidates, seconds);
  if (ranked.length < tuning.recommendationCount) throw notEnoughPlaces();

  // generate rechecks access and atomically claims SETUP after the (slow) Google calls.
  return recommendationService.generate(meetupId, userId, ranked.slice(0, tuning.recommendationCount));
}

export const placeSearchService = { search, generate };
