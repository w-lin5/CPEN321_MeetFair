import request, { type Test } from 'supertest';

import { createApp } from '../../src/app';
import { MeetupModel } from '../../src/users-groups/models';
import { bearer } from '../helpers/auth';
import { expectApiError } from '../helpers/http';
import { clearDb, startDb, stopDb } from '../helpers/db';
import {
  MISSING_ID,
  addMember,
  createFinalizedMeetup,
  createGroup,
  createMeetup,
  createUserN,
} from '../helpers/factories';

beforeAll(startDb);
afterEach(clearDb);
afterAll(stopDb);

const BODY = { accessToken: 'google-access-token' };

function post(meetupId: string, userId?: string, body: object = BODY): Test {
  const req = request(createApp()).post(`/api/meetups/${meetupId}/calendar-event`);
  return (userId === undefined ? req : req.set('Authorization', bearer(userId))).send(body);
}

// Interface POST /api/meetups/:meetupId/calendar-event
// Every case here is rejected before Google is called; success paths need a real Google token (see mock tests)
describe('Unmocked: POST /api/meetups/:meetupId/calendar-event', () => {
  // Input: no Authorization header
  // Expected status code: 401
  test('Requires authentication', async () => {
    const res = await post(MISSING_ID);
    expectApiError(res, 401, 'Missing bearer token');
  });

  // Input: missing accessToken, then a malformed meetup id
  // Expected status code: 400
  // Expected output: { error, details }
  test('Rejects invalid input', async () => {
    const user = await createUserN(1);
    const group = await createGroup(user.id);
    const meetup = await createFinalizedMeetup(group.id, user.id, [user.id]);

    const noToken = await post(meetup.id, user.id, {});
    const badId = await post('not-an-id', user.id);

    expectApiError(noToken, 400, 'Invalid request', ['accessToken must be a non-empty string of at most 2048 characters']);
    expectApiError(badId, 400, 'Invalid request', ['meetupId must be a 24-hex ObjectId']);
    expect((await MeetupModel.findById(meetup.id).orFail()).participants[0]?.calendarEventId).toBeUndefined();
  });

  // Input: meetup id that does not exist
  // Expected status code: 404
  test('Missing meetup', async () => {
    const user = await createUserN(1);
    expectApiError(await post(MISSING_ID, user.id), 404, 'Meetup not found');
  });

  // Input: user outside the meetup's group
  // Expected status code: 403
  test('Non-member', async () => {
    const owner = await createUserN(1);
    const outsider = await createUserN(2);
    const group = await createGroup(owner.id);
    const meetup = await createFinalizedMeetup(group.id, owner.id, [owner.id]);

    expectApiError(await post(meetup.id, outsider.id), 403, 'Not a member of this group');
  });

  // Input: meetup still in SETUP
  // Expected status code: 409
  // Expected output: { error: "Only a finalized meetup can be added to a calendar" }
  test('Meetup not finalized', async () => {
    const user = await createUserN(1);
    const group = await createGroup(user.id);
    const meetup = await createMeetup(group.id, user.id);

    const res = await post(meetup.id, user.id);

    expect(res.status).toBe(409);
    expect(res.body).toEqual({ error: 'Only a finalized meetup can be added to a calendar' });
  });

  // Input: group member who never submitted participation details
  // Expected status code: 403
  test('Member who is not a participant', async () => {
    const owner = await createUserN(1);
    const member = await createUserN(2);
    const group = await createGroup(owner.id);
    await addMember(group.id, member.id);
    const meetup = await createFinalizedMeetup(group.id, owner.id, [owner.id]);

    expectApiError(await post(meetup.id, member.id), 403, 'Only participants can do this');
  });

  // Input: participant who already added the meetup
  // Expected status code: 409
  // Expected output: { error: "This meetup is already in your calendar" }
  test('Already added', async () => {
    const user = await createUserN(1);
    const group = await createGroup(user.id);
    const meetup = await createFinalizedMeetup(group.id, user.id, [user.id]);
    await MeetupModel.updateOne({ _id: meetup._id }, { $set: { 'participants.0.calendarEventId': 'existing' } });

    const res = await post(meetup.id, user.id);

    expect(res.status).toBe(409);
    expect(res.body).toEqual({ error: 'This meetup is already in your calendar' });
  });
});

