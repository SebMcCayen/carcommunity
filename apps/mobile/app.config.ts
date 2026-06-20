import type { ExpoConfig } from 'expo/config';

const appDisplayName = process.env.EXPO_PUBLIC_BRAND_SHORT_NAME ?? 'KCC';

const config: ExpoConfig = {
  name: appDisplayName,
  slug: 'carcommunity-mobile',
  version: '0.1.0',
  orientation: 'portrait',
  userInterfaceStyle: 'automatic',
  experiments: {
    typedRoutes: false,
  },
  // TODO: @rnmapbox/maps is a native module and requires a custom Expo development
  //       build or an EAS build. It does NOT work in Expo Go.
  //       Before building:
  //         1. Set MAPBOX_ACCESS_TOKEN (build secret) in your .env or EAS Secrets.
  //         2. Run `npx expo prebuild` or `eas build` to apply this plugin.
  //       See https://rnmapbox.github.io/docs/setup/installation
  plugins: [
    [
      '@rnmapbox/maps',
      {
        // MAPBOX_ACCESS_TOKEN is used by the plugin to configure iOS/Android native build scripts.
        // Do not use EXPO_PUBLIC_MAPBOX_ACCESS_TOKEN here (that is the runtime token).
        RNMapboxMapsDownloadToken: process.env.MAPBOX_ACCESS_TOKEN ?? '',
      },
    ],
    // expo-apple-authentication adds the Apple Sign-In entitlement to the iOS build.
    // TODO (production): Verify the "Sign in with Apple" capability is enabled in your
    //                    Apple Developer account and App Store Connect before submitting.
    // TODO (production): Confirm bundle ID and entitlements match your Apple App ID configuration.
    'expo-apple-authentication',
    // expo-location provides foreground and background location access.
    // Background location is only active during an explicit, user-initiated sharing session.
    // It is never used passively or outside an active session.
    //
    // NOTE: Background location requires a custom development build or EAS build.
    //       It does NOT work in Expo Go. Run `npx expo prebuild` or `eas build` first.
    //
    // TODO (physical device): Validate background location on a real iOS and Android device.
    // TODO (App Store): Review Apple's privacy nutrition label requirements for
    //   NSLocationAlwaysAndWhenInUseUsageDescription before App Store submission.
    [
      'expo-location',
      {
        // iOS — shown in the system permission dialog (NSLocationWhenInUseUsageDescription).
        // Build-time config strings cannot use runtime i18n keys.
        locationWhenInUsePermission: `${appDisplayName} behöver din plats medan du aktivt delar din liveposition. Delning är frivillig och tidsbegränsad. Du kan stoppa delningen när som helst.`,
        // iOS — shown if background permission is later requested (NSLocationAlwaysAndWhenInUseUsageDescription).
        // Only requested after explicit user opt-in, never at startup.
        // Build-time config strings cannot use runtime i18n keys.
        locationAlwaysAndWhenInUsePermission: `${appDisplayName} kan uppdatera din liveposition när appen är i bakgrunden, men endast under en aktiv, tidsbegränsad delningssession. ${appDisplayName} spårar inte din position utanför aktiva sessioner.`,
        // iOS — enable UIBackgroundModes: location so the app can receive location
        // updates while backgrounded during an active sharing session.
        isBackgroundLocationEnabled: true,
        // Android — foreground service notification displayed while background location
        // sharing is active. The notification clearly states that sharing is active
        // and is visible to the user for the duration of the session.
        // Build-time config strings cannot use runtime i18n keys.
        foregroundService: {
          notificationTitle: `${appDisplayName} liveposition är aktiv`,
          notificationBody: 'Din position delas under den aktiva, tidsbegränsade sessionen.',
          notificationColor: '#1a1a1a',
        },
      },
    ],
  ],
  extra: {
    appDisplayName,
    appFullName: process.env.EXPO_PUBLIC_BRAND_FULL_NAME ?? 'Kungsbacka Car Community',
    githubUrl: process.env.EXPO_PUBLIC_GITHUB_URL ?? 'https://github.com/SebMcCayen/carcommunity',
    githubReleasesUrl:
      process.env.EXPO_PUBLIC_GITHUB_RELEASES_URL ??
      'https://github.com/SebMcCayen/carcommunity/releases',
  },
};

export default config;
