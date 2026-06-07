import { KccButton } from '../components/KccButton';
import { KccCard } from '../components/KccCard';
import { ScreenContainer } from '../components/ScreenContainer';
import { useI18n } from '../hooks/useI18n';

export const HomeScreen = () => {
  const { t } = useI18n();

  return (
    <ScreenContainer>
      <KccButton label={t('home.liveLocationButton')} />
      <KccCard title={t('home.communityStatusTitle')} body={t('home.communityStatusBody')} />
      <KccCard title={t('home.nextEventTitle')} body={t('home.nextEventBody')} />
      <KccCard title={t('home.memberValueTitle')} body={t('home.memberValueBody')} />
      <KccCard title={t('subscription.teaserTitle')} body={t('subscription.teaserBody')} />
    </ScreenContainer>
  );
};
