import type { Types } from 'mongoose';

import { NotFound } from '../common/errors';
import { signSessionToken } from '../common/session';
import { type GoogleProfile, idTokenClient } from '../integrations/id-token.client';
import { UserModel } from './models';

// Create or update a user within our DB using Google's identifier
async function upsertFromGoogle(profile: GoogleProfile) {
  return UserModel.findOneAndUpdate(
    { googleSub: profile.sub },
    { $set: { email: profile.email, name: profile.name, photoUrl: profile.picture } },
    { upsert: true, returnDocument: 'after', setDefaultsOnInsert: true },
  ).orFail();
}

async function signInWithGoogle(idToken: string) {
  // Verify Google ID token before trusting profile contents
  const profile = await idTokenClient.verifyIdToken(idToken);
  const user = await upsertFromGoogle(profile);
  return { token: signSessionToken(user.id), user };
}

async function getById(userId: string) {
  const user = await UserModel.findById(userId);
  if (!user) throw new NotFound('User not found');
  return user;
}

async function findByIds(ids: Types.ObjectId[]) {
  return UserModel.find({ _id: { $in: ids } });
}

// The name other members should see: the user's own choice, else their Google name
function displayNameOf(user: { name: string; displayName?: string | null | undefined }): string {
  return user.displayName ?? user.name;
}

// null clears the custom name so the Google name is shown again
async function updateDisplayName(userId: string, displayName: string | null) {
  const updated = await UserModel.findByIdAndUpdate(
    userId,
    { $set: { displayName } },
    { returnDocument: 'after' },
  );
  if (!updated) throw new NotFound('User not found');
  return updated;
}

export const userService = { upsertFromGoogle, signInWithGoogle, getById, findByIds, displayNameOf, updateDisplayName };
