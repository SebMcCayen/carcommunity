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
