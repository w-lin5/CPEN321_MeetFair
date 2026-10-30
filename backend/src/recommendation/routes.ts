import { Router } from 'express';

import { asyncHandler } from '../common/middleware/error-handler';
import { getUserId } from '../common/middleware/require-auth';
import { parseInput } from '../common/middleware/validate';
import { meetupParams } from '../users-groups/schemas';
import { recommendationService } from './recommendation.service';
import { placeSearchService } from './place-search.service';

export const recommendationRouter = Router();

recommendationRouter.post('/meetups/:meetupId/recommendations/from-places', asyncHandler(async (req, res) => {
  const { meetupId } = parseInput(meetupParams, req.params);
  res.status(201).json(await placeSearchService.generate(meetupId, getUserId(req)));
}));

// Cafe search around the members' starting locations; previews places without changing meetup state.
recommendationRouter.post('/meetups/:meetupId/places/search', asyncHandler(async (req, res) => {
  const { meetupId } = parseInput(meetupParams, req.params);
  res.json(await placeSearchService.search(meetupId, getUserId(req)));
}));

recommendationRouter.get('/meetups/:meetupId/recommendations', asyncHandler(async (req, res) => {
  const { meetupId } = parseInput(meetupParams, req.params);
  res.json(await recommendationService.getForMember(meetupId, getUserId(req)));
}));
