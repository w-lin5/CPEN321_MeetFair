import { Types } from 'mongoose';
import request from 'supertest';
import { google } from 'googleapis';

import { createApp } from '../../src/app';
import { signSessionToken } from '../../src/common/session';
import { BadRequest, Unauthorized, UpstreamError } from '../../src/common/errors';
import { calendarService } from '../../src/integrations/google-calendar.service';
import { CalendarEventModel } from '../../src/integrations/calendar-event.model';
import { createGoogleCalendarEvent, fetchCalendarEvents } from '../../src/integrations/google-calendar.client';

jest.mock('../../src/integrations/google-calendar.client', () => ({
  createGoogleCalendarEvent: jest.fn(),
  fetchCalendarEvents: jest.fn(),
}));

jest.mock('../../src/integrations/calendar-event.model', () => ({
  CalendarEventModel: {
    bulkWrite: jest.fn(),
    create: jest.fn(),
    find: jest.fn(),
  },
}));

jest.mock('googleapis', () => ({
  google: {
    auth: { OAuth2: jest.fn() },
    calendar: jest.fn(),
  },
}));

const mockFetchEvents = jest.mocked(fetchCalendarEvents);
const mockCreateGoogleEvent = jest.mocked(createGoogleCalendarEvent);
const mockBulkWrite = jest.mocked(CalendarEventModel.bulkWrite);
const mockCreate = jest.mocked(CalendarEventModel.create);
const {
  createGoogleCalendarEvent: createGoogleCalendarEventDirectly,
  fetchCalendarEvents: fetchCalendarEventsDirectly,
} = jest.requireActual<typeof import('../../src/integrations/google-calendar.client')>(
  '../../src/integrations/google-calendar.client',
);
const mockOAuth2 = jest.mocked(google.auth.OAuth2);
const mockCalendar = jest.mocked(google.calendar);
const mockInsert = jest.fn();
const mockList = jest.fn();
const mockSetCredentials = jest.fn();

