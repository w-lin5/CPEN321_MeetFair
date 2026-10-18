import { Router } from 'express';

import { getUserId } from '../common/middleware/require-auth';
import { parseInput } from '../common/middleware/validate';
import { groupParams, meetupParams, updateProfileBody } from '../users-groups/schemas';
import { fairCreditService } from './fair-credit.service';
import {
  calendarEventBody,
  closeVotingSessionBody,
  createVotingSessionBody,
  submitVoteBody,
  votingSessionParams,
} from './schemas';
import { votingService } from './voting.service';

export const votingFairnessRouter = Router();

// ---- Voting ----

// Organizer starts voting on the recommended candidates; the meetup moves to VOTING
votingFairnessRouter.post('/meetups/:meetupId/voting-session', async (req, res) => {
  const { meetupId } = parseInput(meetupParams, req.params);
  const { candidates } = parseInput(createVotingSessionBody, req.body);
  res.status(201).json(await votingService.createSession(meetupId, getUserId(req), candidates));
});

votingFairnessRouter.get('/meetups/:meetupId/voting-session', async (req, res) => {
  const { meetupId } = parseInput(meetupParams, req.params);
  res.json(await votingService.getSessionForMeetup(meetupId, getUserId(req)));
});

votingFairnessRouter.get('/voting-sessions/:sessionId', async (req, res) => {
  const { sessionId } = parseInput(votingSessionParams, req.params);
  res.json(await votingService.getSession(sessionId, getUserId(req)));
});

// Create or change the caller's vote; the weight comes from their Fair Credit, never from the client
votingFairnessRouter.post('/voting-sessions/:sessionId/vote', async (req, res) => {
  const userId = getUserId(req);
  const { sessionId } = parseInput(votingSessionParams, req.params);
  const { candidateId } = parseInput(submitVoteBody, req.body);
  const weight = await fairCreditService.getFairCredit(userId, sessionId);
  res.json(await votingService.submitVote(sessionId, userId, candidateId, weight));
});

votingFairnessRouter.get('/voting-sessions/:sessionId/votes', async (req, res) => {
  const { sessionId } = parseInput(votingSessionParams, req.params);
  res.json(await votingService.getResults(sessionId, getUserId(req)));
});

votingFairnessRouter.patch('/voting-sessions/:sessionId/close', async (req, res) => {
  const { sessionId } = parseInput(votingSessionParams, req.params);
  const { winnerCandidateId } = parseInput(closeVotingSessionBody, req.body);
  res.json(await votingService.closeSession(sessionId, getUserId(req), winnerCandidateId));
});

// ---- Google Calendar ----

// Adds the finalized meetup to the caller's own Google Calendar; the access token is used once and never stored
votingFairnessRouter.post('/meetups/:meetupId/calendar-event', async (req, res) => {
  const { meetupId } = parseInput(meetupParams, req.params);
  const { accessToken } = parseInput(calendarEventBody, req.body);
  res.status(201).json(await votingService.addToCalendar(meetupId, getUserId(req), accessToken));
});

// ---- Profile and fairness ----

// Account info, groups and per-group fairness debt in one response
votingFairnessRouter.get('/users/me/profile', async (req, res) => {
  res.json(await fairCreditService.getProfile(getUserId(req)));
});

// Replaces the editable profile fields (currently only displayName)
votingFairnessRouter.put('/users/me/profile', async (req, res) => {
  const input = parseInput(updateProfileBody, req.body);
  res.json(await fairCreditService.updateProfile(getUserId(req), input));
});

// The caller's fairness debt and commute history in one of their groups
votingFairnessRouter.get('/users/me/groups/:groupId/fairness', async (req, res) => {
  const { groupId } = parseInput(groupParams, req.params);
  res.json(await fairCreditService.getFairnessHistory(getUserId(req), groupId));
});
