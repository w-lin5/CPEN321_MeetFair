import { rankCandidates, scorePlace, type ScoringMember } from '../../src/recommendation/scoring';

// beta = 0.5, gamma = 0.5, mu = 10 minutes (tuning)
const FIVE_HOURS = 5 * 60 * 60;

function member(userId: string, overrides: Partial<ScoringMember> = {}): ScoringMember {
  return { userId, mode: 'WALK', fairCredit: 0, preferences: [], ...overrides };
}

function place(placeId: string, types: string[] = []) {
  return { placeId, name: placeId, address: '', location: { lat: 49.26, lng: -123.25 }, types };
}

const minutes = (value: number): number => value * 60;

// Interface scorePlace (pure)
describe('recommendation score formula', () => {
  // Input: burdens [10, 20, 30] minutes, weights [1, 1, 1.5], mismatches [0, 1, 1]
  // Expected output: W = (10 + 20 + 45) / 3.5, M = 30, I = 20 / 3, P = 10 x 2.5 / 3.5, total = W + 15 + I / 2 + P
  test('combines weighted travel, max travel, imbalance and weighted preference', () => {
    const scores = scorePlace([
      { burden: 10, weight: 1, mismatch: 0 }, { burden: 20, weight: 1, mismatch: 1 }, { burden: 30, weight: 1.5, mismatch: 1 },
    ]);
    expect(scores.weightedTravel).toBeCloseTo(75 / 3.5);
    expect(scores.maxTravel).toBe(30);
    expect(scores.imbalance).toBeCloseTo(20 / 3);
    expect(scores.preference).toBeCloseTo(25 / 3.5);
    expect(scores.total).toBeCloseTo(75 / 3.5 + 15 + 10 / 3 + 25 / 3.5);
  });

  // Input: one member, burden 12 minutes, weight 1, no mismatch
  // Expected output: W = M = 12, I = 0, P = 0, total = 18
  test('handles a single participant', () => {
    expect(scorePlace([{ burden: 12, weight: 1, mismatch: 0 }])).toEqual({ weightedTravel: 12, maxTravel: 12, imbalance: 0, preference: 0, total: 18 });
  });
});

// Interface rankCandidates (pure)
describe('recommendation ranking', () => {
  // Input: places taking 30, 10, 20 and 40 minutes for both members
  // Expected output: all four places, lowest total first, with per-member travel attached
  test('ranks every reachable place best first', () => {
    const ranked = rankCandidates([member('a'), member('b', { mode: 'DRIVE' })],
      [place('p30'), place('p10'), place('p20'), place('p40')],
      [[30, 10, 20, 40].map(minutes), [30, 10, 20, 40].map(minutes)]);
    expect(ranked.map(c => c.placeId)).toEqual(['p10', 'p20', 'p30', 'p40']);
    expect(ranked[0]?.travel).toEqual([
      { userId: 'a', mode: 'WALK', seconds: 600, normalizedSeconds: 600 },
      { userId: 'b', mode: 'DRIVE', seconds: 600, normalizedSeconds: 600 },
    ]);
    expect(ranked[0]?.scores.total).toBe(15);
  });

  // Input: x takes a 10 / b 30 minutes, y takes a 30 / b 10 minutes; first with equal credit, then b has 5 hours
  // Expected output: equal credit ties and falls back to placeId order (x, y); with credit, y (shorter for b) wins
  test('members with more Fair Credit are asked to travel less', () => {
    const places = [place('x'), place('y')];
    const seconds = [[minutes(10), minutes(30)], [minutes(30), minutes(10)]];
    const equal = rankCandidates([member('a'), member('b')], places, seconds);
    expect(equal.map(c => c.placeId)).toEqual(['x', 'y']);
    expect(equal[0]?.scores.total).toBe(equal[1]?.scores.total);

    const favoured = rankCandidates([member('a'), member('b', { fairCredit: FIVE_HOURS })], places, seconds);
    expect(favoured.map(c => c.placeId)).toEqual(['y', 'x']);
    expect(favoured[0]?.scores.weightedTravel).toBeCloseTo(18);
  });

  // Input: negative Fair Credit for b on the same two places
  // Expected output: same as equal credit (weights never drop below 1)
  test('negative Fair Credit does not reduce a member below a normal weight', () => {
    const ranked = rankCandidates([member('a'), member('b', { fairCredit: -FIVE_HOURS })],
      [place('y'), place('x')], [[minutes(10), minutes(30)], [minutes(30), minutes(10)]]);
    expect(ranked.map(c => c.placeId)).toEqual(['x', 'y']);
  });

  // Input: a cafe and a restaurant at the same travel time; a prefers COFFEE, b (5 hours of credit) prefers FOOD
  // Expected output: restaurant first; the cafe's preference penalty is 10 x 1.5 / 2.5 = 6, the restaurant's 4
  test('preferences of members with more Fair Credit count more', () => {
    const ranked = rankCandidates(
      [member('a', { preferences: ['COFFEE'] }), member('b', { preferences: ['FOOD'], fairCredit: FIVE_HOURS })],
      [place('cafe', ['cafe']), place('restaurant', ['restaurant', 'food'])],
      [[600, 600], [600, 600]],
    );
    expect(ranked.map(c => c.placeId)).toEqual(['restaurant', 'cafe']);
    expect(ranked[0]?.scores.preference).toBeCloseTo(4);
    expect(ranked[1]?.scores.preference).toBeCloseTo(6);
  });

  // Input: members with no preferences, or only the removed STUDY value
  // Expected output: no preference penalty for any place
  test('members without preferences are never penalized', () => {
    const ranked = rankCandidates([member('a'), member('b', { preferences: ['STUDY'] })],
      [place('park', ['park'])], [[600], [600]]);
    expect(ranked[0]?.scores.preference).toBe(0);
  });

  // Input: three places where a has no route to the first, b a negative time to the second, and seconds is short a row
  // Expected output: only the third place is ranked; with a missing row nothing is ranked
  test('drops places some member cannot reach', () => {
    const places = [place('p1'), place('p2'), place('p3')];
    expect(rankCandidates([member('a'), member('b')], places, [[null, 600, 600], [600, -1, 600]])
      .map(c => c.placeId)).toEqual(['p3']);
    expect(rankCandidates([member('a'), member('b')], places, [[600, 600, 600]])).toEqual([]);
  });

  // Input: no members; places given in two different orders
  // Expected output: []; identical rankings regardless of input order
  test('handles no members and is deterministic', () => {
    expect(rankCandidates([], [place('p1')], [])).toEqual([]);
    const members = [member('a'), member('b', { fairCredit: 3600 })];
    const forward = rankCandidates(members, [place('p1'), place('p2'), place('p3')],
      [[600, 600, 900], [900, 600, 600]]);
    const backward = rankCandidates(members, [place('p3'), place('p2'), place('p1')],
      [[900, 600, 600], [600, 600, 900]]);
    expect(backward).toEqual(forward);
    expect(forward.map(c => c.placeId)).toEqual(['p2', 'p3', 'p1']);
  });
});
