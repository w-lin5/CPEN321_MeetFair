import { type Parser, fields, numberInRange, optionalText, text } from '../common/middleware/validate';
import type { LatLng } from '../common/types';
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

// The device's current position while it shares a trip
export interface TripPosition extends LatLng {
  accuracyM: number;
}

export const tripLocationBody: Parser<TripPosition> = (data) => {
  const f = fields(data);
  return {
    lat: numberInRange(f, 'lat', -tuning.maxLatitude, tuning.maxLatitude),
    lng: numberInRange(f, 'lng', -tuning.maxLongitude, tuning.maxLongitude),
    accuracyM: numberInRange(f, 'accuracyM', 0, tuning.maxLocationAccuracyMeters),
  };
};
