import request from 'supertest';

import { createApp } from '../../src/app';
import { tuning } from '../../src/config';
import { GroupModel, UserModel } from '../../src/users-groups/models';
import { bearer } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import { createGroup, createUserN } from '../helpers/factories';

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

function duplicateKeyError() {
  return Object.assign(new Error('E11000 duplicate key'), { code: tuning.mongoDuplicateKeyCode });
}

// Interface POST /api/groups
describe('Mocked: POST /api/groups', () => {
  // Input: { name: "Crew" }
  // Expected status code: 201
  // Expected behavior: a colliding invite code is regenerated; the group is created on the second attempt
  // Mocked behavior: GroupModel.create rejects once with a duplicate-key error, then runs for real
  test('Retries after an invite code collision', async () => {
    const user = await createUserN(1);
    const create = jest.spyOn(GroupModel, 'create').mockRejectedValueOnce(duplicateKeyError() as never);

    const res = await request(createApp()).post('/api/groups').set('Authorization', bearer(user.id)).send({ name: 'Crew' });

    expect(res.status).toBe(201);
    expect(create).toHaveBeenCalledTimes(2);
    expect(await GroupModel.countDocuments()).toBe(1);
  });

  // Input: { name: "Crew" }
  // Expected status code: 500
  // Expected behavior: gives up after the configured number of attempts; no group stored
  // Expected output: { error: "Internal Server Error" }
  // Mocked behavior: GroupModel.create always rejects with a duplicate-key error
  test('Gives up when every invite code collides', async () => {
    quietConsole();
    const user = await createUserN(1);
    const create = jest.spyOn(GroupModel, 'create').mockRejectedValue(duplicateKeyError() as never);

    const res = await request(createApp()).post('/api/groups').set('Authorization', bearer(user.id)).send({ name: 'Crew' });

    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'Internal Server Error' });
    expect(create).toHaveBeenCalledTimes(tuning.inviteCodeMaxAttempts);
  });

  // Input: { name: "Crew" }
  // Expected status code: 500
  // Expected behavior: a failure that is not a duplicate key is not retried
  // Expected output: { error: "Internal Server Error" }
  // Mocked behavior: GroupModel.create rejects with a generic error
  test('Database failure is not retried', async () => {
    quietConsole();
    const user = await createUserN(1);
    const create = jest.spyOn(GroupModel, 'create').mockRejectedValue(new Error('db down') as never);

    const res = await request(createApp()).post('/api/groups').set('Authorization', bearer(user.id)).send({ name: 'Crew' });

    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'Internal Server Error' });
    expect(create).toHaveBeenCalledTimes(1);
  });
});

// Interface GET /api/groups
describe('Mocked: GET /api/groups', () => {
  // Input: valid token
  // Expected status code: 500
  // Expected output: { error: "Internal Server Error" }
  // Mocked behavior: GroupModel.find(...).sort rejects
  test('Database failure', async () => {
    quietConsole();
    const user = await createUserN(1);
    jest.spyOn(GroupModel, 'find').mockReturnValue({
      sort: () => Promise.reject(new Error('db down')),
    } as never);

    const res = await request(createApp()).get('/api/groups').set('Authorization', bearer(user.id));

    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'Internal Server Error' });
  });
});

// Interface GET /api/groups/:groupId
describe('Mocked: GET /api/groups/:groupId', () => {
  // Input: id of a group the caller belongs to
  // Expected status code: 500
  // Expected output: { error: "Internal Server Error" }
  // Mocked behavior: GroupModel.findById rejects
  test('Group lookup failure', async () => {
    quietConsole();
    const user = await createUserN(1);
    const group = await createGroup(user.id);
    jest.spyOn(GroupModel, 'findById').mockRejectedValue(new Error('db down') as never);

    const res = await request(createApp()).get(`/api/groups/${group.id}`).set('Authorization', bearer(user.id));

    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'Internal Server Error' });
  });

  // Input: id of a group the caller belongs to
  // Expected status code: 500
  // Expected behavior: no partial group is returned when member names cannot be loaded
  // Mocked behavior: UserModel.find rejects
  test('Member name lookup failure', async () => {
    quietConsole();
    const user = await createUserN(1);
    const group = await createGroup(user.id);
    jest.spyOn(UserModel, 'find').mockRejectedValue(new Error('db down') as never);

    const res = await request(createApp()).get(`/api/groups/${group.id}`).set('Authorization', bearer(user.id));

    expect(res.status).toBe(500);
    expect(res.body.members).toBeUndefined();
  });

  // Input: id of a group whose member's user document is gone
  // Expected status code: 200
  // Expected behavior: the member is still listed, with a null name
  // Mocked behavior: UserModel.find resolves no users
  test('Member without a user document has a null name', async () => {
    const user = await createUserN(1);
    const group = await createGroup(user.id);
    jest.spyOn(UserModel, 'find').mockResolvedValue([] as never);

    const res = await request(createApp()).get(`/api/groups/${group.id}`).set('Authorization', bearer(user.id));

    expect(res.status).toBe(200);
    expect(res.body.members).toEqual([expect.objectContaining({ userId: user.id, name: null, role: 'ORGANIZER' })]);
  });
});

// Interface POST /api/groups/join
describe('Mocked: POST /api/groups/join', () => {
  // Input: valid invite code
  // Expected status code: 500
  // Expected output: { error: "Internal Server Error" }
  // Mocked behavior: GroupModel.findOne rejects
  test('Code lookup failure', async () => {
    quietConsole();
    const user = await createUserN(1);
    jest.spyOn(GroupModel, 'findOne').mockRejectedValue(new Error('db down') as never);

    const res = await request(createApp())
      .post('/api/groups/join')
      .set('Authorization', bearer(user.id))
      .send({ inviteCode: 'ABC234' });

    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'Internal Server Error' });
  });

  // Input: valid invite code from a user who is not yet a member
  // Expected status code: 409
  // Expected behavior: if another request added the user between lookup and update, the join reports a conflict
  // Mocked behavior: GroupModel.updateOne reports that nothing was modified
  test('Lost race with a concurrent join', async () => {
    const organizer = await createUserN(1);
    const joiner = await createUserN(2);
    await createGroup(organizer.id);
    jest.spyOn(GroupModel, 'updateOne').mockResolvedValue({ modifiedCount: 0 } as never);

    const res = await request(createApp())
      .post('/api/groups/join')
      .set('Authorization', bearer(joiner.id))
      .send({ inviteCode: 'ABC234' });

    expect(res.status).toBe(409);
    expect(res.body).toEqual({ error: 'Already a member of this group' });
  });

  // Input: valid invite code
  // Expected status code: 500
  // Expected behavior: the user is not added
  // Mocked behavior: GroupModel.updateOne rejects
  test('Update failure', async () => {
    quietConsole();
    const organizer = await createUserN(1);
    const joiner = await createUserN(2);
    const group = await createGroup(organizer.id);
    jest.spyOn(GroupModel, 'updateOne').mockRejectedValue(new Error('db down') as never);

    const res = await request(createApp())
      .post('/api/groups/join')
      .set('Authorization', bearer(joiner.id))
      .send({ inviteCode: 'ABC234' });

    expect(res.status).toBe(500);
    jest.restoreAllMocks();
    expect((await GroupModel.findById(group.id).orFail()).members).toHaveLength(1);
  });
});

