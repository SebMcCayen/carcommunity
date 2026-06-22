/**
 * Saved drive detail screen.
 *
 * Shows drive summary and, for eligible members, a route overview placeholder.
 *
 * Privacy:
 *  - No top speed, speed rankings, or other users.
 *  - Route overview only shown for members; null is shown gracefully.
 *  - Raw coordinate lists are never rendered.
 *  - Protected data (routeOverview) is cleared on unmount via useSavedDriveDetail.
 *
 * TODO: Add map-based route overview rendering for members once Mapbox
 *       integration and TemporaryDrivePoint route buffer are implemented.
 */

import { ActivityIndicator, ScrollView, StyleSheet, Text, View } from 'react-native';

import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import type { RootStackParamList } from '../navigation/types';

import { useAppTheme } from '../hooks/useAppTheme';
import { useI18n } from '../hooks/useI18n';
import { useSavedDriveDetail } from '../hooks/useSavedDrives';
import { ScreenContainer } from '../components/ScreenContainer';

type Props = NativeStackScreenProps<RootStackParamList, 'SavedDriveDetail'>;

function formatDuration(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (h > 0) return `${h}h ${m}min`;
  return `${m}min`;
}

function formatDistance(metres: number | null): string | null {
  if (metres === null) return null;
  if (metres >= 1000) return `${(metres / 1000).toFixed(1)} km`;
  return `${Math.round(metres)} m`;
}

function formatSpeed(mps: number | null): string | null {
  if (mps === null) return null;
  const kmh = mps * 3.6;
  return `${kmh.toFixed(1)} km/h`;
}

function formatDate(isoString: string): string {
  return new Date(isoString).toLocaleString('sv-SE', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

interface StatRowProps {
  label: string;
  value: string;
}

const StatRow = ({ label, value }: StatRowProps) => {
  const { theme } = useAppTheme();
  return (
    <View
      style={[styles.statRow, { borderBottomColor: theme.colors.borderDefault }]}
    >
      <Text style={[styles.statLabel, { color: theme.colors.textSecondary }]}>{label}</Text>
      <Text style={[styles.statValue, { color: theme.colors.textPrimary }]}>{value}</Text>
    </View>
  );
};

export const SavedDriveDetailScreen = ({ route }: Props) => {
  const { driveId } = route.params;
  const { t } = useI18n();
  const { theme } = useAppTheme();
  const { drive, isLoading, error } = useSavedDriveDetail(driveId);

  return (
    <ScreenContainer>
      <ScrollView contentContainerStyle={[styles.container, { padding: theme.spacing[4] }]}>
        {isLoading && (
          <ActivityIndicator color={theme.colors.brandPrimary} style={{ marginTop: theme.spacing[8] }} />
        )}

        {error !== null && (
          <Text style={[styles.errorText, { color: theme.colors.statusError }]}>
            {t(error)}
          </Text>
        )}

        {drive !== null && (
          <>
            <Text style={[styles.dateText, { color: theme.colors.textSecondary }]}>
              {formatDate(drive.startedAt)}
            </Text>

            <View
              style={[
                styles.card,
                {
                  backgroundColor: theme.colors.surfaceBackground,
                  borderColor: theme.colors.borderDefault,
                  borderRadius: theme.radius.lg,
                  marginTop: theme.spacing[4],
                },
              ]}
            >
              <StatRow label={t('savedDrives.duration')} value={formatDuration(drive.durationSeconds)} />
              {drive.distanceMeters !== null && (
                <StatRow label={t('savedDrives.distance')} value={formatDistance(drive.distanceMeters) ?? '-'} />
              )}
              {drive.averageSpeedMetersPerSecond !== null && (
                <StatRow
                  label={t('savedDrives.averageSpeed')}
                  value={formatSpeed(drive.averageSpeedMetersPerSecond) ?? '-'}
                />
              )}
              {drive.approximateStartArea !== null && (
                <StatRow label={t('savedDrives.startArea')} value={drive.approximateStartArea} />
              )}
              {drive.approximateEndArea !== null && (
                <StatRow label={t('savedDrives.endArea')} value={drive.approximateEndArea} />
              )}
            </View>

            {drive.routeOverview !== null && drive.routeOverview.length > 0 ? (
              <View
                style={[
                  styles.routeCard,
                  {
                    backgroundColor: theme.colors.surfaceBackground,
                    borderColor: theme.colors.borderDefault,
                    borderRadius: theme.radius.lg,
                    padding: theme.spacing[4],
                    marginTop: theme.spacing[4],
                  },
                ]}
              >
                <Text style={[styles.routeTitle, { color: theme.colors.textPrimary }]}>
                  {t('savedDrives.routeOverview')}
                </Text>
                {/* TODO: Render Mapbox route overlay once map integration is ready.
                    Do NOT render raw coordinate lists. Show only a visual map route. */}
                <Text style={[styles.routePlaceholder, { color: theme.colors.textSecondary }]}>
                  {t('savedDrives.routeOverviewPlaceholder')}
                </Text>
              </View>
            ) : (
              drive.routeOverview === null && (
                <View
                  style={[
                    styles.routeCard,
                    {
                      backgroundColor: theme.colors.subtleBackground,
                      borderColor: theme.colors.borderDefault,
                      borderRadius: theme.radius.lg,
                      padding: theme.spacing[4],
                      marginTop: theme.spacing[4],
                    },
                  ]}
                >
                  <Text style={[styles.routePlaceholder, { color: theme.colors.textSecondary }]}>
                    {t('savedDrives.routeOverviewMemberOnly')}
                  </Text>
                </View>
              )
            )}
          </>
        )}
      </ScrollView>
    </ScreenContainer>
  );
};

const styles = StyleSheet.create({
  container: {
    gap: 0,
  },
  dateText: {
    fontSize: 13,
    marginBottom: 4,
  },
  card: {
    borderWidth: 1,
    overflow: 'hidden',
  },
  statRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderBottomWidth: 1,
  },
  statLabel: {
    fontSize: 13,
  },
  statValue: {
    fontSize: 15,
    fontWeight: '600',
  },
  routeCard: {
    borderWidth: 1,
  },
  routeTitle: {
    fontSize: 15,
    fontWeight: '600',
    marginBottom: 8,
  },
  routePlaceholder: {
    fontSize: 13,
    lineHeight: 18,
  },
  errorText: {
    fontSize: 14,
    marginTop: 16,
    textAlign: 'center',
  },
});
