import request, { type Test } from 'supertest';

import { createApp } from '../../src/app';
import { MeetupModel } from '../../src/users-groups/models';
import { fairCreditService } from '../../src/voting-fairness/fair-credit.service';
import { FairCreditEntryModel, FairCreditModel } from '../../src/voting-fairness/models';
import { bearer } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import { MISSING_ID, addMember, createGroup, createMeetup, createUserN } from '../helpers/factories';

beforeAll(startDb);
afterEach(clearDb);
afterAll(stopDb);

const MINUTE = 60;

// Ada and Grace share group A; only Ada is in group B. Two meetups in A, one in B
async function setup() {
  const ada = await createUserN(1);
  const grace = await createUserN(2);
  const groupA = await createGroup(ada.id, { name: 'Study Crew', inviteCode: 'AAA234' });
  const groupB = await createGroup(ada.id, { name: 'Climbing', inviteCode: 'BBB234' });
  await addMember(groupA.id, grace.id);
  const meetupA = await createMeetup(groupA.id, ada.id);
  const meetupA2 = await createMeetup(groupA.id, ada.id, { title: 'Second session' });
  const meetupB = await createMeetup(groupB.id, ada.id);
  return { ada, grace, groupA, groupB, meetupA, meetupA2, meetupB };
}

function trip(userId: string, minutes: number, source: 'TRACKED' | 'ESTIMATED' = 'TRACKED') {
  return { userId, mode: 'TRANSIT' as const, actualSeconds: minutes * MINUTE, source };
}

function getFairness(groupId: string, userId: string): Test {
  return request(createApp()).get(`/api/groups/${groupId}/fairness`).set('Authorization', bearer(userId));
}

// Interface GET /api/groups/:groupId/fairness
describe('Unmocked: GET /api/groups/:groupId/fairness', () => {
  // Input: two settled meetups in group A (and one in group B), requested by a member
  // Expected status code: 200
  // Expected output: every member's balance with display name, and group A's ledger newest first
  test('Returns balances and the ledger for one group', async () => {
    const { ada, grace, groupA, groupB, meetupA, meetupA2, meetupB } = await setup();
    await fairCreditService.settleMeetup(groupA.id, meetupA.id, [trip(ada.id, 40), trip(grace.id, 10)]);
    await fairCreditService.settleMeetup(groupA.id, meetupA2.id, [trip(ada.id, 15, 'ESTIMATED'), trip(grace.id, 25)]);
    await fairCreditService.settleMeetup(groupB.id, meetupB.id, [trip(ada.id, 90), trip(grace.id, 10)]);
    await MeetupModel.updateOne({ _id: meetupA2.id }, { $set: {
      winner: { placeId: 'library', name: 'Library', address: '123 Main St', location: { lat: 49, lng: -123 } },
    } });

    const res = await getFairness(groupA.id, grace.id);

    expect(res.status).toBe(200);
    expect(res.body.groupId).toBe(groupA.id);
    expect(res.body.members).toEqual([
      { userId: ada.id, name: 'User 1', balance: 900 - 300 },
      { userId: grace.id, name: 'User 2', balance: -900 + 300 },
    ]);
    expect(res.body.ledger).toHaveLength(4);
    expect(res.body.ledger[0]).toMatchObject({ meetupId: meetupA2.id });
    expect(res.body.ledger).toContainEqual(expect.objectContaining({
      userId: ada.id,
      meetupId: meetupA2.id,
      meetupTitle: 'Second session',
      placeName: 'Library',
      actualSeconds: 900,
      normalizedBurden: 900,
      expectedBurden: 1200,
      delta: -300,
      source: 'ESTIMATED',
    }));
  });

  // Input: group with nothing settled
  // Expected output: every member at the initial balance (0) and an empty ledger
  test('Nothing settled yet', async () => {
    const { ada, grace, groupA } = await setup();

    const res = await getFairness(groupA.id, grace.id);

    expect(res.body).toEqual({
      groupId: groupA.id,
      members: [
        { userId: ada.id, name: 'User 1', balance: 0 },
        { userId: grace.id, name: 'User 2', balance: 0 },
      ],
      ledger: [],
    });
  });

  // Input: group the caller is not in / missing group / malformed id
  // Expected status code: 403 / 404 / 400
  test('Non-member, missing and malformed group', async () => {
    const { grace, groupB } = await setup();

    expect((await getFairness(groupB.id, grace.id)).status).toBe(403);
    expect((await getFairness(MISSING_ID, grace.id)).status).toBe(404);
    expect((await getFairness('nope', grace.id)).status).toBe(400);
  });
});

