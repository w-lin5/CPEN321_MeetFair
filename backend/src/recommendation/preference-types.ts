import type { Preference } from '../common/types';

// Temporary category rules for organizer-only testing, not recommendation scoring.
const PREFERENCE_TYPES: Record<Preference, readonly string[]> = {
  COFFEE: ['cafe'],
  FOOD: ['restaurant'],
  OUTDOORS: ['park'],
  SOCIAL: ['club', 'bowling_alley', 'bar', 'night_club'],
};

// Old stored meetups may still contain removed categories; ignore them safely.
export function placeTypesForPreferences(preferences: readonly string[]): string[] {
  const mapping = new Map(Object.entries(PREFERENCE_TYPES));
  const types = [...new Set(preferences.flatMap(preference => mapping.get(preference) ?? []))];
  return types.length > 0 ? types : ['cafe'];
}
