import { Router } from 'express';

import { getUserId } from '../common/middleware/require-auth';
import { parseInput } from '../common/middleware/validate';
import { meetupParams } from '../users-groups/schemas';
import { fairCreditService } from './fair-credit.service';
import {
  closeVotingSessionBody,
  createVotingSessionBody,
  submitVoteBody,
  votingSessionParams,
} from './schemas';
import { voteService } from './vote.service';

export const votingRouter = Router();

// Organizer starts voting on the recommended candidates; the meetup moves to VOTING
votingRouter.post('/meetups/:meetupId/voting-session', async (req, res) => {
  const { meetupId } = parseInput(meetupParams, req.params);
  const { candidates } = parseInput(createVotingSessionBody, req.body);
  res.status(201).json(await voteService.createSession(meetupId, getUserId(req), candidates));
});

votingRouter.get('/meetups/:meetupId/voting-session', async (req, res) => {
  const { meetupId } = parseInput(meetupParams, req.params);
  res.json(await voteService.getSessionForMeetup(meetupId, getUserId(req)));
});

votingRouter.get('/voting-sessions/:sessionId', async (req, res) => {
  const { sessionId } = parseInput(votingSessionParams, req.params);
  res.json(await voteService.getSession(sessionId, getUserId(req)));
});

// Create or change the caller's vote; the weight comes from their Fair Credit, never from the client
votingRouter.post('/voting-sessions/:sessionId/vote', async (req, res) => {
  const userId = getUserId(req);
  const { sessionId } = parseInput(votingSessionParams, req.params);
  const { candidateId } = parseInput(submitVoteBody, req.body);
  const weight = await fairCreditService.getFairCredit(userId, sessionId);
  res.json(await voteService.submitVote(sessionId, userId, candidateId, weight));
});

votingRouter.get('/voting-sessions/:sessionId/votes', async (req, res) => {
  const { sessionId } = parseInput(votingSessionParams, req.params);
  res.json(await voteService.getResults(sessionId, getUserId(req)));
});

votingRouter.patch('/voting-sessions/:sessionId/close', async (req, res) => {
  const { sessionId } = parseInput(votingSessionParams, req.params);
  const { winnerCandidateId } = parseInput(closeVotingSessionBody, req.body);
  res.json(await voteService.closeSession(sessionId, getUserId(req), winnerCandidateId));
});
