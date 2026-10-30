import { google } from 'googleapis';
import request, { type Test } from 'supertest';

import { createApp } from '../../src/app';
import { UpstreamError } from '../../src/common/errors';
import { calendarClient } from '../../src/integrations/calendar.client';
import { MeetupModel } from '../../src/users-groups/models';
import { bearer } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import { addParticipants, createFinalizedMeetup, createGroup, createMeetup, createUserN } from '../helpers/factories';

jest.mock('googleapis', () => ({
  google: {
    auth: { OAuth2: jest.fn() },
    calendar: jest.fn(),
  },
}));

const mockOAuth2 = jest.mocked(google.auth.OAuth2);
const mockCalendar = jest.mocked(google.calendar);
const mockInsert = jest.fn();
const mockSetCredentials = jest.fn();

beforeAll(startDb);
afterEach(async () => {
  jest.restoreAllMocks();
  await clearDb();
});
afterAll(stopDb);

async function setup() {
  const user = await createUserN(1);
  const group = await createGroup(user.id);
  const meetup = await createFinalizedMeetup(group.id, user.id, [user.id]);
  return { user, meetup };
}

function addToCalendar(meetupId: string, userId: string): Test {
  return request(createApp())
    .post(`/api/meetups/${meetupId}/calendar-event`)
    .set('Authorization', bearer(userId))
    .send({ accessToken: 'google-access-token' });
}

// Interface POST /api/meetups/:meetupId/calendar-event
describe('Mocked: POST /api/meetups/:meetupId/calendar-event', () => {
  // Input: participant of a FINALIZED meetup with a calendar access token
  // Expected status code: 201
  // Expected behavior: the event is inserted with the meetup's title, time and winning place, and its id is
  //                    recorded on the caller's participation entry
  // Expected output: { eventId, htmlLink }
  // Mocked behavior: calendarClient.createEvent resolves with a created event
  test('Adds the meetup to the caller calendar', async () => {
    const { user, meetup } = await setup();
    const eventId = `meetfair${meetup.id}${user.id}`;
    const createEvent = jest.spyOn(calendarClient, 'createEvent')
      .mockResolvedValue({ externalId: eventId, htmlLink: 'https://calendar.google.com/event/1', duplicate: false });

    const res = await addToCalendar(meetup.id, user.id);

    expect(res.status).toBe(201);
    expect(res.body).toEqual({ eventId, htmlLink: 'https://calendar.google.com/event/1' });
    expect(createEvent).toHaveBeenCalledWith('google-access-token', {
      eventId,
      title: 'Library session',
      description: 'Planned with MeetFair',
      startTime: '2026-11-05T22:30:00.000Z',
      endTime: '2026-11-05T23:30:00.000Z',
      timezone: 'UTC',
      location: 'Koerner Library, 1958 Main Mall',
      attendeeEmails: [],
    });
    const saved = await MeetupModel.findById(meetup.id).orFail();
    expect(saved.participants[0]?.calendarEventId).toBe(eventId);
  });

  // Input: participant of a FINALIZED meetup
  // Expected status code: 502
  // Expected behavior: no event id is recorded, so the user can retry
  // Mocked behavior: calendarClient.createEvent rejects with UpstreamError
  test('Google Calendar failure', async () => {
    const { user, meetup } = await setup();
    jest.spyOn(calendarClient, 'createEvent').mockRejectedValue(new UpstreamError('Failed to create event in Google Calendar.'));

    const res = await addToCalendar(meetup.id, user.id);

    expect(res.status).toBe(502);
    expect(res.body).toEqual({ error: 'Failed to create event in Google Calendar.' });
    const saved = await MeetupModel.findById(meetup.id).orFail();
    expect(saved.participants[0]?.calendarEventId).toBeUndefined();
  });

  // Input: participant of a FINALIZED meetup with no event recorded yet
  // Expected status code: 409
  // Expected behavior: Google already has this meetup's event (a concurrent request, or an earlier one whose
  //                    DB write failed), so no second event exists and the id is recorded for next time
  // Mocked behavior: calendarClient.createEvent reports a duplicate event id
  test('Duplicate event in Google', async () => {
    const { user, meetup } = await setup();
    const eventId = `meetfair${meetup.id}${user.id}`;
    jest.spyOn(calendarClient, 'createEvent').mockResolvedValue({ externalId: eventId, htmlLink: null, duplicate: true });

    const res = await addToCalendar(meetup.id, user.id);

    expect(res.status).toBe(409);
    expect(res.body).toEqual({ error: 'This meetup is already in your calendar' });
    const saved = await MeetupModel.findById(meetup.id).orFail();
    expect(saved.participants[0]?.calendarEventId).toBe(eventId);
  });

  // Input: participant of a FINALIZED meetup that has no winner stored
  // Expected status code: 201
  // Expected behavior: the event is still created, with an empty location
  // Mocked behavior: calendarClient.createEvent resolves with a created event
  test('Meetup without a winner gets an empty location', async () => {
    const user = await createUserN(1);
    const group = await createGroup(user.id);
    const meetup = await createMeetup(group.id, user.id, { status: 'FINALIZED' });
    await addParticipants(meetup.id, [user.id]);
    const createEvent = jest.spyOn(calendarClient, 'createEvent')
      .mockResolvedValue({ externalId: 'event-1', htmlLink: null, duplicate: false });

    const res = await addToCalendar(meetup.id, user.id);

    expect(res.status).toBe(201);
    expect(createEvent).toHaveBeenCalledWith('google-access-token', expect.objectContaining({ location: '' }));
  });
});

