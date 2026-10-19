import { decideWinner, sameWeight, tally } from '../../src/voting-fairness/tally';

const PLACES = ['a', 'b', 'c'];

function locked(placeId: string, weight: number) {
  return { placeId, locked: true, weight };
}

function picked(placeId: string) {
  return { placeId, locked: false, weight: null };
}

// Interface tally (pure)
describe('tally', () => {
  // Input: two locked votes and one unlocked pick
  // Expected output: only locked weight counts toward weight/share; every pick counts toward voteCount
  test('Counts locked weight and all picks', () => {
    expect(tally(PLACES, [locked('a', 2), locked('b', 1), picked('b')])).toEqual({
      candidates: [
        { placeId: 'a', voteCount: 1, weight: 2, share: 2 / 3 },
        { placeId: 'b', voteCount: 2, weight: 1, share: 1 / 3 },
        { placeId: 'c', voteCount: 0, weight: 0, share: 0 },
      ],
      totalWeight: 3,
      lockedCount: 2,
    });
  });

  // Input: a locked vote with no weight recorded
  // Expected output: the vote is counted but carries no weight
  test('Locked vote without a weight counts as 0', () => {
    const result = tally(PLACES, [{ placeId: 'a', locked: true }]);
    expect(result.candidates[0]).toEqual({ placeId: 'a', voteCount: 1, weight: 0, share: 0 });
    expect(result.lockedCount).toBe(1);
  });

  // Input: no locked votes
  // Expected output: all shares are 0 (no division by zero)
  test('No locked votes', () => {
    const result = tally(PLACES, [picked('a')]);
    expect(result.totalWeight).toBe(0);
    expect(result.candidates.map((c) => c.share)).toEqual([0, 0, 0]);
  });
});

// Interface decideWinner (pure)
describe('decideWinner', () => {
  // Input: a clear weighted leader
  // Expected output: that candidate wins
  test('Highest locked weight wins', () => {
    expect(decideWinner(PLACES, [locked('a', 1), locked('b', 1.2), picked('a'), picked('a')]))
      .toEqual({ kind: 'winner', placeId: 'b' });
  });

  // Input: equal totals, but one side has a single heavier voter
  // Expected output: the side with the heaviest individual voter wins
  test('Tie broken by highest individual weight', () => {
    expect(decideWinner(PLACES, [locked('a', 1), locked('a', 1), locked('b', 2)]))
      .toEqual({ kind: 'winner', placeId: 'b' });
  });

  // Input: equal totals and equal top voters
  // Expected output: an unbreakable tie listing the tied candidates
  test('Unbreakable tie', () => {
    expect(decideWinner(PLACES, [locked('a', 1), locked('c', 1)])).toEqual({ kind: 'tie', placeIds: ['a', 'c'] });
  });

  // Input: only unlocked picks
  // Expected output: no winner can be decided
  test('No locked votes', () => {
    expect(decideWinner(PLACES, [picked('a')])).toEqual({ kind: 'none' });
  });

  // Input: weights that differ only by floating-point noise
  // Expected behavior: treated as equal
  test('Floating-point drift counts as a tie', () => {
    expect(sameWeight(0.1 + 0.2, 0.3)).toBe(true);
    expect(decideWinner(PLACES, [locked('a', 0.1), locked('a', 0.2), locked('b', 0.3)]).kind).toBe('winner');
  });
});
