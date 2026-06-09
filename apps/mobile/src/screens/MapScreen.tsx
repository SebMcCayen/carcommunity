/**
 * Map screen — Mapbox foundation.
 *
 * Renders a basic Mapbox map centred on Kungsbacka, Sweden, with fake placeholder
 * markers for the current user and a nearby community member. No real location
 * data is used; all marker data is local and clearly labelled as fake.
 *
 * Build requirements:
 *   This screen requires a custom Expo development build or EAS build.
 *   @rnmapbox/maps uses native modules that are NOT available in Expo Go.
 *   Set EXPO_PUBLIC_MAPBOX_ACCESS_TOKEN (runtime) and MAPBOX_ACCESS_TOKEN (plugin),
 *   then run `npx expo prebuild` or `eas build` to apply native changes.
 *
 * TODO: Center map on the user's real position after explicit foreground
 *       location permission has been granted (future step).
 * TODO: Request foreground location permission before reading device GPS.
 * TODO: Request background location ONLY during an active live-sharing session.
 * TODO: Add Android foreground notification when background location is introduced.
 * TODO: Add iOS background location handling only during an active sharing session.
 * TODO: Only show other users' markers for member_monthly subscribers after backend
 *       authorisation. Free users must not see other users' positions.
 * TODO: Suspension must override subscription access — enforce on the backend.
 * TODO: Blocking must eventually filter marker visibility.
 * TODO: Do not show stale or expired positions (respect TTL from the API).
 * TODO: Do not expose route or position history.
 * TODO: Throttle position updates to ~25–50 m or 5–10 s in safe driving mode.
 * TODO: Enforce safe driving mode — suppress distracting map interactions while
 *       the device is in motion.
 * TODO: Do not log coordinate values unnecessarily.
 */

import MapboxGL from '@rnmapbox/maps';
import { StyleSheet, View } from 'react-native';

import { getMapboxAccessToken } from '../config/mapbox';
import type { MapMarkerViewModel } from '../map/types';
import { useAppTheme } from '../hooks/useAppTheme';

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

/** @fake Placeholder for the current user's position near Kungsbacka centre. */
const FAKE_SELF_MARKER: MapMarkerViewModel = {
  id: 'fake-self',
  coordinate: { latitude: 57.5086, longitude: 12.0742 },
  type: 'self',
};

/** @fake Placeholder for a nearby community member marker. */
const FAKE_MEMBER_MARKER: MapMarkerViewModel = {
  id: 'fake-member-1',
  coordinate: { latitude: 57.515, longitude: 12.085 },
  type: 'member',
};

/** @fake All placeholder markers shown before real data is available. */
const PLACEHOLDER_MARKERS: readonly MapMarkerViewModel[] = [FAKE_SELF_MARKER, FAKE_MEMBER_MARKER];

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

  return (
    <View style={styles.container}>
      <MapboxGL.MapView
        style={styles.map}
        styleURL={MapboxGL.StyleURL.Street}
        compassEnabled
      >
      >
        <MapboxGL.Camera
          zoomLevel={DEFAULT_ZOOM_LEVEL}
          centerCoordinate={KUNGSBACKA_CENTER}
          animationMode="none"
        />

        {/* TODO: Replace placeholder markers with real live location markers once
                  the mobile API client safely supports them and the user has granted
                  location permission and started a sharing session. */}
        {PLACEHOLDER_MARKERS.map((marker) => (
          <MapboxGL.PointAnnotation
            key={marker.id}
            id={marker.id}
            coordinate={[marker.coordinate.longitude, marker.coordinate.latitude]}
          >
            <MarkerDot
              color={
                marker.type === 'self'
                  ? theme.colors.brandPrimary
                  : theme.colors.textSecondary
              }
            />
          </MapboxGL.PointAnnotation>
        ))}
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
