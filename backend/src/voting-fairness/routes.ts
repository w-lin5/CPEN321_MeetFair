import { Router } from 'express';

import { asyncHandler } from '../common/middleware/error-handler';
import { getUserId } from '../common/middleware/require-auth';
import { parseInput } from '../common/middleware/validate';
import { groupParams, meetupParams, updateProfileBody } from '../users-groups/schemas';
import { fairCreditService } from './fair-credit.service';
import { calendarEventBody, finalizeBody, voteBody } from './schemas';
import { votingService } from './voting.service';

export const votingFairnessRouter = Router();

// ---- Voting ----

// Create or change the caller's vote until it is locked
votingFairnessRouter.put('/meetups/:meetupId/vote', asyncHandler(async (req, res) => {
  const { meetupId } = parseInput(meetupParams, req.params);
  const { placeId } = parseInput(voteBody, req.body);
  res.json(await votingService.castVote(meetupId, getUserId(req), placeId));
}));

// Lock the caller's vote; its weight comes from their Fair Credit, never from the client
votingFairnessRouter.post('/meetups/:meetupId/vote/lock', asyncHandler(async (req, res) => {
  const { meetupId } = parseInput(meetupParams, req.params);
  res.json(await votingService.lockVote(meetupId, getUserId(req)));
}));

votingFairnessRouter.get('/meetups/:meetupId/votes', asyncHandler(async (req, res) => {
  const { meetupId } = parseInput(meetupParams, req.params);
  res.json(await votingService.getVotes(meetupId, getUserId(req)));
}));

// Organizer picks the winner by locked weight (or names one to break a tie) and the meetup moves to FINALIZED
votingFairnessRouter.post('/meetups/:meetupId/finalize', asyncHandler(async (req, res) => {
  const { meetupId } = parseInput(meetupParams, req.params);
  const { placeId } = parseInput(finalizeBody, req.body);
  res.json(await votingService.finalize(meetupId, getUserId(req), placeId));
}));

// ---- Google Calendar ----

// Adds the finalized meetup to the caller's own Google Calendar; the access token is used once and never stored
votingFairnessRouter.post('/meetups/:meetupId/calendar-event', asyncHandler(async (req, res) => {
  const { meetupId } = parseInput(meetupParams, req.params);
  const { accessToken } = parseInput(calendarEventBody, req.body);
  res.status(201).json(await votingService.addToCalendar(meetupId, getUserId(req), accessToken));
}));

// ---- Profile and fairness ----

// Account info, groups and per-group Fair Credit in one response
votingFairnessRouter.get('/users/me/profile', asyncHandler(async (req, res) => {
  res.json(await fairCreditService.getProfile(getUserId(req)));
}));

// Replaces the editable profile fields (currently only displayName)
votingFairnessRouter.put('/users/me/profile', asyncHandler(async (req, res) => {
  const input = parseInput(updateProfileBody, req.body);
  res.json(await fairCreditService.updateProfile(getUserId(req), input));
}));

// Every member's Fair Credit in the group plus the group's ledger
votingFairnessRouter.get('/groups/:groupId/fairness', asyncHandler(async (req, res) => {
  const { groupId } = parseInput(groupParams, req.params);
  res.json(await fairCreditService.getGroupFairness(groupId, getUserId(req)));
}));
