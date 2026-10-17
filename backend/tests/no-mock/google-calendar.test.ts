import mongoose, { Types } from 'mongoose';
import request from 'supertest';

import { createApp } from '../../src/app';
import { signSessionToken } from '../../src/common/session';
import { env } from '../../src/config';
import { CalendarEventModel } from '../../src/integrations/calendar-event.model';
import { createGoogleCalendarEvent } from '../../src/integrations/google-calendar.client';

jest.mock('../../src/integrations/google-calendar.client', () => ({
  createGoogleCalendarEvent: jest.fn(),
  fetchCalendarEvents: jest.fn(),
}));

const mockCreateGoogleCalendarEvent = jest.mocked(createGoogleCalendarEvent);

describe('Calendar event route integration with MongoDB', () => {
  const userId = new Types.ObjectId();
  const otherUserId = new Types.ObjectId();
  const createEventUserId = new Types.ObjectId();
  const token = signSessionToken(userId.toString());
  const app = createApp();
  let dbConnected = false;

  beforeAll(async () => {
    await mongoose.connect(env.mongoUri, { serverSelectionTimeoutMS: 5000 });
    dbConnected = true;
  }, 10000);

  afterAll(async () => {
    if (dbConnected) {
      await CalendarEventModel.deleteMany({ userId: { $in: [userId, otherUserId, createEventUserId] } });
    }
    await mongoose.disconnect();
  }, 10000);

  test('Requires an application session to list calendar events', async () => {
    const response = await request(app)
      .get('/api/integrations/google-calendar/events');

    expect(response.status).toBe(401);
    expect(response.body).toEqual({ error: 'Missing bearer token' });
  });

  test('Returns persisted current CalendarEvent records only for the signed-in user', async () => {
    const ownEvent = await CalendarEventModel.create({
      userId,
      externalId: 'unmocked-event-own',
      source: 'google',
      title: 'My event',
      startTime: new Date('2026-11-02T17:00:00Z'),
      endTime: new Date('2026-11-02T18:00:00Z'),
      timezone: 'America/Vancouver',
    });
    await CalendarEventModel.create({
      userId: otherUserId,
      externalId: 'other-users-event',
      source: 'google',
      title: 'Private event',
      startTime: new Date('2026-11-02T16:00:00Z'),
      endTime: new Date('2026-11-02T17:00:00Z'),
      timezone: 'UTC',
    });

    const response = await request(app)
      .get('/api/integrations/google-calendar/events')
      .set('Authorization', `Bearer ${token}`);

    expect(response.status).toBe(200);
    expect(response.body).toHaveLength(1);
    expect(response.body.map((event: { id: string }) => event.id)).toEqual([
      ownEvent.id,
    ]);
    expect(response.body[0]).toEqual(expect.objectContaining({
      externalId: 'unmocked-event-own',
      source: 'google',
      title: 'My event',
      timezone: 'America/Vancouver',
      busy: true,
    }));
  });

  test('Creates an event through the route and persists the Google-linked CalendarEvent', async () => {
    mockCreateGoogleCalendarEvent.mockResolvedValue({
      externalId: 'google-created-integration-test',
      htmlLink: 'https://calendar.google.com/event/test',
    });

    const response = await request(app)
      .post('/api/integrations/google-calendar/events')
      .set('Authorization', `Bearer ${signSessionToken(createEventUserId.toString())}`)
      .send({
        accessToken: 'test-google-token',
        title: 'Integration test meeting',
        description: 'Verify Mongo persistence',
        startTime: '2026-11-02T18:00:00Z',
        endTime: '2026-11-02T19:00:00Z',
        timezone: 'America/Vancouver',
        location: 'Test room',
        attendeeEmails: ['alex@example.com'],
      });

    expect(response.status).toBe(201);
    expect(response.body).toEqual(expect.objectContaining({
      externalId: 'google-created-integration-test',
      source: 'app',
      title: 'Integration test meeting',
      description: 'Verify Mongo persistence',
      location: 'Test room',
      startTime: '2026-11-02T18:00:00.000Z',
      endTime: '2026-11-02T19:00:00.000Z',
      timezone: 'America/Vancouver',
      attendees: [{ email: 'alex@example.com', displayName: null, responseStatus: 'needsAction' }],
    }));
    expect(mockCreateGoogleCalendarEvent).toHaveBeenCalledWith('test-google-token', expect.objectContaining({
      title: 'Integration test meeting',
      timezone: 'America/Vancouver',
    }));

    const storedEvent = await CalendarEventModel.findOne({
      userId: createEventUserId,
      externalId: 'google-created-integration-test',
    }).lean();
    expect(storedEvent).toEqual(expect.objectContaining({
      source: 'app',
      title: 'Integration test meeting',
      description: 'Verify Mongo persistence',
      location: 'Test room',
      timezone: 'America/Vancouver',
      busy: true,
    }));
    expect(storedEvent?.startTime.toISOString()).toBe('2026-11-02T18:00:00.000Z');
    expect(storedEvent?.endTime.toISOString()).toBe('2026-11-02T19:00:00.000Z');
  });

  test('Rejects an event with an end time before its start without calling Google', async () => {
    const response = await request(app)
      .post('/api/integrations/google-calendar/events')
      .set('Authorization', `Bearer ${token}`)
      .send({
        accessToken: 'not-used-for-invalid-event',
        title: 'Invalid event',
        description: '',
        startTime: '2026-11-02T19:00:00Z',
        endTime: '2026-11-02T18:00:00Z',
        timezone: 'UTC',
        location: '',
        attendeeEmails: [],
      });

    expect(response.status).toBe(400);
    expect(response.body).toEqual({ error: 'endTime must be after startTime.' });
    await expect(CalendarEventModel.countDocuments({ userId, title: 'Invalid event' })).resolves.toBe(0);
  });

  test('Validates the Google access token field on sync before making an upstream request', async () => {
    const response = await request(app)
      .post('/api/integrations/google-calendar/sync')
      .set('Authorization', `Bearer ${token}`)
      .send({});

    expect(response.status).toBe(400);
    expect(response.body).toEqual({
      error: 'Invalid request',
      details: ['accessToken must be a non-empty string of at most 2048 characters'],
    });
  });
});
