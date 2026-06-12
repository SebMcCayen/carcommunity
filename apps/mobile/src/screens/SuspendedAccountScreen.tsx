import { KccButton } from '../components/KccButton';
import { KccCard } from '../components/KccCard';
import { ScreenContainer } from '../components/ScreenContainer';
import { useI18n } from '../hooks/useI18n';

/**
 * Placeholder screen shown to suspended or limited users.
 *
 * Suspended users retain access to:
 * - Support link
 * - Suspension information
 * - Appeal placeholder (not yet implemented)
 * - Subscription management placeholder (not yet implemented)
 * - Account deletion placeholder (not yet implemented)
 * - Policy and terms links
 *
 * TODO: Connect support link, appeal, subscription management, and account deletion
 *   to real backend-backed flows once those foundations are in place.
 * TODO: Real appeal flow must be implemented before production use.
 * TODO: Real account deletion flow must be implemented before production use.
 */
export const SuspendedAccountScreen = () => {
  const { t } = useI18n();

  return (
    <ScreenContainer>
      <KccCard
        title={t('accountStatus.suspendedTitle')}
        body={t('accountStatus.suspendedBody')}
      />

      {/* Support — always available to suspended users */}
      <KccButton label={t('accountStatus.supportLink')} variant="primary" />

      {/* Appeal placeholder — real flow not implemented yet */}
      <KccButton label={t('accountStatus.appealPlaceholder')} variant="secondary" />

      {/* Subscription management placeholder — real flow not implemented yet */}
      <KccButton label={t('accountStatus.subscriptionManagementPlaceholder')} variant="secondary" />

      {/* Account deletion placeholder — real flow not implemented yet */}
      <KccButton label={t('accountStatus.accountDeletionPlaceholder')} variant="destructive" />

      {/* Policy and terms — always available */}
      <KccButton label={t('accountStatus.policyLink')} variant="secondary" />
    </ScreenContainer>
  );
};
