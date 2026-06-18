import { StatusBar } from 'expo-status-bar';

import { AppNavigator } from '../navigation/AppNavigator';
import { AppThemeProvider } from '../hooks/useAppTheme';
import { AuthProvider } from '../hooks/useAuth';
import { I18nProvider } from '../hooks/useI18n';
import { LiveLocationProvider } from '../context/LiveLocationContext';

export const AppRoot = () => {
  return (
    <I18nProvider>
      <AppThemeProvider>
        <AuthProvider>
          <LiveLocationProvider>
            <StatusBar style="auto" />
            <AppNavigator />
          </LiveLocationProvider>
        </AuthProvider>
      </AppThemeProvider>
    </I18nProvider>
  );
};
