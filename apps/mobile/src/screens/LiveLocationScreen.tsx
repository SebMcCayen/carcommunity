/**
 * Live location sharing screen.
 *
 * This screen allows users to voluntarily share their position with other
 * Community members for a fixed duration. All sharing is explicit opt-in.
 *
 * Foreground location permission is requested only when the user taps to
 * start sharing. Background location permission is requested only after
 * the user reads the rationale and explicitly agrees — never at startup.
 * No route history is stored or uploaded.
 * Backend is the source of truth for all access control decisions.
 */

import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';

import {
  LIVE_LOCATION_DURATIONS,
  type LiveLocationDuration,
} from '@carcommunity/shared/live-location';

import type { AppTheme } from '../design/theme';
import { useAppTheme } from '../hooks/useAppTheme';
import { useI18n } from '../hooks/useI18n';
import { type BackgroundPermissionMode, type LiveSharingStatus } from '../hooks/useLiveLocationSession';
import { useLiveLocation } from '../context/LiveLocationContext';
import { KccButton } from '../components/KccButton';
import { SaveDrivePromptModal } from '../components/SaveDrivePromptModal';
import { saveDrive, discardDrive } from '../api/saved-drives';
import { loadSessionToken } from '../storage/tokenStorage';

const STATUS_DOT_SIZE = 16;

const PRIVACY_KEYS = [
  'liveLocation.privacyOptional',
  'liveLocation.privacyTimeLimited',
  'liveLocation.privacyStopAnytime',
  'liveLocation.privacyNoHistory',
  'liveLocation.privacySafeDriving',
] as const;

const ACCESS_KEYS = [
  'liveLocation.shareOwnFree',
  'liveLocation.viewOthersMemberOnly',
  'liveLocation.accessLimitedByModeration',
] as const;

type DurationChipProps = {
  label: string;
  selected: boolean;
  onPress: () => void;
  theme: AppTheme;
};

const DurationChip = ({ label, selected, onPress, theme }: DurationChipProps) => (
  <Pressable
    onPress={onPress}
    accessibilityRole="radio"
    accessibilityState={{ selected }}
    style={[
      styles.chip,
      {
        backgroundColor: selected ? theme.colors.brandPrimary : theme.colors.surfaceBackground,
        borderColor: selected ? theme.colors.brandPrimary : theme.colors.borderDefault,
        borderRadius: theme.radius.full,
        paddingHorizontal: theme.spacing[4],
        paddingVertical: theme.spacing[2],
      },
    ]}
  >
    <Text
      style={[
        styles.chipText,
        {
          color: selected ? theme.colors.textPrimary : theme.colors.textSecondary,
          fontWeight: selected ? '600' : '400',
        },
      ]}
    >
      {label}
    </Text>
  </Pressable>
);

function getStatusColor(status: LiveSharingStatus, theme: AppTheme): string {
  switch (status) {
    case 'sharing':
      return theme.colors.statusSuccess;
    case 'starting':
    case 'stopping':
      return theme.colors.brandPrimary;
    case 'error':
    case 'permission_denied':
      return theme.colors.statusError;
    default:
      return theme.colors.textSecondary;
  }
}

function getBgModeLabel(mode: BackgroundPermissionMode, t: (key: string) => string): string {
  switch (mode) {
    case 'granted':
      return t('liveLocation.backgroundSharingActive');
    case 'foreground_only':
      return t('liveLocation.foregroundOnlySharing');
    default:
      return '';
  }
}

function formatLastUpdated(date: Date | null, label: string): string | null {
  if (!date) return null;
  return `${label}: ${date.toLocaleTimeString()}`;
}

