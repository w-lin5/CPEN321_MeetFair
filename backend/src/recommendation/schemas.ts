import { ValidationError } from '../common/errors';
import {
  type Fields,
  type Parser,
  fields,
  numberInRange,
  objectList,
  optionalText,
  text,
} from '../common/middleware/validate';
import type { LatLng } from '../common/types';
import { tuning } from '../config';

export interface CandidateInput {
  placeId: string;
  name: string;
  address: string;
  location: LatLng;
}

function parseCandidate(f: Fields): CandidateInput {
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

/**
 * TEMPORARY until feature 3 (Places + Routes + scoring) generates candidates on the server:
 * the organizer supplies exactly the top places; the same place must not appear twice.
 */
export const generateRecommendationsBody: Parser<{ candidates: CandidateInput[] }> = (data) => {
  const candidates = objectList(fields(data), 'candidates', tuning.recommendationCount).map(parseCandidate);

  if (new Set(candidates.map((c) => c.placeId)).size !== candidates.length) {
    throw new ValidationError(['candidates must not contain the same placeId twice']);
  }

  return { candidates };
};
