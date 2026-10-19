import { Conflict, Forbidden, NotFound } from '../common/errors';
import { tuning } from '../config';
import { SERVER_EVENTS, meetupRoom } from '../realtime/events';
import { publisher } from '../realtime/publisher';
import { meetupService } from '../users-groups/meetup.service';
import { RecommendationModel } from './models';
import type { CandidateInput } from './schemas';

function isDuplicateKey(err: unknown): boolean {
  return typeof err === 'object' && err !== null && 'code' in err && err.code === tuning.mongoDuplicateKeyCode;
}

/**
 * Organizer stores the meetup's top candidates and moves it from SETUP to VOTING.
 * TEMPORARY: the candidates come from the request until feature 3 builds them from Places + Routes
 * and scores them; the endpoint, status transition and events already follow the final design.
 */
async function generate(meetupId: string, userId: string, candidates: CandidateInput[]) {
  const meetup = await meetupService.getForMember(meetupId, userId);
  if (String(meetup.organizerId) !== userId) {
    throw new Forbidden('Only the meetup organizer can generate recommendations');
  }

  // Claim the status transition first so two concurrent requests cannot both succeed
  if (!(await meetupService.transitionStatus(meetupId, 'SETUP', 'VOTING'))) {
    throw new Conflict('Recommendations can only be generated while the meetup is in SETUP');
  }

  let recommendation;
  try {
    recommendation = await RecommendationModel.create({ meetupId, candidates });
  } catch (err) {
    await meetupService.transitionStatus(meetupId, 'VOTING', 'SETUP');
    if (isDuplicateKey(err)) throw new Conflict('This meetup already has recommendations');
    throw err;
  }

  publisher.publish(meetupRoom(meetupId), SERVER_EVENTS.recommendationsReady, { meetupId });
  publisher.publish(meetupRoom(meetupId), SERVER_EVENTS.meetupStatus, { meetupId, status: 'VOTING' });

  return recommendation;
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

export const recommendationService = { generate, getByMeetupId, getForMember };
