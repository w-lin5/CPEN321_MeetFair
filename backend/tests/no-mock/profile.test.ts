import request from 'supertest';

import { createApp } from '../../src/app';
import { UserModel } from '../../src/users-groups/models';
import { profileService } from '../../src/users-groups/profile.service';
import { userService } from '../../src/users-groups/user.service';
import { fairCreditService } from '../../src/voting-fairness/fair-credit.service';
import { FairCreditEntryModel } from '../../src/voting-fairness/models';
import { GOOGLE_PROFILE, bearer } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import { MISSING_ID, addMember, createGroup, createMeetup, createUserN } from '../helpers/factories';

beforeAll(startDb);
afterEach(clearDb);
afterAll(stopDb);

// Two users sharing group A; only the first is also in group B. One meetup per group
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

function commute(
  userId: string,
  groupId: string,
  meetupId: string,
  commuteMinutes: number,
  groupAverageMinutes: number,
  source: 'TRACKED' | 'ESTIMATED' = 'TRACKED',
) {
  return fairCreditService.recordCommute({ userId, groupId, meetupId, commuteMinutes, groupAverageMinutes, source });
}

// Interface GET /api/users/me/profile
describe('Unmocked: GET /api/users/me/profile', () => {
  // Input: user in two groups with commutes recorded in one of them
  // Expected status code: 200
  // Expected output: account info, both groups with role, and per-group fairness debt (0 when nothing recorded)
  test('Returns account info, groups and fairness debt', async () => {
    const { ada, groupA, groupB, meetupA, meetupA2 } = await setup();
    await commute(ada.id, groupA.id, meetupA.id, 40, 25);
    await commute(ada.id, groupA.id, meetupA2.id, 10, 20);

    const res = await request(createApp()).get('/api/users/me/profile').set('Authorization', bearer(ada.id));

    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({
      id: ada.id,
      email: 'user1@example.com',
      googleName: 'User 1',
      displayName: 'User 1',
      hasCustomDisplayName: false,
      photoUrl: null,
    });
    const groups = new Map(res.body.groups.map((g: { groupId: string }) => [g.groupId, g]));
    expect(groups.get(groupA.id)).toMatchObject({ name: 'Study Crew', role: 'ORGANIZER', fairnessDebt: 5 });
    expect(groups.get(groupB.id)).toMatchObject({ name: 'Climbing', role: 'ORGANIZER', fairnessDebt: 0 });
  });

  // Input: member (not organizer) of one group, while another user has debt in the same group
  // Expected output: only their own group and only their own debt
  test('Debt and groups are per user', async () => {
    const { ada, grace, groupA, meetupA } = await setup();
    await commute(ada.id, groupA.id, meetupA.id, 60, 30);
    await commute(grace.id, groupA.id, meetupA.id, 20, 30);

    const res = await request(createApp()).get('/api/users/me/profile').set('Authorization', bearer(grace.id));

    expect(res.body.groups).toEqual([
      expect.objectContaining({ groupId: groupA.id, role: 'MEMBER', fairnessDebt: -10 }),
    ]);
  });

  // Input: token for a user that no longer exists / no token
  // Expected status code: 404 / 401
  test('Missing user and unauthenticated', async () => {
    const app = createApp();

    expect((await request(app).get('/api/users/me/profile').set('Authorization', bearer(MISSING_ID))).status).toBe(404);
    expect((await request(app).get('/api/users/me/profile')).status).toBe(401);
  });
});

// Interface PUT /api/users/me/profile
describe('Unmocked: PUT /api/users/me/profile', () => {
  // Input: a new display name, then null
  // Expected status code: 200 both times
  // Expected behavior: the custom name is stored (trimmed), then cleared back to the Google name
  test('Sets and clears the display name', async () => {
    const { ada } = await setup();
    const app = createApp();

    const set = await request(app).put('/api/users/me/profile').set('Authorization', bearer(ada.id)).send({ displayName: '  Ada L. ' });
    const cleared = await request(app).put('/api/users/me/profile').set('Authorization', bearer(ada.id)).send({ displayName: null });

    expect(set.status).toBe(200);
    expect(set.body).toMatchObject({ displayName: 'Ada L.', googleName: 'User 1', hasCustomDisplayName: true });
    expect(cleared.status).toBe(200);
    expect(cleared.body).toMatchObject({ displayName: 'User 1', hasCustomDisplayName: false });
  });

  // Input: missing displayName, blank, too long, wrong type
  // Expected status code: 400
  // Expected behavior: nothing changes
  test.each([[{}], [{ displayName: '   ' }], [{ displayName: 'x'.repeat(61) }], [{ displayName: 42 }]])(
    'Invalid body %j',
    async (body) => {
      const { ada } = await setup();

      const res = await request(createApp()).put('/api/users/me/profile').set('Authorization', bearer(ada.id)).send(body);

      expect(res.status).toBe(400);
      expect((await UserModel.findById(ada.id))?.displayName).toBeNull();
    },
  );

  // Input: valid body for a user that no longer exists
  // Expected status code: 404
  test('Missing user', async () => {
    const res = await request(createApp())
      .put('/api/users/me/profile')
      .set('Authorization', bearer(MISSING_ID))
      .send({ displayName: 'Ghost' });

    expect(res.status).toBe(404);
  });

  // Input: custom display name, then the user signs in with Google again
  // Expected behavior: sign-in refreshes the Google name but keeps the custom display name
  test('Google sign-in does not overwrite the custom name', async () => {
    const user = await UserModel.create({ googleSub: GOOGLE_PROFILE.sub, email: 'old@example.com', name: 'Old Name' });
    await profileService.updateProfile(user.id, { displayName: 'Ada L.' });

    const refreshed = await userService.upsertFromGoogle({ ...GOOGLE_PROFILE, name: 'Ada Lovelace' });

    expect(refreshed.name).toBe('Ada Lovelace');
    expect(refreshed.displayName).toBe('Ada L.');
  });

  // Input: custom display name, then another member opens the group
  // Expected behavior: group members see the custom name
  test('Group detail shows the custom name', async () => {
    const { ada, grace, groupA } = await setup();
    await profileService.updateProfile(grace.id, { displayName: 'Amazing Grace' });

    const res = await request(createApp()).get(`/api/groups/${groupA.id}`).set('Authorization', bearer(ada.id));

    expect(res.body.members.map((m: { name: string }) => m.name).sort()).toEqual(['Amazing Grace', 'User 1']);
  });
});

