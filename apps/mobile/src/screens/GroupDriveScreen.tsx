/**
 * GroupDriveScreen — event-based group driving for eligible members.
 *
 * Access rules (enforced by backend; client-side check is UX only):
 *   - Active member_monthly entitlement required.
 *   - RSVP must be `going` or `maybe`.
 *   - Event must be published.
 *
 * Safe-driving:
 *   - Status controls are unavailable while the safe-driving placeholder is active.
 *   - "Leave group drive" remains available at all times.
 *   - Map polling continues regardless to keep data fresh.
 *
 * Privacy:
 *   - Live location is NOT started automatically by joining.
 *   - Group drive state is held in transient state; never persisted.
 *   - Coordinates are never logged.
 *   - Backend is the source of truth for all access and visibility decisions.
 */

import { useCallback, useMemo } from 'react';
import { ActivityIndicator, StyleSheet, Text, View } from 'react-native';
import { useNavigation, useRoute } from '@react-navigation/native';
import type { RouteProp } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { canAccessMemberFeatures } from '@carcommunity/shared/users';
import type { GroupDriveUpdatableStatus } from '@carcommunity/shared/group-drive';

import { KccButton } from '../components/KccButton';
import { LockedFeatureNotice } from '../components/LockedFeatureNotice';
import { ScreenContainer } from '../components/ScreenContainer';
import { SectionHeader } from '../components/SectionHeader';
import { useAppTheme } from '../hooks/useAppTheme';
import { useAuth } from '../hooks/useAuth';
import { useI18n } from '../hooks/useI18n';
import { useGroupDrive } from '../hooks/useGroupDrive';
import { useGroupDriveMarkers } from '../hooks/useGroupDriveMarkers';
import type { RootStackParamList } from '../navigation/types';

type GroupDriveRouteProp = RouteProp<RootStackParamList, 'GroupDrive'>;
type GroupDriveNavProp = NativeStackNavigationProp<RootStackParamList>;

// ---------------------------------------------------------------------------
// Safe-driving placeholder
// ---------------------------------------------------------------------------

/**
 * Placeholder safe-driving hook.
 * TODO: Integrate with a real driving/motion state when available.
 * Returns false (not driving) conservatively until detection is implemented.
 */
function useSafeDrivingPlaceholder(): boolean {
  return false;
}

// ---------------------------------------------------------------------------
// Status controls
// ---------------------------------------------------------------------------

type StatusControlsProps = {
  currentStatus: 'joined' | 'on_the_way' | 'arrived';
  isPending: boolean;
  isDriving: boolean;
  onSelect: (status: GroupDriveUpdatableStatus) => void;
};

function StatusControls({ currentStatus, isPending, isDriving, onSelect }: StatusControlsProps) {
  const { t } = useI18n();
  const { theme } = useAppTheme();

  const options: { status: GroupDriveUpdatableStatus; labelKey: string }[] = [
    { status: 'joined', labelKey: 'groupDrive.statusJoined' },
    { status: 'on_the_way', labelKey: 'groupDrive.statusOnTheWay' },
    { status: 'arrived', labelKey: 'groupDrive.statusArrived' },
  ];

  return (
    <View style={[styles.statusRow, { gap: theme.spacing[2] }]}>
      {isDriving && (
        <Text style={[styles.safeDrivingText, { color: theme.colors.statusWarning }]}>
          {t('groupDrive.safeDrivingStopFirst')}
        </Text>
      )}
      {options.map(({ status, labelKey }) => (
        <KccButton
          key={status}
          testID={`group-drive-status-${status}`}
          label={t(labelKey)}
          variant={currentStatus === status ? 'primary' : 'secondary'}
          disabled={isPending || isDriving}
          onPress={() => onSelect(status)}
        />
      ))}
    </View>
  );
}

// ---------------------------------------------------------------------------
// Participant counts summary
// ---------------------------------------------------------------------------

type CountSummaryProps = {
  joinedCount: number;
  onTheWayCount: number;
  arrivedCount: number;
};

