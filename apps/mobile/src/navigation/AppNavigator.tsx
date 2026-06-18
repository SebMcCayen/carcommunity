import {
  DarkTheme as NavigationDarkTheme,
  DefaultTheme as NavigationLightTheme,
  NavigationContainer,
  Theme as NavigationTheme,
} from '@react-navigation/native';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { ActivityIndicator, View } from 'react-native';

import { useAppTheme } from '../hooks/useAppTheme';
import { useAuth } from '../hooks/useAuth';
import { useI18n } from '../hooks/useI18n';
import { AboutAppScreen } from '../screens/AboutAppScreen';
import { ChatScreen } from '../screens/ChatScreen';
import { EventsScreen } from '../screens/EventsScreen';
import { HomeScreen } from '../screens/HomeScreen';
import { LiveLocationScreen } from '../screens/LiveLocationScreen';
import { LoginScreen } from '../screens/LoginScreen';
import { MapScreen } from '../screens/MapScreen';
import { OnboardingScreen } from '../screens/OnboardingScreen';
import { PrivacySettingsScreen } from '../screens/PrivacySettingsScreen';
import { ProfileScreen } from '../screens/ProfileScreen';
import { SettingsScreen } from '../screens/SettingsScreen';
import { MainTabParamList, RootStackParamList } from './types';

const Tab = createBottomTabNavigator<MainTabParamList>();
const Stack = createNativeStackNavigator<RootStackParamList>();

const MainTabs = () => {
  const { t } = useI18n();

  return (
    <Tab.Navigator>
      <Tab.Screen name="Home" component={HomeScreen} options={{ title: t('navigation.home') }} />
      <Tab.Screen name="Map" component={MapScreen} options={{ title: t('navigation.map') }} />
      <Tab.Screen
        name="Events"
        component={EventsScreen}
        options={{ title: t('navigation.events') }}
      />
      <Tab.Screen name="Chat" component={ChatScreen} options={{ title: t('navigation.chat') }} />
      <Tab.Screen
        name="Profile"
        component={ProfileScreen}
        options={{ title: t('navigation.profile') }}
      />
    </Tab.Navigator>
  );
};

export const AppNavigator = () => {
  const { theme, resolvedMode } = useAppTheme();
  const { t } = useI18n();
  const { isAuthenticated, isLoading, currentUser } = useAuth();

  const onboardingCompleted = Boolean(currentUser?.onboardingCompletedAt);

  const navigationTheme: NavigationTheme = {
    ...(resolvedMode === 'dark' ? NavigationDarkTheme : NavigationLightTheme),
    colors: {
      ...(resolvedMode === 'dark' ? NavigationDarkTheme.colors : NavigationLightTheme.colors),
      primary: theme.colors.brandPrimary,
      background: theme.colors.pageBackground,
      card: theme.colors.surfaceBackground,
      text: theme.colors.textPrimary,
      border: theme.colors.borderDefault,
      notification: theme.colors.statusError,
    },
  };

  // Show a minimal loading indicator while the session is being restored.
  // This prevents a flash of the login screen on apps that have an active session.
  if (isLoading) {
    return (
      <View
        testID="app-loading"
        style={{ flex: 1, justifyContent: 'center', alignItems: 'center', backgroundColor: theme.colors.pageBackground }}
      >
        <ActivityIndicator color={theme.colors.brandPrimary} />
      </View>
    );
  }

  return (
    <NavigationContainer theme={navigationTheme}>
      <Stack.Navigator>
        {!isAuthenticated ? (
          <Stack.Screen
            name="Login"
            component={LoginScreen}
            options={{ headerShown: false }}
          />
        ) : !onboardingCompleted ? (
          <Stack.Screen
            name="Onboarding"
            component={OnboardingScreen}
            options={{ headerShown: false, gestureEnabled: false }}
          />
        ) : (
          <>
            <Stack.Screen name="MainTabs" component={MainTabs} options={{ headerShown: false }} />
            <Stack.Screen
              name="Settings"
              component={SettingsScreen}
              options={{ title: t('navigation.settings') }}
            />
            <Stack.Screen
              name="About"
              component={AboutAppScreen}
              options={{ title: t('navigation.about') }}
            />
            <Stack.Screen
              name="LiveLocation"
              component={LiveLocationScreen}
              options={{ title: t('liveLocation.screenTitle') }}
            />
            <Stack.Screen
              name="PrivacySettings"
              component={PrivacySettingsScreen}
              options={{ title: t('settings.privacy') }}
            />
          </>
        )}
      </Stack.Navigator>
    </NavigationContainer>
  );
};
