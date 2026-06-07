import { KccCard } from '../components/KccCard';
import { ScreenContainer } from '../components/ScreenContainer';
import { useI18n } from '../hooks/useI18n';

export const EventsScreen = () => {
  const { t } = useI18n();

  return (
    <ScreenContainer>
      <KccCard title={t('events.title')} body={t('events.placeholder')} />
    </ScreenContainer>
  );
};
