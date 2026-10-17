import { google } from 'googleapis';

import { BadRequest, Unauthorized, UpstreamError } from '../common/errors';
import { env } from '../config';
import type { CalendarEventFields } from './calendar-event.model';

type CalendarEventAttendeeData = Pick<
  CalendarEventFields['attendees'][number],
  'email' | 'displayName' | 'responseStatus'
>;

type CalendarEventData = Omit<Pick<
  CalendarEventFields,
  'title' | 'description' | 'location' | 'startTime' | 'endTime' | 'timezone'
    | 'recurrence' | 'allDay' | 'attendees' | 'busy'
>, 'attendees'> & { attendees: CalendarEventAttendeeData[] };

// Google event data normalized to the fields persisted by CalendarEventModel.
export type GoogleCalendarEvent = CalendarEventData & { externalId: string };

export interface CreateGoogleCalendarEventInput {
  title: string;
  description: string;
  startTime: string;
  endTime: string;
  timezone: string;
  location: string;
  attendeeEmails: string[];
}

export interface CreatedGoogleCalendarEvent {
  externalId: string;
  htmlLink: string | null;
}

export async function createGoogleCalendarEvent(
  accessToken: string,
  event: CreateGoogleCalendarEventInput,
): Promise<CreatedGoogleCalendarEvent> {
  const oauth2Client = new google.auth.OAuth2(env.googleClientId, env.googleClientSecret, env.googleRedirectUri);
  oauth2Client.setCredentials({ access_token: accessToken });
  const calendar = google.calendar({ version: 'v3', auth: oauth2Client });

  try {
    const response = await calendar.events.insert({
      calendarId: 'primary',
      requestBody: {
        summary: event.title,
        ...(event.description ? { description: event.description } : {}),
        ...(event.location ? { location: event.location } : {}),
        start: { dateTime: event.startTime, timeZone: event.timezone },
        end: { dateTime: event.endTime, timeZone: event.timezone },
        attendees: event.attendeeEmails.map((email) => ({ email })),
      },
      sendUpdates: event.attendeeEmails.length > 0 ? 'all' : 'none',
    });
    if (!response.data.id) throw new UpstreamError('Google Calendar did not return an event ID.');
    return { externalId: response.data.id, htmlLink: response.data.htmlLink ?? null };
  } catch (err: unknown) {
    if (err instanceof UpstreamError) throw err;
    if (isGoogleAuthError(err)) {
      throw new Unauthorized('Google OAuth token is invalid or lacks calendar write permission. Please re-authorize.');
    }
    if (isGoogleRateLimitError(err)) {
      throw new UpstreamError('Google Calendar API rate limit reached. Please retry in a few minutes.');
    }
    throw new UpstreamError('Failed to create event in Google Calendar.');
  }
}

// Maximum events to fetch per sync to stay inside reasonable memory and rate limits
const MAX_EVENTS_PER_SYNC = 250;

/**
 * Exchanges a short-lived server-auth-code for access/refresh tokens, then
 * returns a list of normalised events from the user's primary Google Calendar.
 *
 * The caller is responsible for persisting events; this module only talks to
 * the Google API and normalises the raw response.
 */
export async function fetchCalendarEvents(
  accessToken: string,
): Promise<GoogleCalendarEvent[]> {
  const oauth2Client = new google.auth.OAuth2(
    env.googleClientId,
    env.googleClientSecret,
    env.googleRedirectUri,
  );
  oauth2Client.setCredentials({ access_token: accessToken });

  const calendar = google.calendar({ version: 'v3', auth: oauth2Client });

  let response;
  try {
    response = await calendar.events.list({
      calendarId: 'primary',
      // Only fetch future events and the recent past (rolling 7-day window)
      timeMin: new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString(),
      maxResults: MAX_EVENTS_PER_SYNC,
      singleEvents: true,
      orderBy: 'startTime',
    });
  } catch (err: unknown) {
    if (isGoogleAuthError(err)) {
      throw new Unauthorized(
        'Google OAuth token is invalid or has been revoked. Please re-authenticate.',
      );
    }
    if (isGoogleRateLimitError(err)) {
      throw new UpstreamError(
        'Google Calendar API rate limit reached. Please retry in a few minutes.',
      );
    }
    throw new UpstreamError('Failed to reach Google Calendar API.');
  }

  const items = response.data.items ?? [];

  const events: GoogleCalendarEvent[] = [];
  for (const item of items) {
    if (item.id === undefined || item.id === null) continue;
    if (item.status === 'cancelled') continue;

    const { startTime, endTime, timezone, allDay } = extractTimes(item);
    events.push({
      externalId: item.id,
      title: item.summary ?? '(No title)',
      description: item.description ?? '',
      location: item.location ?? '',
      startTime,
      endTime,
      timezone,
      recurrence: item.recurrence ?? [],
      allDay,
      attendees: (item.attendees ?? []).map((a) => ({
        email: a.email ?? '',
        displayName: a.displayName ?? null,
        responseStatus: (a.responseStatus as CalendarEventAttendeeData['responseStatus']) ?? 'needsAction',
      })).filter((a) => a.email !== ''),
      busy: item.transparency !== 'transparent'
        && !(item.attendees?.length && item.attendees.every((attendee) => attendee.responseStatus === 'declined')),
    });
  }

  return events;
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

interface GCalEvent {
  description?: string | null;
  location?: string | null;
  transparency?: string | null;
  start?: { dateTime?: string | null; date?: string | null; timeZone?: string | null } | null;
  end?: { dateTime?: string | null; date?: string | null; timeZone?: string | null } | null;
}

function extractTimes(item: GCalEvent): {
  startTime: Date;
  endTime: Date;
  timezone: string;
  allDay: boolean;
} {
  // All-day events use `date` (YYYY-MM-DD); timed events use `dateTime` (ISO 8601)
  const startRaw = item.start?.dateTime ?? item.start?.date;
  const endRaw   = item.end?.dateTime   ?? item.end?.date;

  if (!startRaw || !endRaw) {
    throw new BadRequest('Google Calendar event is missing start or end time.');
  }

  const allDay  = !item.start?.dateTime;
  const timezone = item.start?.timeZone ?? item.end?.timeZone ?? 'UTC';

  return {
    startTime: new Date(startRaw),
    endTime:   new Date(endRaw),
    timezone,
    allDay,
  };
}

function isGoogleAuthError(err: unknown): boolean {
  if (typeof err !== 'object' || err === null) return false;
  const status = (err as Record<string, unknown>).status ?? (err as Record<string, unknown>).code;
  return status === 401 || status === 403;
}

function isGoogleRateLimitError(err: unknown): boolean {
  if (typeof err !== 'object' || err === null) return false;
  const status = (err as Record<string, unknown>).status ?? (err as Record<string, unknown>).code;
  return status === 429 || status === 403;
}

