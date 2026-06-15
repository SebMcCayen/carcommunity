import { StatusBar } from 'expo-status-bar';

import { AppNavigator } from '../navigation/AppNavigator';
import { AppThemeProvider } from '../hooks/useAppTheme';
import { AuthProvider } from '../hooks/useAuth';
import { I18nProvider } from '../hooks/useI18n';

export const AppRoot = () => {
  return (
    <I18nProvider>
      <AppThemeProvider>
        <AuthProvider>
          <StatusBar style="auto" />
          <AppNavigator />
        </AuthProvider>
      </AppThemeProvider>
    </I18nProvider>
  );
};
