import request, { type Test } from 'supertest';

import { createApp } from '../../src/app';
import { RecommendationModel } from '../../src/recommendation/models';
import { GroupModel, MeetupModel } from '../../src/users-groups/models';
import { FairCreditModel, VoteModel } from '../../src/voting-fairness/models';
import { bearer } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import { PLACE_1, createGroup, createUserN, createVotingMeetup } from '../helpers/factories';

beforeAll(startDb);
afterEach(async () => {
  jest.restoreAllMocks();
  await clearDb();
});
afterAll(stopDb);

// Silences the error handler's logging for tests that expect a 500
function quietConsole() {
  jest.spyOn(console, 'error').mockImplementation(() => undefined);
}

// A group whose organizer voted in a VOTING meetup and has Fair Credit
async function setup() {
  const organizer = await createUserN(1);
  const group = await createGroup(organizer.id);
  const meetup = await createVotingMeetup(group.id, organizer.id, [organizer.id]);
  await VoteModel.create({ meetupId: meetup.id, userId: organizer.id, placeId: PLACE_1 });
  await FairCreditModel.create({ groupId: group.id, userId: organizer.id, balance: 30 });
  return { organizer, group, meetup };
}

function del(path: string, userId: string): Test {
  return request(createApp()).delete(`/api/${path}`).set('Authorization', bearer(userId));
}

// Interface DELETE /api/meetups/:meetupId
describe('Mocked: DELETE /api/meetups/:meetupId', () => {
  // Mocked behavior: the delete matches nothing, as if another request deleted the meetup a moment earlier
  // Input: organizer deletes the meetup
  // Expected status code: 404
  // Expected behavior: no cleanup runs: the meetup's votes and recommendation are untouched
  // Expected output: { error: "Meetup not found" }
  test('Meetup deleted concurrently', async () => {
    const { organizer, meetup } = await setup();
    jest.spyOn(MeetupModel, 'deleteOne').mockResolvedValue({ deletedCount: 0 } as never);

    const res = await del(`meetups/${meetup.id}`, organizer.id);

    expect(res.status).toBe(404);
    expect(res.body).toEqual({ error: 'Meetup not found' });
    expect(await VoteModel.countDocuments()).toBe(1);
    expect(await RecommendationModel.countDocuments()).toBe(1);
  });

  // Mocked behavior: deleting the meetup's recommendation rejects
  // Input: organizer deletes the meetup
  // Expected status code: 500
  // Expected behavior: the meetup itself is already gone, so its leftover data cannot be reached any more
  // Expected output: { error: "Internal Server Error" }
  test('Database failure during cleanup', async () => {
    quietConsole();
    const { organizer, meetup } = await setup();
    jest.spyOn(RecommendationModel, 'deleteMany').mockRejectedValue(new Error('db down') as never);

    const res = await del(`meetups/${meetup.id}`, organizer.id);

    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'Internal Server Error' });
    expect(await MeetupModel.countDocuments()).toBe(0);
  });
});

// Interface DELETE /api/groups/:groupId
describe('Mocked: DELETE /api/groups/:groupId', () => {
  // Mocked behavior: the delete matches nothing, as if another request deleted the group a moment earlier
  // Input: organizer deletes the group
  // Expected status code: 404
  // Expected behavior: no cleanup runs: the group's meetup and Fair Credit are untouched
  // Expected output: { error: "Group not found" }
  test('Group deleted concurrently', async () => {
    const { organizer, group } = await setup();
    jest.spyOn(GroupModel, 'deleteOne').mockResolvedValue({ deletedCount: 0 } as never);

    const res = await del(`groups/${group.id}`, organizer.id);

    expect(res.status).toBe(404);
    expect(res.body).toEqual({ error: 'Group not found' });
    expect(await MeetupModel.countDocuments()).toBe(1);
    expect(await FairCreditModel.countDocuments()).toBe(1);
  });

  // Mocked behavior: deleting the group's Fair Credit balances rejects
  // Input: organizer deletes the group
  // Expected status code: 500
  // Expected behavior: the group, its meetups and their votes are already gone
  // Expected output: { error: "Internal Server Error" }
  test('Database failure during cleanup', async () => {
    quietConsole();
    const { organizer, group } = await setup();
    jest.spyOn(FairCreditModel, 'deleteMany').mockRejectedValue(new Error('db down') as never);

    const res = await del(`groups/${group.id}`, organizer.id);

    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'Internal Server Error' });
    expect(await GroupModel.countDocuments()).toBe(0);
    expect(await MeetupModel.countDocuments()).toBe(0);
    expect(await VoteModel.countDocuments()).toBe(0);
  });
});
