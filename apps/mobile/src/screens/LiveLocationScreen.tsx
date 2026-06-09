/**
 * Live location sharing screen.
 *
 * This screen allows users to voluntarily share their position with other
 * Community members for a fixed duration. All sharing is explicit opt-in.
 *
 * TODO: Request foreground location permission before starting a session.
 * TODO: Request background location only during an active session.
 * TODO: Add Android foreground notification when background tracking is introduced.
 * TODO: Add iOS background location handling only during an active session.
 * TODO: Throttle position updates to ~25–50 m or 5–10 s once device GPS is wired in.
 * TODO: Send only the latest position — no route history is ever uploaded.
 * TODO: Add Mapbox map rendering to display nearby members in a later step.
 * TODO: Enforce safe driving mode — suppress distracting interactions when device is in motion.
 * TODO: Backend must enforce feature access. Client-side flag is UI-only; never trust it for security.
 */

import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';

import {
  LIVE_LOCATION_DURATIONS,
  type LiveLocationDuration,
} from '@carcommunity/shared/live-location';

import type { AppTheme } from '../design/theme';
import { useAppTheme } from '../hooks/useAppTheme';
import { useI18n } from '../hooks/useI18n';
import { type LiveSharingStatus, useLiveLocationSession } from '../hooks/useLiveLocationSession';
import { KccButton } from '../components/KccButton';

const STATUS_DOT_SIZE = 16;

const PRIVACY_KEYS = [
  'liveLocation.privacyOptional',
  'liveLocation.privacyTimeLimited',
  'liveLocation.privacyStopAnytime',
  'liveLocation.privacyNoHistory',
  'liveLocation.privacySafeDriving',
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
      return theme.colors.statusError;
    default:
      return theme.colors.textSecondary;
  }
}

export const LiveLocationScreen = () => {
  const { t } = useI18n();
  const { theme } = useAppTheme();
  const {
    status,
    selectedDuration,
    error,
    isLoading,
    selectDuration,
    startSession,
    stopSession,
    hideMeNow,
  } = useLiveLocationSession();

  const isSharing = status === 'sharing';
  const isNotSharing = status === 'not_sharing' || status === 'error';
  const isBusy = status === 'starting' || status === 'stopping' || isLoading;

  const statusColor = getStatusColor(status, theme);

  const statusLabel: Record<LiveSharingStatus, string> = {
    not_sharing: t('liveLocation.statusNotSharing'),
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

  return (
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
        <Text
          style={[styles.statusText, { color: statusColor }]}
          accessibilityRole="text"
          accessibilityLabel={statusLabel[status]}
        >
          {statusLabel[status]}
        </Text>
      </View>

      {/* Error message */}
      {error !== null && (
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
        <View style={[styles.privacyList, { gap: theme.spacing[2] }]}>
          {PRIVACY_KEYS.map((key) => (
            <Text key={key} style={[styles.privacyItem, { color: theme.colors.textSecondary }]}>
              {'• '}
              {t(key)}
            </Text>
          ))}
        </View>
      </View>
    </ScrollView>
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
  statusText: {
    fontSize: 22,
    fontWeight: '600',
    flexShrink: 1,
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
  privacyList: {},
  privacyItem: {
    fontSize: 13,
    lineHeight: 19,
  },
});
