import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { Text } from 'react-native';

import { HomeCard } from '../components/HomeCard';
import { KccButton } from '../components/KccButton';
import { LockedFeatureNotice } from '../components/LockedFeatureNotice';
import { PrimaryActionButton } from '../components/PrimaryActionButton';
import { ScreenContainer } from '../components/ScreenContainer';
import { SectionHeader } from '../components/SectionHeader';
import { StatusBadge } from '../components/StatusBadge';
import { useAppTheme } from '../hooks/useAppTheme';
import { useAuth } from '../hooks/useAuth';
import { useI18n } from '../hooks/useI18n';
import { type LiveSharingStatus, useLiveLocationSession } from '../hooks/useLiveLocationSession';
import type { RootStackParamList } from '../navigation/types';

type HomeScreenNavProp = NativeStackNavigationProp<RootStackParamList>;

type LiveStatusColorArgs = {
  status: LiveSharingStatus;
  statusSuccess: string;
  brandPrimary: string;
  statusError: string;
  textSecondary: string;
};

function getLiveStatusColor({
  status,
  statusSuccess,
  brandPrimary,
  statusError,
  textSecondary,
}: LiveStatusColorArgs): string {
  switch (status) {
    case 'sharing':
      return statusSuccess;
    case 'starting':
    case 'stopping':
      return brandPrimary;
    case 'error':
      return statusError;
    default:
      return textSecondary;
  }
}

export const HomeScreen = () => {
  const { t } = useI18n();
  const { theme } = useAppTheme();
  const navigation = useNavigation<HomeScreenNavProp>();
  const { currentUser } = useAuth();
  const { status } = useLiveLocationSession();

  // Access flags derived conservatively from session state.
  // AuthenticatedUserSummary does not yet carry role/status/subscription fields;
  // those are only available once the full user profile is loaded.
  // Backend enforces the real access decisions — these flags are UX-only.
  // TODO: replace with currentUserCanShareOwnLiveLocation / currentUserCanViewOtherLiveLocations
  //   once the session user shape carries role/status/subscriptionEntitlement.
  const canShare = currentUser !== null;
  const canViewOthers = false; // requires member_monthly — default free-user experience

  const isSharing = status === 'sharing' || status === 'starting';

  const statusColor = getLiveStatusColor({
    status,
    statusSuccess: theme.colors.statusSuccess,
    brandPrimary: theme.colors.brandPrimary,
    statusError: theme.colors.statusError,
    textSecondary: theme.colors.textSecondary,
  });

  const statusLabel: Record<LiveSharingStatus, string> = {
    not_sharing: t('home.liveLocationStatusNotSharing'),
    starting: t('liveLocation.statusStarting'),
    sharing: t('liveLocation.statusSharing'),
    stopping: t('liveLocation.statusStopping'),
    error: t('liveLocation.statusError'),
  };

  return (
    <ScreenContainer testID="home-screen">
      {/* ── 1. Live location ─────────────────────────────────────── */}
      <SectionHeader title={t('home.liveLocationSectionTitle')} />

      <StatusBadge
        testID="home-live-status"
        color={statusColor}
        label={statusLabel[status]}
      />

      <PrimaryActionButton
        testID="home-live-location-button"
        label={isSharing ? t('home.liveLocationStopButton') : t('home.liveLocationButton')}
        onPress={() => navigation.navigate('LiveLocation')}
        disabled={!canShare}
      />

      <Text
        style={{
          color: theme.colors.textSecondary,
          fontSize: theme.typography.size.caption,
          lineHeight: 18,
        }}
      >
        {t('home.liveLocationDisclaimer')}
      </Text>

      {!canViewOthers && (
        <LockedFeatureNotice
          testID="home-member-only-live-notice"
          message={t('home.memberOnlyLiveView')}
        />
      )}

      {/* ── 2. Community status ──────────────────────────────────── */}
      <SectionHeader title={t('home.communityStatusTitle')} />
      <HomeCard body={t('home.communityStatusBody')} />

      {/* ── 3. Next event ────────────────────────────────────────── */}
      <SectionHeader title={t('home.nextEventTitle')} />
      <HomeCard body={t('home.nextEventBody')} />
      {!canViewOthers && (
        <LockedFeatureNotice
          testID="home-event-member-notice"
          message={t('home.eventDetailsMemberOnly')}
        />
      )}

      {/* ── 4. Membership value ──────────────────────────────────── */}
      <HomeCard
        accent
        title={t('home.memberValueTitle')}
        body={t('home.memberValueBody')}
      />

      {/* ── 5. Settings shortcut ─────────────────────────────────── */}
      <KccButton
        testID="home-settings-shortcut"
        label={t('home.settingsShortcut')}
        variant="secondary"
        onPress={() => navigation.navigate('Settings')}
      />
    </ScreenContainer>
  );
};