describe('Mocked calendar service', () => {
  const userId = new Types.ObjectId();

  beforeEach(() => jest.clearAllMocks());

  test('Syncs Google events with the authenticated user and preserves the unified event fields', async () => {
    const googleEvent = {
      externalId: 'google-event-1',
      title: 'Planning',
      description: 'Sprint planning',
      location: 'Room 5',
      startTime: new Date('2026-11-02T18:00:00Z'),
      endTime: new Date('2026-11-02T19:00:00Z'),
      timezone: 'America/Vancouver',
      recurrence: [],
      allDay: false,
      attendees: [{ email: 'alex@example.com', displayName: 'Alex', responseStatus: 'accepted' as const }],
      busy: true,
    };
    mockFetchEvents.mockResolvedValue([googleEvent]);
    mockBulkWrite.mockResolvedValue({ upsertedCount: 1, modifiedCount: 0 } as never);

    await expect(calendarService.syncCalendar(userId, 'google-access-token'))
      .resolves.toEqual({ upserted: 1, total: 1 });

    expect(mockFetchEvents).toHaveBeenCalledWith('google-access-token');
    expect(mockBulkWrite).toHaveBeenCalledWith([
      expect.objectContaining({
        updateOne: expect.objectContaining({
          filter: { userId, externalId: googleEvent.externalId },
          update: expect.objectContaining({
            $set: expect.objectContaining({
              title: googleEvent.title,
              description: googleEvent.description,
              location: googleEvent.location,
              timezone: googleEvent.timezone,
              attendees: googleEvent.attendees,
              busy: true,
            }),
            $setOnInsert: { source: 'google' },
          }),
          upsert: true,
        }),
      }),
    ], { ordered: false });
  });

  test('Does not write when Google returns no events', async () => {
    mockFetchEvents.mockResolvedValue([]);

    await expect(calendarService.syncCalendar(userId, 'google-access-token'))
      .resolves.toEqual({ upserted: 0, total: 0 });
    expect(mockBulkWrite).not.toHaveBeenCalled();
  });

  test('Creates an app-originated CalendarEvent after Google returns its external ID', async () => {
    mockCreateGoogleEvent.mockResolvedValue({ externalId: 'google-created-1', htmlLink: null });
    const savedEvent = { id: 'app-event-1', externalId: 'google-created-1', source: 'app' };
    mockCreate.mockResolvedValue(savedEvent as never);
    const input = {
      accessToken: 'google-access-token',
      title: 'Study session',
      description: 'Review notes',
      startTime: '2026-11-02T18:00:00Z',
      endTime: '2026-11-02T19:00:00Z',
      timezone: 'America/Vancouver',
      location: 'Library',
      attendeeEmails: ['alex@example.com'],
    };

    await expect(calendarService.addCalendarEvent(userId, input)).resolves.toBe(savedEvent);

    expect(mockCreateGoogleEvent).toHaveBeenCalledWith(input.accessToken, input);
    expect(mockCreate).toHaveBeenCalledWith(expect.objectContaining({
      userId,
      externalId: 'google-created-1',
      source: 'app',
      title: input.title,
      startTime: new Date(input.startTime),
      endTime: new Date(input.endTime),
      timezone: input.timezone,
      attendees: [{ email: 'alex@example.com', responseStatus: 'needsAction' }],
    }));
  });

  test('Rejects an event whose end is not after its start before calling Google', async () => {
    await expect(calendarService.addCalendarEvent(userId, {
      accessToken: 'google-access-token',
      title: 'Invalid event',
      description: '',
      startTime: '2026-11-02T19:00:00Z',
      endTime: '2026-11-02T18:00:00Z',
      timezone: 'UTC',
      location: '',
      attendeeEmails: [],
    })).rejects.toMatchObject({ status: 400 });

    expect(mockCreateGoogleEvent).not.toHaveBeenCalled();
    expect(mockCreate).not.toHaveBeenCalled();
  });

  test('Authenticates, parses the request, calls the service, and returns the created event', async () => {
    const userId = new Types.ObjectId().toString();
    const token = signSessionToken(userId);
    const createdEvent = {
      id: 'app-event-1',
      externalId: 'google-created-1',
      source: 'app',
      title: 'Study session',
      startTime: '2026-11-02T18:00:00.000Z',
      endTime: '2026-11-02T19:00:00.000Z',
      timezone: 'America/Vancouver',
    };
    const addEvent = jest.spyOn(calendarService, 'addCalendarEvent')
      .mockResolvedValue(createdEvent as never);
    const input = {
      accessToken: 'google-access-token',
      title: 'Study session',
      description: 'Review notes',
      startTime: '2026-11-02T18:00:00Z',
      endTime: '2026-11-02T19:00:00Z',
      timezone: 'America/Vancouver',
      location: 'Library',
      attendeeEmails: ['alex@example.com'],
    };

    const response = await request(createApp())
      .post('/api/integrations/google-calendar/events')
      .set('Authorization', `Bearer ${token}`)
      .send(input);

    expect(response.status).toBe(201);
    expect(response.body).toEqual(createdEvent);
    expect(addEvent).toHaveBeenCalledWith(userId, input);
  });
});