export const LiveLocationScreen = () => {
  const { t } = useI18n();
  const { theme } = useAppTheme();
  const {
    status,
    selectedDuration,
    sessionId,
    sessionExpiresAt,
    backgroundPermissionMode,
    error,
    isLoading,
    lastUpdatedAt,
    stoppedSessionId,
    dismissSavePrompt,
    selectDuration,
    startSession,
    stopSession,
    hideMeNow,
    requestBackgroundPermission,
    skipBackgroundPermission,
  } = useLiveLocation();

  const hasActiveSession = sessionId !== null;
  // Show sharing controls when actively sharing, or when an error occurred
  // while a backend session is still open — prevents orphaning an active session.
  const isSharing = status === 'sharing' || (status === 'error' && hasActiveSession);
  // Show start/duration controls only when there is no lingering session.
  const isNotSharing =
    status === 'not_sharing' ||
    status === 'permission_denied' ||
    (status === 'error' && !hasActiveSession);
  const isBusy = status === 'starting' || status === 'stopping' || isLoading;

  // Show the background permission rationale card when sharing is active
  // and the user hasn't yet been asked or declined.
  const showBackgroundRationale = isSharing && backgroundPermissionMode === 'not_requested';

  const statusColor = getStatusColor(status, theme);

  const statusLabel: Record<LiveSharingStatus, string> = {
    not_sharing: t('liveLocation.statusNotSharing'),
    permission_denied: t('liveLocation.statusPermissionDenied'),
    starting: t('liveLocation.statusStarting'),
    sharing: t('liveLocation.statusSharing'),
    stopping: t('liveLocation.statusStopping'),
    error: t('liveLocation.statusError'),
  };

  const durationLabels: Record<LiveLocationDuration, string> = {
    '1h': t('liveLocation.duration1h'),
    '2h': t('liveLocation.duration2h'),
    '4h': t('liveLocation.duration4h'),
  };

  const lastUpdatedText = formatLastUpdated(lastUpdatedAt, t('liveLocation.lastUpdated'));

  const backgroundModeLabel = getBgModeLabel(backgroundPermissionMode, t);

  const sessionExpiresText = sessionExpiresAt
    ? `${t('liveLocation.sessionAutoExpires')}: ${sessionExpiresAt.toLocaleTimeString()}`
    : null;

  /**
   * Handle save drive from the post-drive prompt.
   * Requires explicit user action — never called automatically.
   * "Dölj mig nu" never reaches this path.
   */
  const handleSaveDrive = async (sid: string) => {
    const auth = await loadSessionToken().catch(() => null);
    await saveDrive(sid, auth?.token ?? undefined);
  };

  /**
   * Handle discard drive from the post-drive prompt.
   * Deletes any temporary route data. Does not save a drive.
   */
  const handleDiscardDrive = async (sid: string) => {
    const auth = await loadSessionToken().catch(() => null);
    await discardDrive(sid, auth?.token ?? undefined);
  };

  return (
    <>
    <ScrollView
      style={{ flex: 1, backgroundColor: theme.colors.pageBackground }}
      contentContainerStyle={[
        styles.container,
        { padding: theme.spacing[4], paddingBottom: theme.spacing[10] },
      ]}
    >
      {/* Status area — large for safe driving visibility */}
      <View
        style={[
          styles.statusCard,
          {
            backgroundColor: theme.colors.surfaceBackground,
            borderColor: theme.colors.borderDefault,
            borderRadius: theme.radius.lg,
            padding: theme.spacing[6],
          },
        ]}
        accessibilityRole="none"
      >
        <View style={[styles.statusDot, { backgroundColor: statusColor }]} />
        <View style={styles.statusTextContainer}>
          <Text
            style={[styles.statusText, { color: statusColor }]}
            accessibilityRole="text"
            accessibilityLabel={statusLabel[status]}
          >
            {statusLabel[status]}
          </Text>
          {isSharing && backgroundPermissionMode !== 'not_requested' && (
            <Text
              style={[styles.bgModeText, { color: theme.colors.textSecondary }]}
              accessibilityRole="text"
            >
              {backgroundModeLabel}
            </Text>
          )}
          {isSharing && lastUpdatedText !== null && (
            <Text style={[styles.lastUpdatedText, { color: theme.colors.textSecondary }]}>
              {lastUpdatedText}
            </Text>
          )}
          {isSharing && sessionExpiresText !== null && (
            <Text style={[styles.lastUpdatedText, { color: theme.colors.textSecondary }]}>
              {sessionExpiresText}
            </Text>
          )}
        </View>
      </View>

      {/* Safe driving warning — shown prominently while sharing */}
      {isSharing && (
        <View
          style={[
            styles.warningBanner,
            {
              backgroundColor: theme.colors.subtleBackground,
              borderColor: theme.colors.borderDefault,
              borderRadius: theme.radius.md,
              padding: theme.spacing[4],
            },
          ]}
          accessibilityRole="alert"
        >
          <Text style={[styles.warningText, { color: theme.colors.textSecondary }]}>
            {t('liveLocation.safeDrivingWarning')}
          </Text>
        </View>
      )}

      {/* Background permission rationale — only shown once, after session starts */}
      {showBackgroundRationale && (
        <View
          style={[
            styles.rationaleCard,
            {
              backgroundColor: theme.colors.surfaceBackground,
              borderColor: theme.colors.borderDefault,
              borderRadius: theme.radius.lg,
              padding: theme.spacing[4],
              gap: theme.spacing[3],
            },
          ]}
          accessibilityRole="none"
        >
          <Text style={[styles.sectionLabel, { color: theme.colors.textPrimary }]}>
            {t('liveLocation.backgroundPermissionTitle')}
          </Text>
          <Text style={[styles.rationaleBody, { color: theme.colors.textSecondary }]}>
            {t('liveLocation.backgroundPermissionRationale')}
          </Text>
          <Text style={[styles.rationaleBody, { color: theme.colors.textSecondary }]}>
            {t('liveLocation.backgroundPermissionTimeLimited')}
          </Text>
          <Text style={[styles.rationaleBody, { color: theme.colors.textSecondary }]}>
            {t('liveLocation.backgroundPermissionControl')}
          </Text>
          <Text style={[styles.rationaleBody, { color: theme.colors.textSecondary }]}>
            {t('liveLocation.backgroundPermissionNoTracking')}
          </Text>
          <KccButton
            label={t('liveLocation.backgroundPermissionAllow')}
            onPress={() =>
              requestBackgroundPermission(
                t('liveLocation.backgroundNotificationTitle'),
                t('liveLocation.backgroundNotificationBody'),
              )
            }
            variant="secondary"
            disabled={isBusy}
          />
          <KccButton
            label={t('liveLocation.backgroundPermissionSkip')}
            onPress={skipBackgroundPermission}
            variant="secondary"
            disabled={isBusy}
          />
        </View>
      )}

      {/* Background permission denied explanation */}
      {isSharing && backgroundPermissionMode === 'foreground_only' && (
        <View
          style={[
            styles.infoBanner,
            {
              backgroundColor: theme.colors.surfaceBackground,
              borderColor: theme.colors.borderDefault,
              borderRadius: theme.radius.md,
              padding: theme.spacing[4],
            },
          ]}
          accessibilityRole="none"
        >
          <Text style={[styles.infoText, { color: theme.colors.textSecondary }]}>
            {t('liveLocation.backgroundPermissionDenied')}
          </Text>
        </View>
      )}

      {/* Permission denied explanation */}
      {status === 'permission_denied' && (
        <View
          style={[
            styles.errorBanner,
            {
              backgroundColor: theme.colors.surfaceBackground,
              borderColor: theme.colors.statusError,
              borderRadius: theme.radius.md,
              padding: theme.spacing[4],
            },
          ]}
          accessibilityRole="alert"
        >
          <Text style={[styles.errorText, { color: theme.colors.statusError }]}>
            {t('liveLocation.permissionDenied')}
          </Text>
        </View>
      )}

      {/* General error message */}
      {error !== null && status !== 'permission_denied' && (
        <View
          style={[
            styles.errorBanner,
            {
              backgroundColor: theme.colors.surfaceBackground,
              borderColor: theme.colors.statusError,
              borderRadius: theme.radius.md,
              padding: theme.spacing[4],
            },
          ]}
          accessibilityRole="alert"
        >
          <Text style={[styles.errorText, { color: theme.colors.statusError }]}>{t(error)}</Text>
        </View>
      )}

      {/* Duration selector — only shown when not actively sharing */}
      {isNotSharing && (
        <View
          style={[
            styles.durationCard,
            {
              backgroundColor: theme.colors.surfaceBackground,
              borderColor: theme.colors.borderDefault,
              borderRadius: theme.radius.lg,
              padding: theme.spacing[4],
              gap: theme.spacing[3],
            },
          ]}
        >
          <Text style={[styles.sectionLabel, { color: theme.colors.textPrimary }]}>
            {t('liveLocation.durationLabel')}
          </Text>
          <View style={styles.durationRow} accessibilityRole="radiogroup">
            {LIVE_LOCATION_DURATIONS.map((d: LiveLocationDuration) => (
              <DurationChip
                key={d}
                label={durationLabels[d] ?? d}
                selected={selectedDuration === d}
                onPress={() => selectDuration(d)}
                theme={theme}
              />
            ))}
          </View>
        </View>
      )}

      {/* Primary action — start sharing */}
      {isNotSharing && (
        <KccButton
          label={isBusy ? t('liveLocation.statusStarting') : t('liveLocation.start')}
          onPress={startSession}
          variant="primary"
          disabled={isBusy}
        />
      )}

      {/* Active sharing actions — large buttons for safe driving */}
      {isSharing && (
        <View style={[styles.activeActions, { gap: theme.spacing[3] }]}>
          <KccButton
            label={isBusy ? t('liveLocation.statusStopping') : t('liveLocation.stop')}
            onPress={stopSession}
            variant="secondary"
            disabled={isBusy}
          />
          <KccButton
            label={t('liveLocation.hideNow')}
            onPress={hideMeNow}
            variant="destructive"
            disabled={isBusy}
          />
        </View>
      )}

      {/* Who can see you + privacy/safety copy */}
      <View
        style={[
          styles.privacyCard,
          {
            backgroundColor: theme.colors.subtleBackground,
            borderColor: theme.colors.borderDefault,
            borderRadius: theme.radius.lg,
            padding: theme.spacing[4],
            gap: theme.spacing[3],
          },
        ]}
      >
        <Text style={[styles.privacyTitle, { color: theme.colors.textPrimary }]}>
          {t('liveLocation.whoCanSeeTitle')}
        </Text>
        <Text style={[styles.privacyBody, { color: theme.colors.textSecondary }]}>
          {t('liveLocation.whoCanSeeBody')}
        </Text>
        <View style={{ gap: theme.spacing[2] }}>
          {ACCESS_KEYS.map((key) => (
            <Text key={key} style={[styles.privacyItem, { color: theme.colors.textSecondary }]}>
              {'• '}
              {t(key)}
            </Text>
          ))}
        </View>
        <View style={{ gap: theme.spacing[2] }}>
          {PRIVACY_KEYS.map((key) => (
            <Text key={key} style={[styles.privacyItem, { color: theme.colors.textSecondary }]}>
              {'• '}
              {t(key)}
            </Text>
          ))}
        </View>
      </View>
    </ScrollView>
    <SaveDrivePromptModal
      visible={stoppedSessionId !== null}
      sessionId={stoppedSessionId}
      onSave={handleSaveDrive}
      onDiscard={handleDiscardDrive}
      onDismiss={dismissSavePrompt}
    />
    </>
  );
};