// Interface GET /api/users/me/groups/:groupId/fairness
describe('Unmocked: GET /api/users/me/groups/:groupId/fairness', () => {
  // Input: member with two recorded commutes in the group (and one in another group)
  // Expected status code: 200
  // Expected output: debt for this group only, history newest first
  test('Returns debt and history for one group', async () => {
    const { ada, groupA, groupB, meetupA, meetupA2, meetupB } = await setup();
    await commute(ada.id, groupA.id, meetupA.id, 40, 25);
    await commute(ada.id, groupA.id, meetupA2.id, 15, 20, 'ESTIMATED');
    await commute(ada.id, groupB.id, meetupB.id, 90, 10);

    const res = await request(createApp())
      .get(`/api/users/me/groups/${groupA.id}/fairness`)
      .set('Authorization', bearer(ada.id));

    expect(res.status).toBe(200);
    expect(res.body.groupId).toBe(groupA.id);
    expect(res.body.fairnessDebt).toBe(10);
    expect(res.body.history).toEqual([
      expect.objectContaining({ meetupId: meetupA2.id, commuteMinutes: 15, extraMinutes: -5, source: 'ESTIMATED' }),
      expect.objectContaining({ meetupId: meetupA.id, commuteMinutes: 40, extraMinutes: 15, source: 'TRACKED' }),
    ]);
  });

  // Input: member with no recorded commutes
  // Expected output: debt 0 and empty history
  test('No history yet', async () => {
    const { grace, groupA } = await setup();

    const res = await request(createApp())
      .get(`/api/users/me/groups/${groupA.id}/fairness`)
      .set('Authorization', bearer(grace.id));

    expect(res.body).toEqual({ groupId: groupA.id, fairnessDebt: 0, history: [] });
  });

  // Input: group the caller is not in / missing group / malformed id
  // Expected status code: 403 / 404 / 400
  test('Non-member, missing and malformed group', async () => {
    const { grace, groupB } = await setup();
    const app = createApp();

    expect((await request(app).get(`/api/users/me/groups/${groupB.id}/fairness`).set('Authorization', bearer(grace.id))).status).toBe(403);
    expect((await request(app).get(`/api/users/me/groups/${MISSING_ID}/fairness`).set('Authorization', bearer(grace.id))).status).toBe(404);
    expect((await request(app).get('/api/users/me/groups/nope/fairness').set('Authorization', bearer(grace.id))).status).toBe(400);
  });
});

// Interface fairCreditService.recordCommute / getFairnessDebt (used by other features, no route)
describe('Unmocked: fairCreditService fairness writes', () => {
  // Input: the same meetup recorded twice
  // Expected behavior: the second call replaces the first; debt is not double counted
  test('Re-recording a meetup replaces the entry', async () => {
    const { ada, groupA, meetupA } = await setup();

    await commute(ada.id, groupA.id, meetupA.id, 50, 30, 'ESTIMATED');
    await commute(ada.id, groupA.id, meetupA.id, 35, 30);

    expect(await FairCreditEntryModel.countDocuments()).toBe(1);
    expect(await fairCreditService.getFairnessDebt(ada.id, groupA.id)).toBe(5);
  });

  // Input: tracked commute, then an estimate for the same meetup
  // Expected behavior: the tracked value is kept
  test('An estimate never overwrites a tracked commute', async () => {
    const { ada, groupA, meetupA } = await setup();

    await commute(ada.id, groupA.id, meetupA.id, 35, 30);
    const kept = await commute(ada.id, groupA.id, meetupA.id, 90, 30, 'ESTIMATED');

    expect(kept).toMatchObject({ commuteMinutes: 35, source: 'TRACKED' });
    expect(await fairCreditService.getFairnessDebt(ada.id, groupA.id)).toBe(5);
  });

  // Input: negative, non-finite or longer-than-a-day minutes
  // Expected behavior: rejected with 400; nothing stored
  test.each([
    [-1, 10],
    [10, -1],
    [Number.NaN, 10],
    [10, Number.POSITIVE_INFINITY],
    [24 * 60 + 1, 10],
  ])('Rejects commute %p with average %p', async (minutes, average) => {
    const { ada, groupA, meetupA } = await setup();

    await expect(commute(ada.id, groupA.id, meetupA.id, minutes, average)).rejects.toMatchObject({ status: 400 });
    expect(await FairCreditEntryModel.countDocuments()).toBe(0);
  });

  // Input: group with no entries
  // Expected output: 0
  test('Debt defaults to zero', async () => {
    const { ada, groupB } = await setup();

    expect(await fairCreditService.getFairnessDebt(ada.id, groupB.id)).toBe(0);
  });
});
