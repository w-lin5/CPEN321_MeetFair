import type { Types } from 'mongoose';

import { signSessionToken } from '../../src/common/session';
import { UserModel } from '../../src/users-groups/models';

// Created documents are typed by the fields tests read, so `id` stays a string even if Mongoose types are unavailable
export interface CreatedDoc {
  id: string;
  _id: Types.ObjectId;
}

// Example test profile
export const GOOGLE_PROFILE = {
  sub: 'google-sub-1',
  email: 'ada@example.com',
  name: 'Ada Lovelace',
  picture: null,
} as const;

export async function createUser(
  overrides: Partial<{ googleSub: string; email: string; name: string }> = {},
): Promise<CreatedDoc> {
  const user: CreatedDoc = await UserModel.create({
    googleSub: 'google-sub-1',
    email: 'ada@example.com',
    name: 'Ada Lovelace',
    ...overrides,
  });
  return user;
}

export function bearer(userId: string): string {
  return `Bearer ${signSessionToken(userId)}`;
}
