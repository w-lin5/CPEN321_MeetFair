import { creditChanges, normalizedBurden } from '../../src/voting-fairness/fair-credit';
import { voteWeight } from '../../src/voting-fairness/vote-weight';

const HOUR = 3600;

// Interface creditChanges / normalizedBurden (pure)
describe('creditChanges', () => {
  // Input: three trips of 40, 25 and 10 minutes
  // Expected output: expected burden is the 25-minute average; deltas +15, 0, -15 minutes (sum 0)
  test('Credits members who travelled more than average', () => {
    const changes = creditChanges([
      { userId: 'a', mode: 'TRANSIT', actualSeconds: 2400 },
      { userId: 'b', mode: 'DRIVE', actualSeconds: 1500 },
      { userId: 'c', mode: 'WALK', actualSeconds: 600 },
    ]);

    expect(changes).toEqual([
      { userId: 'a', actualSeconds: 2400, normalizedBurden: 2400, expectedBurden: 1500, delta: 900 },
      { userId: 'b', actualSeconds: 1500, normalizedBurden: 1500, expectedBurden: 1500, delta: 0 },
      { userId: 'c', actualSeconds: 600, normalizedBurden: 600, expectedBurden: 1500, delta: -900 },
    ]);
  });

  // Input: averages that are not whole seconds
  // Expected output: every duration is rounded to an integer second
  test('Rounds to whole seconds', () => {
    const changes = creditChanges([
      { userId: 'a', mode: 'WALK', actualSeconds: 100.4 },
      { userId: 'b', mode: 'WALK', actualSeconds: 0 },
      { userId: 'c', mode: 'WALK', actualSeconds: 0 },
    ]);

    expect(changes.map((c) => [c.actualSeconds, c.expectedBurden, c.delta])).toEqual([[100, 33, 67], [0, 33, -33], [0, 33, -33]]);
  });

  // Input: no trips
  // Expected output: no changes
  test('Empty settlement', () => {
    expect(creditChanges([])).toEqual([]);
  });

  // Input: one trip per mode
  // Expected output: all multipliers are 1.0 for now, so the burden equals the raw time
  test.each(['WALK', 'TRANSIT', 'DRIVE'] as const)('%s is not reweighted yet', (mode) => {
    expect(normalizedBurden(600, mode)).toBe(600);
  });
});

// Interface voteWeight (pure)
describe('voteWeight', () => {
  // Input: Fair Credit of 0, 1 h, 2.5 h
  // Expected output: 1, 1.1, 1.25 (1 + 0.1 per hour)
  test('Grows by 0.1 per hour of credit', () => {
    expect(voteWeight(0)).toBe(1);
    expect(voteWeight(HOUR)).toBeCloseTo(1.1);
    expect(voteWeight(2.5 * HOUR)).toBeCloseTo(1.25);
  });

  // Input: negative credit; very large credit
  // Expected output: never below 1, never above 1.5
  test('Clamped to [1, 1.5]', () => {
    expect(voteWeight(-10 * HOUR)).toBe(1);
    expect(voteWeight(100 * HOUR)).toBe(1.5);
  });
});