describe('Mocked Google Calendar API client', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockOAuth2.mockImplementation(() => ({ setCredentials: mockSetCredentials }) as never);
    mockCalendar.mockReturnValue({
      events: { insert: mockInsert, list: mockList },
    } as never);
  });

  test('Sends event fields, timezone, attendees, and update notifications to Google', async () => {
    mockInsert.mockResolvedValue({
      data: { id: 'google-event-123', htmlLink: 'https://calendar.google.com/event/123' },
    });
    const event = {
      title: 'Design review',
      description: 'Review the new flow',
      location: 'Meeting room 4',
      startTime: '2026-11-02T10:00:00-08:00',
      endTime: '2026-11-02T11:00:00-08:00',
      timezone: 'America/Vancouver',
      attendeeEmails: ['alex@example.com', 'sam@example.com'],
    };

    await expect(createGoogleCalendarEventDirectly('oauth-token', event)).resolves.toEqual({
      externalId: 'google-event-123',
      htmlLink: 'https://calendar.google.com/event/123',
    });

    expect(mockSetCredentials).toHaveBeenCalledWith({ access_token: 'oauth-token' });
    expect(mockCalendar).toHaveBeenCalledWith({ version: 'v3', auth: expect.anything() });
    expect(mockInsert).toHaveBeenCalledWith({
      calendarId: 'primary',
      requestBody: {
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

  test('Omits empty optional fields and skips attendee notifications when there are no attendees', async () => {
    mockInsert.mockResolvedValue({ data: { id: 'google-event-456' } });

    await createGoogleCalendarEventDirectly('oauth-token', {
      title: 'Focus time',
      description: '',
      location: '',
      startTime: '2026-11-02T10:00:00Z',
      endTime: '2026-11-02T11:00:00Z',
      timezone: 'UTC',
      attendeeEmails: [],
    });

    expect(mockInsert).toHaveBeenCalledWith(expect.objectContaining({
      requestBody: expect.objectContaining({
        summary: 'Focus time',
        start: { dateTime: '2026-11-02T10:00:00Z', timeZone: 'UTC' },
        end: { dateTime: '2026-11-02T11:00:00Z', timeZone: 'UTC' },
      }),
      sendUpdates: 'none',
    }));
    const requestBody = mockInsert.mock.calls[0]?.[0].requestBody;
    expect(requestBody).not.toHaveProperty('description');
    expect(requestBody).not.toHaveProperty('location');
  });

  test('Normalizes Google events and skips canceled events', async () => {
    mockList.mockResolvedValue({
      data: {
        items: [
          {
            id: 'imported-1',
            summary: 'Imported meeting',
            description: 'Agenda',
            location: 'Online',
            start: { dateTime: '2026-11-02T10:00:00-08:00', timeZone: 'America/Vancouver' },
            end: { dateTime: '2026-11-02T11:00:00-08:00', timeZone: 'America/Vancouver' },
            attendees: [{ email: 'alex@example.com', displayName: 'Alex', responseStatus: 'accepted' }],
          },
          { id: 'canceled-1', status: 'cancelled' },
        ],
      },
    });

    const events = await fetchCalendarEventsDirectly('oauth-token');

    expect(events).toHaveLength(1);
    expect(events[0]).toEqual(expect.objectContaining({
      externalId: 'imported-1',
      title: 'Imported meeting',
      description: 'Agenda',
      location: 'Online',
      startTime: new Date('2026-11-02T18:00:00Z'),
      endTime: new Date('2026-11-02T19:00:00Z'),
      timezone: 'America/Vancouver',
      allDay: false,
      busy: true,
      attendees: [{ email: 'alex@example.com', displayName: 'Alex', responseStatus: 'accepted' }],
    }));
  });

  test('Rejects imported events that are missing start or end timestamps', async () => {
    mockList.mockResolvedValue({ data: { items: [{ id: 'incomplete-1', summary: 'Missing dates' }] } });

    await expect(fetchCalendarEventsDirectly('oauth-token')).rejects.toBeInstanceOf(BadRequest);
  });

  test('Normalizes all-day events and marks transparent events as not busy', async () => {
    mockList.mockResolvedValue({
      data: {
        items: [{
          id: 'all-day-1',
          transparency: 'transparent',
          start: { date: '2026-11-02' },
          end: { date: '2026-11-03' },
        }],
      },
    });

    const events = await fetchCalendarEventsDirectly('oauth-token');

    expect(events[0]).toEqual(expect.objectContaining({
      externalId: 'all-day-1',
      startTime: new Date('2026-11-02T00:00:00Z'),
      endTime: new Date('2026-11-03T00:00:00Z'),
      allDay: true,
      busy: false,
    }));
  });

  test('Maps invalid Google credentials to Unauthorized', async () => {
    mockInsert.mockRejectedValue({ status: 401 });

    await expect(createGoogleCalendarEventDirectly('revoked-token', {
      title: 'Meeting',
      description: '',
      location: '',
      startTime: '2026-11-02T10:00:00Z',
      endTime: '2026-11-02T11:00:00Z',
      timezone: 'UTC',
      attendeeEmails: [],
    })).rejects.toBeInstanceOf(Unauthorized);
  });

  test('Maps Google rate limits and unexpected create failures to UpstreamError', async () => {
    const event = {
      title: 'Meeting',
      description: '',
      location: '',
      startTime: '2026-11-02T10:00:00Z',
      endTime: '2026-11-02T11:00:00Z',
      timezone: 'UTC',
      attendeeEmails: [],
    };
    mockInsert.mockRejectedValueOnce({ status: 429 });
    await expect(createGoogleCalendarEventDirectly('oauth-token', event))
      .rejects.toEqual(expect.objectContaining({
        constructor: UpstreamError,
        message: expect.stringContaining('rate limit'),
      }));

    mockInsert.mockRejectedValueOnce(new Error('network failure'));
    await expect(createGoogleCalendarEventDirectly('oauth-token', event))
      .rejects.toEqual(expect.objectContaining({
        constructor: UpstreamError,
        message: 'Failed to create event in Google Calendar.',
      }));
  });
});
