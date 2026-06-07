export const publicEnv = {
  appEnv: process.env.EXPO_PUBLIC_APP_ENV ?? 'development',
  apiBaseUrl: process.env.EXPO_PUBLIC_API_BASE_URL ?? ''
} as const;
