
import {BadRequest,Forbidden,NotFound,Conflict} from '../common/errors';
import { VoteModel, VotingSessionModel } from './models';


async function submitVote(
  sessionId: string,
  userId: string,
  candidateId: string,
  weight: number,
) {
    if (!Number.isFinite(weight) || weight < 0) {
        throw new BadRequest('Vote weight must be a non-negative finite number');
    }

    const session = await VotingSessionModel.findById(sessionId);
    if (!session) {
        throw new NotFound('Voting session not found');
    }

    if (session.status !== 'open') {
        throw new Conflict('Voting session is closed');
    }

    const isValidCandidate = session.candidateIds.some(
    (id) => id.toString() === candidateId,
    );
    if (!isValidCandidate) {
    throw new BadRequest('Candidate does not belong to this voting session');
    }

    // Upsert the vote: if a vote already exists for this user in this session, update it; otherwise, create a new vote
    const vote = await VoteModel.findOneAndUpdate(
        { sessionId, userId },
        { $set: { candidateId, weight } },
        {
            upsert: true, 
            returnDocument: 'after',
            setDefaultsOnInsert: true,
        },
    );

    return vote;
}


async function getResults(sessionId: string) {
  const session = await VotingSessionModel.findById(sessionId);

  if (!session) {
    throw new NotFound('Voting session not found');
  }

const votes = await VoteModel.find({ sessionId });

const totals = session.candidateIds.map((candidateId) => {
  const candidateVotes = votes.filter(
    (vote) => vote.candidateId.toString() === candidateId.toString(),
  );

  const voteCount = candidateVotes.length;

  const totalWeight = candidateVotes.reduce(
    (sum, vote) => sum + vote.weight,
    0,
  );

  // return both how many votes and total weight for each candidate
  return {
    candidateId: candidateId.toString(),
    voteCount,
    totalWeight,
  };
});

return {
    sessionId,
    totals,
};
}

async function createSession(
    candidateIds: string[],
    userId: string,
) {
    const session = await VotingSessionModel.create({
        candidateIds,
        createdBy: userId,
    });

    return session;
}

async function closeSession(
    sessionId: string,
    userId: string,
) {
    const session = await VotingSessionModel.findById(sessionId);

    if (!session) {
        throw new NotFound('Voting session not found');
    }

    if (session.createdBy.toString() !== userId) {
        throw new Forbidden('Only the creator can close this voting session');
    }

    session.status = 'closed';
    await session.save();

    return session;
}

async function getSession(sessionId: string) {
  const session = await VotingSessionModel.findById(sessionId);

  if (!session) {
    throw new NotFound('Voting session not found');
  }

  return session;
}

export const voteService = {
    submitVote,
    getResults,
    createSession,
    closeSession,
    getSession,
};