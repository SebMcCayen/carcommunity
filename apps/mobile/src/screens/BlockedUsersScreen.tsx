import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, Alert, FlatList, StyleSheet, Text, View } from 'react-native';

import type { BlockedUserSummary } from '@carcommunity/shared/blocking';

import { listBlockedUsers, unblockUser } from '../api/blocking';
import { KccButton } from '../components/KccButton';
import { ScreenContainer } from '../components/ScreenContainer';
import { useAppTheme } from '../hooks/useAppTheme';
import { useAuth } from '../hooks/useAuth';
import { useI18n } from '../hooks/useI18n';

export const BlockedUsersScreen = () => {
  const { t } = useI18n();
  const { theme } = useAppTheme();
  const { withToken } = useAuth();

  const [blockedUsers, setBlockedUsers] = useState<BlockedUserSummary[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchBlockedUsers = useCallback(async () => {
    setIsLoading(true);
    setError(null);

    try {
      await withToken(async (token) => {
        const response = await listBlockedUsers(1, 20, token);
        setBlockedUsers(response.data.blockedUsers);
      });
    } catch {
      setError(t('blocking.errorGeneric'));
    } finally {
      setIsLoading(false);
    }
  }, [t, withToken]);

  useEffect(() => {
    void fetchBlockedUsers();
  }, [fetchBlockedUsers]);

  const handleUnblock = useCallback(
    (userId: string, displayName: string | null | undefined) => {
      const name = displayName ?? t('blocking.blockedUsersTitle');

      Alert.alert(
        t('blocking.unblockConfirmTitle'),
        name,
        [
          {
            text: t('blocking.unblockCancelAction'),
            style: 'cancel',
          },
          {
            text: t('blocking.unblockConfirmAction'),
            style: 'destructive',
            onPress: () => {
              void (async () => {
                try {
                  await withToken(async (token) => {
                    await unblockUser(userId, token);
                  });
                  setBlockedUsers((prev) => prev.filter((u) => u.userId !== userId));
                } catch {
                  Alert.alert(t('blocking.errorGeneric'));
                }
              })();
            },
          },
        ],
        { cancelable: true },
      );
    },
    [t, withToken],
  );

  if (isLoading) {
    return (
      <ScreenContainer>
        <View style={styles.centered}>
          <ActivityIndicator color={theme.colors.brandPrimary} />
        </View>
      </ScreenContainer>
    );
  }

  if (error) {
    return (
      <ScreenContainer>
        <Text style={[styles.errorText, { color: theme.colors.statusError }]}>{error}</Text>
      </ScreenContainer>
    );
  }

  return (
    <ScreenContainer>
      {blockedUsers.length === 0 ? (
        <Text
          style={[styles.emptyText, { color: theme.colors.textSecondary }]}
          accessibilityRole="text"
        >
          {t('blocking.blockedUsersEmpty')}
        </Text>
      ) : (
        <FlatList
          data={blockedUsers}
          keyExtractor={(item) => item.userId}
          renderItem={({ item }) => (
            <View
              style={[styles.row, { borderBottomColor: theme.colors.borderDefault }]}
              accessible
              accessibilityRole="none"
            >
              <View style={styles.rowInfo}>
                {/* Display name shown if available; internal userId is never shown in UI */}
                <Text
                  style={[styles.displayName, { color: theme.colors.textPrimary }]}
                  numberOfLines={1}
                  accessibilityRole="text"
                >
                  {item.displayName ?? '—'}
                </Text>
              </View>
              <KccButton
                label={t('blocking.unblock')}
                variant="secondary"
                onPress={() => handleUnblock(item.userId, item.displayName)}
              />
            </View>
          )}
          scrollEnabled={false}
        />
      )}
    </ScreenContainer>
  );
};

const styles = StyleSheet.create({
  centered: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  errorText: {
    fontSize: 14,
    marginTop: 16,
  },
  emptyText: {
    fontSize: 14,
    marginTop: 16,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  rowInfo: {
    flex: 1,
    marginRight: 8,
  },
  displayName: {
    fontSize: 15,
    fontWeight: '500',
  },
});
