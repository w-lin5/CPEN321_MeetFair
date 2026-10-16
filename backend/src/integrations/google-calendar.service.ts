import type { Types } from 'mongoose';

import { CalendarEventModel } from './calendar-event.model';
import { fetchCalendarEvents } from './google-calendar.client';

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

  // Bulk-upsert: update if the (userId, googleEventId) pair already exists,
  // otherwise insert.
  const ops = events.map((ev) => ({
    updateOne: {
      filter: { userId, googleEventId: ev.googleEventId },
      update: {
        $set: {
          title:     ev.title,
          startTime: ev.startTime,
          endTime:   ev.endTime,
          timezone:  ev.timezone,
          recurrence: ev.recurrence,
          allDay:    ev.allDay,
          attendees: ev.attendees,
        },
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

export const calendarService = { syncCalendar, getEventsForUser };

