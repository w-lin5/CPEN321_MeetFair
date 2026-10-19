import { type InferSchemaType, Schema, model } from 'mongoose';

import { GROUP_ROLES, MEETUP_STATUSES, PREFERENCES, TRAVEL_MODES } from '../common/types';
import { jsonOptions } from '../db';

// Define persisted user fields and enforce uniqueness for Google identities (i.e., one account per Google id)
const userSchema = new Schema(
  {
    googleSub: { type: String, required: true, unique: true },
    email: { type: String, required: true },
    name: { type: String, required: true },
    photoUrl: { type: String, default: null },
    // Name the user chose in the app; null falls back to the Google name. Sign-in never overwrites it
    displayName: { type: String, default: null },
  },
  { timestamps: true, toJSON: jsonOptions },
);

export type UserFields = InferSchemaType<typeof userSchema>;
export const UserModel = model('User', userSchema);

// A user's membership in a group; the group's creator is the ORGANIZER
const memberSchema = new Schema(
  {
    userId: { type: Schema.Types.ObjectId, ref: 'User', required: true },
    role: { type: String, enum: [...GROUP_ROLES], required: true },
    joinedAt: { type: Date, default: Date.now },
  },
  { _id: false },
);

// Invite codes are unique so that joining by code identifies exactly one group
const groupSchema = new Schema(
  {
    name: { type: String, required: true },
    inviteCode: { type: String, required: true, unique: true },
    organizerId: { type: Schema.Types.ObjectId, ref: 'User', required: true },
    members: { type: [memberSchema], default: [] },
  },
  { timestamps: true, toJSON: jsonOptions },
);
groupSchema.index({ 'members.userId': 1 });

export type GroupFields = InferSchemaType<typeof groupSchema>;
export const GroupModel = model('Group', groupSchema);

// One member's input for a meetup; resubmitting replaces the whole entry
const participantSchema = new Schema(
  {
    userId: { type: Schema.Types.ObjectId, ref: 'User', required: true },
    startLocation: {
      lat: { type: Number, required: true },
      lng: { type: Number, required: true },
      label: { type: String, default: '' },
    },
    travelMode: { type: String, enum: [...TRAVEL_MODES], required: true },
    preferences: { type: [{ type: String, enum: [...PREFERENCES] }], default: [] },
    calendarEventId: { type: String },
  },
  { _id: false },
);

// The place the meetup was finalized at (a copy of the winning recommendation candidate)
const winnerSchema = new Schema(
  {
    placeId: { type: String, required: true },
    name: { type: String, required: true },
    address: { type: String, default: '' },
    location: {
      lat: { type: Number, required: true },
      lng: { type: Number, required: true },
    },
  },
  { _id: false },
);

const meetupSchema = new Schema(
  {
    groupId: { type: Schema.Types.ObjectId, ref: 'Group', required: true, index: true },
    organizerId: { type: Schema.Types.ObjectId, ref: 'User', required: true },
    title: { type: String, required: true },
    scheduledAt: { type: Date, required: true },
    status: { type: String, enum: [...MEETUP_STATUSES], default: 'SETUP' },
    participants: { type: [participantSchema], default: [] },
    winner: { type: winnerSchema, default: undefined },
    finalizedAt: { type: Date },
  },
  { timestamps: true, toJSON: jsonOptions },
);

export type MeetupFields = InferSchemaType<typeof meetupSchema>;
export type MeetupWinner = InferSchemaType<typeof winnerSchema>;
export const MeetupModel = model('Meetup', meetupSchema);
