import { type Parser, fields, text } from '../common/middleware/validate';
import { tuning } from '../config';

// Validate and extract required Google ID from request body
export const googleSignInBody: Parser<{ idToken: string }> = (data) => ({
  idToken: text(fields(data), 'idToken', tuning.idTokenMaxLength),
});
