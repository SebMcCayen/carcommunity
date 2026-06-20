import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, RefreshControl, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { canAccessMemberFeatures } from '@carcommunity/shared/users';
import type { EventTeaser } from '@carcommunity/shared/events';

import { loadEventTeasers } from '../api/events';
import { KccButton } from '../components/KccButton';
import { LockedFeatureNotice } from '../components/LockedFeatureNotice';
import { ScreenContainer } from '../components/ScreenContainer';
import { useAppTheme } from '../hooks/useAppTheme';
import { useAuth } from '../hooks/useAuth';
import { useI18n } from '../hooks/useI18n';
import type { RootStackParamList } from '../navigation/types';

type EventsScreenNavProp = NativeStackNavigationProp<RootStackParamList>;

/**
 * EventTeaserCard shows safe public event info for all authenticated users.
 * Exact location details are never shown here — those require a member subscription.
 */
function EventTeaserCard({
  event,
  isMember,
  onPress,
}: {
  event: EventTeaser;
  isMember: boolean;
  onPress: () => void;
}) {
  const { theme } = useAppTheme();
  const { t, locale } = useI18n();

  const isCancelled = event.status === 'cancelled';

  return (
    <TouchableOpacity
      testID={`event-teaser-card-${event.id}`}
      accessibilityRole="button"
      onPress={onPress}
      style={[
        styles.teaserCard,
        {
          backgroundColor: theme.colors.surfaceBackground,
          borderColor: event.isOfficial ? theme.colors.brandPrimary : theme.colors.borderDefault,
          borderRadius: theme.radius.lg,
          padding: theme.spacing[4],
          gap: theme.spacing[2],
          opacity: isCancelled ? 0.7 : 1,
        },
      ]}
    >
      {event.isOfficial && (
        <Text style={[styles.officialBadge, { color: theme.colors.brandPrimary }]}>
          {t('events.officialBadge')}
        </Text>
      )}
      {isCancelled && (
        <Text
          testID={`event-cancelled-badge-${event.id}`}
          style={[styles.cancelledBadge, { color: theme.colors.statusError }]}
        >
          {t('events.cancelledBadge')}
        </Text>
      )}
      <Text style={[styles.teaserTitle, { color: theme.colors.textPrimary }]}>{event.title}</Text>
      <Text style={[styles.teaserMeta, { color: theme.colors.textSecondary }]}>
        {new Date(event.startsAt).toLocaleDateString(locale, {
          weekday: 'long',
          day: 'numeric',
          month: 'long',
          year: 'numeric',
        })}
      </Text>
      <Text style={[styles.teaserMeta, { color: theme.colors.textSecondary }]}>
        {event.approximateArea}
      </Text>
      {!isMember && (
        <Text
          testID={`event-lock-indicator-${event.id}`}
          style={[styles.lockIndicator, { color: theme.colors.textSecondary }]}
        >
          {t('events.lockIndicator')}
        </Text>
      )}
    </TouchableOpacity>
  );
}

/**
 * EventsScreen — Upcoming event list.
 *
 * All authenticated users see event teasers (safe fields only — no exact location).
 * Free users see a membership notice; members can tap a card to see full details.
 */
