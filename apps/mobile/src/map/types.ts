/**
 * Map marker view model types for the mobile map UI.
 *
 * These types describe what the map rendering layer needs.
 * They are intentionally separate from the shared API contracts so
 * the map UI is decoupled from backend data shapes.
 */

/** Marker type used to distinguish between different icon styles on the map. */
export type MapMarkerType = 'self' | 'member';

/** A lat/lng coordinate used by the map UI layer. */
export interface MapCoordinate {
  latitude: number;
  longitude: number;
}

/**
 * View model for a single map marker.
 *
 * All data in this type is safe to render; no personal data beyond position.
 * Callers must ensure positions are not stale before constructing this type.
 */
export interface MapMarkerViewModel {
  /** Stable unique identifier for the marker (e.g. session ID). */
  id: string;
  /** Geographic position to render the marker at. */
  coordinate: MapCoordinate;
  /** Visual type determining the icon and colour used on the map. */
  type: MapMarkerType;
}
