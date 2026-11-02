import { type InferSchemaType, Schema, model } from 'mongoose';

import { TRAVEL_MODES } from '../common/types';
import { jsonOptions } from '../db';

// Score breakdown (minutes of mode-normalized travel, lower is better); see recommendation/scoring.ts
const scoresSchema = new Schema(
  {
    // Fair Credit-weighted average travel
    weightedTravel: { type: Number, required: true },
    // Longest single member's travel
    maxTravel: { type: Number, required: true },
    // Mean absolute deviation of members' travel
    imbalance: { type: Number, required: true },
    // Fair Credit-weighted preference mismatch penalty
    preference: { type: Number, required: true },
    total: { type: Number, required: true },
  },
  { _id: false },
);

// One member's expected trip to a candidate
const travelSchema = new Schema(
  {
    userId: { type: Schema.Types.ObjectId, ref: 'User', required: true },
    mode: { type: String, enum: [...TRAVEL_MODES], required: true },
    seconds: { type: Number, required: true },
    normalizedSeconds: { type: Number, required: true },
  },
  { _id: false },
);

// One recommended place; votes refer to it by its Google Places id
const candidateSchema = new Schema(
  {
    placeId: { type: String, required: true },
    name: { type: String, required: true },
    address: { type: String, default: '' },
    location: {
      lat: { type: Number, required: true },
      lng: { type: Number, required: true },
    },
    types: { type: [String], default: [] },
    scores: { type: scoresSchema, default: undefined },
    travel: { type: [travelSchema], default: [] },
  },
  { _id: false },
);

// The top candidates for one meetup; generating them moves the meetup to VOTING
const recommendationSchema = new Schema(
  {
    meetupId: { type: Schema.Types.ObjectId, ref: 'Meetup', required: true, unique: true },
    generatedAt: { type: Date, default: Date.now },
    candidates: { type: [candidateSchema], required: true },
  },
  { toJSON: jsonOptions },
);

export type RecommendationFields = InferSchemaType<typeof recommendationSchema>;
export const RecommendationModel = model('Recommendation', recommendationSchema);
