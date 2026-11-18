/**
 * Runs the actions of one meetup one after another. Starting a trip and settling Fair Credit both use
 * it, so no trip can start between settlement's check for active trips and freezing its inputs.
 * The lock lives in this process's memory, which is enough for our single backend instance.
 */
const pending = new Map<string, Promise<void>>();

export async function withMeetupLock<T>(meetupId: string, action: () => Promise<T>): Promise<T> {
  const previous = pending.get(meetupId) ?? Promise.resolve();
  let release: () => void = () => undefined;
  const current = new Promise<void>((resolve) => { release = resolve; });
  pending.set(meetupId, current);

  await previous;
  try {
    return await action();
  } finally {
    release();
    // Forget the meetup once nothing is queued behind this action
    if (pending.get(meetupId) === current) pending.delete(meetupId);
  }
}
