/**
 * Saved drives list screen — "Mina körningar".
 *
 * Shows the authenticated user's saved drives, newest first.
 * Supports pagination, pull-to-refresh, deletion, and navigation to detail.
 *
 * Privacy:
 *  - Only shows the current user's drives.
 *  - No top speed, rankings, or other users.
 *  - Route overview is only accessible from the detail screen for eligible members.
 *
 * TODO: Add social sharing card preparation once sharing feature is specified.
 *   Future default sharing card must exclude exact routes, start/end points,
 *   other users, and live positions.
 */

import {
  ActivityIndicator,
  Alert,
  FlatList,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';

import type { SavedDriveListItem } from '@carcommunity/shared/saved-drives';

import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import type { RootStackParamList } from '../navigation/types';

import { useAppTheme } from '../hooks/useAppTheme';
import { useI18n } from '../hooks/useI18n';
import { useSavedDrives } from '../hooks/useSavedDrives';
import { ScreenContainer } from '../components/ScreenContainer';

type Props = NativeStackScreenProps<RootStackParamList, 'SavedDrives'>;

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

function formatDate(isoString: string): string {
  return new Date(isoString).toLocaleDateString('sv-SE', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  });
}

interface DriveCardProps {
  drive: SavedDriveListItem;
  onPress: () => void;
  onDelete: () => void;
}

const DriveCard = ({ drive, onPress, onDelete }: DriveCardProps) => {
  const { theme } = useAppTheme();
  const { t } = useI18n();

  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      style={[
        styles.card,
        {
          backgroundColor: theme.colors.surfaceBackground,
          borderColor: theme.colors.borderDefault,
          borderRadius: theme.radius.lg,
          padding: theme.spacing[4],
          marginBottom: theme.spacing[3],
        },
      ]}
    >
      <View style={styles.cardRow}>
        <Text style={[styles.cardDate, { color: theme.colors.textPrimary }]}>
          {formatDate(drive.startedAt)}
        </Text>
        <Pressable
          onPress={onDelete}
          accessibilityRole="button"
          accessibilityLabel={t('savedDrives.deleteAction')}
          hitSlop={8}
        >
          <Text style={[styles.deleteText, { color: theme.colors.statusError }]}>
            {t('savedDrives.deleteAction')}
          </Text>
        </Pressable>
      </View>

      <View style={[styles.cardRow, { marginTop: theme.spacing[2] }]}>
        <View style={styles.statItem}>
          <Text style={[styles.statLabel, { color: theme.colors.textSecondary }]}>
            {t('savedDrives.duration')}
          </Text>
          <Text style={[styles.statValue, { color: theme.colors.textPrimary }]}>
            {formatDuration(drive.durationSeconds)}
          </Text>
        </View>
        {drive.distanceMeters !== null && (
          <View style={styles.statItem}>
            <Text style={[styles.statLabel, { color: theme.colors.textSecondary }]}>
              {t('savedDrives.distance')}
            </Text>
            <Text style={[styles.statValue, { color: theme.colors.textPrimary }]}>
              {formatDistance(drive.distanceMeters)}
            </Text>
          </View>
        )}
      </View>

      {(drive.approximateStartArea ?? drive.approximateEndArea) && (
        <Text
          style={[styles.areaText, { color: theme.colors.textSecondary, marginTop: theme.spacing[1] }]}
          numberOfLines={1}
        >
          {[drive.approximateStartArea, drive.approximateEndArea]
            .filter(Boolean)
            .join(' → ')}
        </Text>
      )}
    </Pressable>
  );
};

export const SavedDrivesScreen = ({ navigation }: Props) => {
  const { t } = useI18n();
  const { theme } = useAppTheme();
  const { drives, isLoading, error, hasNext, loadMore, refresh, deleteDrive } = useSavedDrives();

  const handleDelete = (driveId: string) => {
    Alert.alert(
      t('savedDrives.deleteConfirmTitle'),
      t('savedDrives.deleteConfirmBody'),
      [
        { text: t('savedDrives.deleteConfirmCancel'), style: 'cancel' },
        {
          text: t('savedDrives.deleteConfirmAction'),
          style: 'destructive',
          onPress: () => void deleteDrive(driveId),
        },
      ],
    );
  };

  return (
    <ScreenContainer>
      {isLoading && drives.length === 0 && (
        <ActivityIndicator
          color={theme.colors.brandPrimary}
          style={{ marginTop: theme.spacing[8] }}
        />
      )}

      {error !== null && drives.length === 0 && (
        <View style={[styles.emptyContainer, { padding: theme.spacing[6] }]}>
          <Text style={[styles.emptyText, { color: theme.colors.statusError }]}>
            {t(error)}
          </Text>
        </View>
      )}

      {!isLoading && drives.length === 0 && error === null && (
        <View style={[styles.emptyContainer, { padding: theme.spacing[6] }]}>
          <Text style={[styles.emptyText, { color: theme.colors.textSecondary }]}>
            {t('savedDrives.empty')}
          </Text>
        </View>
      )}

      <FlatList
        data={drives}
        keyExtractor={(item) => item.id}
        contentContainerStyle={{ padding: theme.spacing[4] }}
        onRefresh={refresh}
        refreshing={isLoading && drives.length > 0}
        onEndReached={hasNext ? () => void loadMore() : undefined}
        onEndReachedThreshold={0.3}
        renderItem={({ item }) => (
          <DriveCard
            drive={item}
            onPress={() => navigation.navigate('SavedDriveDetail', { driveId: item.id })}
            onDelete={() => handleDelete(item.id)}
          />
        )}
        ListFooterComponent={
          isLoading && drives.length > 0 ? (
            <ActivityIndicator
              color={theme.colors.brandPrimary}
              style={{ marginVertical: theme.spacing[4] }}
            />
          ) : null
        }
      />
    </ScreenContainer>
  );
};

const styles = StyleSheet.create({
  card: {
    borderWidth: 1,
  },
  cardRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  cardDate: {
    fontSize: 15,
    fontWeight: '600',
  },
  deleteText: {
    fontSize: 13,
  },
  statItem: {
    marginRight: 16,
  },
  statLabel: {
    fontSize: 11,
    fontWeight: '500',
    textTransform: 'uppercase',
  },
  statValue: {
    fontSize: 14,
    fontWeight: '600',
    marginTop: 2,
  },
  areaText: {
    fontSize: 12,
  },
  emptyContainer: {
    alignItems: 'center',
  },
  emptyText: {
    fontSize: 15,
    textAlign: 'center',
  },
});
