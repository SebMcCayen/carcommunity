import { type ReactNode } from 'react';
import { StatusBar } from 'expo-status-bar';

import { AppNavigator } from '../navigation/AppNavigator';
import { AppThemeProvider } from '../hooks/useAppTheme';
import { AuthProvider, useAuth } from '../hooks/useAuth';
import { I18nProvider } from '../hooks/useI18n';
import { LiveLocationProvider } from '../context/LiveLocationContext';

/**
 * Mounts LiveLocationProvider only while the user is authenticated so that
 * the GPS watcher cleanup in useLiveLocationSession runs reliably on logout.
 */
const AuthenticatedLiveLocationProvider = ({ children }: { children: ReactNode }) => {
  const { isAuthenticated } = useAuth();
  return isAuthenticated ? (
    <LiveLocationProvider>{children}</LiveLocationProvider>
  ) : (
    <>{children}</>
  );
};

export const AppRoot = () => {
  return (
    <I18nProvider>
      <AppThemeProvider>
        <AuthProvider>
          <AuthenticatedLiveLocationProvider>
            <StatusBar style="auto" />
            <AppNavigator />
          </AuthenticatedLiveLocationProvider>
        </AuthProvider>
      </AppThemeProvider>
    </I18nProvider>
  );
};
