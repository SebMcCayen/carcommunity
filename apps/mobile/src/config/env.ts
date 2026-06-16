export const publicEnv = {
  appEnv: process.env.EXPO_PUBLIC_APP_ENV ?? 'development',
  apiBaseUrl: process.env.EXPO_PUBLIC_API_BASE_URL ?? '',
  /**
   * Auth mode controls whether real native provider SDKs are used.
   * Set to 'native' to enable expo-apple-authentication / Google Sign-In.
   * Defaults to 'dev' (placeholder login) so the app remains runnable
   * without a custom native build.
   *
   * TODO (production): Set EXPO_PUBLIC_AUTH_MODE=native in production builds.
   */
  authMode: (process.env.EXPO_PUBLIC_AUTH_MODE ?? 'dev') as 'native' | 'dev',
  /**
   * Google OAuth client IDs — read from Expo public environment variables.
   * Must NOT be hardcoded or committed. Use .env / EAS Secrets.
   *
   * TODO (production): Set these in your production .env / EAS Secrets.
   * TODO (production): Verify SHA certificate fingerprints and package name in Google Cloud Console.
   * TODO (production): Validate audience (aud) on the backend for each client ID.
   */
  googleIosClientId: process.env.EXPO_PUBLIC_GOOGLE_IOS_CLIENT_ID ?? '',
  googleAndroidClientId: process.env.EXPO_PUBLIC_GOOGLE_ANDROID_CLIENT_ID ?? '',
   googleWebClientId: process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID ?? '',
} as const;
