import type { Preference } from '../common/types';

// Temporary category rules for organizer-only testing, not recommendation scoring.
const PREFERENCE_TYPES: Record<Preference, readonly string[]> = {
  STUDY: ['library'],
  COFFEE: ['cafe'],
  FOOD: ['restaurant'],
  OUTDOORS: ['park'],
  SOCIAL: ['cafe', 'bowling_alley'],
};

export function placeTypesForPreferences(preferences: readonly Preference[]): string[] {
  if (preferences.length === 0) return ['cafe'];
  return [...new Set(preferences.flatMap(preference => PREFERENCE_TYPES[preference]))];
}
