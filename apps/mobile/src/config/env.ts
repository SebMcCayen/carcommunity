const fallbackApiBaseUrl = 'http://localhost:4000';

export const publicEnv = {
  appEnv: process.env.EXPO_PUBLIC_APP_ENV ?? 'development',
  apiBaseUrl: process.env.EXPO_PUBLIC_API_BASE_URL ?? fallbackApiBaseUrl
} as const;
