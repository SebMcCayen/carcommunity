/**
 * EventDetailScreen — Full event detail for members; teaser + gate for free users.
 *
 * Access rules (enforced by backend; client-side check is UX only):
 *   - Active member_monthly: sees full detail, RSVP controls, navigation.
 *   - All other authenticated users: sees teaser fields + membership notice.
 *   - Entitlement loss while screen is open: clears protected data immediately.
 *
 * Security notes:
 *   - Exact coordinates are never logged.
 *   - Exact location fields are cleared when membership is lost.
 *   - Backend is the source of truth for all access decisions.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Alert, StyleSheet, Text, View } from 'react-native';
import { useNavigation, useRoute } from '@react-navigation/native';
import type { RouteProp } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { canAccessMemberFeatures } from '@carcommunity/shared/users';
import type { EventDetail, EventRsvpStatus } from '@carcommunity/shared/events';

import { loadEventDetails, updateEventRsvp } from '../api/events';
import { KccButton } from '../components/KccButton';
import { LockedFeatureNotice } from '../components/LockedFeatureNotice';
import { ScreenContainer } from '../components/ScreenContainer';
import { SectionHeader } from '../components/SectionHeader';
import { useAppTheme } from '../hooks/useAppTheme';
import { useAuth } from '../hooks/useAuth';
import { useI18n } from '../hooks/useI18n';
import type { RootStackParamList } from '../navigation/types';
import { openExternalNavigation } from '../utils/eventNavigation';

type EventDetailRouteProp = RouteProp<RootStackParamList, 'EventDetail'>;
type EventDetailNavProp = NativeStackNavigationProp<RootStackParamList>;

// ---------------------------------------------------------------------------
// RSVP controls
// ---------------------------------------------------------------------------

type RsvpControlsProps = {
  currentRsvp: EventRsvpStatus | null;
  isPending: boolean;
  onSelect: (status: EventRsvpStatus) => void;
};

function RsvpControls({ currentRsvp, isPending, onSelect }: RsvpControlsProps) {
  const { t } = useI18n();
  const { theme } = useAppTheme();

  const options: { status: EventRsvpStatus; labelKey: string }[] = [
    { status: 'going', labelKey: 'events.rsvpGoing' },
    { status: 'maybe', labelKey: 'events.rsvpMaybe' },
    { status: 'not_going', labelKey: 'events.rsvpNotGoing' },
  ];

  return (
    <View style={[styles.rsvpRow, { gap: theme.spacing[2] }]}>
      {options.map(({ status, labelKey }) => (
        <KccButton
          key={status}
          testID={`rsvp-button-${status}`}
          label={t(labelKey)}
          variant={currentRsvp === status ? 'primary' : 'secondary'}
          disabled={isPending}
          onPress={() => onSelect(status)}
        />
      ))}
    </View>
  );
}

// ---------------------------------------------------------------------------
// RSVP summary
// ---------------------------------------------------------------------------

type RsvpSummaryProps = {
  going: number;
  maybe: number;
  not_going: number;
};

function RsvpSummary({ going, maybe, not_going }: RsvpSummaryProps) {
  const { theme } = useAppTheme();
  const { t } = useI18n();

  return (
    <View testID="event-rsvp-summary" style={[styles.rsvpSummary, { gap: theme.spacing[2] }]}>
      <Text style={[styles.rsvpSummaryLabel, { color: theme.colors.textSecondary }]}>
        {`${t('events.rsvpGoing')}: ${going}  ${t('events.rsvpMaybe')}: ${maybe}  ${t('events.rsvpNotGoing')}: ${not_going}`}
      </Text>
    </View>
  );
}

// ---------------------------------------------------------------------------
// Main screen
// ---------------------------------------------------------------------------

export const EventDetailScreen = () => {
  const { t, locale } = useI18n();
  const { theme } = useAppTheme();
  const navigation = useNavigation<EventDetailNavProp>();
  const route = useRoute<EventDetailRouteProp>();
  const { eventId, teaser } = route.params;

  const { currentUser, withToken } = useAuth();

  // Client-side entitlement check — UX only. Backend enforces the real decision.
  const isMember =
    currentUser !== null &&
    canAccessMemberFeatures({
      role: currentUser.roles[0] ?? 'user',
      status: currentUser.status,
      subscriptionEntitlement: currentUser.subscriptionEntitlement,
    });

  const [detail, setDetail] = useState<EventDetail | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // RSVP state — optimistic while pending, reverted on backend rejection.
  const [optimisticRsvp, setOptimisticRsvp] = useState<EventRsvpStatus | null>(null);
  const [isRsvpPending, setIsRsvpPending] = useState(false);
  const [rsvpError, setRsvpError] = useState<string | null>(null);

  // Prevent duplicate in-flight RSVP requests.
  const rsvpInFlight = useRef(false);

  // When entitlement is lost, immediately clear protected member-only data.
  useEffect(() => {
    if (!isMember) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- security: clear protected data as soon as access is lost
      setDetail(null);
      setOptimisticRsvp(null);
      setError(null);
    }
  }, [isMember]);

  const fetchDetail = useCallback(async () => {
    if (!isMember) return;
    setIsLoading(true);
    setError(null);
    try {
      const result = await withToken((token) => loadEventDetails(eventId, token));
      if (result) {
        setDetail(result.data.event);
        setOptimisticRsvp(result.data.event.currentUserRsvp);
      }
    } catch (err) {
      console.error('Failed to load event detail:', err instanceof Error ? err.message : String(err));
      setError(t('events.errorDetail'));
    } finally {
      setIsLoading(false);
    }
  }, [eventId, isMember, t, withToken]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- triggers async fetch; state updates happen in async callbacks
    void fetchDetail();
  }, [fetchDetail]);

  const handleRsvp = useCallback(
    async (status: EventRsvpStatus) => {
      if (rsvpInFlight.current) return;
      const prevRsvp = optimisticRsvp;
      setOptimisticRsvp(status);
      setIsRsvpPending(true);
      setRsvpError(null);
      rsvpInFlight.current = true;

      let rsvpAccepted = false;
      try {
        await withToken((token) => updateEventRsvp(eventId, { status }, token));
        rsvpAccepted = true;
      } catch (err) {
        console.error('RSVP update failed:', err instanceof Error ? err.message : String(err));
        setOptimisticRsvp(prevRsvp);
        setRsvpError(t('events.rsvpSubmitError'));
      } finally {
        setIsRsvpPending(false);
        rsvpInFlight.current = false;
      }

      if (rsvpAccepted) {
        // Best-effort silent refresh to get updated RSVP counts.
        try {
          const fresh = await withToken((token) => loadEventDetails(eventId, token));
          if (fresh) {
            setDetail(fresh.data.event);
            setOptimisticRsvp(fresh.data.event.currentUserRsvp);
          }
        } catch (err) {
          // Ignore — RSVP was accepted; counts may be briefly stale.
          console.error('Post-RSVP detail refresh failed:', err instanceof Error ? err.message : String(err));
        }
      }
    },
    [eventId, optimisticRsvp, t, withToken],
  );

  const handleNavigate = useCallback(async () => {
    if (!detail) return;
    try {
      await openExternalNavigation({
        latitude: detail.latitude,
        longitude: detail.longitude,
        address: detail.address,
        locationName: detail.locationName,
      });
    } catch (err) {
      console.error('Failed to open external navigation:', err instanceof Error ? err.message : String(err));
      Alert.alert(t('events.navigationError'));
    }
  }, [detail, t]);

  // ---------------------------------------------------------------------------
  // Shared header content (always visible regardless of membership)
  // ---------------------------------------------------------------------------

  const isCancelled = teaser.status === 'cancelled';
  const startDate = new Date(teaser.startsAt);

  const formattedDate = startDate.toLocaleDateString(locale, {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  });

  const formattedTime = startDate.toLocaleTimeString(locale, {
    hour: '2-digit',
    minute: '2-digit',
  });

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------

  return (
    <ScreenContainer testID="event-detail-screen">
      {/* ── Cancelled notice ──────────────────────────────────── */}
      {isCancelled && (
        <View
          testID="event-cancelled-notice"
          style={[
            styles.cancelledBanner,
            {
              backgroundColor: theme.colors.statusError,
              borderRadius: theme.radius.md,
              padding: theme.spacing[3],
            },
          ]}
        >
          <Text style={[styles.cancelledText, { color: '#FFFFFF' }]}>
            {t('events.cancelledNotice')}
          </Text>
        </View>
      )}

      {/* ── Header: title, date, official badge ───────────────── */}
      <View style={[styles.header, { gap: theme.spacing[2] }]}>
        {teaser.isOfficial && (
          <Text
            testID="event-official-badge"
            style={[styles.officialBadge, { color: theme.colors.brandPrimary }]}
          >
            {t('events.officialBadge')}
          </Text>
        )}
        <Text style={[styles.title, { color: theme.colors.textPrimary }]}>{teaser.title}</Text>
        <Text style={[styles.meta, { color: theme.colors.textSecondary }]}>
          {formattedDate} · {formattedTime}
        </Text>
        <Text style={[styles.meta, { color: theme.colors.textSecondary }]}>
          {teaser.approximateArea}
        </Text>
      </View>

      {/* ── Member-only content ───────────────────────────────── */}
      {isMember ? (
        <>
          {isLoading && (
            <View testID="event-detail-loading" style={styles.centeredRow}>
              <ActivityIndicator color={theme.colors.brandPrimary} />
              <Text style={[styles.meta, { color: theme.colors.textSecondary }]}>
                {t('events.loadingDetail')}
              </Text>
            </View>
          )}

          {error !== null && !isLoading && (
            <View testID="event-detail-error" style={{ gap: theme.spacing[2] }}>
              <Text style={[styles.meta, { color: theme.colors.statusError }]}>{error}</Text>
              <KccButton
                testID="event-detail-retry"
                label={t('events.retry')}
                variant="secondary"
                onPress={() => void fetchDetail()}
              />
            </View>
          )}

          {detail !== null && !isLoading && (
            <>
              {/* Description */}
              {detail.description !== null && (
                <View style={{ gap: theme.spacing[1] }}>
                  <SectionHeader title={t('events.screenSubtitle')} />
                  <Text style={[styles.description, { color: theme.colors.textPrimary }]}>
                    {detail.description}
                  </Text>
                </View>
              )}

              {/* Exact location — member only */}
              <View testID="event-location-detail" style={{ gap: theme.spacing[1] }}>
                {detail.locationName !== null && (
                  <Text style={[styles.locationName, { color: theme.colors.textPrimary }]}>
                    {detail.locationName}
                  </Text>
                )}
                {detail.address !== null && (
                  <Text style={[styles.meta, { color: theme.colors.textSecondary }]}>
                    {detail.address}
                  </Text>
                )}
              </View>

              {/* Navigate button */}
              {(detail.latitude !== null || detail.address !== null) && (
                <KccButton
                  testID="event-navigate-button"
                  label={t('events.navigateHere')}
                  variant="primary"
                  onPress={() => void handleNavigate()}
                />
              )}

              {/* RSVP controls */}
              {!isCancelled && (
                <View style={{ gap: theme.spacing[2] }}>
                  <SectionHeader title={t('events.rsvpCountsLabel')} />
                  <RsvpControls
                    currentRsvp={optimisticRsvp}
                    isPending={isRsvpPending}
                    onSelect={(s) => void handleRsvp(s)}
                  />
                  {rsvpError !== null && (
                    <Text
                      testID="rsvp-error"
                      style={[styles.meta, { color: theme.colors.statusError }]}
                    >
                      {rsvpError}
                    </Text>
                  )}
                  <RsvpSummary
                    going={detail.rsvpSummary.going}
                    maybe={detail.rsvpSummary.maybe}
                    not_going={detail.rsvpSummary.not_going}
                  />
                </View>
              )}

              {/* Event chat entry — only shown for eligible RSVP members; UX only */}
              {!isCancelled &&
                (optimisticRsvp === 'going' || optimisticRsvp === 'maybe') && (
                  <KccButton
                    testID="event-chat-entry-button"
                    label={t('chat.eventChatTitle')}
                    variant="secondary"
                    onPress={() =>
                      navigation.navigate('EventChat', {
                        eventId,
                        eventTitle: teaser.title,
                        eventRsvpStatus: optimisticRsvp,
                      })
                    }
                  />
                )}

              {/* Group drive entry — shown for eligible RSVP members on published upcoming/active events; UX only */}
              {!isCancelled &&
                (optimisticRsvp === 'going' || optimisticRsvp === 'maybe') && (
                  <KccButton
                    testID="event-group-drive-entry-button"
                    label={t('groupDrive.screenTitle')}
                    variant="secondary"
                    onPress={() =>
                      navigation.navigate('GroupDrive', {
                        eventId,
                        eventTitle: teaser.title,
                        eventRsvpStatus: optimisticRsvp,
                      })
                    }
                  />
                )}
            </>
          )}
        </>
      ) : (
        /* Free-user gate — do not request or retain protected details */
        <LockedFeatureNotice
          testID="event-detail-member-gate"
          message={t('events.memberGateBody')}
        />
      )}
    </ScreenContainer>
  );
};

const styles = StyleSheet.create({
  cancelledBanner: {},
  cancelledText: {
    fontSize: 14,
    fontWeight: '600',
  },
  header: {},
  officialBadge: {
    fontSize: 12,
    fontWeight: '700',
    textTransform: 'uppercase',
    letterSpacing: 0.5,
  },
  title: {
    fontSize: 20,
    fontWeight: '700',
  },
  meta: {
    fontSize: 13,
    lineHeight: 18,
  },
  description: {
    fontSize: 15,
    lineHeight: 22,
  },
  locationName: {
    fontSize: 15,
    fontWeight: '600',
  },
  rsvpRow: {
    flexDirection: 'row',
  },
  rsvpSummary: {},
  rsvpSummaryLabel: {
    fontSize: 13,
  },
  centeredRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
});
