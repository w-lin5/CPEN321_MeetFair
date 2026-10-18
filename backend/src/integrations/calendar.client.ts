import { google } from 'googleapis';

import { Unauthorized, UpstreamError } from '../common/errors';

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

/**
 * Inserts an event into the primary Google Calendar of the user who owns [accessToken].
 * The token is a short-lived access token with the calendar.events scope obtained on the device;
 * it is only used for this one call and is never stored or logged.
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

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

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

// Exported as an object so tests can jest.spyOn(calendarClient, 'createEvent')
export const calendarClient = { createEvent: createGoogleCalendarEvent };
