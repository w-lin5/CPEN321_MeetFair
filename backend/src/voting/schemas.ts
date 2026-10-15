
import { type Parser, fields, objectId } from '../common/middleware/validate';

export const submitVoteBody: Parser<{ candidateId: string }> = (data) => ({
  candidateId: objectId(fields(data), 'candidateId'),
});

