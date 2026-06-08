import { AUTH_ROUTE_PATHS, type AuthRequest, type AuthResponse } from '@carcommunity/shared/auth';

import { publicEnv } from '../config/env';

const base = publicEnv.apiBaseUrl.replace(/\/$/, '');

if (!base) {
  throw new Error('EXPO_PUBLIC_API_BASE_URL is not set. Set it in your .env file.');
}

const buildUrl = (path: string) => `${base}${path.startsWith('/') ? path : `/${path}`}`;

async function postAuth(path: string, body: AuthRequest | Record<string, never>): Promise<AuthResponse> {
  const response = await fetch(buildUrl(path), {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
    },
    body: JSON.stringify(body),
  });

  return (await response.json()) as AuthResponse;
}

export async function loginWithApplePlaceholder(identityToken: string): Promise<AuthResponse> {
  return postAuth(AUTH_ROUTE_PATHS.mobileLogin, {
    provider: 'apple',
    identityToken,
  });
}

export async function loginWithGooglePlaceholder(identityToken: string): Promise<AuthResponse> {
  return postAuth(AUTH_ROUTE_PATHS.mobileLogin, {
    provider: 'google',
    identityToken,
  });
}

export async function logoutPlaceholder(): Promise<AuthResponse> {
  return postAuth(AUTH_ROUTE_PATHS.logout, {});
}

export async function getCurrentUserPlaceholder(): Promise<AuthResponse> {
  const response = await fetch(buildUrl(AUTH_ROUTE_PATHS.me), {
    method: 'GET',
  });

  return (await response.json()) as AuthResponse;
}