function CountSummary({ joinedCount, onTheWayCount, arrivedCount }: CountSummaryProps) {
  const { t } = useI18n();
  const { theme } = useAppTheme();

  return (
    <View testID="group-drive-counts" style={[styles.countsRow, { gap: theme.spacing[3] }]}>
      <View style={styles.countItem}>
        <Text style={[styles.countValue, { color: theme.colors.textPrimary }]}>{joinedCount}</Text>
        <Text style={[styles.countLabel, { color: theme.colors.textSecondary }]}>
          {t('groupDrive.countJoined')}
        </Text>
      </View>
      <View style={styles.countItem}>
        <Text style={[styles.countValue, { color: theme.colors.textPrimary }]}>{onTheWayCount}</Text>
        <Text style={[styles.countLabel, { color: theme.colors.textSecondary }]}>
          {t('groupDrive.countOnTheWay')}
        </Text>
      </View>
      <View style={styles.countItem}>
        <Text style={[styles.countValue, { color: theme.colors.textPrimary }]}>{arrivedCount}</Text>
        <Text style={[styles.countLabel, { color: theme.colors.textSecondary }]}>
          {t('groupDrive.countArrived')}
        </Text>
      </View>
    </View>
  );
}

// ---------------------------------------------------------------------------
// Main screen
// ---------------------------------------------------------------------------

