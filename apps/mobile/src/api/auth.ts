import {
  AUTH_ROUTE_PATHS,
  type LoginRequest,
  type AuthResponse,
  type LogoutResponse,
} from '@carcommunity/shared/auth';

import { publicEnv } from '../config/env';

const base = publicEnv.apiBaseUrl.replace(/\/$/, '');

if (!base) {
  throw new Error('EXPO_PUBLIC_API_BASE_URL is not set. Set it in your .env file.');
}

const buildUrl = (path: string) => `${base}${path.startsWith('/') ? path : `/${path}`}`;

/** Build Authorization header map. Never log the token value itself. */
function bearerHeaders(sessionToken?: string): Record<string, string> {
  if (!sessionToken) return {};
  return { Authorization: 'Bearer ' + sessionToken };
}

async function postAuth(
  path: string,
  body: LoginRequest | Record<string, never>,
  sessionToken?: string,
): Promise<AuthResponse> {
  const response = await fetch(buildUrl(path), {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      ...bearerHeaders(sessionToken),
    },
    body: JSON.stringify(body),
  });

  return (await response.json()) as AuthResponse;
}

/**
 * Placeholder Apple login — sends the identity token to the backend for (future) verification.
 * TODO: Replace with real expo-apple-authentication integration.
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
 * @devOnly NOT PRODUCTION-READY. Google identity token is not verified server-side yet.
 */
export async function loginWithGooglePlaceholder(identityToken: string): Promise<AuthResponse> {
  return postAuth(AUTH_ROUTE_PATHS.login, {
    provider: 'google',
    identityToken,
    platform: 'android',
  });
}

/** Call POST /v1/auth/logout to revoke the current session on the backend. */
export async function logoutPlaceholder(sessionToken?: string): Promise<LogoutResponse> {
  const response = await fetch(buildUrl(AUTH_ROUTE_PATHS.logout), {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      ...bearerHeaders(sessionToken),
    },
    body: JSON.stringify({}),
  });

  return (await response.json()) as LogoutResponse;
}

/** Call GET /v1/auth/me to verify the current session and fetch the user summary. */
export async function getCurrentUser(sessionToken?: string): Promise<AuthResponse> {
  const response = await fetch(buildUrl(AUTH_ROUTE_PATHS.me), {
    method: 'GET',
    headers: {
      ...bearerHeaders(sessionToken),
    },
  });

  return (await response.json()) as AuthResponse;
}

/**
 * User profile shape returned by GET /v1/users/me.
 * TODO: Move to @carcommunity/shared once the contract is finalised.
 */
export interface UsersMeResponse {
  ok: boolean;
  data?: {
    userId: string;
    displayName?: string | null;
    avatarUrl?: string | null;
  };
  error?: { code: string; message: string };
}

/**
 * Call GET /v1/users/me to retrieve the current user's profile.
 * TODO: Backend endpoint and response shape are not yet finalised.
 */
export async function getUsersMe(sessionToken?: string): Promise<UsersMeResponse> {
  const response = await fetch(buildUrl('/v1/users/me'), {
    method: 'GET',
    headers: {
      ...bearerHeaders(sessionToken),
    },
  });

  return (await response.json()) as UsersMeResponse;
}

/** @deprecated Use getCurrentUser instead. */
export const getCurrentUserPlaceholder = getCurrentUser;