export const EventsScreen = () => {
  const { t } = useI18n();
  const { theme } = useAppTheme();
  const navigation = useNavigation<EventsScreenNavProp>();
  const { currentUser, withToken } = useAuth();

  // Client-side entitlement check — UX only. Backend enforces the real decision.
  const isMember =
    currentUser !== null &&
    canAccessMemberFeatures({
      role: currentUser.roles[0] ?? 'user',
      status: currentUser.status,
      subscriptionEntitlement: currentUser.subscriptionEntitlement,
    });

  const [teasers, setTeasers] = useState<EventTeaser[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [isLoadingMore, setIsLoadingMore] = useState(false);

  const fetchTeasers = useCallback(
    async (opts: { refresh?: boolean } = {}) => {
      if (opts.refresh) {
        setIsRefreshing(true);
      } else {
        setIsLoading(true);
      }
      setError(null);

      try {
        const result = await withToken((token) =>
          loadEventTeasers({ token }),
        );
        if (result) {
          setTeasers(result.data.events);
          setNextCursor(result.meta.nextCursor);
        }
      } catch {
        setError(t('events.error'));
      } finally {
        setIsLoading(false);
        setIsRefreshing(false);
      }
    },
    [t, withToken],
  );

  const loadMore = useCallback(async () => {
    if (!nextCursor || isLoadingMore) return;
    setIsLoadingMore(true);
    try {
      const result = await withToken((token) =>
        loadEventTeasers({ cursor: nextCursor, token }),
      );
      if (result) {
        setTeasers((prev) => [...prev, ...result.data.events]);
        setNextCursor(result.meta.nextCursor);
      }
    } catch {
      // Ignore load-more errors — the user can pull-to-refresh
    } finally {
      setIsLoadingMore(false);
    }
  }, [isLoadingMore, nextCursor, withToken]);

  useEffect(() => {
    void fetchTeasers();
  }, [fetchTeasers]);

  const handleCardPress = useCallback(
    (event: EventTeaser) => {
      navigation.navigate('EventDetail', { eventId: event.id, teaser: event });
    },
    [navigation],
  );

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------

  return (
    <ScreenContainer
      testID="events-screen"
      refreshControl={
        <RefreshControl
          refreshing={isRefreshing}
          onRefresh={() => void fetchTeasers({ refresh: true })}
          tintColor={theme.colors.brandPrimary}
        />
      }
    >
      {/* Loading state */}
      {isLoading && (
        <View testID="events-loading" style={styles.centeredRow}>
          <ActivityIndicator color={theme.colors.brandPrimary} />
          <Text style={[styles.meta, { color: theme.colors.textSecondary }]}>
            {t('events.loading')}
          </Text>
        </View>
      )}

      {/* Error state */}
      {error !== null && !isLoading && (
        <View testID="events-error" style={{ gap: theme.spacing[2] }}>
          <Text style={[styles.meta, { color: theme.colors.statusError }]}>{error}</Text>
          <KccButton
            testID="events-retry"
            label={t('events.retry')}
            variant="secondary"
            onPress={() => void fetchTeasers()}
          />
        </View>
      )}

      {/* Empty state */}
      {!isLoading && error === null && teasers.length === 0 && (
        <View testID="events-no-upcoming">
          <Text style={[styles.noUpcomingTitle, { color: theme.colors.textPrimary }]}>
            {t('events.noUpcomingTitle')}
          </Text>
          <Text style={[styles.meta, { color: theme.colors.textSecondary }]}>
            {t('events.noUpcomingBody')}
          </Text>
        </View>
      )}

      {/* Event list */}
      {teasers.map((event) => (
        <EventTeaserCard
          key={event.id}
          event={event}
          isMember={isMember}
          onPress={() => handleCardPress(event)}
        />
      ))}

      {/* Load more */}
      {nextCursor !== null && !isLoadingMore && (
        <KccButton
          testID="events-load-more"
          label={t('events.loadMore')}
          variant="secondary"
          onPress={() => void loadMore()}
        />
      )}
      {isLoadingMore && (
        <ActivityIndicator
          testID="events-loading-more"
          color={theme.colors.brandPrimary}
        />
      )}

      {/* Membership gate notice for free users */}
      {!isMember && (
        <LockedFeatureNotice
          testID="events-member-upgrade-banner"
          message={t('events.memberRequiredBody')}
        />
      )}
    </ScreenContainer>
  );
};

const styles = StyleSheet.create({
  teaserCard: {
    borderWidth: 1,
  },
  officialBadge: {
    fontSize: 12,
    fontWeight: '700',
    textTransform: 'uppercase',
    letterSpacing: 0.5,
  },
  cancelledBadge: {
    fontSize: 11,
    fontWeight: '700',
    textTransform: 'uppercase',
    letterSpacing: 0.5,
  },
  teaserTitle: {
    fontSize: 16,
    fontWeight: '600',
  },
  teaserMeta: {
    fontSize: 13,
    lineHeight: 18,
  },
  lockIndicator: {
    fontSize: 11,
  },
  meta: {
    fontSize: 13,
    lineHeight: 18,
  },
  noUpcomingTitle: {
    fontSize: 16,
    fontWeight: '600',
    marginBottom: 4,
  },
  centeredRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
});

