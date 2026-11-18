import request, { type Test } from 'supertest';

import { createApp } from '../../src/app';
import { GroupModel, MeetupModel } from '../../src/users-groups/models';
import { TripModel, VoteModel } from '../../src/voting-fairness/models';
import { bearer } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import { PLACE_1, addMember, addParticipants, createGroup, createMeetup, createUserN } from '../helpers/factories';

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

// A group of an organizer and a member who both entered details for a SETUP meetup
async function setup() {
  const organizer = await createUserN(1);
  const member = await createUserN(2);
  const group = await createGroup(organizer.id);
  await addMember(group.id, member.id);
  const meetup = await createMeetup(group.id, organizer.id);
  await addParticipants(meetup.id, [organizer.id, member.id]);
  return { organizer, member, group, meetup };
}

function del(path: string, userId: string): Test {
  return request(createApp()).delete(`/api/${path}`).set('Authorization', bearer(userId));
}

async function memberIds(groupId: string): Promise<string[]> {
  return (await GroupModel.findById(groupId).orFail()).members.map((m) => String(m.userId));
}

async function participantIds(meetupId: string): Promise<string[]> {
  return (await MeetupModel.findById(meetupId).orFail()).participants.map((p) => String(p.userId));
}

// Interface DELETE /api/groups/:groupId/members/:memberId
describe('Mocked: DELETE /api/groups/:groupId/members/:memberId', () => {
  // Mocked behavior: the membership update matches nothing, as if the member left a moment earlier
  // Input: organizer removes the member
  // Expected status code: 409
  // Expected behavior: the member's meetup details are left alone
  // Expected output: { error: "Group membership changed. Please refresh and try again." }
  test('Membership changed concurrently', async () => {
    const { organizer, member, group, meetup } = await setup();
    const update = jest.spyOn(GroupModel, 'updateOne').mockResolvedValue({ modifiedCount: 0 } as never);

    const res = await del(`groups/${group.id}/members/${member.id}`, organizer.id);

    expect(res.status).toBe(409);
    expect(res.body).toEqual({ error: 'Group membership changed. Please refresh and try again.' });
    expect(update).toHaveBeenCalledTimes(1);
    expect(await participantIds(meetup.id)).toEqual([organizer.id, member.id]);
  });

  // Mocked behavior: deleting the removed member's votes rejects
  // Input: organizer removes the member
  // Expected status code: 500
  // Expected behavior: the membership was already revoked, so the member has no access while cleanup is incomplete
  // Expected output: { error: "Internal Server Error" }
  test('Database failure while removing the member\'s votes', async () => {
    quietConsole();
    const { organizer, member, group } = await setup();
    jest.spyOn(VoteModel, 'deleteMany').mockRejectedValue(new Error('db down') as never);

    const res = await del(`groups/${group.id}/members/${member.id}`, organizer.id);

    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'Internal Server Error' });
    expect(await memberIds(group.id)).toEqual([organizer.id]);
  });
});

// Interface DELETE /api/groups/:groupId/members/me
describe('Mocked: DELETE /api/groups/:groupId/members/me', () => {
  // Mocked behavior: the membership update matches nothing, as if the organizer removed the member a moment earlier
  // Input: member leaves the group
  // Expected status code: 409
  // Expected behavior: the request does not report a successful departure
  // Expected output: { error: "Group membership changed. Please refresh and try again." }
  test('Membership changed concurrently', async () => {
    const { member, group } = await setup();
    const update = jest.spyOn(GroupModel, 'updateOne').mockResolvedValue({ modifiedCount: 0 } as never);

    const res = await del(`groups/${group.id}/members/me`, member.id);

    expect(res.status).toBe(409);
    expect(res.body).toEqual({ error: 'Group membership changed. Please refresh and try again.' });
    expect(update).toHaveBeenCalledTimes(1);
  });

  // Mocked behavior: stopping the member's location sharing rejects
  // Input: member leaves the group
  // Expected status code: 500
  // Expected behavior: nothing was changed yet: the member is still in the group and in the meetup
  // Expected output: { error: "Internal Server Error" }
  test('Database failure while stopping location sharing', async () => {
    quietConsole();
    const { organizer, member, group, meetup } = await setup();
    jest.spyOn(TripModel, 'updateMany').mockRejectedValue(new Error('db down') as never);

    const res = await del(`groups/${group.id}/members/me`, member.id);

    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'Internal Server Error' });
    expect(await memberIds(group.id)).toEqual([organizer.id, member.id]);
    expect(await participantIds(meetup.id)).toEqual([organizer.id, member.id]);
  });
});

// Interface DELETE /api/meetups/:meetupId/participation
describe('Mocked: DELETE /api/meetups/:meetupId/participation', () => {
  // Mocked behavior: deleting the caller's votes rejects
  // Input: member withdraws from a SETUP meetup
  // Expected status code: 500
  // Expected behavior: the error is reported without leaking internals
  // Expected output: { error: "Internal Server Error" }
  test('Database failure while withdrawing', async () => {
    quietConsole();
    const { member, meetup } = await setup();
    await VoteModel.create({ meetupId: meetup.id, userId: member.id, placeId: PLACE_1 });
    jest.spyOn(VoteModel, 'deleteMany').mockRejectedValue(new Error('db down') as never);

    const res = await del(`meetups/${meetup.id}/participation`, member.id);

    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'Internal Server Error' });
  });
});
