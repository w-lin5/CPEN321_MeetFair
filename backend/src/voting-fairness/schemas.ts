import {
  type Parser,
  fields,
  numberInRange,
  objectId,
  objectList,
  optionalObjectId,
  optionalText,
  text,
} from '../common/middleware/validate';
import { ValidationError } from '../common/errors';
import type { LatLng } from '../common/types';
import { tuning } from '../config';

export interface CandidateInput {
  placeId: string;
  name: string;
  address: string;
  location: LatLng;
}

// Check if the submitted candidateId is a valid ObjectId
export const submitVoteBody: Parser<{ candidateId: string }> = (data) => ({
  candidateId: objectId(fields(data), 'candidateId'),
});

// Check if the sessionId is a valid ObjectId
export const votingSessionParams: Parser<{ sessionId: string }> = (data) => ({
  sessionId: objectId(fields(data, 'params'), 'sessionId'),
});

function parseCandidate(f: ReturnType<typeof fields>): CandidateInput {
  const loc = fields(f.get('location'), 'location');

  return {
    placeId: text(f, 'placeId', tuning.placeIdMaxLength),
    name: text(f, 'name', tuning.placeNameMaxLength),
    address: optionalText(f, 'address', tuning.placeAddressMaxLength),
    location: {
      lat: numberInRange(loc, 'lat', -tuning.maxLatitude, tuning.maxLatitude),
      lng: numberInRange(loc, 'lng', -tuning.maxLongitude, tuning.maxLongitude),
    },
  };
}

// The recommender's top places; the same place must not appear twice
export const createVotingSessionBody: Parser<{ candidates: CandidateInput[] }> = (data) => {
  const candidates = objectList(fields(data), 'candidates', tuning.votingCandidateCount).map(parseCandidate);

  if (new Set(candidates.map((c) => c.placeId)).size !== candidates.length) {
    throw new ValidationError(['candidates must not contain the same placeId twice']);
  }

  return { candidates };
};

// The organizer may name the winner to break a tie, or when nobody voted
export const closeVotingSessionBody: Parser<{ winnerCandidateId: string | undefined }> = (data) => ({
  winnerCandidateId: optionalObjectId(fields(data ?? {}), 'winnerCandidateId'),
});

// Short-lived Google OAuth access token with the calendar.events scope; never stored or logged
export const calendarEventBody: Parser<{ accessToken: string }> = (data) => ({
  accessToken: text(fields(data), 'accessToken', tuning.oauthAccessTokenMaxLength),
});
