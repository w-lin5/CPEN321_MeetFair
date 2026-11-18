/**
 * Recommendations, votes, trips and Fair Credit are owned by other components, but they hang off a
 * meetup, a group or a member's participation. Those components register how to clean up their data
 * (wired once in app.ts), so users-groups can cascade a deletion or a departure without importing
 * them: every other component depends on users-groups, never the other way round.
 */
export interface DependentDataOwner {
  /** The meetups were deleted; nothing may be left that refers to them. */
  meetupsDeleted?(meetupIds: string[]): Promise<unknown>;
  /** The group was deleted (its meetups are reported separately through meetupsDeleted). */
  groupDeleted?(groupId: string): Promise<unknown>;
  /** The member's participation entry was removed from these meetups. */
  participationWithdrawn?(meetupIds: string[], userId: string): Promise<unknown>;
  /** The member left, or was removed from, the group these meetups belong to. */
  memberLeft?(meetupIds: string[], userId: string): Promise<unknown>;
}

let owners: readonly DependentDataOwner[] = [];

// Replaces the registered owners, so calling it again (e.g. one app per test) never registers twice
export function setDependentDataOwners(next: readonly DependentDataOwner[]): void {
  owners = next;
}

// Owners are told one after another, in registration order, so a failure stops the cascade at a known point
async function meetupsDeleted(meetupIds: string[]): Promise<void> {
  for (const owner of owners) await owner.meetupsDeleted?.(meetupIds);
}

async function groupDeleted(groupId: string): Promise<void> {
  for (const owner of owners) await owner.groupDeleted?.(groupId);
}

async function participationWithdrawn(meetupIds: string[], userId: string): Promise<void> {
  for (const owner of owners) await owner.participationWithdrawn?.(meetupIds, userId);
}

async function memberLeft(meetupIds: string[], userId: string): Promise<void> {
  for (const owner of owners) await owner.memberLeft?.(meetupIds, userId);
}

export const dependentData = { meetupsDeleted, groupDeleted, participationWithdrawn, memberLeft };
