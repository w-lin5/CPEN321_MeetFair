
import {
  type Parser,
  fields,
  objectId,
  objectIdArray,
} from '../common/middleware/validate';

// Check if the submitted candidateId is a valid ObjectId
export const submitVoteBody: Parser<{ candidateId: string }> = (data) => ({
  candidateId: objectId(fields(data), 'candidateId'),
});

// Check if the sessionId is a valid ObjectId
export const votingSessionParams: Parser<{ sessionId: string }> = (data) => ({
  sessionId: objectId(fields(data, 'params'), 'sessionId'),
});

export const createVotingSessionBody: Parser<{
  candidateIds: string[];
}> = (data) => ({
  candidateIds: objectIdArray(
    fields(data),
    'candidateIds',
    3,
  ),
});
