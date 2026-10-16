
import { BadRequest, NotFound } from '../common/errors';
import { VoteModel, VotingSessionModel } from './models';

async function submitVote(
    sessionId: string,
    userId: string,
    candidateId: string,
) {
    // Check if the voting session exists
    const session = await VotingSessionModel.findById(sessionId);
    if (!session) {
        throw new NotFound('Voting session not found');
    }

    // Check if the voting session is open
    if (session.status !== 'open') {
        throw new BadRequest('Voting session is closed');
    }

    // Check if the candidateId belongs to the voting session
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

export const voteService = {
  submitVote,
};