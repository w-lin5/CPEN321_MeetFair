import { groupService } from './group.service';
import { userService } from './user.service';

/**
 * The user's persistent profile: account info and the groups they belong to.
 * Fair Credit per group is owned by voting-fairness, which adds it on top of this profile
 * (voting-fairness/fair-credit.service.ts), so users-groups stays independent of other components.
 */

// Account fields shown to the user; displayName is already resolved to the name others should see
function account(user: Awaited<ReturnType<typeof userService.getById>>) {
  return {
    id: user.id,
    email: user.email,
    googleName: user.name,
    displayName: userService.displayNameOf(user),
    hasCustomDisplayName: user.displayName !== null && user.displayName !== undefined,
    photoUrl: user.photoUrl ?? null,
  };
}

// Full profile: account info plus every group the user is in
async function getProfile(userId: string) {
  const user = await userService.getById(userId);
  const groups = await groupService.listForUser(userId);

  return {
    ...account(user),
    groups: groups.map((group) => {
      const membership = group.members.find((m) => String(m.userId) === userId);
      return {
        groupId: group.id,
        name: group.name,
        role: membership?.role ?? 'MEMBER',
        joinedAt: membership?.joinedAt ?? null,
      };
    }),
  };
}

// null clears the custom name so the Google name is shown again
async function updateProfile(userId: string, input: { displayName: string | null }) {
  await userService.updateDisplayName(userId, input.displayName);
  return getProfile(userId);
}

export const profileService = { getProfile, updateProfile };
