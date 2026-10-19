import { Router } from 'express';

import { getUserId } from '../common/middleware/require-auth';
import { parseInput } from '../common/middleware/validate';
import { meetupParams } from '../users-groups/schemas';
import { recommendationService } from './recommendation.service';
import { generateRecommendationsBody } from './schemas';

export const recommendationRouter = Router();

// Organizer only; moves the meetup to VOTING. The body is temporary (see generateRecommendationsBody)
recommendationRouter.post('/meetups/:meetupId/recommendations', async (req, res) => {
  const { meetupId } = parseInput(meetupParams, req.params);
  const { candidates } = parseInput(generateRecommendationsBody, req.body);
  res.status(201).json(await recommendationService.generate(meetupId, getUserId(req), candidates));
});

recommendationRouter.get('/meetups/:meetupId/recommendations', async (req, res) => {
  const { meetupId } = parseInput(meetupParams, req.params);
  res.json(await recommendationService.getForMember(meetupId, getUserId(req)));
});
