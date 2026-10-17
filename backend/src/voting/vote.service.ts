
import {BadRequest,Forbidden,NotFound,Conflict} from '../common/errors';
import { VoteModel, VotingSessionModel } from './models';


async function submitVote(
    sessionId: string,
    userId: string,
    candidateId: string,
) {
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
        { $set: { candidateId } },
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
    // Count the number of votes for this candidate
    const count = votes.filter(
        (vote) => vote.candidateId.toString() === candidateId.toString(),
    ).length;

    return {
        candidateId: candidateId.toString(),
        count,
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

export const voteService = {
    submitVote,
    getResults,
    createSession,
    closeSession,
};