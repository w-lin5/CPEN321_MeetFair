import type { Preference } from '../../src/common/types';
import { placeTypesForPreferences } from '../../src/recommendation/preference-types';

describe('organizer preference categories', () => {
  test.each<[Preference, string[]]>([
    ['COFFEE', ['cafe']], ['FOOD', ['restaurant']],
    ['OUTDOORS', ['park']], ['SOCIAL', ['club', 'bowling_alley', 'bar', 'night_club']],
  ])('maps %s to Google types', (preference, types) => {
    expect(placeTypesForPreferences([preference])).toEqual(types);
  });

  test('combines and deduplicates multiple preferences', () => {
    expect(placeTypesForPreferences(['COFFEE', 'SOCIAL', 'FOOD', 'COFFEE']))
      .toEqual(['cafe', 'club', 'bowling_alley', 'bar', 'night_club', 'restaurant']);
  });

  test('defaults empty preferences to cafes', () => {
    expect(placeTypesForPreferences([])).toEqual(['cafe']);
  });

  test('ignores removed STUDY in old saved preferences', () => {
    expect(placeTypesForPreferences(['STUDY', 'FOOD'])).toEqual(['restaurant']);
    expect(placeTypesForPreferences(['STUDY'])).toEqual(['cafe']);
  });
});
