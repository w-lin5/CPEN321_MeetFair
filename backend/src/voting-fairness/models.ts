import { type InferSchemaType, Schema, model } from 'mongoose';

import { COMMUTE_SOURCES, TRAVEL_MODES, TRIP_STATUSES } from '../common/types';
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
    // Meetups whose settlement is already included in the balance, so a retried settlement never counts twice
    appliedMeetups: { type: [Schema.Types.ObjectId], default: [], select: false },
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

// The commute durations a settlement was computed from. They are frozen when settlement starts,
// so an interrupted settlement resumes with exactly the same numbers
const settlementTripSchema = new Schema(
  {
    userId: { type: String, required: true },
    mode: { type: String, enum: [...TRAVEL_MODES], required: true },
    actualSeconds: { type: Number, required: true },
    source: { type: String, enum: [...COMMUTE_SOURCES], required: true },
  },
  { _id: false },
);

const settlementSchema = new Schema({
  meetupId: { type: Schema.Types.ObjectId, ref: 'Meetup', required: true, unique: true },
  groupId: { type: Schema.Types.ObjectId, ref: 'Group', required: true },
  trips: { type: [settlementTripSchema], required: true },
});

export const SettlementModel = model('Settlement', settlementSchema);

// Where a member last reported to be; kept only while they share their location
const tripLocationSchema = new Schema(
  {
    lat: { type: Number, required: true },
    lng: { type: Number, required: true },
    accuracyM: { type: Number, required: true },
    updatedAt: { type: Date, required: true },
  },
  { _id: false },
);

// One participant's commute to a finalized meetup, timed by the server from "I'm leaving" to "I arrived"
const tripSchema = new Schema(
  {
    meetupId: { type: Schema.Types.ObjectId, ref: 'Meetup', required: true },
    userId: { type: Schema.Types.ObjectId, ref: 'User', required: true },
    travelMode: { type: String, enum: [...TRAVEL_MODES], required: true },
    status: { type: String, enum: [...TRIP_STATUSES], default: 'ACTIVE', required: true },
    // Pausing sharing hides the location but keeps the commute timer running
    sharing: { type: Boolean, default: true, required: true },
    startedAt: { type: Date, required: true },
    arrivedAt: { type: Date, default: null },
    // arrivedAt - startedAt, set on arrival
    actualSeconds: { type: Number, default: null },
    location: { type: tripLocationSchema, default: null },
  },
  { timestamps: true, toJSON: jsonOptions },
);
// One trip per participant per meetup
tripSchema.index({ meetupId: 1, userId: 1 }, { unique: true });
// A member shares their location for one meetup at a time
tripSchema.index({ userId: 1 }, { unique: true, partialFilterExpression: { sharing: true } });

export const TripModel = model('Trip', tripSchema);
