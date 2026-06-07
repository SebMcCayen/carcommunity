import Constants from 'expo-constants';
import { Linking } from 'react-native';

import { KccButton } from '../components/KccButton';
import { KccCard } from '../components/KccCard';
import { ScreenContainer } from '../components/ScreenContainer';
import { brandConfig } from '../config/brand';
import { useI18n } from '../hooks/useI18n';

const appVersion = Constants.expoConfig?.version ?? '0.1.0';
const buildNumber =
  Constants.expoConfig?.ios?.buildNumber ??
  String(Constants.expoConfig?.android?.versionCode ?? Constants.nativeBuildVersion ?? '1');

export const AboutAppScreen = () => {
  const { t } = useI18n();

  return (
    <ScreenContainer>
      <KccCard title={t('about.appName')} body={brandConfig.fullName} />
      <KccCard title={t('about.version')} body={appVersion} />
      <KccCard title={t('about.buildNumber')} body={buildNumber} />
      <KccButton
        label={t('about.githubReleases')}
        onPress={() => {
          void Linking.openURL(brandConfig.githubReleasesUrl);
        }}
        variant="secondary"
      />
    </ScreenContainer>
  );
};
