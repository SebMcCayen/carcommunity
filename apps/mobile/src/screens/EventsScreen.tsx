import { StyleSheet, Text, View } from 'react-native';

import { KccButton } from '../components/KccButton';
import { KccCard } from '../components/KccCard';
import { ScreenContainer } from '../components/ScreenContainer';
import { useAppTheme } from '../hooks/useAppTheme';
import { useI18n } from '../hooks/useI18n';
import type { EventTeaser, EventRsvpStatus } from '@carcommunity/shared/events';

/**
 * EventTeaserCard shows safe public event info for all authenticated users.
 * Exact location details are never shown here — those require a member subscription.
 */
function EventTeaserCard({ event }: { event: EventTeaser }) {
  const { theme } = useAppTheme();
  const { t } = useI18n();

  return (
    <View
      style={[
        styles.teaserCard,
        {
          backgroundColor: theme.colors.surfaceBackground,
          borderColor: event.isOfficial ? theme.colors.brandPrimary : theme.colors.borderDefault,
          borderRadius: theme.radius.lg,
          padding: theme.spacing[4],
          gap: theme.spacing[2],
        },
      ]}
    >
      {event.isOfficial && (
        <Text style={[styles.officialBadge, { color: theme.colors.brandPrimary }]}>
          {t('events.officialBadge')}
        </Text>
      )}
      <Text style={[styles.teaserTitle, { color: theme.colors.textPrimary }]}>{event.title}</Text>
      <Text style={[styles.teaserMeta, { color: theme.colors.textSecondary }]}>
        {new Date(event.startsAt).toLocaleDateString(undefined, {
          weekday: 'long',
          day: 'numeric',
          month: 'long',
          year: 'numeric',
        })}
      </Text>
      <Text style={[styles.teaserMeta, { color: theme.colors.textSecondary }]}>
        {event.approximateArea}
      </Text>
    </View>
  );
}

/**
 * MemberUpgradeBanner is shown below teasers to prompt free users.
 */
function MemberUpgradeBanner() {
  const { theme } = useAppTheme();
  const { t } = useI18n();

  return (
    <View
      style={[
        styles.upgradeBanner,
        {
          backgroundColor: theme.colors.surfaceBackground,
          borderColor: theme.colors.borderDefault,
          borderRadius: theme.radius.lg,
          padding: theme.spacing[4],
          gap: theme.spacing[2],
        },
      ]}
    >
      <Text style={[styles.upgradeTitle, { color: theme.colors.textPrimary }]}>
        {t('events.memberRequiredTitle')}
      </Text>
      <Text style={[styles.upgradeBody, { color: theme.colors.textSecondary }]}>
        {t('events.memberRequiredBody')}
      </Text>
    </View>
  );
}

/**
 * RsvpButtons shows the three RSVP states.
 * These are UI-only placeholders — actual RSVP submission requires a member subscription
 * and is enforced by the backend.
 *
 * TODO: Wire up actual RSVP calls via submitEventRsvp() from api/events.ts once
 *   the member detail view is built out.
 */
function RsvpButtons({ current }: { current?: EventRsvpStatus | null }) {
  const { t } = useI18n();

  const options: { status: EventRsvpStatus; labelKey: string }[] = [
    { status: 'going', labelKey: 'events.rsvpGoing' },
    { status: 'maybe', labelKey: 'events.rsvpMaybe' },
    { status: 'not_going', labelKey: 'events.rsvpNotGoing' },
  ];

  return (
    <View style={styles.rsvpRow}>
      {options.map(({ status, labelKey }) => (
        <KccButton
          key={status}
          label={t(labelKey)}
          variant={current === status ? 'primary' : 'secondary'}
          disabled
        />
      ))}
    </View>
  );
}

/**
 * EventsScreen — Events foundation.
 *
 * Free users see event teasers (approximate area, date) but not exact location.
 * Members with active subscription can see full details and RSVP.
 *
 * TODO: Load teasers from loadEventTeasers() once auth context is wired into the mobile app.
 * TODO: Show member detail view for users with member_monthly subscription.
 * TODO: Open Apple Maps on iOS and Google Maps on Android for navigation (future version).
 */
export const EventsScreen = () => {
  const { t } = useI18n();

  // TODO: Replace placeholder teasers with real data from loadEventTeasers().
  const placeholderTeasers: EventTeaser[] = [];
  const isMember = false; // TODO: derive from session access helpers

  return (
    <ScreenContainer>
      <KccCard title={t('events.title')} body={t('events.screenSubtitle')} />

      {placeholderTeasers.length === 0 && (
        <KccCard title={t('events.noUpcomingTitle')} body={t('events.noUpcomingBody')} />
      )}

      {placeholderTeasers.map((event) => (
        <EventTeaserCard key={event.id} event={event} />
      ))}

      {!isMember && <MemberUpgradeBanner />}

      {isMember && (
        <KccCard
          title={t('events.memberDetailPlaceholder')}
          body={t('events.navigationPlaceholder')}
          footer={<RsvpButtons current={null} />}
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
  teaserTitle: {
    fontSize: 16,
    fontWeight: '600',
  },
  teaserMeta: {
    fontSize: 13,
    lineHeight: 18,
  },
  upgradeBanner: {
    borderWidth: 1,
  },
  upgradeTitle: {
    fontSize: 16,
    fontWeight: '600',
  },
  upgradeBody: {
    fontSize: 14,
    lineHeight: 20,
  },
  rsvpRow: {
    flexDirection: 'row',
    gap: 8,
    marginTop: 8,
  },
});

