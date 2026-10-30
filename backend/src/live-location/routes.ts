import { Router } from 'express';

import { asyncHandler } from '../common/middleware/error-handler';
import { getUserId } from '../common/middleware/require-auth';
import { fields, numberInRange, parseInput } from '../common/middleware/validate';
import { meetupParams } from '../users-groups/schemas';
import { tripService } from './trip.service';

export const tripRouter = Router();
tripRouter.get('/meetups/:meetupId/trips', asyncHandler(async (req, res) => {
  const { meetupId } = parseInput(meetupParams, req.params);
  res.json(await tripService.list(meetupId, getUserId(req)));
}));
tripRouter.post('/meetups/:meetupId/trip/start', asyncHandler(async (req, res) => {
  const { meetupId } = parseInput(meetupParams, req.params);
  res.json(await tripService.start(meetupId, getUserId(req)));
}));
tripRouter.put('/meetups/:meetupId/trip/location', asyncHandler(async (req, res) => {
  const { meetupId } = parseInput(meetupParams, req.params);
  const body = fields(req.body);
  res.json(await tripService.location(meetupId, getUserId(req), {
    lat: numberInRange(body, 'lat', -90, 90), lng: numberInRange(body, 'lng', -180, 180),
    accuracyM: numberInRange(body, 'accuracyM', 0, 100000),
  }));
}));
tripRouter.post('/meetups/:meetupId/trip/arrive', asyncHandler(async (req, res) => {
  const { meetupId } = parseInput(meetupParams, req.params);
  res.json(await tripService.arrive(meetupId, getUserId(req)));
}));
tripRouter.post('/meetups/:meetupId/trip/stop', asyncHandler(async (req, res) => {
  const { meetupId } = parseInput(meetupParams, req.params);
  await tripService.stop(meetupId, getUserId(req));
  res.status(204).end();
}));
