import type { LatLng } from '../common/types';

/**
 * Where to look for candidate places: the plain geographic average of the members' start locations.
 * That is accurate enough for a local meetup, and the candidates found around it are ranked by actual
 * travel times afterwards. Pure; `locations` must not be empty.
 */
export function searchCenter(locations: LatLng[]): LatLng {
  const total = locations.reduce(
    (sum, location) => ({ lat: sum.lat + location.lat, lng: sum.lng + location.lng }),
    { lat: 0, lng: 0 },
  );
  return { lat: total.lat / locations.length, lng: total.lng / locations.length };
}
