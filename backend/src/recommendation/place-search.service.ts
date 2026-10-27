import { BadRequest, Conflict, Forbidden } from '../common/errors';
import { tuning } from '../config';
import { searchNearby } from '../integrations/places.client';
import { meetupService } from '../users-groups/meetup.service';
import { recommendationService } from './recommendation.service';

// Preview only: selecting and saving three candidates uses the existing recommendations endpoint.
async function search(meetupId: string, userId: string) {
  const meetup = await meetupService.getForMember(meetupId, userId);
  if (String(meetup.organizerId) !== userId) {
    throw new Forbidden('Only the meetup organizer can search for places');
  }
  if (meetup.status !== 'SETUP') {
    throw new Conflict('Places can only be searched while the meetup is in SETUP');
  }
  const candidates = await searchNearby();
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
