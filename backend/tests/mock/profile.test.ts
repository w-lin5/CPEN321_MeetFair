import request from 'supertest';

import { createApp } from '../../src/app';
import { groupService } from '../../src/users-groups/group.service';
import { UserModel } from '../../src/users-groups/models';
import { FairCreditModel } from '../../src/voting-fairness/models';
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

// Interface GET /api/users/me/profile
describe('Mocked: GET /api/users/me/profile', () => {
  // Input: authenticated user in one group
  // Expected status code: 500
  // Expected output: { error: "Internal Server Error" }
  // Mocked behavior: loading the Fair Credit balances rejects
  test('Fair Credit lookup failure', async () => {
    quietConsole();
    const user = await createUserN(1);
    await createGroup(user.id);
    jest.spyOn(FairCreditModel, 'find').mockImplementation(() => {
      throw new Error('db down');
    });

    const res = await request(createApp()).get('/api/users/me/profile').set('Authorization', bearer(user.id));

    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'Internal Server Error' });
  });

  // Input: authenticated user
  // Expected status code: 200
  // Expected output: a group without the caller's membership entry is shown as role MEMBER with joinedAt null
  // Mocked behavior: groupService.listForUser resolves a group whose members list lacks the caller
  test('Group without the caller membership entry', async () => {
    const user = await createUserN(1);
    const other = await createUserN(2);
    const group = await createGroup(other.id);
    jest.spyOn(groupService, 'listForUser').mockResolvedValue([group] as never);

    const res = await request(createApp()).get('/api/users/me/profile').set('Authorization', bearer(user.id));

    expect(res.status).toBe(200);
    expect(res.body.groups).toEqual([
      { groupId: group.id, name: 'Study Crew', role: 'MEMBER', joinedAt: null, fairCredit: 0 },
    ]);
  });
});

// Interface PUT /api/users/me/profile
describe('Mocked: PUT /api/users/me/profile', () => {
  // Input: valid display name
  // Expected status code: 500
  // Mocked behavior: UserModel.findByIdAndUpdate rejects
  test('Database failure while saving', async () => {
    quietConsole();
    const user = await createUserN(1);
    jest.spyOn(UserModel, 'findByIdAndUpdate').mockRejectedValue(new Error('db down') as never);

    const res = await request(createApp())
      .put('/api/users/me/profile')
      .set('Authorization', bearer(user.id))
      .send({ displayName: 'Ada' });

    expect(res.status).toBe(500);
  });
});
