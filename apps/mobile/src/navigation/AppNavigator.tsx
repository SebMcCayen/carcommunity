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
import { BlockedUsersScreen } from '../screens/BlockedUsersScreen';
import { ChatScreen } from '../screens/ChatScreen';
import { EventChatScreen } from '../screens/EventChatScreen';
import { EventDetailScreen } from '../screens/EventDetailScreen';
import { GroupDriveScreen } from '../screens/GroupDriveScreen';
import { EventsScreen } from '../screens/EventsScreen';
import { GarageScreen } from '../screens/GarageScreen';
import { HomeScreen } from '../screens/HomeScreen';
import { LiveLocationScreen } from '../screens/LiveLocationScreen';
import { LoginScreen } from '../screens/LoginScreen';
import { MapScreen } from '../screens/MapScreen';
import { OnboardingScreen } from '../screens/OnboardingScreen';
import { PrivacySettingsScreen } from '../screens/PrivacySettingsScreen';
import { ProfileScreen } from '../screens/ProfileScreen';
import { SettingsScreen } from '../screens/SettingsScreen';
import { SavedDrivesScreen } from '../screens/SavedDrivesScreen';
import { SavedDriveDetailScreen } from '../screens/SavedDriveDetailScreen';
import { VehicleDetailScreen } from '../screens/VehicleDetailScreen';
import { VehicleFormScreen } from '../screens/VehicleFormScreen';
import { BadgesScreen } from '../screens/BadgesScreen';
import { PointsWalletScreen } from '../screens/PointsWalletScreen';
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
        name="Garage"
        component={GarageScreen}
        options={{ title: t('garage.screenTitle') }}
      />
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
            <Stack.Screen
              name="BlockedUsers"
              component={BlockedUsersScreen}
              options={{ title: t('blocking.blockedUsersTitle') }}
            />
            <Stack.Screen
              name="EventDetail"
              component={EventDetailScreen}
              options={{ title: t('events.title') }}
            />
            <Stack.Screen
              name="EventChat"
              component={EventChatScreen}
              options={{ title: t('chat.eventChatTitle') }}
            />
            <Stack.Screen
              name="GroupDrive"
              component={GroupDriveScreen}
              options={{ title: t('groupDrive.screenTitle') }}
            />
            <Stack.Screen
              name="SavedDrives"
              component={SavedDrivesScreen}
              options={{ title: t('savedDrives.screenTitle') }}
            />
            <Stack.Screen
              name="SavedDriveDetail"
              component={SavedDriveDetailScreen}
              options={{ title: t('savedDrives.detailTitle') }}
            />
            <Stack.Screen
              name="VehicleDetail"
              component={VehicleDetailScreen}
              options={{ title: t('garage.detailTitle') }}
            />
            <Stack.Screen
              name="VehicleForm"
              component={VehicleFormScreen}
              options={({ route }) =>
                ({ title: route.params?.vehicleId ? t('garage.formTitleEdit') : t('garage.formTitleCreate') })
              }
            />
            <Stack.Screen
              name="Badges"
              component={BadgesScreen}
              options={{ title: t('badges.screenTitle') }}
            />
            <Stack.Screen
              name="PointsWallet"
              component={PointsWalletScreen}
              options={{ title: t('points.screenTitle') }}
            />
          </>
        )}
      </Stack.Navigator>
    </NavigationContainer>
  );
};
