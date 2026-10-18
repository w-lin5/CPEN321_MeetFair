import type { Request } from 'express';

import { Unauthorized } from '../../src/common/errors';
import { getUserId } from '../../src/common/middleware/require-auth';

// Interface: getUserId
describe('Unmocked: getUserId', () => {
  // Input: request that went through requireAuth (userId set)
  // Expected output: the user id
  test('Returns the authenticated user id', () => {
    expect(getUserId({ userId: 'abc' } as unknown as Request)).toBe('abc');
  });

  // Input: request that never passed requireAuth (userId unset)
  // Expected behavior: Unauthorized is thrown
  test('Throws when no user is attached', () => {
    expect(() => getUserId({} as unknown as Request)).toThrow(Unauthorized);
  });
});
