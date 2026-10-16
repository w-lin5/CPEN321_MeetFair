import { google } from 'googleapis';

import { BadRequest, Unauthorized, UpstreamError } from '../common/errors';
import { env } from '../config';

export interface GoogleCalendarAttendee {
  email: string;
  displayName: string | null;
  responseStatus: 'accepted' | 'declined' | 'tentative' | 'needsAction';
}

// Normalised shape of a single Google Calendar event after extraction
export interface GoogleCalendarEvent {
  googleEventId: string;
  title: string;
  startTime: Date;
  endTime: Date;
  timezone: string;
  recurrence: string[];
  allDay: boolean;
  attendees: GoogleCalendarAttendee[];
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
      // Only fetch future events and the recent past (rolling 30-day window)
      timeMin: new Date(Date.now() - 30 * 24 * 60 * 60 * 1000).toISOString(),
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
      googleEventId: item.id,
      title: item.summary ?? '(No title)',
      startTime,
      endTime,
      timezone,
      recurrence: item.recurrence ?? [],
      allDay,
      attendees: (item.attendees ?? []).map((a) => ({
        email: a.email ?? '',
        displayName: a.displayName ?? null,
        responseStatus: (a.responseStatus as GoogleCalendarAttendee['responseStatus']) ?? 'needsAction',
      })).filter((a) => a.email !== ''),
    });
  }

  return events;
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

interface GCalEvent {
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

