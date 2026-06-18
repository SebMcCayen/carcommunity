import type {
  UserProfileResponse,
  UserProfileUpdateRequest,
  PrivacySettingsResponse,
  PrivacySettingsUpdateRequest,
  AppSettingsLinksResponse,
} from '@carcommunity/shared/onboarding';
import { ONBOARDING_ROUTE_PATHS, APP_SETTINGS_LINKS_PATH } from '@carcommunity/shared/onboarding';
import type { CurrentUserResponse } from '@carcommunity/shared/users';

import { publicEnv } from '../config/env';

const base = publicEnv.apiBaseUrl.replace(/\/$/, '');

const buildUrl = (path: string) => `${base}${path.startsWith('/') ? path : `/${path}`}`;

function bearerHeaders(sessionToken: string): Record<string, string> {
  return { Authorization: 'Bearer ' + sessionToken };
}

/**
 * GET /v1/users/me — fetch the current user's full profile including onboarding status.
 */
export async function getUserProfile(sessionToken: string): Promise<CurrentUserResponse> {
  const response = await fetch(buildUrl(ONBOARDING_ROUTE_PATHS.me), {
    method: 'GET',
    headers: bearerHeaders(sessionToken),
  });
  return (await response.json()) as CurrentUserResponse;
}

/**
 * PATCH /v1/users/me/profile — update display name and/or complete onboarding.
 */
export async function patchUserProfile(
  sessionToken: string,
  body: UserProfileUpdateRequest,
): Promise<UserProfileResponse> {
  const response = await fetch(buildUrl(ONBOARDING_ROUTE_PATHS.profile), {
    method: 'PATCH',
    headers: {
      'content-type': 'application/json',
      ...bearerHeaders(sessionToken),
    },
    body: JSON.stringify(body),
  });
  return (await response.json()) as UserProfileResponse;
}

/**
 * GET /v1/users/me/privacy-settings — fetch current privacy preferences.
 */
export async function getPrivacySettings(sessionToken: string): Promise<PrivacySettingsResponse> {
  const response = await fetch(buildUrl(ONBOARDING_ROUTE_PATHS.privacySettings), {
    method: 'GET',
    headers: bearerHeaders(sessionToken),
  });
  return (await response.json()) as PrivacySettingsResponse;
}

/**
 * PATCH /v1/users/me/privacy-settings — update privacy preferences.
 */
export async function patchPrivacySettings(
  sessionToken: string,
  body: PrivacySettingsUpdateRequest,
): Promise<PrivacySettingsResponse> {
  const response = await fetch(buildUrl(ONBOARDING_ROUTE_PATHS.privacySettings), {
    method: 'PATCH',
    headers: {
      'content-type': 'application/json',
      ...bearerHeaders(sessionToken),
    },
    body: JSON.stringify(body),
  });
  return (await response.json()) as PrivacySettingsResponse;
}

/**
 * GET /v1/app/settings-links — fetch app settings link configuration.
 */
export async function getAppSettingsLinks(): Promise<AppSettingsLinksResponse> {
  const response = await fetch(buildUrl(APP_SETTINGS_LINKS_PATH), {
    method: 'GET',
  });
  return (await response.json()) as AppSettingsLinksResponse;
}
