// Shared domain constants and types. Mirrored by the Android enums.

export const TRAVEL_MODES = ['WALK', 'TRANSIT', 'DRIVE'] as const;
export type TravelMode = (typeof TRAVEL_MODES)[number];

export const PREFERENCES = ['COFFEE', 'FOOD', 'OUTDOORS', 'SOCIAL'] as const;
export type Preference = (typeof PREFERENCES)[number];

export const MEETUP_STATUSES = ['SETUP', 'VOTING', 'FINALIZED', 'SETTLED'] as const;
export type MeetupStatus = (typeof MEETUP_STATUSES)[number];

export const GROUP_ROLES = ['ORGANIZER', 'MEMBER'] as const;
export type GroupRole = (typeof GROUP_ROLES)[number];

// TRACKED: measured by live commute tracking; ESTIMATED: Google Maps fallback when tracking never started
export const COMMUTE_SOURCES = ['TRACKED', 'ESTIMATED'] as const;
export type CommuteSource = (typeof COMMUTE_SOURCES)[number];

// ACTIVE from "I'm leaving" until the member arrives; ARRIVED trips carry the actual commute time
export const TRIP_STATUSES = ['ACTIVE', 'ARRIVED'] as const;
export type TripStatus = (typeof TRIP_STATUSES)[number];

// Latitude and longitude
export interface LatLng {
  lat: number;
  lng: number;
}

// A place a group could meet at, identified by its Google Places id
export interface Place {
  placeId: string;
  name: string;
  address: string;
  location: LatLng;
  // Google Places types (e.g. "cafe"), used to match member preferences
  types?: string[];
}
