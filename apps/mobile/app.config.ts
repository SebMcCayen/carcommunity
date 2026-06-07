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