// Interface calendarClient.createEvent (the only Google Calendar call)
describe('Mocked: calendarClient.createEvent', () => {
  const event = {
    eventId: 'meetfair0123456789abcdef',
    title: 'Design review',
    description: 'Review the new flow',
    location: 'Meeting room 4',
    startTime: '2026-11-02T10:00:00-08:00',
    endTime: '2026-11-02T11:00:00-08:00',
    timezone: 'America/Vancouver',
    attendeeEmails: ['alex@example.com', 'sam@example.com'],
  };
  const noExtras = { ...event, description: '', location: '', attendeeEmails: [] };

  beforeEach(() => {
    jest.clearAllMocks();
    mockOAuth2.mockImplementation(() => ({ setCredentials: mockSetCredentials }) as never);
    mockCalendar.mockReturnValue({ events: { insert: mockInsert } } as never);
  });

  // Input: event with description, location and attendees
  // Expected behavior: all fields, timezone, attendees and update notifications are sent to Google
  // Expected output: { externalId, htmlLink } from Google's response
  // Mocked behavior: googleapis calendar.events.insert resolves
  test('Sends event fields, timezone, attendees, and update notifications to Google', async () => {
    mockInsert.mockResolvedValue({
      data: { id: 'google-event-123', htmlLink: 'https://calendar.google.com/event/123' },
    });

    await expect(calendarClient.createEvent('oauth-token', event)).resolves.toEqual({
      externalId: 'google-event-123',
      htmlLink: 'https://calendar.google.com/event/123',
      duplicate: false,
    });

    expect(mockSetCredentials).toHaveBeenCalledWith({ access_token: 'oauth-token' });
    expect(mockCalendar).toHaveBeenCalledWith({ version: 'v3', auth: expect.anything() });
    expect(mockInsert).toHaveBeenCalledWith({
      calendarId: 'primary',
      requestBody: {
        id: event.eventId,
        summary: event.title,
        description: event.description,
        location: event.location,
        start: { dateTime: event.startTime, timeZone: event.timezone },
        end: { dateTime: event.endTime, timeZone: event.timezone },
        attendees: [{ email: 'alex@example.com' }, { email: 'sam@example.com' }],
      },
      sendUpdates: 'all',
    });
  });

  // Input: event without description, location or attendees
  // Expected behavior: empty optional fields are omitted and no attendee notifications are sent
  // Mocked behavior: googleapis calendar.events.insert resolves without htmlLink
  test('Omits empty optional fields and skips attendee notifications when there are no attendees', async () => {
    mockInsert.mockResolvedValue({ data: { id: 'google-event-456' } });

    await expect(calendarClient.createEvent('oauth-token', noExtras))
      .resolves.toEqual({ externalId: 'google-event-456', htmlLink: null, duplicate: false });

    expect(mockInsert).toHaveBeenCalledWith(expect.objectContaining({ sendUpdates: 'none' }));
    const requestBody = mockInsert.mock.calls[0]?.[0].requestBody;
    expect(requestBody).not.toHaveProperty('description');
    expect(requestBody).not.toHaveProperty('location');
  });

  // Input: any event
  // Expected behavior: a response without an event id is an upstream failure
  // Mocked behavior: googleapis calendar.events.insert resolves with no id
  test('Rejects a response without an event id', async () => {
    mockInsert.mockResolvedValue({ data: {} });

    await expect(calendarClient.createEvent('oauth-token', noExtras)).rejects.toBeInstanceOf(UpstreamError);
  });

  // Input: an event id Google already has
  // Expected output: { duplicate: true } with the requested id instead of an error
  // Mocked behavior: googleapis calendar.events.insert rejects with status 409
  test('Reports an existing event id as a duplicate', async () => {
    mockInsert.mockRejectedValue({ status: 409 });

    await expect(calendarClient.createEvent('oauth-token', noExtras))
      .resolves.toEqual({ externalId: noExtras.eventId, htmlLink: null, duplicate: true });
  });

  // Input: revoked token, or a token without the calendar.events scope
  // Expected behavior: mapped to UpstreamError (502), never 401, which would sign the user out of MeetFair
  // Mocked behavior: googleapis calendar.events.insert rejects with status 401, then 403 insufficientPermissions
  test('Maps invalid Google credentials to UpstreamError', async () => {
    mockInsert.mockRejectedValueOnce({ status: 401 });
    await expect(calendarClient.createEvent('revoked-token', noExtras))
      .rejects.toEqual(expect.objectContaining({ status: 502, message: expect.stringContaining('re-authorize') }));

    mockInsert.mockRejectedValueOnce({ status: 403, errors: [{ reason: 'insufficientPermissions' }] });
    await expect(calendarClient.createEvent('oauth-token', noExtras))
      .rejects.toEqual(expect.objectContaining({ status: 502, message: expect.stringContaining('re-authorize') }));
  });

  // Input: any event
  // Expected behavior: rate limits and unexpected failures become UpstreamError (502)
  // Mocked behavior: googleapis calendar.events.insert rejects with 429, 403 userRateLimitExceeded, a network error
  test('Maps Google rate limits and unexpected create failures to UpstreamError', async () => {
    mockInsert.mockRejectedValueOnce({ status: 429 });
    await expect(calendarClient.createEvent('oauth-token', noExtras))
      .rejects.toEqual(expect.objectContaining({
        constructor: UpstreamError,
        message: expect.stringContaining('rate limit'),
      }));

    mockInsert.mockRejectedValueOnce({ status: 403, errors: [{ reason: 'userRateLimitExceeded' }] });
    await expect(calendarClient.createEvent('oauth-token', noExtras))
      .rejects.toEqual(expect.objectContaining({ message: expect.stringContaining('rate limit') }));

    mockInsert.mockRejectedValueOnce(new Error('network failure'));
    await expect(calendarClient.createEvent('oauth-token', noExtras))
      .rejects.toEqual(expect.objectContaining({
        constructor: UpstreamError,
        message: 'Failed to create event in Google Calendar.',
      }));
  });
});

