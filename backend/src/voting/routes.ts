import { Router } from 'express';

import { parseInput } from '../common/middleware/validate';
import { getUserId } from '../common/middleware/require-auth';
import { voteService } from './vote.service';
import { submitVoteBody, votingSessionParams } from './schemas';

export const votingRouter = Router();

votingRouter.post('/voting-sessions/:sessionId/vote', async (req, res) => {
  const userId = getUserId(req);
  const { candidateId } = parseInput(submitVoteBody, req.body);
  const { sessionId } = parseInput(votingSessionParams, req.params);

  const vote = await voteService.submitVote(
    sessionId,
    userId,
    candidateId,
  );

  res.json(vote);
});

votingRouter.get('/voting-sessions/:sessionId/votes', async (req, res) => {
  const { sessionId } = parseInput(votingSessionParams, req.params);

  const results = await voteService.getResults(sessionId);

  res.json(results);
});
