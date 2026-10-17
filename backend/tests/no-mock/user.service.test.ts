import { Types } from 'mongoose';

import { userService } from '../../src/users-groups/user.service';
import { createUser } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';

beforeAll(startDb);
afterEach(clearDb);
afterAll(stopDb);

// Interface userService.findByIds
describe('Unmocked: userService.findByIds', () => {
  // Input: ids of two existing users and one unknown id
  // Expected behavior: only the existing users are returned
  // Expected output: array with exactly the two users
  test('Returns the users whose ids are listed', async () => {
    const ada = await createUser();
    const grace = await createUser({ googleSub: 'google-sub-2', email: 'grace@example.com', name: 'Grace Hopper' });
    await createUser({ googleSub: 'google-sub-3', email: 'linus@example.com', name: 'Linus' });

    const found = await userService.findByIds([ada._id, grace._id, new Types.ObjectId()]);

    expect(found.map((u) => u.id).sort()).toEqual([ada.id, grace.id].sort());
  });

  // Input: empty id list
  // Expected output: empty array
  test('Empty list returns nothing', async () => {
    await createUser();

    expect(await userService.findByIds([])).toEqual([]);
  });
});
