import type { LatLng } from '../common/types';

export interface CandidateInput {
  placeId: string;
  name: string;
  address: string;
  location: LatLng;
}
