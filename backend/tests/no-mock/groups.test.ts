import request from 'supertest';

import { createApp } from '../../src/app';
import { GroupModel } from '../../src/users-groups/models';
import { bearer, createUser } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import { MISSING_ID, addMember, createGroup, createUserN } from '../helpers/factories';

beforeAll(startDb);
afterEach(clearDb);
afterAll(stopDb);

// Interface POST /api/groups
describe('Unmocked: POST /api/groups', () => {
  // Input: { name: "  Study Crew  " }
  // Expected status code: 201
  // Expected behavior: group stored with the caller as its only member and organizer; name trimmed
  // Expected output: group with id, name, 6-character inviteCode, organizerId, members [{ userId, role: ORGANIZER }]
  test('Creates a group with the caller as organizer', async () => {
    const user = await createUser();

    const res = await request(createApp())
      .post('/api/groups')
      .set('Authorization', bearer(user.id))
      .send({ name: '  Study Crew  ' });

    expect(res.status).toBe(201);
    expect(res.body).toMatchObject({ name: 'Study Crew', organizerId: user.id });
    expect(res.body.inviteCode).toMatch(/^[A-HJ-KM-NP-Z2-9]{6}$/);
    expect(res.body.members).toHaveLength(1);
    expect(res.body.members[0]).toMatchObject({ userId: user.id, role: 'ORGANIZER' });
    expect(res.body._id).toBeUndefined();
    expect(await GroupModel.countDocuments()).toBe(1);
  });

  // Input: two groups created back to back
  // Expected status code: 201 for both
  // Expected behavior: every group gets its own invite code
  test('Invite codes are unique', async () => {
    const user = await createUser();
    const app = createApp();

    const a = await request(app).post('/api/groups').set('Authorization', bearer(user.id)).send({ name: 'A' });
    const b = await request(app).post('/api/groups').set('Authorization', bearer(user.id)).send({ name: 'B' });

    expect(a.body.inviteCode).not.toBe(b.body.inviteCode);
  });

  // Input: name missing / not a string / blank / over the length limit
  // Expected status code: 400
  // Expected behavior: nothing is stored
  test.each([[undefined], [123], ['   '], ['x'.repeat(61)]])('Invalid name %j', async (name) => {
    const user = await createUser();

    const res = await request(createApp()).post('/api/groups').set('Authorization', bearer(user.id)).send({ name });

    expect(res.status).toBe(400);
    expect(res.body.details).toHaveLength(1);
    expect(await GroupModel.countDocuments()).toBe(0);
  });

  // Input: no Authorization header
  // Expected status code: 401
  test('No token', async () => {
    const res = await request(createApp()).post('/api/groups').send({ name: 'Study Crew' });

    expect(res.status).toBe(401);
  });
});

// Interface GET /api/groups
describe('Unmocked: GET /api/groups', () => {
  // Input: user who belongs to no group
  // Expected status code: 200
  // Expected output: []
  test('Empty list', async () => {
    const user = await createUser();

    const res = await request(createApp()).get('/api/groups').set('Authorization', bearer(user.id));

    expect(res.status).toBe(200);
    expect(res.body).toEqual([]);
  });

  // Input: caller organizes one group and has joined another; a third group belongs to someone else
  // Expected status code: 200
  // Expected output: exactly the caller's two groups, newest first
  test('Returns only the caller\'s groups, newest first', async () => {
    const me = await createUserN(1);
    const other = await createUserN(2);
    const first = await createGroup(me.id, { name: 'First', inviteCode: 'AAAAAA' });
    const second = await createGroup(other.id, { name: 'Second', inviteCode: 'BBBBBB' });
    await addMember(second.id, me.id);
    await createGroup(other.id, { name: 'Not mine', inviteCode: 'CCCCCC' });

    const res = await request(createApp()).get('/api/groups').set('Authorization', bearer(me.id));

    expect(res.status).toBe(200);
    expect(res.body.map((g: { id: string }) => g.id)).toEqual([second.id, first.id]);
  });

  // Input: no Authorization header
  // Expected status code: 401
  test('No token', async () => {
    const res = await request(createApp()).get('/api/groups');

    expect(res.status).toBe(401);
  });
});

