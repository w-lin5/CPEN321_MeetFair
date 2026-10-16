import { type Parser, fields, text } from '../common/middleware/validate';
import { tuning } from '../config';

export const syncCalendarBody: Parser<{ accessToken: string }> = (data) => ({
  accessToken: text(fields(data), 'accessToken', tuning.oauthAccessTokenMaxLength),
});

