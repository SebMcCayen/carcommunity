import { AUTH_ROUTE_PATHS, type LoginRequest, type AuthResponse } from '@carcommunity/shared/auth';

import { publicEnv } from '../config/env';

const base = publicEnv.apiBaseUrl.replace(/\/$/, '');

if (!base) {
  throw new Error('EXPO_PUBLIC_API_BASE_URL is not set. Set it in your .env file.');
}

const buildUrl = (path: string) => `${base}${path.startsWith('/') ? path : `/${path}`}`;

async function postAuth(path: string, body: LoginRequest | Record<string, never>): Promise<AuthResponse> {
  const response = await fetch(buildUrl(path), {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
    },
    body: JSON.stringify(body),
  });

  return (await response.json()) as AuthResponse;
}

/**
 * Placeholder Apple login — sends the identity token to the backend for (future) verification.
 * TODO: Replace with real expo-apple-authentication integration.
 * TODO: Store the resulting session token securely using expo-secure-store (not AsyncStorage).
 * @devOnly NOT PRODUCTION-READY. Apple identity token is not verified server-side yet.
 */
export async function loginWithApplePlaceholder(identityToken: string): Promise<AuthResponse> {
  return postAuth(AUTH_ROUTE_PATHS.login, {
    provider: 'apple',
    identityToken,
    platform: 'ios',
  });
}

/**
 * Placeholder Google login — sends the identity token to the backend for (future) verification.
 * TODO: Replace with real @react-native-google-signin/google-signin integration.
 * TODO: Store the resulting session token securely using expo-secure-store (not AsyncStorage).
 * @devOnly NOT PRODUCTION-READY. Google identity token is not verified server-side yet.
 */
export async function loginWithGooglePlaceholder(identityToken: string): Promise<AuthResponse> {
  return postAuth(AUTH_ROUTE_PATHS.login, {
    provider: 'google',
    identityToken,
    platform: 'android',
  });
}

export async function logoutPlaceholder(): Promise<AuthResponse> {
  return postAuth(AUTH_ROUTE_PATHS.logout, {});
}

export async function getCurrentUserPlaceholder(): Promise<AuthResponse> {
  // TODO: Attach Authorization header with stored session token once token storage is implemented.
  const response = await fetch(buildUrl(AUTH_ROUTE_PATHS.me), {
    method: 'GET',
  });

  return (await response.json()) as AuthResponse;
}
