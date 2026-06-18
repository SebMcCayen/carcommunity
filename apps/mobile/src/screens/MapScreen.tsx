/**
 * Map screen — Mapbox foundation.
 *
 * Renders a basic Mapbox map centred on Kungsbacka, Sweden.
 * When live location sharing is active, the user's real foreground position
 * is shown instead of the placeholder self marker. Fake member markers remain
 * until real marker data is safely implemented.
 *
 * Build requirements:
 *   This screen requires a custom Expo development build or EAS build.
 *   @rnmapbox/maps uses native modules that are NOT available in Expo Go.
 *   Set EXPO_PUBLIC_MAPBOX_ACCESS_TOKEN (runtime) and MAPBOX_ACCESS_TOKEN (plugin),
 *   then run `npx expo prebuild` or `eas build` to apply native changes.
 *
 * TODO: Center map on the user's real position after explicit foreground
 *       location permission has been granted (future step).
 * TODO: Only show other users' markers for member_monthly subscribers after backend
 *       authorisation. Free users must not see other users' positions.
 * TODO: Suspension must override subscription access — enforce on the backend.
 * TODO: Do not show stale or expired positions (respect TTL from the API).
 * TODO: Throttle position updates to ~25–50 m or 5–10 s in safe driving mode.
 * TODO: Do not log coordinate values unnecessarily.
 */

import MapboxGL from '@rnmapbox/maps';
import { StyleSheet, View } from 'react-native';

import { getMapboxAccessToken } from '../config/mapbox';
import type { MapMarkerViewModel } from '../map/types';
import { useAppTheme } from '../hooks/useAppTheme';
import { useLiveLocation } from '../context/LiveLocationContext';

// Kungsbacka, Sweden — [longitude, latitude] as required by Mapbox.
const KUNGSBACKA_CENTER: [number, number] = [12.0742, 57.5086];
const DEFAULT_ZOOM_LEVEL = 12;

// TODO: Move MapboxGL.setAccessToken to a global app initialisation step once
//       one exists (e.g. App.tsx setup effect). Calling it at module load time
//       is an acceptable starting point for MVP.
const mapboxToken = getMapboxAccessToken();
if (mapboxToken) {
  MapboxGL.setAccessToken(mapboxToken);
}

// ---------------------------------------------------------------------------
// FAKE PLACEHOLDER DATA — remove before wiring in real live location markers.
// ---------------------------------------------------------------------------

/** @fake Placeholder for a nearby community member marker. */
const FAKE_MEMBER_MARKER: MapMarkerViewModel = {
  id: 'fake-member-1',
  coordinate: { latitude: 57.515, longitude: 12.085 },
  type: 'member',
};

// ---------------------------------------------------------------------------

const MARKER_SIZE = 20;

type MarkerDotProps = {
  color: string;
};

const MarkerDot = ({ color }: MarkerDotProps) => (
  <View
    style={[
      styles.markerDot,
      { backgroundColor: color, borderRadius: MARKER_SIZE / 2, width: MARKER_SIZE, height: MARKER_SIZE },
    ]}
    accessible={false}
  />
);

export const MapScreen = () => {
  const { theme } = useAppTheme();
  const { status, currentPosition } = useLiveLocation();

  // Show the user's real position only while actively sharing.
  // Coordinates are not logged.
  const isSharingActive = status === 'sharing';
  const selfMarker: MapMarkerViewModel | null =
    isSharingActive && currentPosition !== null
      ? {
          id: 'self',
          coordinate: {
            latitude: currentPosition.latitude,
            longitude: currentPosition.longitude,
          },
          type: 'self',
        }
      : null;

  return (
    <View style={styles.container}>
      <MapboxGL.MapView
        style={styles.map}
        styleURL={MapboxGL.StyleURL.Street}
        compassEnabled
      >
        <MapboxGL.Camera
          zoomLevel={DEFAULT_ZOOM_LEVEL}
          centerCoordinate={KUNGSBACKA_CENTER}
          animationMode="none"
        />

        {/* Real self-position marker — shown only while foreground sharing is active. */}
        {selfMarker !== null && (
          <MapboxGL.PointAnnotation
            key={selfMarker.id}
            id={selfMarker.id}
            coordinate={[selfMarker.coordinate.longitude, selfMarker.coordinate.latitude]}
          >
            <MarkerDot color={theme.colors.brandPrimary} />
          </MapboxGL.PointAnnotation>
        )}

        {/* @fake Placeholder community member marker — replace with real data in a later step. */}
        <MapboxGL.PointAnnotation
          key={FAKE_MEMBER_MARKER.id}
          id={FAKE_MEMBER_MARKER.id}
          coordinate={[
            FAKE_MEMBER_MARKER.coordinate.longitude,
            FAKE_MEMBER_MARKER.coordinate.latitude,
          ]}
        >
          <MarkerDot color={theme.colors.textSecondary} />
        </MapboxGL.PointAnnotation>
      </MapboxGL.MapView>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  map: {
    flex: 1,
  },
  markerDot: {
    borderWidth: 2,
    borderColor: '#FFFFFF',
  },
});
