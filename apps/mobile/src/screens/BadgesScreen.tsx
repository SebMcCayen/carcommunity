/**
 * BadgesScreen — "Utmärkelser" (Awards / Badges).
 *
 * Shows the current user's earned badges, sorted by catalog order.
 * Includes an unobtrusive in-app notice when a new badge has been awarded.
 *
 * Privacy rules:
 *  - Only shows the current user's own badges — never other users'.
 *  - Badge data is cleared when the hook unmounts (e.g. on logout).
 *  - No rankings, comparisons, or percentages are shown.
 *  - Backend is the sole authority for badge eligibility.
 *  - Clients must never award badges directly.
 *
 * Accessibility:
 *  - All interactive elements have accessibilityRole and accessibilityLabel.
 *  - Text uses readable contrast via design tokens.
 *  - Safe driving: no animations that could distract while driving.
 */

import { useEffect } from 'react';
import {
  ActivityIndicator,
  FlatList,
  StyleSheet,
  Text,
  View,
} from 'react-native';

import type { AwardedBadge } from '@carcommunity/shared/badges';

import { useAppTheme } from '../hooks/useAppTheme';
import { useBadges } from '../hooks/useBadges';
import { useI18n } from '../hooks/useI18n';
import { KccButton } from '../components/KccButton';
import { ScreenContainer } from '../components/ScreenContainer';

// ---------------------------------------------------------------------------
// Badge card
// ---------------------------------------------------------------------------

interface BadgeCardProps {
  badge: AwardedBadge;
  awardedOnLabel: string;
}

const BadgeCard = ({ badge, awardedOnLabel }: BadgeCardProps) => {
  const { theme } = useAppTheme();

  const awardedDate = new Date(badge.awardedAt).toLocaleDateString('sv-SE', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  });

  return (
    <View
      accessibilityRole="text"
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
      <Text
        style={[
          styles.cardTitle,
          {
            color: theme.colors.textPrimary,
            fontSize: theme.typography.size.titleMd,
            fontWeight: theme.typography.weight.semibold,
          },
        ]}
      >
        {badge.name}
      </Text>
      <Text
        style={[
          styles.cardDesc,
          { color: theme.colors.textSecondary, fontSize: theme.typography.size.bodySm },
        ]}
      >
        {badge.description}
      </Text>
      <Text
        style={[
          styles.cardDate,
          { color: theme.colors.textSecondary, fontSize: theme.typography.size.caption },
        ]}
      >
        {awardedOnLabel} {awardedDate}
      </Text>
    </View>
  );
};

// ---------------------------------------------------------------------------
// New-badge notice (non-blocking, shown once per badge per session)
// ---------------------------------------------------------------------------

interface NewBadgeNoticeProps {
  badgeName: string;
  onDismiss: () => void;
  dismissLabel: string;
  titleLabel: string;
  bodyPrefix: string;
}

const NewBadgeNotice = ({
  badgeName,
  onDismiss,
  dismissLabel,
  titleLabel,
  bodyPrefix,
}: NewBadgeNoticeProps) => {
  const { theme } = useAppTheme();

  return (
    <View
      accessibilityRole="alert"
      style={[
        styles.notice,
        {
          backgroundColor: theme.colors.brandPrimary,
          borderRadius: theme.radius.md,
          padding: theme.spacing[3],
          marginBottom: theme.spacing[4],
        },
      ]}
    >
      <Text
        style={[
          styles.noticeTitle,
          { color: theme.colors.textPrimary, fontWeight: theme.typography.weight.semibold },
        ]}
      >
        {titleLabel}
      </Text>
      <Text style={[styles.noticeBody, { color: theme.colors.textPrimary }]}>
        {bodyPrefix} {badgeName}.
      </Text>
      <KccButton
        label={dismissLabel}
        onPress={onDismiss}
        variant="secondary"
      />
    </View>
  );
};

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

export const BadgesScreen = () => {
  const { t } = useI18n();
  const { theme } = useAppTheme();
  const { badges, isLoading, error, refresh, newBadgeKey, dismissNewBadge } = useBadges();

  // Find the name of the newly awarded badge for the notice.
  const newBadge = newBadgeKey ? badges.find((b) => b.key === newBadgeKey) : null;

  // Accessibility: announce when a new badge is detected.
  useEffect(() => {
    // No-op — announcement is handled by accessibilityRole="alert" on the notice.
  }, [newBadgeKey]);

  return (
    <ScreenContainer>
      <Text
        accessibilityRole="header"
        style={[
          styles.heading,
          {
            color: theme.colors.textPrimary,
            fontSize: theme.typography.size.headingLg,
            fontWeight: theme.typography.weight.semibold,
            marginBottom: theme.spacing[4],
          },
        ]}
      >
        {t('badges.screenTitle')}
      </Text>

      {newBadge ? (
        <NewBadgeNotice
          badgeName={newBadge.name}
          onDismiss={dismissNewBadge}
          dismissLabel={t('badges.noticeDismiss')}
          titleLabel={t('badges.noticeTitle')}
          bodyPrefix={t('badges.noticeBodyPrefix')}
        />
      ) : null}

      {isLoading ? (
        <ActivityIndicator
          accessibilityLabel={t('badges.loading')}
          color={theme.colors.brandPrimary}
          style={styles.loader}
        />
      ) : error ? (
        <View style={styles.centeredMessage}>
          <Text style={[styles.message, { color: theme.colors.textSecondary }]}>
            {t('badges.error')}
          </Text>
          <KccButton label={t('badges.retry')} onPress={refresh} variant="secondary" />
        </View>
      ) : badges.length === 0 ? (
        <View style={styles.centeredMessage}>
          <Text
            accessibilityRole="text"
            style={[
              styles.message,
              { color: theme.colors.textSecondary, fontSize: theme.typography.size.bodyMd },
            ]}
          >
            {t('badges.empty')}
          </Text>
          <Text
            style={[
              styles.message,
              {
                color: theme.colors.textSecondary,
                fontSize: theme.typography.size.bodySm,
                marginTop: theme.spacing[2],
              },
            ]}
          >
            {t('badges.emptyHint')}
          </Text>
        </View>
      ) : (
        <FlatList
          data={badges}
          keyExtractor={(item) => item.key}
          renderItem={({ item }) => (
            <BadgeCard
              badge={item}
              awardedOnLabel={t('badges.awardedOn')}
            />
          )}
          scrollEnabled={false}
        />
      )}
    </ScreenContainer>
  );
};

// ---------------------------------------------------------------------------
// Styles
// ---------------------------------------------------------------------------

const styles = StyleSheet.create({
  heading: {
    marginTop: 0,
  },
  loader: {
    marginTop: 32,
  },
  centeredMessage: {
    marginTop: 32,
    alignItems: 'center',
  },
  message: {
    textAlign: 'center',
    lineHeight: 22,
  },
  card: {
    borderWidth: 1,
  },
  cardTitle: {
    marginBottom: 4,
  },
  cardDesc: {
    marginBottom: 6,
    lineHeight: 20,
  },
  cardDate: {
    lineHeight: 18,
  },
  notice: {
    gap: 8,
  },
  noticeTitle: {
    fontSize: 16,
    marginBottom: 2,
  },
  noticeBody: {
    fontSize: 14,
    lineHeight: 20,
  },
});
