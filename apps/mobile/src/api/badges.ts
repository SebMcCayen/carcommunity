/**
 * Badge API client for the mobile app.
 *
 * Privacy rules:
 *  - Only the current user's badges are fetched — never other users'.
 *  - Tokens are never logged or exposed in error messages.
 *  - Clear badge data on logout via the useBadges hook's cleanup effect.
 *  - Backend remains the authoritative source for all badge eligibility.
 *  - Clients must never award badges directly.
 */

import {
  BADGE_ROUTE_PATHS,
  type CurrentUserBadgesResponse,
} from '@carcommunity/shared/badges';

import { publicEnv } from '../config/env';

const base = publicEnv.apiBaseUrl.replace(/\/$/, '');

const buildUrl = (path: string) => `${base}${path.startsWith('/') ? path : `/${path}`}`;
const buildAuthHeader = (token?: string): Record<string, string> =>
  token ? { authorization: 'Bearer ' + token } : {};

export class BadgeApiError extends Error {
  constructor(
    public readonly statusCode: number,
    message: string,
  ) {
    super(message);
    this.name = 'BadgeApiError';
  }
}

async function requestJson<TResponse>(path: string, init?: RequestInit): Promise<TResponse> {
  if (!base) {
    throw new Error(
      'API base URL is not configured. Set EXPO_PUBLIC_API_BASE_URL in your .env file.',
    );
  }

  const response = await fetch(buildUrl(path), init);

  if (!response.ok) {
    throw new BadgeApiError(
      response.status,
      `Badge request failed with status ${response.status}`,
    );
  }

  return (await response.json()) as TResponse;
}

/**
 * Fetch the current user's awarded badges.
 * Returns only the current user's badges — never other users'.
 */
export async function getCurrentUserBadges(token?: string): Promise<CurrentUserBadgesResponse> {
  return requestJson<CurrentUserBadgesResponse>(BADGE_ROUTE_PATHS.myBadges, {
    method: 'GET',
    headers: buildAuthHeader(token),
  });
}
