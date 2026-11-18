import { Router } from 'express';

import { asyncHandler } from '../common/middleware/error-handler';
import { getUserId } from '../common/middleware/require-auth';
import { parseInput } from '../common/middleware/validate';
import { meetupParams } from '../users-groups/schemas';
import { recommendationService } from './recommendation.service';

export const recommendationRouter = Router();

// Organizer only: generates the meetup's recommendations from Google Places and Routes and opens voting
recommendationRouter.post('/meetups/:meetupId/recommendations', asyncHandler(async (req, res) => {
  const { meetupId } = parseInput(meetupParams, req.params);
  res.status(201).json(await recommendationService.generate(meetupId, getUserId(req)));
}));

recommendationRouter.get('/meetups/:meetupId/recommendations', asyncHandler(async (req, res) => {
  const { meetupId } = parseInput(meetupParams, req.params);
  res.json(await recommendationService.getForMember(meetupId, getUserId(req)));
}));
