/**
 * NotificationsScreen — in-app notification inbox.
 *
 * Shows:
 *  - Unread count indicator
 *  - Paginated notification list (newest first)
 *  - Per-notification: category icon, title, preview, date, read/unread state
 *  - Empty, loading, and error states
 *  - "Markera alla som lästa" action
 *
 * Privacy rules:
 *  - Only shows current user's own notifications.
 *  - Does not render notification content as raw HTML.
 *  - Does not navigate to protected data without backend revalidation.
 *  - Only opens allowlisted internal app destinations.
 *  - Notification state is cleared on logout (via useNotifications cleanup).
 *
 * Accessibility:
 *  - All interactive elements have accessibilityRole and accessibilityLabel.
 *  - Unread state is communicated via accessibilityState.
 *  - Text uses readable contrast via design tokens.
 */

import {
  ActivityIndicator,
  FlatList,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';

import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useNavigation } from '@react-navigation/native';

import type { NotificationSummary, NotificationActionType } from '@carcommunity/shared/notifications';

import { useAppTheme } from '../hooks/useAppTheme';
import { useI18n } from '../hooks/useI18n';
import { useNotifications } from '../hooks/useNotifications';
import { KccButton } from '../components/KccButton';
import { ScreenContainer } from '../components/ScreenContainer';
import type { RootStackParamList } from '../navigation/types';

type NotificationsNavProp = NativeStackNavigationProp<RootStackParamList, 'Notifications'>;

// ---------------------------------------------------------------------------
// Allowlisted navigation destinations
// ---------------------------------------------------------------------------

/**
 * Only these action types produce in-app navigation.
 * Unknown or missing action types default to 'open_notifications' (stay on screen).
 * Never navigate to arbitrary external URLs.
 */
function useHandleNotificationAction() {
  const navigation = useNavigation<NotificationsNavProp>();

  return (notification: NotificationSummary, markRead: (id: string) => Promise<void>) => {
    void markRead(notification.notificationId);

    const action = notification.actionType as NotificationActionType;

    switch (action) {
      case 'open_event':
        // Protected event details are revalidated after app open via the EventDetail backend API.
        // Only navigate if we have a relatedEntityId — never pass arbitrary URLs.
        if (notification.relatedEntityId) {
          navigation.navigate('EventDetail', {
            eventId: notification.relatedEntityId,
            // Backend revalidates access and content when EventDetailScreen loads.
            teaser: {
              id: notification.relatedEntityId,
              title: notification.title,
              status: 'published',
              startsAt: '',
              endsAt: null,
              approximateArea: '',
              isOfficial: false,
            },
          });
        }
        break;
      case 'open_subscription':
        // Navigate to subscription screen (placeholder — add when subscription screen is added).
        break;
      case 'open_settings':
        navigation.navigate('Settings');
        break;
      case 'open_profile':
        // Profile is accessible via the tab navigator.
        break;
      case 'open_notifications':
      case 'none':
      default:
        // Stay on notifications screen.
        break;
    }
  };
}

// ---------------------------------------------------------------------------
// Category label helper
// ---------------------------------------------------------------------------

function useCategoryLabel() {
  const { t } = useI18n();
  return (category: string): string => {
    switch (category) {
      case 'event_reminder':
      case 'event_updated':
      case 'event_cancelled':
        return t('notifications.categoryEvent');
      case 'admin_message':
        return t('notifications.categoryAdminMessage');
      case 'account_warning':
      case 'account_suspension':
        return t('notifications.categoryAccount');
      case 'subscription_status':
        return t('notifications.categorySubscription');
      case 'system_notice':
      default:
        return t('notifications.categorySystem');
    }
  };
}

// ---------------------------------------------------------------------------
// Notification row
// ---------------------------------------------------------------------------

interface NotificationRowProps {
  notification: NotificationSummary;
  onPress: (notification: NotificationSummary) => void;
  categoryLabel: string;
}

