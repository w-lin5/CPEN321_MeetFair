
import { type Parser, fields, objectId } from '../common/middleware/validate';

// Check if the candidateId is a valid ObjectId
export const submitVoteBody: Parser<{ candidateId: string }> = (data) => ({
  candidateId: objectId(fields(data), 'candidateId'),
});

