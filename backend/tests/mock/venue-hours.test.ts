import request from 'supertest';

import { createApp } from '../../src/app';
import * as routes from '../../src/integrations/routes.client';
import { RecommendationModel } from '../../src/recommendation/models';
import { MeetupModel } from '../../src/users-groups/models';
import { VoteModel } from '../../src/voting-fairness/models';
import { bearer } from '../helpers/auth';
import { freezeDate } from '../helpers/clock';
import { clearDb, startDb, stopDb } from '../helpers/db';
import { addMember, addParticipants, createGroup, createMeetup, createUserN } from '../helpers/factories';

// Reproduce the reported 01:03 Vancouver scenario without relying on today's live Google data.
const scheduledAt = new Date('2025-02-04T09:03:00Z');
const daytimeRestaurant = {
  id: 'daytime-restaurant', displayName: { text: 'Ding Tai Feng (test fixture)' },
  formattedAddress: '1132 Alberni St, Vancouver',
  location: { latitude: 49.286, longitude: -123.124 }, types: ['restaurant'],
  businessStatus: 'OPERATIONAL', timeZone: { id: 'America/Vancouver' },
  regularOpeningHours: { periods: [{ open: { day: 2, hour: 9 }, close: { day: 2, hour: 22 } }] },
};
const openPlaces = [1, 2, 3].map(number => ({ ...daytimeRestaurant, id: `open-${number}`,
  displayName: { text: `24-hour venue ${number}` },
  regularOpeningHours: { periods: [{ open: { day: 0, hour: 0, minute: 0 } }] },
}));
const app = createApp();
const originalKey = process.env.GOOGLE_PLACES_API_KEY;
beforeAll(startDb);
beforeEach(() => {
  // The fixture is a future meetup relative to this controlled clock, in any execution year.
  freezeDate(new Date('2025-02-03T20:00:00Z'));
  process.env.GOOGLE_PLACES_API_KEY = 'test-places-key';
});
afterEach(async () => {
  jest.useRealTimers();
  jest.restoreAllMocks();
  if (originalKey === undefined) delete process.env.GOOGLE_PLACES_API_KEY;
  else process.env.GOOGLE_PLACES_API_KEY = originalKey;
  await clearDb();
});
afterAll(stopDb);

async function setup() {
  const organizer = await createUserN(1);
  const member = await createUserN(2);
  const group = await createGroup(organizer.id);
  await addMember(group.id, member.id);
  const meetup = await createMeetup(group.id, organizer.id, { scheduledAt });
  await addParticipants(meetup.id, [organizer.id, member.id]);
  const travel = jest.spyOn(routes, 'travelSeconds').mockImplementation(async (origins, destinations) =>
    origins.map(() => destinations.map(() => 600)));
  const fetchMock = jest.spyOn(globalThis, 'fetch');
  return { organizer, member, meetup, travel, fetchMock };
}

test('a closed restaurant is excluded before scoring and neither voting nor finalization can select it', async () => {
  const { organizer, member, meetup, travel, fetchMock } = await setup();
  fetchMock.mockResolvedValue(new Response(JSON.stringify({ places: [daytimeRestaurant, ...openPlaces] })));
  const generated = await request(app).post(`/api/meetups/${meetup.id}/recommendations/from-places`)
    .set('Authorization', bearer(organizer.id));
  expect(generated.status).toBe(201);
  expect(generated.body.candidates.map((place: { placeId: string }) => place.placeId)).toEqual(['open-1', 'open-2', 'open-3']);
  expect(travel.mock.calls[0]?.[1]).toHaveLength(3);
  expect(await RecommendationModel.findOne({ meetupId: meetup.id }).lean()).toMatchObject({
    candidates: generated.body.candidates.map((place: { placeId: string }) => expect.objectContaining({ placeId: place.placeId })),
  });

  for (const user of [organizer, member]) {
    const vote = await request(app).put(`/api/meetups/${meetup.id}/vote`)
      .set('Authorization', bearer(user.id)).send({ placeId: daytimeRestaurant.id });
    expect(vote.status).toBe(400);
    expect(vote.body).toEqual({ error: "placeId is not one of this meetup's candidates" });
  }
  expect(await VoteModel.countDocuments()).toBe(0);
  const rejectedWinner = await request(app).post(`/api/meetups/${meetup.id}/finalize`)
    .set('Authorization', bearer(organizer.id)).send({ placeId: daytimeRestaurant.id });
  expect(rejectedWinner.status).toBe(400);
  expect(rejectedWinner.body).toEqual({ error: "placeId is not one of this meetup's candidates" });
  expect((await MeetupModel.findById(meetup.id).orFail()).status).toBe('VOTING');

  for (const user of [organizer, member]) {
    expect((await request(app).put(`/api/meetups/${meetup.id}/vote`)
      .set('Authorization', bearer(user.id)).send({ placeId: 'open-1' })).status).toBe(200);
    expect((await request(app).post(`/api/meetups/${meetup.id}/vote/lock`)
      .set('Authorization', bearer(user.id))).status).toBe(200);
  }
  const finalized = await request(app).post(`/api/meetups/${meetup.id}/finalize`)
    .set('Authorization', bearer(organizer.id));
  expect(finalized.status).toBe(200);
  expect(finalized.body).toMatchObject({ status: 'FINALIZED', winner: { placeId: 'open-1' } });
});

test('insufficient confirmed-open venues leave the meetup in SETUP with a clear error', async () => {
  const { organizer, meetup, travel, fetchMock } = await setup();
  fetchMock.mockResolvedValue(new Response(JSON.stringify({ places: [daytimeRestaurant, ...openPlaces.slice(0, 2)] })));
  const generated = await request(app).post(`/api/meetups/${meetup.id}/recommendations/from-places`)
    .set('Authorization', bearer(organizer.id));
  expect(generated.status).toBe(400);
  expect(generated.body.error).toContain('Fewer than 3 nearby places could be confirmed open at the meetup time');
  expect((await MeetupModel.findById(meetup.id).orFail()).status).toBe('SETUP');
  expect(await RecommendationModel.countDocuments()).toBe(0);
  expect(travel).not.toHaveBeenCalled();
});
