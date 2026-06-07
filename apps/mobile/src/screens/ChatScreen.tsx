import { KccCard } from '../components/KccCard';
import { ScreenContainer } from '../components/ScreenContainer';
import { useI18n } from '../hooks/useI18n';

export const ChatScreen = () => {
  const { t } = useI18n();

  return (
    <ScreenContainer>
      <KccCard title={t('chat.title')} body={t('chat.placeholder')} />
    </ScreenContainer>
  );
};
