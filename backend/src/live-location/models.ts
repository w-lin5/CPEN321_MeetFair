import { Schema, model } from 'mongoose';
import { TRAVEL_MODES } from '../common/types';
import { jsonOptions } from '../db';

// Retain timing for future recommendation work, but only the latest shared coordinate.
const tripSchema = new Schema({
  meetupId: { type: Schema.Types.ObjectId, required: true, index: true },
  userId: { type: Schema.Types.ObjectId, required: true },
  travelMode: { type: String, enum: [...TRAVEL_MODES], required: true },
  status: { type: String, enum: ['ACTIVE', 'ARRIVED'], default: 'ACTIVE', required: true },
  sharing: { type: Boolean, default: true, required: true },
  startedAt: { type: Date, required: true },
  arrivedAt: { type: Date, default: null },
  actualSeconds: { type: Number, default: null },
  source: { type: String, enum: ['TRACKED'], default: 'TRACKED' },
  location: { type: new Schema({
    lat: { type: Number, required: true }, lng: { type: Number, required: true },
    accuracyM: { type: Number, required: true }, updatedAt: { type: Date, required: true },
  }, { _id: false }), default: null },
}, { timestamps: true, toJSON: jsonOptions });
tripSchema.index({ meetupId: 1, userId: 1 }, { unique: true });
tripSchema.index({ userId: 1 }, { unique: true, partialFilterExpression: { sharing: true } });
export const TripModel = model('Trip', tripSchema);
