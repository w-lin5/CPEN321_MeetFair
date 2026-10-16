import { type InferSchemaType, Schema, model } from 'mongoose';

import { jsonOptions } from '../db';

const ATTENDEE_RESPONSE_STATUSES = ['accepted', 'declined', 'tentative', 'needsAction'] as const;

const attendeeSchema = new Schema(
  {
    email:       { type: String, required: true },
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
    googleEventId: { type: String, required: true },
    title:     { type: String, required: true },
    startTime: { type: Date, required: true },
    endTime:   { type: Date, required: true },
    timezone:  { type: String, required: true, default: 'UTC' },
    recurrence: { type: [String], default: [] },
    allDay:    { type: Boolean, default: false },
    attendees: { type: [attendeeSchema], default: [] },
  },
  {
    timestamps: true,
    toJSON: jsonOptions,
  },
);

// Enforce one copy of each Google event per user so re-syncing is idempotent
calendarEventSchema.index({ userId: 1, googleEventId: 1 }, { unique: true });

export type CalendarEventFields = InferSchemaType<typeof calendarEventSchema>;
export const CalendarEventModel = model('CalendarEvent', calendarEventSchema);

