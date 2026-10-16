
import { type Parser, fields, objectId } from '../common/middleware/validate';

// Check if the candidateId is a valid ObjectId
export const submitVoteBody: Parser<{ candidateId: string }> = (data) => ({
  candidateId: objectId(fields(data), 'candidateId'),
});

// Check if the sessionId is a valid ObjectId
export const votingSessionParams: Parser<{ sessionId: string }> = (data) => ({
  sessionId: objectId(fields(data, 'params'), 'sessionId'),
});