const styles = StyleSheet.create({
  container: {
    gap: 16,
  },
  statusCard: {
    borderWidth: 1,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    minHeight: 72,
  },
  statusDot: {
    width: STATUS_DOT_SIZE,
    height: STATUS_DOT_SIZE,
    borderRadius: STATUS_DOT_SIZE / 2,
    flexShrink: 0,
  },
  statusTextContainer: {
    flexShrink: 1,
    gap: 4,
  },
  statusText: {
    fontSize: 22,
    fontWeight: '600',
  },
  lastUpdatedText: {
    fontSize: 13,
    fontWeight: '400',
  },
  warningBanner: {
    borderWidth: 1,
  },
  warningText: {
    fontSize: 14,
    fontWeight: '500',
    textAlign: 'center',
  },
  errorBanner: {
    borderWidth: 1,
  },
  errorText: {
    fontSize: 14,
    fontWeight: '500',
  },
  durationCard: {
    borderWidth: 1,
  },
  sectionLabel: {
    fontSize: 14,
    fontWeight: '600',
  },
  durationRow: {
    flexDirection: 'row',
    gap: 8,
  },
  chip: {
    borderWidth: 1,
  },
  chipText: {
    fontSize: 14,
  },
  activeActions: {
    flexDirection: 'column',
  },
  rationaleCard: {
    borderWidth: 1,
  },
  rationaleBody: {
    fontSize: 13,
    lineHeight: 19,
  },
  infoBanner: {
    borderWidth: 1,
  },
  infoText: {
    fontSize: 13,
    lineHeight: 19,
  },
  bgModeText: {
    fontSize: 13,
    fontWeight: '400',
  },
  privacyCard: {
    borderWidth: 1,
  },
  privacyTitle: {
    fontSize: 16,
    fontWeight: '600',
  },
  privacyBody: {
    fontSize: 14,
    lineHeight: 20,
  },
  privacyItem: {
    fontSize: 13,
    lineHeight: 19,
  },
});
