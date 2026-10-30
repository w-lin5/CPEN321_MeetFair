import request from 'supertest';

import { createApp } from '../../src/app';
import { setTransport } from '../../src/realtime/publisher';
import { recommendationService } from '../../src/recommendation/recommendation.service';
import { MeetupModel } from '../../src/users-groups/models';
import { bearer } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import {
  CANDIDATES,
  MISSING_ID,
  addMember,
  createGroup,
  createMeetup,
  createUserN,
  createVotingMeetup,
} from '../helpers/factories';

beforeAll(startDb);
afterEach(async () => {
  setTransport(null);
  await clearDb();
});
afterAll(stopDb);

async function setup() {
  const organizer = await createUserN(1);
  const member = await createUserN(2);
  const group = await createGroup(organizer.id);
  await addMember(group.id, member.id);
  const meetup = await createMeetup(group.id, organizer.id);
  return { organizer, member, group, meetup };
}

// Interface recommendationService.generate
// Exercise internal persistence without calling Google or exposing manual HTTP input.
describe('Unmocked: server-selected recommendation persistence', () => {
  // Input: organizer generates recommendations from 3 candidates for a SETUP meetup
  // Expected behavior: the candidates are stored, the meetup moves to VOTING, and "recommendations:ready" then
  //   "meetup:status" (VOTING) are published to the meetup room
  // Expected output: recommendation whose candidates are the 3 supplied places
  test('saves candidates, starts voting and publishes events', async () => {
    const { organizer, meetup } = await setup();
    const published: { room: string; event: string; payload: unknown }[] = [];
    setTransport((room, event, payload) => published.push({ room, event, payload }));
    const result = await recommendationService.generate(meetup.id, organizer.id, CANDIDATES);
    expect(result.candidates.map(c => c.placeId)).toEqual(CANDIDATES.map(c => c.placeId));
    expect((await MeetupModel.findById(meetup.id).orFail()).status).toBe('VOTING');
    expect(published).toEqual([
      { room: `meetup:${meetup.id}`, event: 'recommendations:ready', payload: { meetupId: meetup.id } },
      { room: `meetup:${meetup.id}`, event: 'meetup:status', payload: { meetupId: meetup.id, status: 'VOTING' } },
    ]);
  });

  // Input: non-organizer member generates; unknown meetup id
  // Expected behavior: rejected with status 403; rejected with status 404; the meetup stays in SETUP
  // Expected output: None
  test('rejects non-organizers and missing meetups', async () => {
    const { member, meetup } = await setup();
    await expect(recommendationService.generate(meetup.id, member.id, CANDIDATES)).rejects.toMatchObject({ status: 403 });
    await expect(recommendationService.generate(MISSING_ID, member.id, CANDIDATES)).rejects.toMatchObject({ status: 404 });
    expect((await MeetupModel.findById(meetup.id).orFail()).status).toBe('SETUP');
  });

  // Input: organizer generates twice for the same meetup
  // Expected behavior: the second call is rejected with status 409
  // Expected output: None
  test('rejects a second generation', async () => {
    const { organizer, meetup } = await setup();
    await recommendationService.generate(meetup.id, organizer.id, CANDIDATES);
    await expect(recommendationService.generate(meetup.id, organizer.id, CANDIDATES)).rejects.toMatchObject({ status: 409 });
  });
});

// Interface GET /api/meetups/:meetupId/recommendations
describe('Unmocked: GET /api/meetups/:meetupId/recommendations', () => {
  // Input: member of a meetup in VOTING
  // Expected status code: 200
  // Expected output: the stored candidates
  test('Member loads recommendations', async () => {
    const { organizer, member, group } = await setup();
    const meetup = await createVotingMeetup(group.id, organizer.id, [organizer.id]);

    const res = await request(createApp())
      .get(`/api/meetups/${meetup.id}/recommendations`)
      .set('Authorization', bearer(member.id));

    expect(res.status).toBe(200);
    expect(res.body.candidates[0]).toMatchObject(CANDIDATES[0] ?? {});
  });

  // Input: meetup still in SETUP (nothing generated); outsider
  // Expected status code: 404; 403
  test('Not generated yet and outsider', async () => {
    const { organizer, meetup } = await setup();
    const outsider = await createUserN(3);
    const get = (userId: string) => request(createApp())
      .get(`/api/meetups/${meetup.id}/recommendations`)
      .set('Authorization', bearer(userId));

    expect((await get(organizer.id)).status).toBe(404);
    expect((await get(outsider.id)).status).toBe(403);
  });
});
