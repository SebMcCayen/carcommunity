/**
 * Helper to map live location API response markers to map view models.
 *
 * Privacy rules enforced at the backend; this helper only converts shapes.
 *
 * TODO: Only pass markers that the backend has already authorised for this user.
 *       - Only member_monthly users receive other users' markers from the API.
 *       - Suspended accounts must be filtered before this function is called.
 *       - Blocked users must be filtered before this function is called.
 * TODO: Validate that marker.coordinate.recordedAt is within the TTL window before
 *       displaying. Do not show stale or expired positions.
 * TODO: Do not log coordinate values.
 */

import type { PublicLiveLocationMarker } from '@carcommunity/shared/live-location';
import type { PartnerMapMarker } from '@carcommunity/shared/partners';

import type { MapMarkerViewModel } from './types';

/**
 * Converts a {@link PublicLiveLocationMarker} from the API response into a
 * {@link MapMarkerViewModel} for the map rendering layer.
 *
 * Only includes latitude and longitude — heading, speed, and accuracy are
 * intentionally omitted to minimise data exposure on the map layer.
 */
export function liveLocationMarkerToViewModel(
  marker: PublicLiveLocationMarker,
): MapMarkerViewModel {
  return {
    id: marker.sessionId,
    coordinate: {
      latitude: marker.coordinate.latitude,
      longitude: marker.coordinate.longitude,
    },
    type: 'member',
  };
}

/**
 * Converts an array of {@link PublicLiveLocationMarker} objects to view models.
 */
export function liveLocationMarkersToViewModels(
  markers: PublicLiveLocationMarker[],
): MapMarkerViewModel[] {
  return markers.map(liveLocationMarkerToViewModel);
}

/**
 * Converts a {@link PartnerMapMarker} from the API response into a
 * {@link MapMarkerViewModel} for the map rendering layer.
 *
 * Partner markers are always type 'partner' to be visually distinct from
 * member and self markers. Only safe public fields are used.
 */
export function partnerMarkerToViewModel(marker: PartnerMapMarker): MapMarkerViewModel {
  return {
    id: `partner-${marker.partnerId}`,
    coordinate: {
      latitude: marker.latitude,
      longitude: marker.longitude,
    },
    type: 'partner',
  };
}

/**
 * Converts an array of {@link PartnerMapMarker} objects to view models.
 */
export function partnerMarkersToViewModels(markers: PartnerMapMarker[]): MapMarkerViewModel[] {
  return markers.map(partnerMarkerToViewModel);
}
