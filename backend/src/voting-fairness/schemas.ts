import { type Parser, fields, optionalText, text } from '../common/middleware/validate';
import { tuning } from '../config';

// The candidate (Google Places id) the caller votes for
export const voteBody: Parser<{ placeId: string }> = (data) => ({
  placeId: text(fields(data), 'placeId', tuning.placeIdMaxLength),
});

// The organizer may name the winner to break a tie, or when nobody locked a vote; the body is optional
export const finalizeBody: Parser<{ placeId: string | undefined }> = (data) => {
  const placeId = optionalText(fields(data ?? {}), 'placeId', tuning.placeIdMaxLength);
  return { placeId: placeId === '' ? undefined : placeId };
};

// Short-lived Google OAuth access token with the calendar.events scope; never stored or logged
export const calendarEventBody: Parser<{ accessToken: string }> = (data) => ({
  accessToken: text(fields(data), 'accessToken', tuning.oauthAccessTokenMaxLength),
});
