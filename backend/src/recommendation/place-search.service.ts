import { BadRequest, Conflict, Forbidden } from '../common/errors';
import { tuning } from '../config';
import { searchNearby } from '../integrations/places.client';
import { meetupService } from '../users-groups/meetup.service';
import { recommendationService } from './recommendation.service';
import { placeTypesForPreferences } from './preference-types';

// Preview only; generate below saves the selected candidates and starts voting.
async function search(meetupId: string, userId: string) {
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
  const locations = meetup.participants.map(participant => {
    const location = participant.startLocation;
    if (!location || !Number.isFinite(location.lat) || Math.abs(location.lat) > tuning.maxLatitude
      || !Number.isFinite(location.lng) || Math.abs(location.lng) > tuning.maxLongitude) {
      throw new BadRequest('A member has an invalid starting location. Please submit it again.');
    }
    return location;
  });
  // Simple geographic average for local meetups; travel-time fairness is a later step.
  const center = {
    lat: locations.reduce((sum, location) => sum + location.lat, 0) / locations.length,
    lng: locations.reduce((sum, location) => sum + location.lng, 0) / locations.length,
  };
  const organizer = meetup.participants.find(participant => String(participant.userId) === String(meetup.organizerId));
  if (!organizer) {
    throw new BadRequest('The organizer must submit their starting location and preferences before searching for places.');
  }
  const includedTypes = placeTypesForPreferences(organizer.preferences);
  const candidates = await searchNearby(center, includedTypes);
  return {
    candidates,
    message: candidates.length === 0 ? 'No nearby places found.' : null,
  };
}

async function generate(meetupId: string, userId: string) {
  const { candidates } = await search(meetupId, userId);
  if (candidates.length < tuning.recommendationCount) {
    throw new BadRequest(`Not enough nearby places found. At least ${tuning.recommendationCount} are needed to start voting.`);
  }
  // Temporary selection; generate rechecks access and atomically claims SETUP after the search.
  return recommendationService.generate(meetupId, userId, candidates.slice(0, tuning.recommendationCount));
}

export const placeSearchService = { search, generate };
