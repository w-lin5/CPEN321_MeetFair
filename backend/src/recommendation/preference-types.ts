import type { Preference } from '../common/types';

// Google Places types that count as each preference category, for both searching and scoring
const PREFERENCE_TYPES: Record<Preference, readonly string[]> = {
  COFFEE: ['cafe'],
  FOOD: ['restaurant'],
  OUTDOORS: ['park'],
  SOCIAL: ['club', 'bowling_alley', 'bar', 'night_club'],
};

// Old stored meetups may still contain removed categories; ignore them safely.
function matchingTypes(preferences: readonly string[]): string[] {
  const mapping = new Map(Object.entries(PREFERENCE_TYPES));
  return [...new Set(preferences.flatMap(preference => mapping.get(preference) ?? []))];
}

export function placeTypesForPreferences(preferences: readonly string[]): string[] {
  const types = matchingTypes(preferences);
  return types.length > 0 ? types : ['cafe'];
}

/**
 * Preference mismatch P_ij in [0, 1]: 0 when the place matches any of the member's categories (or the
 * member has no preference), 1 otherwise.
 */
export function preferenceMismatch(preferences: readonly string[], placeTypes: readonly string[]): number {
  const wanted = matchingTypes(preferences);
  if (wanted.length === 0) return 0;
  return wanted.some(type => placeTypes.includes(type)) ? 0 : 1;
}
