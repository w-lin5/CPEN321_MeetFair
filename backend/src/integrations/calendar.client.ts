import { google } from 'googleapis';

import { UpstreamError } from '../common/errors';

export interface CreateGoogleCalendarEventInput {
  // Caller-chosen Google event id (base32hex: 0-9 and a-v, 5-1024 chars); a repeat insert with the same id is a duplicate
  eventId: string;
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
  // true when Google already had an event with this id, so nothing new was created
  duplicate: boolean;
}

// HTTP statuses Google Calendar answers with
const GOOGLE_STATUS = { unauthorized: 401, forbidden: 403, conflict: 409, tooManyRequests: 429 } as const;
// 403 reasons that mean "slow down" rather than "not allowed"
const RATE_LIMIT_REASONS = new Set(['rateLimitExceeded', 'userRateLimitExceeded', 'quotaExceeded']);

/**
 * Inserts an event into the primary Google Calendar of the user who owns [accessToken].
 * The token is a short-lived access token with the calendar.events scope obtained on the device;
 * it is only used for this one call and is never stored or logged.
 * Every Google failure becomes an UpstreamError (502): a 401 here would sign the user out of MeetFair.
 */
async function createGoogleCalendarEvent(
  accessToken: string,
  event: CreateGoogleCalendarEventInput,
): Promise<CreatedGoogleCalendarEvent> {
  // An access token alone is enough to call the API; no client secret or redirect URI is involved
  const oauth2Client = new google.auth.OAuth2();
  oauth2Client.setCredentials({ access_token: accessToken });
  const calendar = google.calendar({ version: 'v3', auth: oauth2Client });

  try {
    const response = await calendar.events.insert({
      calendarId: 'primary',
      requestBody: {
        id: event.eventId,
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
    return { externalId: response.data.id, htmlLink: response.data.htmlLink ?? null, duplicate: false };
  } catch (err: unknown) {
    if (err instanceof UpstreamError) throw err;
    if (statusOf(err) === GOOGLE_STATUS.conflict) {
      return { externalId: event.eventId, htmlLink: null, duplicate: true };
    }
    if (isGoogleRateLimitError(err)) {
      throw new UpstreamError('Google Calendar API rate limit reached. Please retry in a few minutes.');
    }
    if (isGoogleAuthError(err)) {
      throw new UpstreamError('Google OAuth token is invalid or lacks calendar write permission. Please re-authorize.');
    }
    throw new UpstreamError('Failed to create event in Google Calendar.');
  }
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

function field(value: unknown, key: string): unknown {
  return typeof value === 'object' && value !== null ? new Map(Object.entries(value)).get(key) : undefined;
}

// googleapis errors carry the HTTP status as `status` (or a numeric `code`)
function statusOf(err: unknown): unknown {
  return field(err, 'status') ?? field(err, 'code');
}

// First error reason, e.g. "rateLimitExceeded" or "insufficientPermissions"
function reasonOf(err: unknown): unknown {
  const errors = field(err, 'errors');
  const first: unknown = Array.isArray(errors) ? errors[0] : undefined;
  return field(first, 'reason');
}

function isGoogleAuthError(err: unknown): boolean {
  const status = statusOf(err);
  return status === GOOGLE_STATUS.unauthorized || status === GOOGLE_STATUS.forbidden;
}

// Google reports per-user rate limits as 403 with a reason, and global ones as 429
function isGoogleRateLimitError(err: unknown): boolean {
  const status = statusOf(err);
  const reason = reasonOf(err);
  return status === GOOGLE_STATUS.tooManyRequests
    || (status === GOOGLE_STATUS.forbidden && typeof reason === 'string' && RATE_LIMIT_REASONS.has(reason));
}

// Exported as an object so tests can jest.spyOn(calendarClient, 'createEvent')
export const calendarClient = { createEvent: createGoogleCalendarEvent };