// Interface fairCreditService.settleMeetup / getBalance / getVoteWeight (used by commute tracking and voting)
describe('Unmocked: fairCreditService settlement', () => {
  // Input: one meetup settled for two members
  // Expected behavior: ledger rows written, balances created from the deltas; deltas sum to zero
  test('Settles a meetup into balances', async () => {
    const { ada, grace, groupA, meetupA } = await setup();

    const entries = await fairCreditService.settleMeetup(groupA.id, meetupA.id, [trip(ada.id, 50), trip(grace.id, 30)]);

    expect(entries.map((e) => e.delta)).toEqual([600, -600]);
    expect(await fairCreditService.getBalance(ada.id, groupA.id)).toBe(600);
    expect(await fairCreditService.getBalance(grace.id, groupA.id)).toBe(-600);
    expect(await FairCreditModel.countDocuments()).toBe(2);
  });

  // Input: one member walked, one drove, one took transit (all multipliers currently 1.0)
  // Expected behavior: every travel mode is normalized and settled against the group average
  test('Settles every travel mode', async () => {
    const { ada, grace, groupA, meetupA } = await setup();
    const alan = await createUserN(3);

    const entries = await fairCreditService.settleMeetup(groupA.id, meetupA.id, [
      { ...trip(ada.id, 40), mode: 'WALK' },
      { ...trip(grace.id, 20), mode: 'DRIVE' },
      trip(alan.id, 30),
    ]);

    expect(entries.map((e) => [e.normalizedBurden, e.delta])).toEqual([[2400, 600], [1200, -600], [1800, 0]]);
    expect(await fairCreditService.getBalance(grace.id, groupA.id)).toBe(-600);
  });

  // Input: settlement with no trips
  // Expected behavior: nothing is written and no balance changes
  test('Settling with no trips changes nothing', async () => {
    const { ada, groupA, meetupA } = await setup();

    expect(await fairCreditService.settleMeetup(groupA.id, meetupA.id, [])).toEqual([]);
    expect(await FairCreditEntryModel.countDocuments()).toBe(0);
    expect(await fairCreditService.getBalance(ada.id, groupA.id)).toBe(0);
  });

  // Input: the same meetup settled twice
  // Expected behavior: 409 the second time; balances are not double counted
  test('A meetup can only be settled once', async () => {
    const { ada, grace, groupA, meetupA } = await setup();
    await fairCreditService.settleMeetup(groupA.id, meetupA.id, [trip(ada.id, 50), trip(grace.id, 30)]);

    await expect(fairCreditService.settleMeetup(groupA.id, meetupA.id, [trip(ada.id, 90), trip(grace.id, 10)]))
      .rejects.toMatchObject({ status: 409 });
    expect(await fairCreditService.getBalance(ada.id, groupA.id)).toBe(600);
    expect(await FairCreditEntryModel.countDocuments()).toBe(2);
  });

  // Input: negative, non-finite or longer-than-a-day durations
  // Expected behavior: rejected with 400; nothing stored
  test.each([-1, Number.NaN, Number.POSITIVE_INFINITY, 24 * 60 * 60 + 1])('Rejects a %p second trip', async (seconds) => {
    const { ada, grace, groupA, meetupA } = await setup();

    await expect(fairCreditService.settleMeetup(groupA.id, meetupA.id, [
      { ...trip(ada.id, 10), actualSeconds: seconds },
      trip(grace.id, 10),
    ])).rejects.toMatchObject({ status: 400 });
    expect(await FairCreditEntryModel.countDocuments()).toBe(0);
  });

  // Input: member with no settled meetups, then one with 2 h of credit
  // Expected output: initial credit 0 and weight 1; then weight 1.2
  test('Vote weight follows the balance', async () => {
    const { ada, grace, groupA, meetupA } = await setup();
    expect(await fairCreditService.getBalance(ada.id, groupA.id)).toBe(0);
    expect(await fairCreditService.getVoteWeight(ada.id, groupA.id)).toBe(1);

    await fairCreditService.settleMeetup(groupA.id, meetupA.id, [trip(ada.id, 240), trip(grace.id, 0)]);

    expect(await fairCreditService.getVoteWeight(ada.id, groupA.id)).toBeCloseTo(1.2);
    expect(await fairCreditService.getVoteWeight(grace.id, groupA.id)).toBe(1);
  });
});

