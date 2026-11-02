import type { Preference } from '../../src/common/types';
import { placeTypesForPreferences, preferenceMismatch } from '../../src/recommendation/preference-types';

// Interface placeTypesForPreferences (pure)
describe('organizer preference categories', () => {
  // Input: one preference: COFFEE, FOOD, OUTDOORS or SOCIAL
  // Expected output: its Google Places types (SOCIAL maps to club, bowling_alley, bar, night_club)
  test.each<[Preference, string[]]>([
    ['COFFEE', ['cafe']], ['FOOD', ['restaurant']],
    ['OUTDOORS', ['park']], ['SOCIAL', ['club', 'bowling_alley', 'bar', 'night_club']],
  ])('maps %s to Google types', (preference, types) => {
    expect(placeTypesForPreferences([preference])).toEqual(types);
  });

  // Input: ["COFFEE", "SOCIAL", "FOOD", "COFFEE"]
  // Expected output: the types of each preference in order, each listed once
  test('combines and deduplicates multiple preferences', () => {
    expect(placeTypesForPreferences(['COFFEE', 'SOCIAL', 'FOOD', 'COFFEE']))
      .toEqual(['cafe', 'club', 'bowling_alley', 'bar', 'night_club', 'restaurant']);
  });

  // Input: []
  // Expected output: ["cafe"]
  test('defaults empty preferences to cafes', () => {
    expect(placeTypesForPreferences([])).toEqual(['cafe']);
  });

  // Input: ["STUDY", "FOOD"]; ["STUDY"]
  // Expected output: ["restaurant"]; ["cafe"] (the removed STUDY value is ignored)
  test('ignores removed STUDY in old saved preferences', () => {
    expect(placeTypesForPreferences(['STUDY', 'FOOD'])).toEqual(['restaurant']);
    expect(placeTypesForPreferences(['STUDY'])).toEqual(['cafe']);
  });
});

// Interface preferenceMismatch (pure)
describe('preference mismatch', () => {
  // Input: preferences and a place's Google types
  // Expected output: 0 when any preferred category matches or there are no (known) preferences, otherwise 1
  test.each<[string[], string[], number]>([
    [['COFFEE', 'FOOD'], ['restaurant', 'food'], 0],
    [['SOCIAL'], ['bar'], 0],
    [['OUTDOORS'], ['cafe'], 1],
    [['COFFEE'], [], 1],
    [[], ['cafe'], 0],
    [['STUDY'], ['library'], 0],
  ])('%j at %j is %i', (preferences, types, expected) => {
    expect(preferenceMismatch(preferences, types)).toBe(expected);
  });
});
