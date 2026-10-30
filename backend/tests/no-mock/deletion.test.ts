import request, { type Test } from 'supertest';
import { createApp } from '../../src/app';
import { RecommendationModel } from '../../src/recommendation/models';
import { setRoomCloser, setTransport } from '../../src/realtime/publisher';
import { GroupModel, MeetupModel } from '../../src/users-groups/models';
import { FairCreditEntryModel, FairCreditModel, VoteModel } from '../../src/voting-fairness/models';
import { bearer } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import { addMember, addParticipants, CANDIDATES, createGroup, createMeetup, createUserN, MISSING_ID, PLACE_1 } from '../helpers/factories';

beforeAll(startDb);
afterEach(async () => { setRoomCloser(null); setTransport(null); await clearDb(); });
afterAll(stopDb);

async function setup(status: 'SETUP' | 'VOTING' | 'FINALIZED' | 'SETTLED' = 'VOTING') {
  const organizer = await createUserN(1);
  const member = await createUserN(2);
  const outsider = await createUserN(3);
  const group = await createGroup(organizer.id);
  await addMember(group.id, member.id);
  const meetup = await createMeetup(group.id, organizer.id, { status });
  await addParticipants(meetup.id, [organizer.id, member.id]);
  await RecommendationModel.create({ meetupId: meetup.id, candidates: CANDIDATES });
  await VoteModel.create({ meetupId: meetup.id, userId: member.id, placeId: PLACE_1, locked: true, weight: 1 });
  await FairCreditModel.create({ groupId: group.id, userId: member.id, balance: 30 });
  await FairCreditEntryModel.create({ groupId: group.id, userId: member.id, meetupId: meetup.id,
    actualSeconds: 60, normalizedBurden: 60, expectedBurden: 30, delta: 30, source: 'ESTIMATED' });
  return { organizer, member, outsider, group, meetup };
}

function del(path: string, userId?: string): Test {
  const req = request(createApp()).delete(`/api/${path}`);
  return userId ? req.set('Authorization', bearer(userId)) : req;
}

// Shared access checks for DELETE /api/groups/:groupId and DELETE /api/meetups/:meetupId
async function expectDeletionGuards(kind: 'groups' | 'meetups') {
  const { organizer, member, outsider, group, meetup } = await setup();
  const id = kind === 'groups' ? group.id : meetup.id;
  expect((await del(`${kind}/${id}`)).status).toBe(401);
  expect((await del(`${kind}/${id}`, member.id)).status).toBe(403);
  expect((await del(`${kind}/${id}`, outsider.id)).status).toBe(403);
  expect((await del(`${kind}/invalid`, organizer.id)).status).toBe(400);
  expect((await del(`${kind}/${MISSING_ID}`, organizer.id)).status).toBe(404);
  expect(await GroupModel.exists({ _id: group.id })).not.toBeNull();
  expect(await MeetupModel.exists({ _id: meetup.id })).not.toBeNull();
}

