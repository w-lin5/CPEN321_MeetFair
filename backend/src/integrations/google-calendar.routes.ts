import { Router } from 'express';

import { getUserId } from '../common/middleware/require-auth';
import { parseInput } from '../common/middleware/validate';
import { calendarService } from './google-calendar.service';
import { createCalendarEventBody, syncCalendarBody } from './google-calendar.schemas';

export const googleCalendarRouter = Router();

// Fetch events from the user's primary Google Calendar and upsert them locally.
// The client must supply a valid Google OAuth access token with the
// https://www.googleapis.com/auth/calendar.events scope.
googleCalendarRouter.post('/sync', async (req, res) => {
  const userId = getUserId(req);
  const { accessToken } = parseInput(syncCalendarBody, req.body);

  const result = await calendarService.syncCalendar(userId, accessToken);

  res.status(200).json({
    message: `Synced ${result.upserted} of ${result.total} calendar events.`,
    upserted: result.upserted,
    total: result.total,
  });
});

// Return all calendar events stored locally for the authenticated user.
googleCalendarRouter.get('/events', async (req, res) => {
  const userId = getUserId(req);
  const events = await calendarService.getEventsForUser(userId);
  res.json(events);
});


googleCalendarRouter.post('/events', async (req, res) => {
  const userId = getUserId(req);
  const input = parseInput(createCalendarEventBody, req.body);
  const event = await calendarService.addCalendarEvent(userId, input);
  res.status(201).json(event);
});
