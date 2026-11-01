import type { LatLng, TravelMode } from '../common/types';

export interface CandidateInput {
  placeId: string;
  name: string;
  address: string;
  location: LatLng;
  // Google Places types, used to match member preferences
  types?: string[];
}

// Score components in minutes of mode-normalized travel; lower is better
export interface CandidateScores {
  weightedTravel: number;
  maxTravel: number;
  imbalance: number;
  preference: number;
  total: number;
}

export interface CandidateTravel {
  userId: string;
  mode: TravelMode;
  seconds: number;
  normalizedSeconds: number;
}

export interface ScoredCandidate extends CandidateInput {
  types: string[];
  scores: CandidateScores;
  travel: CandidateTravel[];
}