// Interface DELETE /api/meetups/:meetupId
describe('Unmocked: DELETE /api/meetups/:meetupId', () => {
  // Input: meetup organizer deletes their meetup in each status; the meetup has a recommendation, a locked vote and a settled Fair Credit entry
  // Expected status code: 204; then 404 for the member's GET and for a second delete
  // Expected behavior: meetup, recommendation and votes are removed; the group, the Fair Credit entry and the balance (30) are kept;
  //   "meetup:deleted" is published to the meetup room, "group:meetup-deleted" to each of the 2 group members, and the meetup room is closed
  // Expected output: None
  test.each(['SETUP', 'VOTING', 'FINALIZED', 'SETTLED'] as const)('deletes meetup in %s and preserves booked Fair Credit', async status => {
    const { organizer, member, group, meetup } = await setup(status);
    const events: string[] = [];
    const closed: string[] = [];
    setTransport((_room, event) => events.push(event));
    setRoomCloser(room => closed.push(room));
    expect((await del(`meetups/${meetup.id}`, organizer.id)).status).toBe(204);
    expect(await MeetupModel.exists({ _id: meetup.id })).toBeNull();
    expect(await RecommendationModel.countDocuments({ meetupId: meetup.id })).toBe(0);
    expect(await VoteModel.countDocuments({ meetupId: meetup.id })).toBe(0);
    expect(await GroupModel.exists({ _id: group.id })).not.toBeNull();
    expect(await FairCreditEntryModel.countDocuments({ meetupId: meetup.id })).toBe(1);
    expect((await FairCreditModel.findOne({ groupId: group.id, userId: member.id }).orFail()).balance).toBe(30);
    expect(events).toEqual(['meetup:deleted', 'group:meetup-deleted', 'group:meetup-deleted']);
    expect(closed).toEqual([`meetup:${meetup.id}`]);
    expect((await request(createApp()).get(`/api/meetups/${meetup.id}`).set('Authorization', bearer(member.id))).status).toBe(404);
    expect((await del(`meetups/${meetup.id}`, organizer.id)).status).toBe(404);
  });

  // Input: request without a token; group member who is not the meetup organizer; outsider; malformed id; unknown id
  // Expected status code: 401; 403; 403; 400; 404
  // Expected behavior: the group and the meetup are unchanged
  // Expected output: None
  test('enforces authorization and validates ids', async () => {
    await expectDeletionGuards('meetups');
  });

  // Input: group organizer deletes a meetup organized by another member, then that member deletes it
  // Expected status code: 403; 204
  // Expected behavior: only the meetup organizer may delete the meetup
  // Expected output: None
  test('group ownership does not grant deletion of another organizer\'s meetup', async () => {
    const { organizer, member, group } = await setup();
    const meetup = await createMeetup(group.id, member.id);
    expect((await del(`meetups/${meetup.id}`, organizer.id)).status).toBe(403);
    expect((await del(`meetups/${meetup.id}`, member.id)).status).toBe(204);
  });
});

// Interface DELETE /api/groups/:groupId
describe('Unmocked: DELETE /api/groups/:groupId', () => {
  // Input: group organizer deletes a group with meetups in every status; the member also organizes another group with its own meetup
  // Expected status code: 204; then 404 when joining with the deleted group's invite code
  // Expected behavior: the group, its meetups, their recommendations and votes, and the group's Fair Credit balances and entries are removed;
  //   the other group's meetup, recommendation and vote are kept
  // Expected output: None
  test('deletes group and all statuses of meetups, leaving other groups intact', async () => {
    const { organizer, member, group, meetup } = await setup();
    for (const status of ['SETUP', 'FINALIZED', 'SETTLED'] as const) await createMeetup(group.id, member.id, { status });
    const other = await createGroup(member.id, { inviteCode: 'XYZ234' });
    const otherMeetup = await createMeetup(other.id, member.id);
    await RecommendationModel.create({ meetupId: otherMeetup.id, candidates: CANDIDATES });
    await VoteModel.create({ meetupId: otherMeetup.id, userId: member.id, placeId: PLACE_1 });
    expect((await del(`groups/${group.id}`, organizer.id)).status).toBe(204);
    expect(await GroupModel.exists({ _id: group.id })).toBeNull();
    expect(await MeetupModel.countDocuments({ groupId: group.id })).toBe(0);
    expect(await RecommendationModel.countDocuments({ meetupId: meetup.id })).toBe(0);
    expect(await VoteModel.countDocuments({ meetupId: meetup.id })).toBe(0);
    expect(await FairCreditEntryModel.countDocuments({ groupId: group.id })).toBe(0);
    expect(await FairCreditModel.countDocuments({ groupId: group.id })).toBe(0);
    expect(await MeetupModel.exists({ _id: otherMeetup.id })).not.toBeNull();
    expect(await RecommendationModel.countDocuments({ meetupId: otherMeetup.id })).toBe(1);
    expect(await VoteModel.countDocuments({ meetupId: otherMeetup.id })).toBe(1);
    expect((await request(createApp()).post('/api/groups/join').set('Authorization', bearer(member.id)).send({ inviteCode: group.inviteCode })).status).toBe(404);
  });

  // Input: request without a token; non-organizer member; outsider; malformed id; unknown id
  // Expected status code: 401; 403; 403; 400; 404
  // Expected behavior: the group and its meetup are unchanged
  // Expected output: None
  test('enforces authorization and validates ids', async () => {
    await expectDeletionGuards('groups');
  });
});
