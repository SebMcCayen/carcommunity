import { Linking } from 'react-native';

import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';

import { KccButton } from '../components/KccButton';
import { KccCard } from '../components/KccCard';
import { ScreenContainer } from '../components/ScreenContainer';
import { brandConfig } from '../config/brand';
import { useAppTheme } from '../hooks/useAppTheme';
import { useI18n } from '../hooks/useI18n';
import type { RootStackParamList } from '../navigation/types';

type SettingsNavProp = NativeStackNavigationProp<RootStackParamList, 'Settings'>;

// Brand website links — populated from app.config.ts extra values or brandConfig defaults.
const BRAND_LINKS = brandConfig.websiteLinks;

export const SettingsScreen = () => {
  const { t } = useI18n();
  const { mode, setMode } = useAppTheme();
  const navigation = useNavigation<SettingsNavProp>();

  const openUrl = (url: string) => {
    Linking.openURL(url).catch(() => undefined);
  };

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
      <KccCard
        title={t('settings.authPlannedTitle')}
        body={t('settings.authPlannedBody')}
      />

      <KccButton
        label={t('settings.privacy')}
        variant="secondary"
        onPress={() => navigation.navigate('PrivacySettings')}
      />
      <KccButton
        label={t('settings.blockedUsers')}
        variant="secondary"
        onPress={() => navigation.navigate('BlockedUsers')}
      />
      <KccButton
        label={t('settings.subscription')}
        variant="secondary"
        disabled
      />
      <KccButton
        label={t('settings.support')}
        variant="secondary"
        onPress={() => openUrl(BRAND_LINKS.support)}
      />
      <KccButton
        label={t('settings.terms')}
        variant="secondary"
        onPress={() => openUrl(BRAND_LINKS.terms)}
      />
      <KccButton
        label={t('settings.privacyPolicy')}
        variant="secondary"
        onPress={() => openUrl(BRAND_LINKS.privacyPolicy)}
      />
      <KccButton
        label={t('settings.accountDeletion')}
        variant="secondary"
        onPress={() => openUrl(BRAND_LINKS.accountDeletion)}
      />
      <KccButton
        label={t('settings.reportBug')}
        variant="secondary"
      />
      <KccButton
        label={t('settings.github')}
        variant="secondary"
        onPress={() => {
          Linking.openURL(brandConfig.githubUrl).catch(() => undefined);
        }}
      />
    </ScreenContainer>
  );
};
