import { type InferSchemaType, Schema, model } from 'mongoose';

import { jsonOptions } from '../db';

const ATTENDEE_RESPONSE_STATUSES = ['accepted', 'declined', 'tentative', 'needsAction'] as const;

const attendeeSchema = new Schema(
  {
    email:       { type: String, required: true },
    displayName: { type: String, default: null },
    responseStatus: {
      type: String,
      enum: ATTENDEE_RESPONSE_STATUSES,
      default: 'needsAction',
    },
  },
  { _id: false },
);

const calendarEventSchema = new Schema(
  {
    userId:    { type: Schema.Types.ObjectId, ref: 'User', required: true, index: true },
    externalId: { type: String },
    source:    { type: String, enum: ['google', 'app'], required: true, default: 'google' },
    title:     { type: String, required: true },
    description: { type: String, default: '' },
    location:  { type: String, default: '' },
    startTime: { type: Date, required: true },
    endTime:   { type: Date, required: true },
    timezone:  { type: String, required: true, default: 'UTC' },
    recurrence: { type: [String], default: [] },
    allDay:    { type: Boolean, default: false },
    attendees: { type: [attendeeSchema], default: [] },
    busy:      { type: Boolean, default: true },
    syncedAt:  { type: Date },
  },
  {
    timestamps: true,
    toJSON: jsonOptions,
  },
);

// Each external calendar event has one record per user.
calendarEventSchema.index(
  { userId: 1, externalId: 1 },
  { unique: true, partialFilterExpression: { externalId: { $type: 'string' } } },
);

export type CalendarEventFields = InferSchemaType<typeof calendarEventSchema>;
export const CalendarEventModel = model('CalendarEvent', calendarEventSchema);

