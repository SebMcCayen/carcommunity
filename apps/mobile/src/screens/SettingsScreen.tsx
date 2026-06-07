import { Linking } from 'react-native';

import { KccButton } from '../components/KccButton';
import { KccCard } from '../components/KccCard';
import { ScreenContainer } from '../components/ScreenContainer';
import { brandConfig } from '../config/brand';
import { useAppTheme } from '../hooks/useAppTheme';
import { useI18n } from '../hooks/useI18n';

export const SettingsScreen = () => {
  const { t } = useI18n();
  const { mode, setMode } = useAppTheme();

  return (
    <ScreenContainer>
      <KccCard
        title={t('settings.themeTitle')}
        body={t('settings.themeSystem')}
        footer={
          <>
            <KccButton
              label={t('settings.themeSystem')}
              onPress={() => setMode('system')}
              variant={mode === 'system' ? 'primary' : 'secondary'}
            />
            <KccButton
              label={t('settings.themeLight')}
              onPress={() => setMode('light')}
              variant={mode === 'light' ? 'primary' : 'secondary'}
            />
            <KccButton
              label={t('settings.themeDark')}
              onPress={() => setMode('dark')}
              variant={mode === 'dark' ? 'primary' : 'secondary'}
            />
          </>
        }
      />

      <KccButton label={t('settings.privacy')} variant="secondary" />
      <KccButton label={t('settings.notifications')} variant="secondary" />
      <KccButton label={t('settings.deleteAccount')} variant="destructive" />
      <KccButton label={t('settings.reportBug')} variant="secondary" />
      <KccButton label={t('settings.about')} variant="secondary" />
      <KccButton
        label={t('settings.github')}
        variant="secondary"
        onPress={() => {
          void Linking.openURL(brandConfig.githubUrl);
        }}
      />
    </ScreenContainer>
  );
};