export const GroupDriveScreen = () => {
  const { t } = useI18n();
  const { theme } = useAppTheme();
  const navigation = useNavigation<GroupDriveNavProp>();
  const route = useRoute<GroupDriveRouteProp>();
  const { eventId, eventTitle, eventRsvpStatus } = route.params;

  const { currentUser } = useAuth();
  const isDriving = useSafeDrivingPlaceholder();

  // Client-side eligibility check — UX only. Backend enforces the real decision.
  const isMember = useMemo(
    () =>
      currentUser !== null &&
      canAccessMemberFeatures({
        role: currentUser.roles?.[0] ?? 'user',
        status: currentUser.status,
        subscriptionEntitlement: currentUser.subscriptionEntitlement,
      }),
    [currentUser],
  );

  const isEligibleRsvp = eventRsvpStatus === 'going' || eventRsvpStatus === 'maybe';
  const isEligible = isMember && isEligibleRsvp;

  const {
    screenState,
    currentStatus,
    joinedCount,
    onTheWayCount,
    arrivedCount,
    isJoining,
    isLeaving,
    isUpdatingStatus,
    error,
    join,
    leave,
    setStatus,
  } = useGroupDrive({ eventId, isEligible });

  const isParticipating = currentStatus !== null && currentStatus !== 'left';

  // Markers polling — only while user is an active participant
  const { markers, isLoading: isMarkersLoading } = useGroupDriveMarkers({
    eventId,
    isEligible: isEligible && isParticipating,
  });

  const handleLiveLocation = useCallback(() => {
    navigation.navigate('LiveLocation');
  }, [navigation]);

  // ---------------------------------------------------------------------------
  // Render: gate for ineligible users
  // ---------------------------------------------------------------------------

  if (!isMember) {
    return (
      <ScreenContainer testID="group-drive-screen">
        <Text style={[styles.title, { color: theme.colors.textPrimary }]}>{eventTitle}</Text>
        <LockedFeatureNotice
          testID="group-drive-member-gate"
          message={t('groupDrive.memberRequired')}
        />
      </ScreenContainer>
    );
  }

  if (!isEligibleRsvp) {
    return (
      <ScreenContainer testID="group-drive-screen">
        <Text style={[styles.title, { color: theme.colors.textPrimary }]}>{eventTitle}</Text>
        <LockedFeatureNotice
          testID="group-drive-rsvp-gate"
          message={t('groupDrive.rsvpRequired')}
        />
      </ScreenContainer>
    );
  }

  // ---------------------------------------------------------------------------
  // Render: loading
  // ---------------------------------------------------------------------------

  if (screenState === 'loading') {
    return (
      <ScreenContainer testID="group-drive-screen">
        <View testID="group-drive-loading" style={styles.centeredRow}>
          <ActivityIndicator color={theme.colors.brandPrimary} />
          <Text style={[styles.meta, { color: theme.colors.textSecondary }]}>
            {t('groupDrive.loading')}
          </Text>
        </View>
      </ScreenContainer>
    );
  }

  // ---------------------------------------------------------------------------
  // Render: error
  // ---------------------------------------------------------------------------

  if (screenState === 'error') {
    return (
      <ScreenContainer testID="group-drive-screen">
        <Text style={[styles.meta, { color: theme.colors.statusError }]}>{t('groupDrive.error')}</Text>
      </ScreenContainer>
    );
  }

  // ---------------------------------------------------------------------------
  // Render: access lost
  // ---------------------------------------------------------------------------

  if (screenState === 'access_lost') {
    return (
      <ScreenContainer testID="group-drive-screen">
        <LockedFeatureNotice
          testID="group-drive-access-lost"
          message={t('groupDrive.memberRequired')}
        />
      </ScreenContainer>
    );
  }

  // ---------------------------------------------------------------------------
  // Render: main content
  // ---------------------------------------------------------------------------

  return (
    <ScreenContainer testID="group-drive-screen">
      {/* ── Participant counts ────────────────────────────────── */}
      <SectionHeader title={t('groupDrive.participantCount')} />
      <CountSummary
        joinedCount={joinedCount}
        onTheWayCount={onTheWayCount}
        arrivedCount={arrivedCount}
      />

      {/* ── Inline error ──────────────────────────────────────── */}
      {error !== null && (
        <Text style={[styles.meta, { color: theme.colors.statusError }]}>
          {t(`groupDrive.${error}`)}
        </Text>
      )}

      {/* ── Join / status controls / leave ────────────────────── */}
      {!isParticipating ? (
        <KccButton
          testID="group-drive-join-button"
          label={t('groupDrive.joinButton')}
          variant="primary"
          disabled={isJoining}
          onPress={() => void join()}
        />
      ) : (
        <>
          {/* Current status and controls */}
          <SectionHeader title={t('groupDrive.setStatus')} />
          <StatusControls
            currentStatus={currentStatus as 'joined' | 'on_the_way' | 'arrived'}
            isPending={isUpdatingStatus}
            isDriving={isDriving}
            onSelect={(s) => void setStatus(s)}
          />

          {/* Leave button — always available */}
          <KccButton
            testID="group-drive-leave-button"
            label={t('groupDrive.leaveButton')}
            variant="secondary"
            disabled={isLeaving}
            onPress={() => void leave()}
          />
        </>
      )}

      {/* ── Live location disclaimer ──────────────────────────── */}
      <View style={{ gap: theme.spacing[1] }}>
        <Text style={[styles.disclaimer, { color: theme.colors.textSecondary }]}>
          {t('groupDrive.liveLocationDisclaimer')}
        </Text>
        <KccButton
          testID="group-drive-live-location-button"
          label={t('groupDrive.goToLiveLocation')}
          variant="secondary"
          onPress={handleLiveLocation}
        />
      </View>

      {/* ── Markers loading indicator ─────────────────────────── */}
      {isMarkersLoading && (
        <ActivityIndicator
          testID="group-drive-markers-loading"
          color={theme.colors.brandPrimary}
          size="small"
        />
      )}

      {/* ── Group map placeholder ─────────────────────────────── */}
      {isParticipating && markers.length === 0 && !isMarkersLoading && (
        <Text style={[styles.meta, { color: theme.colors.textSecondary }]}>
          {t('groupDrive.noMarkersTitle')}
        </Text>
      )}

      {/*
       * TODO: Render Mapbox map with group drive markers once the map
       * integration is ready. Reuse existing MapboxGL.MapView and
       * PublicLiveLocationMarker rendering from MapScreen.
       * Only render when isParticipating && markers.length > 0.
       * Do not display speed, route history, or ranking.
       */}
      {isParticipating && markers.length > 0 && (
        <Text
          testID="group-drive-map-placeholder"
          style={[styles.meta, { color: theme.colors.textSecondary }]}
        >
          {t('groupDrive.mapTitle')}: {markers.length}
        </Text>
      )}
    </ScreenContainer>
  );
};

const styles = StyleSheet.create({
  title: {
    fontSize: 20,
    fontWeight: '700',
    marginBottom: 8,
  },
  meta: {
    fontSize: 13,
  },
  safeDrivingBanner: {
    fontSize: 12,
    fontStyle: 'italic',
    textAlign: 'center',
  },
  disclaimer: {
    fontSize: 12,
  },
  centeredRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  statusRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
  },
  safeDrivingText: {
    fontSize: 12,
    fontStyle: 'italic',
    width: '100%',
  },
  countsRow: {
    flexDirection: 'row',
    justifyContent: 'space-around',
  },
  countItem: {
    alignItems: 'center',
  },
  countValue: {
    fontSize: 24,
    fontWeight: '700',
  },
  countLabel: {
    fontSize: 12,
  },
});
