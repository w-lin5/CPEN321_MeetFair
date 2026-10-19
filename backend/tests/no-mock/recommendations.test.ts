import request from 'supertest';

import { createApp } from '../../src/app';
import { setTransport } from '../../src/realtime/publisher';
import { RecommendationModel } from '../../src/recommendation/models';
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

function generate(meetupId: string, userId: string, body: object = { candidates: CANDIDATES }) {
  return request(createApp())
    .post(`/api/meetups/${meetupId}/recommendations`)
    .set('Authorization', bearer(userId))
    .send(body);
}

// Interface POST /api/meetups/:meetupId/recommendations
describe('Unmocked: POST /api/meetups/:meetupId/recommendations', () => {
  // Input: organizer of a SETUP meetup with three distinct candidates
  // Expected status code: 201
  // Expected behavior: candidates are stored, the meetup moves to VOTING, recommendations:ready and
  //                    meetup:status are published to the meetup's room
  // Expected output: the recommendation with the candidates in order
  test('Organizer generates recommendations', async () => {
    const { organizer, meetup } = await setup();
    const published: { room: string; event: string; payload: unknown }[] = [];
    setTransport((room, event, payload) => published.push({ room, event, payload }));

    const res = await generate(meetup.id, organizer.id);

    expect(res.status).toBe(201);
    expect(res.body.meetupId).toBe(meetup.id);
    expect(res.body.candidates.map((c: { placeId: string }) => c.placeId)).toEqual(['place-1', 'place-2', 'place-3']);
    expect((await MeetupModel.findById(meetup.id).orFail()).status).toBe('VOTING');
    expect(published).toEqual([
      { room: `meetup:${meetup.id}`, event: 'recommendations:ready', payload: { meetupId: meetup.id } },
      { room: `meetup:${meetup.id}`, event: 'meetup:status', payload: { meetupId: meetup.id, status: 'VOTING' } },
    ]);
  });

  // Input: a group member who is not the organizer
  // Expected status code: 403
  // Expected behavior: the meetup stays in SETUP
  test('Non-organizer member', async () => {
    const { member, meetup } = await setup();

    expect((await generate(meetup.id, member.id)).status).toBe(403);
    expect((await MeetupModel.findById(meetup.id).orFail()).status).toBe('SETUP');
  });

  // Input: user outside the group; missing meetup
  // Expected status code: 403; 404
  test('Outsider and missing meetup', async () => {
    const { meetup } = await setup();
    const outsider = await createUserN(3);

    expect((await generate(meetup.id, outsider.id)).status).toBe(403);
    expect((await generate(MISSING_ID, outsider.id)).status).toBe(404);
  });

  // Input: meetup already in VOTING
  // Expected status code: 409
  test('Meetup no longer in SETUP', async () => {
    const { organizer, group } = await setup();
    const voting = await createVotingMeetup(group.id, organizer.id, [organizer.id]);

    expect((await generate(voting.id, organizer.id)).status).toBe(409);
  });

  // Input: wrong number of candidates, duplicate placeIds, bad coordinates
  // Expected status code: 400
  // Expected behavior: nothing is stored and the meetup stays in SETUP
  test.each([
    ['two candidates', { candidates: CANDIDATES.slice(0, 2) }],
    ['duplicate placeId', { candidates: [CANDIDATES[0], CANDIDATES[0], CANDIDATES[1]] }],
    ['bad latitude', { candidates: [{ ...CANDIDATES[0], location: { lat: 200, lng: 0 } }, CANDIDATES[1], CANDIDATES[2]] }],
  ])('Rejects %s', async (_label, body) => {
    const { organizer, meetup } = await setup();

    expect((await generate(meetup.id, organizer.id, body)).status).toBe(400);
    expect(await RecommendationModel.countDocuments()).toBe(0);
  });

  // Input: no Authorization header
  // Expected status code: 401
  test('Unauthenticated', async () => {
    expect((await request(createApp()).post(`/api/meetups/${MISSING_ID}/recommendations`)).status).toBe(401);
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
