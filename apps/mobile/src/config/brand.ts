import Constants from 'expo-constants';

type ExtraConfig = {
  appDisplayName?: string;
  appFullName?: string;
  githubUrl?: string;
  githubReleasesUrl?: string;
};

const extra = (Constants.expoConfig?.extra ?? {}) as ExtraConfig;

export const brandConfig = {
  shortName: extra.appDisplayName ?? 'Car Community',
  fullName: extra.appFullName ?? 'Car Community',
  githubUrl: extra.githubUrl ?? 'https://github.com/SebMcCayen/carcommunity',
  githubReleasesUrl: extra.githubReleasesUrl ?? 'https://github.com/SebMcCayen/carcommunity/releases'
} as const;
