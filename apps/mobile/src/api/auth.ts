import {
  AUTH_ROUTE_PATHS,
  type LoginRequest,
  type AuthResponse,
  type LogoutResponse,
} from '@carcommunity/shared/auth';

import Constants from 'expo-constants';

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
 * Returns app version and build number from Expo Constants for inclusion in
 * login requests. Helps the backend identify the client version.
 * Neither value is sensitive.
 */
function getClientMeta(): { appVersion?: string; buildNumber?: string } {
  const version = Constants.expoConfig?.version ?? undefined;
  const ios = Constants.platform?.ios;
  const android = Constants.platform?.android;
  const buildNumber =
    (ios?.buildNumber ?? android?.versionCode?.toString()) ?? undefined;
  return { appVersion: version, buildNumber };
}

/**
 * Native Apple login — sends the identity token obtained from
 * expo-apple-authentication to the backend for verification.
 *
 * Does NOT include providerSubject; the backend derives identity
 * exclusively from the verified identity token.
 *
 * TODO (production): Implement nonce generation and pass it to both
 *   AppleAuthentication.signInAsync() and this request for replay protection.
 * TODO (production): Account linking: if a user already exists with a different
 *   provider, surface an account-linking flow rather than creating a duplicate.
 */
export async function loginWithApple(identityToken: string): Promise<AuthResponse> {
  const { appVersion, buildNumber } = getClientMeta();
  return postAuth(AUTH_ROUTE_PATHS.login, {
    provider: 'apple',
    identityToken,
    platform: 'ios',
    appVersion,
    buildNumber,
  });
}

/**
 * Native Google login — sends the Google ID token obtained from
 * @react-native-google-signin/google-signin to the backend for verification.
 *
 * Does NOT include providerSubject; the backend derives identity
 * exclusively from the verified ID token.
 *
 * TODO (production): Validate audience (aud) claim on the backend against
 *   the expected Google client ID for your app.
 * TODO (production): Set up Google Play App Signing and configure correct
 *   SHA-1 / SHA-256 fingerprints in Google Cloud Console.
 * TODO (production): Account linking: surface a linking flow if a user
 *   already exists with a different provider.
 */
export async function loginWithGoogle(identityToken: string): Promise<AuthResponse> {
  const { appVersion, buildNumber } = getClientMeta();
  return postAuth(AUTH_ROUTE_PATHS.login, {
    provider: 'google',
    identityToken,
    platform: 'android',
    appVersion,
    buildNumber,
  });
}

export interface PlaceholderProviderLoginOptions {
  /** Development-only fallback for placeholder backend auth mode. */
  providerSubject?: string;
  /** Optional provider nonce to forward to backend verification. */
  nonce?: string;
}

/**
 * @devOnly Placeholder Apple login — sends a dev identity token to the backend.
 * NOT PRODUCTION-READY. Use loginWithApple for the real native flow.
 * @deprecated Use loginWithApple for native production login.
 */
export async function loginWithApplePlaceholder(
  identityToken: string,
  options: PlaceholderProviderLoginOptions = {},
): Promise<AuthResponse> {
  return postAuth(AUTH_ROUTE_PATHS.login, {
    provider: 'apple',
    identityToken,
    providerSubject: options.providerSubject,
    nonce: options.nonce,
    platform: 'ios',
  });
}

/**
 * @devOnly Placeholder Google login — sends a dev identity token to the backend.
 * NOT PRODUCTION-READY. Use loginWithGoogle for the real native flow.
 * @deprecated Use loginWithGoogle for native production login.
 */
export async function loginWithGooglePlaceholder(
  identityToken: string,
  options: PlaceholderProviderLoginOptions = {},
): Promise<AuthResponse> {
  return postAuth(AUTH_ROUTE_PATHS.login, {
    provider: 'google',
    identityToken,
    providerSubject: options.providerSubject,
    nonce: options.nonce,
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
