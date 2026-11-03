// Serialize trip starts and settlement on the single backend process so no trip starts
// between checking active trips and freezing the settlement inputs.
const pending = new Map<string, Promise<void>>();

export async function withMeetupLock<T>(meetupId: string, action: () => Promise<T>): Promise<T> {
  const previous = pending.get(meetupId) ?? Promise.resolve();
  let release!: () => void;
  const current = new Promise<void>(resolve => { release = resolve; });
  pending.set(meetupId, current);
  await previous;
  try {
    return await action();
  } finally {
    release();
    if (pending.get(meetupId) === current) pending.delete(meetupId);
  }
}
