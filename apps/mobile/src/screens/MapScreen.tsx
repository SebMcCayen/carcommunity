/**
 * Map screen — Mapbox foundation.
 *
 * Renders a basic Mapbox map centred on Kungsbacka, Sweden.
 * When live location sharing is active, the user's real foreground position
 * is shown instead of the placeholder self marker. Active member markers are
 * fetched from the backend via the useLiveLocationMarkers polling hook.
 *
 * Build requirements:
 *   This screen requires a custom Expo development build or EAS build.
 *   @rnmapbox/maps uses native modules that are NOT available in Expo Go.
 *   Set EXPO_PUBLIC_MAPBOX_ACCESS_TOKEN (runtime) and MAPBOX_ACCESS_TOKEN (plugin),
 *   then run `npx expo prebuild` or `eas build` to apply native changes.
 *
 * TODO: Center map on the user's real position after explicit foreground
 *       location permission has been granted (future step).
 * TODO: Suspension must override subscription access — enforce on the backend.
 * TODO: Throttle position updates to ~25–50 m or 5–10 s in safe driving mode.
 * TODO: Do not log coordinate values unnecessarily.
 * TODO: Implement user-blocking / visibility filtering once the blocking graph
 *       is available (see useLiveLocationMarkers TODO).
 */

import MapboxGL from '@rnmapbox/maps';
import { StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';

import { getMapboxAccessToken } from '../config/mapbox';
import { useAppTheme } from '../hooks/useAppTheme';
import { useLiveLocation } from '../context/LiveLocationContext';
import { useLiveLocationMarkers } from '../hooks/useLiveLocationMarkers';
import { usePartnerMarkers } from '../hooks/usePartnerMarkers';
import { useAuth } from '../hooks/useAuth';
import { useI18n } from '../hooks/useI18n';
import type { RootStackParamList } from '../navigation/types';

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

const MARKER_SIZE = 20;
const PARTNER_MARKER_SIZE = 24;

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

type PartnerMarkerDotProps = {
  color: string;
};

/** Partner marker — visually distinct from member/self markers with a diamond shape. */
const PartnerMarkerDot = ({ color }: PartnerMarkerDotProps) => (
  <View
    style={[
      styles.partnerMarkerDot,
      { backgroundColor: color, width: PARTNER_MARKER_SIZE, height: PARTNER_MARKER_SIZE },
    ]}
    accessible={false}
  />
);

export const MapScreen = () => {
  const { theme } = useAppTheme();
  const { status, currentPosition } = useLiveLocation();
  const { isAuthenticated } = useAuth();
  const { t } = useI18n();
  const navigation = useNavigation<NativeStackNavigationProp<RootStackParamList>>();
  const {
    markers: memberMarkers,
    isLoading: isMemberMarkersLoading,
    isMemberEligible,
  } = useLiveLocationMarkers();
  const { rawMarkers: partnerRawMarkers, markers: partnerViewMarkers } = usePartnerMarkers();

  // Show the user's real position only while actively sharing.
  // Coordinates are not logged.
  const isSharingActive = status === 'sharing';
  const selfMarkerCoordinate =
    isSharingActive && currentPosition !== null
      ? ([currentPosition.longitude, currentPosition.latitude] as [number, number])
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
        {selfMarkerCoordinate !== null && (
          <MapboxGL.PointAnnotation
            key="self"
            id="self"
            coordinate={selfMarkerCoordinate}
          >
            <MarkerDot color={theme.colors.brandPrimary} />
          </MapboxGL.PointAnnotation>
        )}

        {/* Other members' live location markers — shown only for eligible members. */}
        {memberMarkers.map((marker) => (
          <MapboxGL.PointAnnotation
            key={marker.id}
            id={marker.id}
            coordinate={[marker.coordinate.longitude, marker.coordinate.latitude]}
          >
            <MarkerDot color={theme.colors.textSecondary} />
          </MapboxGL.PointAnnotation>
        ))}

        {/*
          Partner markers — Samarbetspartner.
          Visually distinct from member/self markers (diamond shape, brand accent).
          Shown to all users; no auth required.
          Tapping opens the partner detail screen.
          No popup while safe-driving mode is active (future step).
        */}
        {partnerRawMarkers.map((raw) => (
          <MapboxGL.PointAnnotation
            key={`partner-${raw.partnerId}`}
            id={`partner-${raw.partnerId}`}
            coordinate={[raw.longitude, raw.latitude]}
            onSelected={() => {
              navigation.navigate('PartnerDetail', { partnerId: raw.partnerId });
            }}
          >
            <TouchableOpacity
              accessibilityRole="button"
              accessibilityLabel={`${raw.label}: ${raw.companyName}`}
              onPress={() => {
                navigation.navigate('PartnerDetail', { partnerId: raw.partnerId });
              }}
            >
              <PartnerMarkerDot color={theme.colors.brandPrimary} />
            </TouchableOpacity>
          </MapboxGL.PointAnnotation>
        ))}
      </MapboxGL.MapView>

      {/* Member notice: shown to authenticated users without member_monthly. */}
      {isAuthenticated && !isMemberEligible && (
        <View style={styles.notice} accessibilityRole="text">
          <Text style={[styles.noticeTitle, { color: theme.colors.textPrimary }]}>
            {t('map.memberRequiredTitle')}
          </Text>
          <Text style={[styles.noticeBody, { color: theme.colors.textSecondary }]}>
            {t('map.shareOwnFreeHint')}
          </Text>
        </View>
      )}

      {/* Empty state: shown when eligible but no markers are available. */}
      {isMemberEligible && !isMemberMarkersLoading && memberMarkers.length === 0 && (
        <View style={styles.notice} accessibilityRole="text">
          <Text style={[styles.noticeTitle, { color: theme.colors.textPrimary }]}>
            {t('map.noOtherMarkersTitle')}
          </Text>
          <Text style={[styles.noticeBody, { color: theme.colors.textSecondary }]}>
            {t('map.noOtherMarkersSubtitle')}
          </Text>
        </View>
      )}
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
  partnerMarkerDot: {
    borderWidth: 2,
    borderColor: '#FFFFFF',
    transform: [{ rotate: '45deg' }],
    borderRadius: 4,
  },
  notice: {
    position: 'absolute',
    bottom: 24,
    left: 16,
    right: 16,
    borderRadius: 8,
    paddingVertical: 10,
    paddingHorizontal: 14,
    backgroundColor: 'rgba(0,0,0,0.55)',
  },
  noticeTitle: {
    fontSize: 13,
    fontWeight: '600',
    marginBottom: 2,
  },
  noticeBody: {
    fontSize: 12,
  },
});