// Interface GET /api/groups/:groupId
describe('Unmocked: GET /api/groups/:groupId', () => {
  // Input: id of a group the caller belongs to
  // Expected status code: 200
  // Expected output: group with invite code and each member's resolved name and role
  test('Returns the group with member names', async () => {
    const organizer = await createUserN(1);
    const member = await createUserN(2);
    const group = await createGroup(organizer.id);
    await addMember(group.id, member.id);

    const res = await request(createApp()).get(`/api/groups/${group.id}`).set('Authorization', bearer(member.id));

    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ id: group.id, name: 'Study Crew', inviteCode: 'ABC234' });
    expect(res.body.members).toEqual([
      expect.objectContaining({ userId: organizer.id, name: 'User 1', role: 'ORGANIZER' }),
      expect.objectContaining({ userId: member.id, name: 'User 2', role: 'MEMBER' }),
    ]);
  });

  // Input: id that is not a 24-hex ObjectId
  // Expected status code: 400
  test('Malformed group id', async () => {
    const user = await createUser();

    const res = await request(createApp()).get('/api/groups/abc').set('Authorization', bearer(user.id));

    expect(res.status).toBe(400);
  });

  // Input: well-formed id of a group that does not exist
  // Expected status code: 404
  // Expected output: { error: "Group not found" }
  test('Unknown group', async () => {
    const user = await createUser();

    const res = await request(createApp()).get(`/api/groups/${MISSING_ID}`).set('Authorization', bearer(user.id));

    expect(res.status).toBe(404);
    expect(res.body).toEqual({ error: 'Group not found' });
  });

  // Input: group the caller is not a member of
  // Expected status code: 403
  // Expected output: { error: "Not a member of this group" }
  test('Non-member', async () => {
    const organizer = await createUserN(1);
    const outsider = await createUserN(2);
    const group = await createGroup(organizer.id);

    const res = await request(createApp()).get(`/api/groups/${group.id}`).set('Authorization', bearer(outsider.id));

    expect(res.status).toBe(403);
    expect(res.body).toEqual({ error: 'Not a member of this group' });
  });
});

// Interface POST /api/groups/join
describe('Unmocked: POST /api/groups/join', () => {
  // Input: the group's invite code
  // Expected status code: 200
  // Expected behavior: caller is added as a MEMBER, organizer unchanged
  // Expected output: the group including the new member
  test('Joins with a valid code', async () => {
    const organizer = await createUserN(1);
    const joiner = await createUserN(2);
    const group = await createGroup(organizer.id);

    const res = await request(createApp())
      .post('/api/groups/join')
      .set('Authorization', bearer(joiner.id))
      .send({ inviteCode: 'ABC234' });

    expect(res.status).toBe(200);
    expect(res.body.id).toBe(group.id);
    expect(res.body.organizerId).toBe(organizer.id);
    expect(res.body.members).toHaveLength(2);
    expect(res.body.members[1]).toMatchObject({ userId: joiner.id, role: 'MEMBER' });
  });

  // Input: code in lower case with surrounding spaces
  // Expected status code: 200
  // Expected behavior: codes are matched case-insensitively
  test('Code is normalised', async () => {
    const organizer = await createUserN(1);
    const joiner = await createUserN(2);
    await createGroup(organizer.id);

    const res = await request(createApp())
      .post('/api/groups/join')
      .set('Authorization', bearer(joiner.id))
      .send({ inviteCode: '  abc234 ' });

    expect(res.status).toBe(200);
  });

  // Input: code that matches no group
  // Expected status code: 404
  // Expected output: { error: "Invalid invite code" }
  test('Unknown code', async () => {
    const user = await createUser();

    const res = await request(createApp())
      .post('/api/groups/join')
      .set('Authorization', bearer(user.id))
      .send({ inviteCode: 'ZZZZZZ' });

    expect(res.status).toBe(404);
    expect(res.body).toEqual({ error: 'Invalid invite code' });
  });

  // Input: existing member (the organizer) joins their own group again
  // Expected status code: 409
  // Expected behavior: membership is not duplicated
  // Expected output: { error: "Already a member of this group" }
  test('Already a member', async () => {
    const organizer = await createUserN(1);
    const group = await createGroup(organizer.id);

    const res = await request(createApp())
      .post('/api/groups/join')
      .set('Authorization', bearer(organizer.id))
      .send({ inviteCode: 'ABC234' });

    expect(res.status).toBe(409);
    expect(res.body).toEqual({ error: 'Already a member of this group' });
    expect((await GroupModel.findById(group.id).orFail()).members).toHaveLength(1);
  });

  // Input: the same user joins twice at the same time
  // Expected status code: one 200 and one 409
  // Expected behavior: the atomic update admits the user exactly once
  test('Concurrent joins add the user once', async () => {
    const organizer = await createUserN(1);
    const joiner = await createUserN(2);
    const group = await createGroup(organizer.id);
    const app = createApp();
    const join = () =>
      request(app).post('/api/groups/join').set('Authorization', bearer(joiner.id)).send({ inviteCode: 'ABC234' });

    const results = await Promise.all([join(), join()]);

    expect(results.map((r) => r.status).sort()).toEqual([200, 409]);
    expect((await GroupModel.findById(group.id).orFail()).members).toHaveLength(2);
  });

  // Input: inviteCode missing / not a string / blank / over the length limit
  // Expected status code: 400
  test.each([[undefined], [42], ['   '], ['A'.repeat(17)]])('Invalid code %j', async (inviteCode) => {
    const user = await createUser();

    const res = await request(createApp())
      .post('/api/groups/join')
      .set('Authorization', bearer(user.id))
      .send({ inviteCode });

    expect(res.status).toBe(400);
  });

  // Input: no Authorization header
  // Expected status code: 401
  test('No token', async () => {
    const res = await request(createApp()).post('/api/groups/join').send({ inviteCode: 'ABC234' });

    expect(res.status).toBe(401);
  });
});
