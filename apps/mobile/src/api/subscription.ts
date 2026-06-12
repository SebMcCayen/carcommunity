/**
 * Mobile subscription API client.
 *
 * Provides helpers to fetch the current user's subscription entitlement
 * from the backend.
 *
 * Security: The backend is the source of truth for all entitlement decisions.
 * These helpers are for user experience only — never use client-side state
 * to unlock member features.
 *
 * TODO: Add native Apple App Store purchase flow (StoreKit 2 / react-native-iap)
 *   when real in-app purchase integration is implemented. Do NOT add Apple or
 *   Google billing dependencies until the native purchase flow is ready.
 *
 * TODO: Add native Google Play Billing purchase flow when ready.
 *
 * TODO: After a native purchase completes, call POST /v1/subscription/refresh-placeholder
 *   (which will eventually be replaced by the real server-side validation endpoint).
 *   Never trust the client-side purchase receipt — always validate server-side.
 */

import {
  SUBSCRIPTION_ROUTE_PATHS,
  type CurrentEntitlementResponse,
  type SubscriptionRefreshPlaceholderResponse,
} from '@carcommunity/shared/subscription';

import { publicEnv } from '../config/env';

const base = publicEnv.apiBaseUrl.replace(/\/$/, '');

const buildUrl = (path: string) => `${base}${path.startsWith('/') ? path : `/${path}`}`;

async function requestJson<TResponse>(path: string, init?: RequestInit): Promise<TResponse> {
  if (!base) {
    throw new Error(
      'API base URL is not configured. Set EXPO_PUBLIC_API_BASE_URL in your .env file.',
    );
  }

  const response = await fetch(buildUrl(path), init);

  if (!response.ok) {
    throw new Error(`Subscription request failed with status ${response.status}`);
  }

  return (await response.json()) as TResponse;
}

/**
 * Fetch the current user's subscription entitlement from the backend.
 * The backend enforces all access rules — this is for display purposes only.
 */
export async function loadCurrentEntitlement(
  bearerToken: string,
): Promise<CurrentEntitlementResponse> {
  return requestJson<CurrentEntitlementResponse>(SUBSCRIPTION_ROUTE_PATHS.me, {
    method: 'GET',
    headers: {
      authorization: 'Bearer ' + bearerToken,
    },
  });
}

/**
 * Request a subscription refresh from the backend (placeholder).
 * This will eventually trigger server-side receipt validation.
 *
 * Not for production use — returns a placeholder response until real
 * Apple/Google validation is implemented.
 */
export async function refreshSubscriptionPlaceholder(
  bearerToken: string,
): Promise<SubscriptionRefreshPlaceholderResponse> {
  return requestJson<SubscriptionRefreshPlaceholderResponse>(
    SUBSCRIPTION_ROUTE_PATHS.refreshPlaceholder,
    {
      method: 'POST',
      headers: {
        authorization: 'Bearer ' + bearerToken,
        'content-type': 'application/json',
      },
    },
  );
}
