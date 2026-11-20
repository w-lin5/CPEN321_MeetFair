import { TripUpdates } from '../../src/live-location/trip-updates';
import { tuning } from '../../src/config';

beforeEach(() => jest.useFakeTimers());
afterEach(() => jest.useRealTimers());

test('a burst across sharing devices emits one leading and one trailing invalidation per meetup', () => {
  const emit = jest.fn();
  const updates = new TripUpdates(emit);
  for (let i = 0; i < 20; i++) updates.position('meetup-1');
  expect(emit.mock.calls).toEqual([['meetup-1']]);
  updates.position('meetup-2');
  expect(emit).toHaveBeenCalledTimes(2);
  jest.advanceTimersByTime(tuning.tripUpdateIntervalMs);
  expect(emit.mock.calls).toEqual([['meetup-1'], ['meetup-2'], ['meetup-1']]);
  jest.advanceTimersByTime(tuning.tripUpdateIntervalMs * 2);
  expect(emit).toHaveBeenCalledTimes(3); // No idle broadcasts or permanently retained timers.
  expect(jest.getTimerCount()).toBe(0);
});

test('pause/arrival invalidates immediately and cancels a pending movement notification', () => {
  const emit = jest.fn();
  const updates = new TripUpdates(emit);
  updates.position('meetup');
  updates.position('meetup');
  updates.state('meetup');
  expect(emit).toHaveBeenCalledTimes(2);
  jest.advanceTimersByTime(tuning.tripUpdateIntervalMs);
  expect(emit).toHaveBeenCalledTimes(2);
  updates.position('meetup'); // A resumed trip can report its first point immediately.
  expect(emit).toHaveBeenCalledTimes(3);
});
