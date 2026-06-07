import {
  DarkTheme as NavigationDarkTheme,
  DefaultTheme as NavigationLightTheme,
  NavigationContainer,
  Theme as NavigationTheme,
} from '@react-navigation/native';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createNativeStackNavigator } from '@react-navigation/native-stack';

import { useAppTheme } from '../hooks/useAppTheme';
import { useI18n } from '../hooks/useI18n';
import { AboutAppScreen } from '../screens/AboutAppScreen';
import { ChatScreen } from '../screens/ChatScreen';
import { EventsScreen } from '../screens/EventsScreen';
import { HomeScreen } from '../screens/HomeScreen';
import { MapScreen } from '../screens/MapScreen';
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

  return (
    <NavigationContainer theme={navigationTheme}>
      <Stack.Navigator>
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
      </Stack.Navigator>
    </NavigationContainer>
  );
};
