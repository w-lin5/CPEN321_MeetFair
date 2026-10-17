import type { Types } from 'mongoose';

import { BadRequest } from '../common/errors';
import { CalendarEventModel } from './calendar-event.model';
import { createGoogleCalendarEvent, fetchCalendarEvents } from './google-calendar.client';
import type { CreateCalendarEventInput } from './google-calendar.schemas';

export interface SyncResult {
  upserted: number;
  total: number;
}

/**
 * Fetches all events from the user's Google Calendar and upserts them into the
 * local database. Existing events are updated in-place; new events are inserted.
 * The operation is idempotent: calling it multiple times with the same token
 * yields the same database state.
 */
async function syncCalendar(
  userId: Types.ObjectId | string,
  accessToken: string,
): Promise<SyncResult> {
  const events = await fetchCalendarEvents(accessToken);

  // Bulk-upsert: update if the (userId, externalId) pair already exists,
  // otherwise insert.
  const ops = events.map((ev) => ({
    updateOne: {
      filter: { userId, externalId: ev.externalId },
      update: {
        $set: {
          title:     ev.title,
          description: ev.description ?? '',
          location: ev.location ?? '',
          startTime: ev.startTime,
          endTime:   ev.endTime,
          timezone:  ev.timezone,
          recurrence: ev.recurrence,
          allDay:    ev.allDay,
          attendees: ev.attendees,
          busy: ev.busy,
          syncedAt: new Date(),
        },
        $setOnInsert: { source: 'google' },
      },
      upsert: true,
    },
  }));

  if (ops.length === 0) {
    return { upserted: 0, total: 0 };
  }

  const result = await CalendarEventModel.bulkWrite(ops, { ordered: false });

  return {
    upserted: result.upsertedCount + result.modifiedCount,
    total: ops.length,
  };
}

/**
 * Returns all calendar events stored locally for a given user, sorted by
 * start time ascending.
 */
async function getEventsForUser(userId: Types.ObjectId | string) {
  return CalendarEventModel.find({ userId }).sort({ startTime: 1 });
}


async function addCalendarEvent(userId: Types.ObjectId | string, input: CreateCalendarEventInput) {
  if (Date.parse(input.endTime) <= Date.parse(input.startTime)) {
    throw new BadRequest('endTime must be after startTime.');
  }
  const created = await createGoogleCalendarEvent(input.accessToken, input);
  return CalendarEventModel.create({
    userId,
    externalId: created.externalId,
    source: 'app',
    title: input.title,
    description: input.description,
    location: input.location,
    startTime: new Date(input.startTime),
    endTime: new Date(input.endTime),
    timezone: input.timezone,
    allDay: false,
    busy: true,
    syncedAt: new Date(),
    attendees: input.attendeeEmails.map((email) => ({ email, responseStatus: 'needsAction' as const })),
  });
}

export const calendarService = { syncCalendar, getEventsForUser, addCalendarEvent };

