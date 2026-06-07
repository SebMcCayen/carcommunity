import { StatusBar } from 'expo-status-bar';

import { AppNavigator } from '../navigation/AppNavigator';
import { AppThemeProvider } from '../hooks/useAppTheme';
import { I18nProvider } from '../hooks/useI18n';

export const AppRoot = () => {
  return (
    <I18nProvider>
      <AppThemeProvider>
        <StatusBar style="auto" />
        <AppNavigator />
      </AppThemeProvider>
    </I18nProvider>
  );
};
