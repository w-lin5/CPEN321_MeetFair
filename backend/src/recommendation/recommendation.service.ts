import { BadRequest, Conflict, Forbidden, NotFound } from '../common/errors';
import type { Place } from '../common/types';
import { tuning } from '../config';
import { isDuplicateKeyError } from '../db';
import { placesClient } from '../integrations/places.client';
import { routesClient } from '../integrations/routes.client';
import { SERVER_EVENTS, meetupRoom } from '../realtime/events';
import { publisher } from '../realtime/publisher';
import { type Traveller, meetupService } from '../users-groups/meetup.service';
import { fairCreditService } from '../voting-fairness/fair-credit.service';
import { RecommendationModel } from './models';
import { placeTypesForPreferences } from './preference-types';
import { type ScoredCandidate, rankCandidates } from './scoring';
import { searchCenter } from './search-area';

type MeetupDoc = Awaited<ReturnType<typeof meetupService.getForMember>>;

const NOT_IN_SETUP = 'Recommendations can only be generated while the meetup is in SETUP';

// A meetup of the caller's group that the caller organizes (404 / 403 otherwise)
async function loadAsOrganizer(meetupId: string, userId: string): Promise<MeetupDoc> {
  const meetup = await meetupService.getForMember(meetupId, userId);
  if (String(meetup.organizerId) !== userId) {
    throw new Forbidden('Only the meetup organizer can generate recommendations');
  }
  return meetup;
}

function notEnoughPlaces(): BadRequest {
  return new BadRequest(
    `Not enough nearby places found. At least ${tuning.recommendationCount} are needed to start voting.`,
  );
}

// The meetup's travellers; the organizer must be one of them before places are searched
function requireTravellers(meetup: MeetupDoc): Traveller[] {
  if (meetup.participants.length === 0) {
    throw new BadRequest('At least one member must submit their starting location before searching for places.');
  }
  const travellers = meetupService.travellers(meetup);
  if (!travellers.some((traveller) => traveller.userId === String(meetup.organizerId))) {
    throw new BadRequest(
      'The organizer must submit their starting location and preferences before searching for places.',
    );
  }
  return travellers;
}

// Nearby places matching any traveller's preferences, ranked by fairness (best first)
async function rankNearbyPlaces(groupId: string, travellers: Traveller[]): Promise<ScoredCandidate[]> {
  // Every traveller's categories, so the ranking has places that can satisfy each of them
  const placeTypes = placeTypesForPreferences(travellers.flatMap((traveller) => traveller.preferences));
  const center = searchCenter(travellers.map((traveller) => traveller.location));
  const places = await placesClient.searchNearby(center, placeTypes);
  if (places.length < tuning.recommendationCount) throw notEnoughPlaces();

  // Independent lookups; travel times are batched into one Routes request per travel mode
  const [balances, seconds] = await Promise.all([
    fairCreditService.getBalances(groupId, travellers.map((traveller) => traveller.userId)),
    routesClient.travelSeconds(travellers, places.map((place) => place.location)),
  ]);
  const members = travellers.map((traveller) => ({
    ...traveller,
    fairCredit: balances.get(traveller.userId) ?? tuning.initialFairCredit,
  }));
  return rankCandidates(members, places, seconds);
}

/**
 * Stores the candidates (best first) and atomically moves the meetup from SETUP to VOTING.
 * Access is checked here as well, because generate() reaches this only after slow Google calls.
 */
async function save(meetupId: string, userId: string, candidates: (Place | ScoredCandidate)[]) {
  await loadAsOrganizer(meetupId, userId);

  // Claim the status transition first so two concurrent requests cannot both succeed
  if (!(await meetupService.transitionStatus(meetupId, 'SETUP', 'VOTING'))) throw new Conflict(NOT_IN_SETUP);

  let recommendation;
  try {
    recommendation = await RecommendationModel.create({ meetupId, candidates });
  } catch (err) {
    await meetupService.transitionStatus(meetupId, 'VOTING', 'SETUP');
    if (isDuplicateKeyError(err)) throw new Conflict('This meetup already has recommendations');
    throw err;
  }

  publisher.publish(meetupRoom(meetupId), SERVER_EVENTS.recommendationsReady, { meetupId });
  publisher.publish(meetupRoom(meetupId), SERVER_EVENTS.meetupStatus, { meetupId, status: 'VOTING' });
  return recommendation;
}

/** Organizer only: finds nearby places, ranks them by fairness, stores the best ones and opens voting. */
async function generate(meetupId: string, userId: string) {
  const meetup = await loadAsOrganizer(meetupId, userId);
  if (meetup.status !== 'SETUP') throw new Conflict(NOT_IN_SETUP);

  const ranked = await rankNearbyPlaces(String(meetup.groupId), requireTravellers(meetup));
  // Places that some member cannot reach are not ranked, so fewer may be left than were found
  if (ranked.length < tuning.recommendationCount) throw notEnoughPlaces();
  return save(meetupId, userId, ranked.slice(0, tuning.recommendationCount));
}

// Load the meetup's recommendation without an access check; 404 until it has been generated
async function getByMeetupId(meetupId: string) {
  const recommendation = await RecommendationModel.findOne({ meetupId });
  if (!recommendation) throw new NotFound('No recommendations for this meetup yet');
  return recommendation;
}

// The meetup's recommendation for any member of its group
async function getForMember(meetupId: string, userId: string) {
  await meetupService.getForMember(meetupId, userId);
  return getByMeetupId(meetupId);
}

// Each member's estimated trip to one candidate; empty if the meetup has no such candidate
async function travelEstimates(meetupId: string, placeId: string) {
  const recommendation = await RecommendationModel.findOne({ meetupId });
  const candidate = recommendation?.candidates.find((c) => c.placeId === placeId);
  return candidate?.travel ?? [];
}

// Registered as dependent data of users-groups (app.ts): recommendations die with their meetup
async function deleteForMeetups(meetupIds: string[]) {
  await RecommendationModel.deleteMany({ meetupId: { $in: meetupIds } });
}

export const recommendationService = { generate, save, getByMeetupId, getForMember, travelEstimates, deleteForMeetups };
