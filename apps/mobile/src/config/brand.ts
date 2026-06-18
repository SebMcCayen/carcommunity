import Constants from 'expo-constants';

type ExtraConfig = {
  appDisplayName?: string;
  appFullName?: string;
  githubUrl?: string;
  githubReleasesUrl?: string;
  supportUrl?: string;
  termsUrl?: string;
  privacyPolicyUrl?: string;
  accountDeletionUrl?: string;
  dataDeletionUrl?: string;
};

const extra = (Constants.expoConfig?.extra ?? {}) as ExtraConfig;

export const brandConfig = {
  shortName: extra.appDisplayName ?? 'Car Community',
  fullName: extra.appFullName ?? 'Car Community',
  githubUrl: extra.githubUrl ?? 'https://github.com/SebMcCayen/carcommunity',
  githubReleasesUrl:
    extra.githubReleasesUrl ?? 'https://github.com/SebMcCayen/carcommunity/releases',
  websiteLinks: {
    support: extra.supportUrl ?? 'https://kungsbackacc.se/support',
    terms: extra.termsUrl ?? 'https://kungsbackacc.se/villkor',
    privacyPolicy: extra.privacyPolicyUrl ?? 'https://kungsbackacc.se/integritetspolicy',
    accountDeletion: extra.accountDeletionUrl ?? 'https://kungsbackacc.se/konto/radera',
    dataDeletion: extra.dataDeletionUrl ?? 'https://kungsbackacc.se/konto/data',
  },
} as const;
