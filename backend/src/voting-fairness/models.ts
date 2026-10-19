import { type InferSchemaType, Schema, model } from 'mongoose';

import { COMMUTE_SOURCES } from '../common/types';
import { jsonOptions } from '../db';

// One participant's vote in a meetup; free to change until locked, then the weight is snapshotted
const voteSchema = new Schema(
  {
    meetupId: { type: Schema.Types.ObjectId, ref: 'Meetup', required: true },
    userId: { type: Schema.Types.ObjectId, ref: 'User', required: true },
    // Google Places id of one of the meetup's recommendation candidates
    placeId: { type: String, required: true },
    locked: { type: Boolean, default: false },
    // Set when locking, from the voter's Fair Credit at that moment
    weight: { type: Number, default: null },
  },
  { timestamps: true, toJSON: jsonOptions },
);

// One vote per participant per meetup
voteSchema.index({ meetupId: 1, userId: 1 }, { unique: true });

export type VoteFields = InferSchemaType<typeof voteSchema>;
export const VoteModel = model('Vote', voteSchema);

// A member's current Fair Credit in one group, in seconds; changes only at settlement
const fairCreditSchema = new Schema(
  {
    groupId: { type: Schema.Types.ObjectId, ref: 'Group', required: true },
    userId: { type: Schema.Types.ObjectId, ref: 'User', required: true },
    balance: { type: Number, required: true },
  },
  { toJSON: jsonOptions },
);
fairCreditSchema.index({ groupId: 1, userId: 1 }, { unique: true });

export const FairCreditModel = model('FairCredit', fairCreditSchema, 'fairCredits');

// Ledger: one entry per member per settled meetup, explaining one balance change (all durations in seconds)
const fairCreditEntrySchema = new Schema(
  {
    groupId: { type: Schema.Types.ObjectId, ref: 'Group', required: true },
    userId: { type: Schema.Types.ObjectId, ref: 'User', required: true },
    meetupId: { type: Schema.Types.ObjectId, ref: 'Meetup', required: true },
    actualSeconds: { type: Number, required: true },
    normalizedBurden: { type: Number, required: true },
    expectedBurden: { type: Number, required: true },
    // normalizedBurden - expectedBurden; negative when the member travelled less than average
    delta: { type: Number, required: true },
    // TRACKED from live commute tracking, ESTIMATED from the Routes fallback
    source: { type: String, enum: [...COMMUTE_SOURCES], required: true },
  },
  { timestamps: true, toJSON: jsonOptions },
);
// At most one entry per member per meetup, so a meetup can never be settled twice
fairCreditEntrySchema.index({ meetupId: 1, userId: 1 }, { unique: true });
fairCreditEntrySchema.index({ groupId: 1, userId: 1, createdAt: -1 });

export type FairCreditEntryFields = InferSchemaType<typeof fairCreditEntrySchema>;
export const FairCreditEntryModel = model('FairCreditEntry', fairCreditEntrySchema, 'fairCreditEntries');
