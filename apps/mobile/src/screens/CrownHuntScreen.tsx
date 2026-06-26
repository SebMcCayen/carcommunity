/**
 * CrownHuntScreen — Kronjakt map screen.
 *
 * Shows active Kronjakt points on a Mapbox map.
 * Members can select a point to see its detail and collect the reward
 * by pressing the "Samla in" button when safely stopped.
 *
 * Safety rules enforced here:
 *  - Collect button is ONLY enabled when isSafeToCollect is true.
 *  - Claims are never initiated automatically.
 *  - No coordinates, tokens, or risk scores are displayed or logged.
 *  - Safety copy is always shown when the detail sheet is open.
 *  - Driving mode (speed too high) disables the collect button.
 *  - Backend always performs its own independent validation.
 *
 * Accessibility:
 *  - All interactive elements have accessibilityRole and accessibilityLabel.
 *  - Status messages use accessibilityLiveRegion.
 *  - Text uses readable contrast via design tokens.
 *
 * Privacy:
 *  - No raw risk scores, fraud metadata, or claim coordinates shown.
 *  - Only the current user's claimed state is shown.
 *  - Other users' claims are not visible.
 */

import MapboxGL from '@rnmapbox/maps';
import {
  ActivityIndicator,
  Modal,
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';

import {
  MAX_CLAIM_SPEED_MPS,
  type CrownHuntPointDetail,
  type CrownHuntClaimResponse,
} from '@carcommunity/shared/crown-hunt';

import { getMapboxAccessToken } from '../config/mapbox';
import { useAppTheme } from '../hooks/useAppTheme';
import { useCrownHunt } from '../hooks/useCrownHunt';
import { useI18n } from '../hooks/useI18n';
import { KccButton } from '../components/KccButton';

// ---------------------------------------------------------------------------
// Mapbox token init (same pattern as MapScreen)
// ---------------------------------------------------------------------------

const mapboxToken = getMapboxAccessToken();
if (mapboxToken) {
  MapboxGL.setAccessToken(mapboxToken);
}

// Kungsbacka, Sweden — [longitude, latitude]
const KUNGSBACKA_CENTER: [number, number] = [12.0742, 57.5086];
const DEFAULT_ZOOM_LEVEL = 12;

// ---------------------------------------------------------------------------
// Claim result status mapping
// ---------------------------------------------------------------------------

function getResultMessageKey(result: CrownHuntClaimResponse): string {
  switch (result.data.result) {
    case 'awarded':
      return 'crownHunt.resultAwarded';
    case 'already_claimed':
      return 'crownHunt.resultAlreadyClaimed';
    case 'outside_geofence':
      return 'crownHunt.resultOutsideGeofence';
    case 'moving_too_fast':
      return 'crownHunt.resultMovingTooFast';
    case 'position_too_old':
      return 'crownHunt.resultPositionTooOld';
    case 'point_inactive':
      return 'crownHunt.resultPointInactive';
    case 'cooldown_active':
      return 'crownHunt.resultCooldownActive';
    case 'daily_limit_reached':
      return 'crownHunt.resultDailyLimit';
    case 'risk_review':
      return 'crownHunt.resultRiskReview';
    case 'feature_disabled':
      return 'crownHunt.resultFeatureDisabled';
    default:
      return 'crownHunt.resultNotEligible';
  }
}

// ---------------------------------------------------------------------------
// Point detail bottom sheet
// ---------------------------------------------------------------------------

interface PointDetailSheetProps {
  point: CrownHuntPointDetail;
  isSafeToCollect: boolean;
  isClaiming: boolean;
  claimResult: CrownHuntClaimResponse | null;
  claimError: string | null;
  currentSpeedMs: number | null;
  onCollect: () => void;
  onClose: () => void;
}

const PointDetailSheet = ({
  point,
  isSafeToCollect,
  isClaiming,
  claimResult,
  claimError,
  currentSpeedMs,
  onCollect,
  onClose,
}: PointDetailSheetProps) => {
  const { theme } = useAppTheme();
  const { t } = useI18n();

  const isMovingTooFast =
    currentSpeedMs !== null && currentSpeedMs > MAX_CLAIM_SPEED_MPS;
  const hasAlreadyClaimed = point.claimedByCurrentUser === true;
  const isUnavailable = point.status !== 'active';

  // Collect button is disabled when: moving, pending, already claimed, or unavailable
  const collectDisabled =
    !isSafeToCollect ||
    isClaiming ||
    hasAlreadyClaimed ||
    isUnavailable ||
    claimResult?.data.result === 'awarded';

  return (
    <Modal
      animationType="slide"
      transparent
      visible
      onRequestClose={onClose}
      accessibilityViewIsModal
    >
      <View style={styles.sheetOverlay}>
        <View
          style={[
            styles.sheet,
            { backgroundColor: theme.colors.surfaceBackground, borderColor: theme.colors.borderDefault },
          ]}
          accessibilityRole="none"
        >
          {/* Close button */}
          <TouchableOpacity
            onPress={onClose}
            style={styles.closeButton}
            accessibilityRole="button"
            accessibilityLabel={t('common.close')}
          >
            <Text style={[styles.closeText, { color: theme.colors.textSecondary }]}>✕</Text>
          </TouchableOpacity>

          <ScrollView showsVerticalScrollIndicator={false}>
            {/* Title */}
            <Text
              style={[styles.pointTitle, { color: theme.colors.textPrimary }]}
              accessibilityRole="header"
            >
              {point.title}
            </Text>

            {/* Reward */}
            <Text style={[styles.rewardLabel, { color: theme.colors.brandPrimary }]}>
              {t('crownHunt.rewardLabel').replace('{points}', String(point.rewardPoints))}
            </Text>

            {/* Description */}
            {point.description ? (
              <Text style={[styles.description, { color: theme.colors.textSecondary }]}>
                {point.description}
              </Text>
            ) : null}

            {/* --- Safety copy (always visible) --- */}
            <View
              style={[styles.safetyBox, { backgroundColor: theme.colors.pageBackground, borderColor: theme.colors.statusWarning }]}
              accessible
              accessibilityRole="text"
              accessibilityLabel={t('crownHunt.safetyStop')}
            >
              <Text style={[styles.safetyText, { color: theme.colors.textPrimary }]}>
                ⚠️ {t('crownHunt.safetyStop')}
              </Text>
              <Text style={[styles.safetyText, { color: theme.colors.textSecondary }]}>
                {t('crownHunt.safetyNoDriving')}
              </Text>
            </View>

            {/* Speed warning */}
            {isMovingTooFast && (
              <Text
                style={[styles.warningText, { color: theme.colors.statusError }]}
                accessibilityLiveRegion="polite"
              >
                {t('crownHunt.movingTooFast')}
              </Text>
            )}

            {/* Already claimed state */}
            {hasAlreadyClaimed && (
              <Text style={[styles.statusText, { color: theme.colors.textSecondary }]}>
                {t('crownHunt.alreadyClaimed')}
              </Text>
            )}

            {/* Claim result feedback — no risk scores exposed */}
            {claimResult !== null && (
              <Text
                style={[
                  styles.statusText,
                  {
                    color:
                      claimResult.data.result === 'awarded'
                        ? theme.colors.statusSuccess
                        : theme.colors.statusError,
                  },
                ]}
                accessibilityLiveRegion="assertive"
              >
                {t(getResultMessageKey(claimResult))}
                {claimResult.data.result === 'awarded' && claimResult.data.pointsAwarded
                  ? ` +${claimResult.data.pointsAwarded} KP`
                  : ''}
              </Text>
            )}

            {/* API error (network) */}
            {claimError !== null && (
              <Text
                style={[styles.warningText, { color: theme.colors.statusError }]}
                accessibilityLiveRegion="assertive"
              >
                {t('crownHunt.errorClaim')}
              </Text>
            )}

            {/* Collect button */}
            {!hasAlreadyClaimed && claimResult?.data.result !== 'awarded' && (
              <KccButton
                label={isClaiming ? t('crownHunt.claiming') : t('crownHunt.collectButton')}
                onPress={onCollect}
                disabled={collectDisabled}
                testID="crown-hunt-collect-btn"
              />
            )}
          </ScrollView>
        </View>
      </View>
    </Modal>
  );
};

// ---------------------------------------------------------------------------
// Main screen
// ---------------------------------------------------------------------------

export const CrownHuntScreen = () => {
  const { theme } = useAppTheme();
  const { t } = useI18n();
  const {
    points,
    isLoadingPoints,
    pointsError,
    selectedPoint,
    isLoadingDetail,
    currentSpeedMs,
    isSafeToCollect,
    isClaiming,
    claimResult,
    claimError,
    selectPoint,
    collect,
    refreshPoints,
    clearClaimResult,
  } = useCrownHunt();

  return (
    <View style={[styles.container, { backgroundColor: theme.colors.pageBackground }]}>
      {/* Mapbox map */}
      <MapboxGL.MapView
        style={styles.map}
        logoEnabled={false}
        attributionEnabled={false}
        accessibilityLabel={t('crownHunt.mapLabel')}
      >
        <MapboxGL.Camera
          defaultSettings={{
            centerCoordinate: KUNGSBACKA_CENTER,
            zoomLevel: DEFAULT_ZOOM_LEVEL,
          }}
        />

        {/* Kronjakt point markers */}
        {points.map((point) => (
          <MapboxGL.MarkerView
            key={point.pointId}
            coordinate={[point.longitude, point.latitude]}
          >
            <TouchableOpacity
              onPress={() => void selectPoint(point.pointId)}
              accessibilityRole="button"
              accessibilityLabel={`${t('crownHunt.pointMarkerLabel')} ${point.title}`}
              style={styles.markerButton}
            >
              <View
                style={[
                  styles.crownMarker,
                  {
                    backgroundColor: point.claimedByCurrentUser
                      ? theme.colors.textSecondary
                      : theme.colors.brandPrimary,
                    borderColor: theme.colors.surfaceBackground,
                  },
                ]}
              >
                <Text style={styles.crownEmoji} importantForAccessibility="no-hide-descendants">
                  👑
                </Text>
              </View>
            </TouchableOpacity>
          </MapboxGL.MarkerView>
        ))}
      </MapboxGL.MapView>

      {/* Loading overlay */}
      {isLoadingPoints && (
        <View style={styles.loadingOverlay} accessibilityLiveRegion="polite">
          <ActivityIndicator color={theme.colors.brandPrimary} />
        </View>
      )}

      {/* Error banner */}
      {pointsError !== null && (
        <View
          style={[styles.errorBanner, { backgroundColor: theme.colors.statusError }]}
          accessible
          accessibilityRole="alert"
        >
          <Text style={[styles.errorBannerText, { color: theme.colors.surfaceBackground }]}>
            {t('crownHunt.error')}
          </Text>
          <TouchableOpacity
            onPress={() => void refreshPoints()}
            accessibilityRole="button"
            accessibilityLabel={t('crownHunt.retry')}
          >
            <Text style={[styles.errorBannerText, { color: theme.colors.surfaceBackground, textDecorationLine: 'underline' }]}>
              {t('crownHunt.retry')}
            </Text>
          </TouchableOpacity>
        </View>
      )}

      {/* Loading detail spinner (while fetching point + position) */}
      {isLoadingDetail && (
        <View style={styles.loadingOverlay} accessibilityLiveRegion="polite">
          <ActivityIndicator color={theme.colors.brandPrimary} />
        </View>
      )}

      {/* Point detail bottom sheet */}
      {selectedPoint !== null && !isLoadingDetail && (
        <PointDetailSheet
          point={selectedPoint}
          isSafeToCollect={isSafeToCollect}
          isClaiming={isClaiming}
          claimResult={claimResult}
          claimError={claimError}
          currentSpeedMs={currentSpeedMs}
          onCollect={() => void collect()}
          onClose={() => {
            clearClaimResult();
            void selectPoint(null);
          }}
        />
      )}
    </View>
  );
};

// ---------------------------------------------------------------------------
// Styles
// ---------------------------------------------------------------------------

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  map: {
    flex: 1,
  },
  markerButton: {
    padding: 4,
  },
  crownMarker: {
    width: 36,
    height: 36,
    borderRadius: 18,
    borderWidth: 2,
    alignItems: 'center',
    justifyContent: 'center',
  },
  crownEmoji: {
    fontSize: 18,
    lineHeight: 22,
  },
  loadingOverlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    justifyContent: 'center',
    alignItems: 'center',
  },
  errorBanner: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 10,
  },
  errorBannerText: {
    fontSize: 14,
    fontWeight: '500',
  },
  sheetOverlay: {
    flex: 1,
    justifyContent: 'flex-end',
  },
  sheet: {
    borderTopLeftRadius: 16,
    borderTopRightRadius: 16,
    borderWidth: 1,
    padding: 20,
    maxHeight: '70%',
  },
  closeButton: {
    alignSelf: 'flex-end',
    padding: 8,
    marginBottom: 4,
  },
  closeText: {
    fontSize: 18,
  },
  pointTitle: {
    fontSize: 20,
    fontWeight: '700',
    marginBottom: 4,
  },
  rewardLabel: {
    fontSize: 16,
    fontWeight: '600',
    marginBottom: 8,
  },
  description: {
    fontSize: 14,
    marginBottom: 12,
    lineHeight: 20,
  },
  safetyBox: {
    borderWidth: 1,
    borderRadius: 8,
    padding: 12,
    marginBottom: 12,
    gap: 4,
  },
  safetyText: {
    fontSize: 13,
    lineHeight: 18,
  },
  warningText: {
    fontSize: 14,
    fontWeight: '500',
    marginBottom: 12,
  },
  statusText: {
    fontSize: 14,
    marginBottom: 12,
    lineHeight: 20,
  },
});
