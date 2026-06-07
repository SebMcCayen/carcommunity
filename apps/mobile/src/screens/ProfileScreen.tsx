import { NativeStackNavigationProp } from '@react-navigation/native-stack';

import { KccButton } from '../components/KccButton';
import { ScreenContainer } from '../components/ScreenContainer';
import { useI18n } from '../hooks/useI18n';
import { RootStackParamList } from '../navigation/types';

type ProfileScreenProps = {
  navigation: NativeStackNavigationProp<RootStackParamList>;
};

export const ProfileScreen = ({ navigation }: ProfileScreenProps) => {
  const { t } = useI18n();

  return (
    <ScreenContainer>
      <KccButton
        label={t('profile.openSettings')}
        onPress={() => navigation.navigate('Settings')}
      />
      <KccButton
        label={t('profile.openAbout')}
        onPress={() => navigation.navigate('About')}
        variant="secondary"
      />
    </ScreenContainer>
  );
};