const NotificationRow = ({ notification, onPress, categoryLabel }: NotificationRowProps) => {
  const { theme } = useAppTheme();
  const isUnread = !notification.readAt;

  const date = new Date(notification.createdAt).toLocaleDateString('sv-SE', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  });

  return (
    <TouchableOpacity
      accessibilityRole="button"
      accessibilityLabel={notification.title}
      accessibilityState={{ selected: !isUnread }}
      onPress={() => onPress(notification)}
      style={[
        styles.row,
        {
          backgroundColor: isUnread
            ? theme.colors.subtleBackground
            : theme.colors.surfaceBackground,
          borderBottomColor: theme.colors.borderDefault,
        },
      ]}
    >
      {isUnread && (
        <View
          style={[styles.unreadDot, { backgroundColor: theme.colors.brandPrimary }]}
          accessibilityElementsHidden
        />
      )}
      <View style={styles.rowContent}>
        <View style={styles.rowHeader}>
          <Text
            style={[
              styles.categoryLabel,
              { color: theme.colors.textSecondary, fontSize: theme.typography.size.caption },
            ]}
          >
            {categoryLabel}
          </Text>
          <Text
            style={[
              styles.date,
              { color: theme.colors.textSecondary, fontSize: theme.typography.size.caption },
            ]}
          >
            {date}
          </Text>
        </View>
        <Text
          style={[
            styles.title,
            {
              color: theme.colors.textPrimary,
              fontSize: theme.typography.size.bodyMd,
              fontWeight: isUnread ? '600' : '400',
            },
          ]}
          numberOfLines={2}
        >
          {notification.title}
        </Text>
        <Text
          style={[
            styles.preview,
            { color: theme.colors.textSecondary, fontSize: theme.typography.size.bodySm },
          ]}
          numberOfLines={2}
        >
          {notification.previewText}
        </Text>
      </View>
    </TouchableOpacity>
  );
};

// ---------------------------------------------------------------------------
// Main screen
// ---------------------------------------------------------------------------

export const NotificationsScreen = () => {
  const { theme } = useAppTheme();
  const { t } = useI18n();
  const {
    notifications,
    unreadCount,
    isLoading,
    error,
    hasMore,
    refresh,
    loadMore,
    markRead,
    markAllRead,
  } = useNotifications();
  const handleAction = useHandleNotificationAction();
  const getCategoryLabel = useCategoryLabel();

  const onNotificationPress = (notification: NotificationSummary) => {
    handleAction(notification, markRead);
  };

  return (
    <ScreenContainer>
      <View
        style={[
          styles.header,
          {
            paddingHorizontal: theme.spacing[4],
            paddingVertical: theme.spacing[3],
            borderBottomColor: theme.colors.borderDefault,
          },
        ]}
      >
        <Text
          style={[
            styles.heading,
            { color: theme.colors.textPrimary, fontSize: theme.typography.size.headingLg },
          ]}
          accessibilityRole="header"
        >
          {t('notifications.title')}
          {unreadCount > 0 ? ` (${unreadCount})` : ''}
        </Text>
        {unreadCount > 0 && (
          <KccButton
            label={t('notifications.markAllRead')}
            onPress={markAllRead}
            variant="secondary"
          />
        )}
      </View>

      {isLoading && notifications.length === 0 && (
        <View style={styles.centered}>
          <ActivityIndicator color={theme.colors.brandPrimary} />
        </View>
      )}

      {error && !isLoading && (
        <View style={styles.centered}>
          <Text style={[styles.emptyText, { color: theme.colors.statusError }]}>{error}</Text>
          <KccButton label={t('notifications.retry')} onPress={refresh} variant="secondary" />
        </View>
      )}

      {!isLoading && !error && notifications.length === 0 && (
        <View style={styles.centered}>
          <Text style={[styles.emptyText, { color: theme.colors.textSecondary }]}>
            {t('notifications.empty')}
          </Text>
        </View>
      )}

      {notifications.length > 0 && (
        <FlatList
          data={notifications}
          keyExtractor={(item) => item.notificationId}
          renderItem={({ item }) => (
            <NotificationRow
              notification={item}
              onPress={onNotificationPress}
              categoryLabel={getCategoryLabel(item.category)}
            />
          )}
          onEndReached={hasMore ? loadMore : undefined}
          onEndReachedThreshold={0.4}
          onRefresh={refresh}
          refreshing={isLoading}
          ListFooterComponent={
            hasMore ? (
              <View style={styles.footer}>
                <ActivityIndicator color={theme.colors.brandPrimary} />
              </View>
            ) : null
          }
        />
      )}
    </ScreenContainer>
  );
};

// ---------------------------------------------------------------------------
// Styles
// ---------------------------------------------------------------------------

const styles = StyleSheet.create({
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  heading: {
    fontWeight: '700',
    flex: 1,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    paddingHorizontal: 16,
    paddingVertical: 14,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  unreadDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    marginTop: 6,
    marginRight: 8,
    flexShrink: 0,
  },
  rowContent: {
    flex: 1,
  },
  rowHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 2,
  },
  categoryLabel: {
    textTransform: 'uppercase',
    letterSpacing: 0.5,
  },
  date: {},
  title: {
    marginBottom: 2,
  },
  preview: {},
  centered: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 24,
  },
  emptyText: {
    textAlign: 'center',
    marginBottom: 16,
  },
  footer: {
    paddingVertical: 16,
    alignItems: 'center',
  },
});
