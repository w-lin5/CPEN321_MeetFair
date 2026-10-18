import request from 'supertest';

import { createApp } from '../../src/app';
import { UserModel } from '../../src/users-groups/models';
import { FairCreditEntryModel } from '../../src/voting-fairness/models';
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
  // Mocked behavior: the fairness debt aggregation rejects
  test('Debt aggregation failure', async () => {
    quietConsole();
    const user = await createUserN(1);
    await createGroup(user.id);
    jest.spyOn(FairCreditEntryModel, 'aggregate').mockRejectedValue(new Error('db down') as never);

    const res = await request(createApp()).get('/api/users/me/profile').set('Authorization', bearer(user.id));

    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'Internal Server Error' });
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

// Interface GET /api/users/me/groups/:groupId/fairness
describe('Mocked: GET /api/users/me/groups/:groupId/fairness', () => {
  // Input: member of the group
  // Expected status code: 500
  // Mocked behavior: loading the history rejects
  test('History query failure', async () => {
    quietConsole();
    const user = await createUserN(1);
    const group = await createGroup(user.id);
    jest.spyOn(FairCreditEntryModel, 'find').mockImplementation(() => {
      throw new Error('db down');
    });

    const res = await request(createApp())
      .get(`/api/users/me/groups/${group.id}/fairness`)
      .set('Authorization', bearer(user.id));

    expect(res.status).toBe(500);
  });
});
