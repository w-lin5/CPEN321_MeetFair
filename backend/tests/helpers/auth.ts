import { signSessionToken } from '../../src/common/session';
import { UserModel } from '../../src/users-groups/models';

// Example test profile
export const GOOGLE_PROFILE = {
  sub: 'google-sub-1',
  email: 'ada@example.com',
  name: 'Ada Lovelace',
  picture: null,
} as const;

export async function createUser(overrides: Partial<{ googleSub: string; email: string; name: string }> = {}) {
  return UserModel.create({
    googleSub: 'google-sub-1',
    email: 'ada@example.com',
    name: 'Ada Lovelace',
    ...overrides,
  });
}

export function bearer(userId: string): string {
  return `Bearer ${signSessionToken(userId)}`;
}
