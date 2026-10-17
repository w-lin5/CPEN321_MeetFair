import { ValidationError } from '../common/errors';
import { type Parser, fields, isoDate, optionalText, text } from '../common/middleware/validate';
import { tuning } from '../config';
import type { CreateGoogleCalendarEventInput } from './google-calendar.client';

export const syncCalendarBody: Parser<{ accessToken: string }> = (data) => ({
  accessToken: text(fields(data), 'accessToken', tuning.oauthAccessTokenMaxLength),
});

export type CreateCalendarEventInput = CreateGoogleCalendarEventInput & { accessToken: string };

export const createCalendarEventBody: Parser<CreateCalendarEventInput> = (data) => {
  const f = fields(data);
  const attendeeEmails = f.get('attendeeEmails') ?? [];
  if (!Array.isArray(attendeeEmails) || attendeeEmails.length > 100
    || attendeeEmails.some((email) => typeof email !== 'string'
      || email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email))) {
    throw new ValidationError(['attendeeEmails must be an array of valid email addresses']);
  }
  return {
    accessToken: text(f, 'accessToken', tuning.oauthAccessTokenMaxLength),
    title: text(f, 'title', 1024),
    description: optionalText(f, 'description', 8192),
    startTime: isoDate(f, 'startTime'),
    endTime: isoDate(f, 'endTime'),
    timezone: text(f, 'timezone', 128),
    location: optionalText(f, 'location', 1024),
    attendeeEmails,
  };
};

