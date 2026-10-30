import request from 'supertest';

import { createApp } from '../../src/app';
import { UserModel } from '../../src/users-groups/models';
import { profileService } from '../../src/users-groups/profile.service';
import { userService } from '../../src/users-groups/user.service';
import { fairCreditService } from '../../src/voting-fairness/fair-credit.service';
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

// Settles one meetup for the given (userId, minutes) trips; fair credit = minutes above the trips' average
function settle(groupId: string, meetupId: string, trips: [string, number][]) {
  return fairCreditService.settleMeetup(
    groupId,
    meetupId,
    trips.map(([userId, minutes]) => ({ userId, mode: 'TRANSIT' as const, actualSeconds: minutes * 60, source: 'TRACKED' as const })),
  );
}

// Interface GET /api/users/me/profile
describe('Unmocked: GET /api/users/me/profile', () => {
  // Input: user in two groups with two settled meetups in one of them
  // Expected status code: 200
  // Expected output: account info, both groups with role, and per-group Fair Credit in seconds (0 when none)
  test('Returns account info, groups and Fair Credit', async () => {
    const { ada, grace, groupA, groupB, meetupA, meetupA2 } = await setup();
    await settle(groupA.id, meetupA.id, [[ada.id, 40], [grace.id, 10]]);
    await settle(groupA.id, meetupA2.id, [[ada.id, 10], [grace.id, 30]]);

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
    const bodyGroups: { groupId: string }[] = res.body.groups;
    const groups = new Map(bodyGroups.map((g) => [g.groupId, g]));
    expect(groups.get(groupA.id)).toMatchObject({ name: 'Study Crew', role: 'ORGANIZER', fairCredit: 300 });
    expect(groups.get(groupB.id)).toMatchObject({ name: 'Climbing', role: 'ORGANIZER', fairCredit: 0 });
  });

  // Input: member (not organizer) of one group, while another user has credit in the same group
  // Expected output: only their own group and only their own credit
  test('Credit and groups are per user', async () => {
    const { ada, grace, groupA, meetupA } = await setup();
    await settle(groupA.id, meetupA.id, [[ada.id, 40], [grace.id, 20]]);

    const res = await request(createApp()).get('/api/users/me/profile').set('Authorization', bearer(grace.id));

    expect(res.body.groups).toEqual([
      expect.objectContaining({ groupId: groupA.id, role: 'MEMBER', fairCredit: -600 }),
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
    await profileService.updateProfile(String(user.id), { displayName: 'Ada L.' });

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
