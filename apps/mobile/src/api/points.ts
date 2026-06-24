/**
 * Points API client for the mobile app (Kronpoäng wallet).
 *
 * Privacy rules:
 *  - Only the current user's balance and transactions are fetched.
 *  - Tokens are never logged or exposed in error messages.
 *  - Clear wallet data on logout via the usePoints hook's cleanup effect.
 *  - Backend is the sole authority for balances and transactions.
 *  - Clients must never calculate balances or award points directly.
 *  - No purchase, transfer, withdrawal, or cash-value fields.
 */

import {
  POINTS_ROUTE_PATHS,
  type PointsBalanceResponse,
  type PaginatedPointsLedgerResponse,
} from '@carcommunity/shared/points';

import { publicEnv } from '../config/env';

const base = publicEnv.apiBaseUrl.replace(/\/$/, '');

const buildUrl = (path: string) => `${base}${path.startsWith('/') ? path : `/${path}`}`;
const buildAuthHeader = (token?: string): Record<string, string> =>
  token ? { Authorization: 'Bearer ' + token } : {};

export class PointsApiError extends Error {
  constructor(
    public readonly statusCode: number,
    message: string,
  ) {
    super(message);
    this.name = 'PointsApiError';
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
    throw new PointsApiError(
      response.status,
      `Points request failed with status ${response.status}`,
    );
  }

  return (await response.json()) as TResponse;
}

/**
 * Fetches the current user's KP balance.
 * Never exposes another user's balance.
 * Use the returned `balance` field as authoritative — do not sum ledger entries.
 */
export async function getPointsBalance(token?: string): Promise<PointsBalanceResponse> {
  return requestJson<PointsBalanceResponse>(POINTS_ROUTE_PATHS.balance, {
    method: 'GET',
    headers: buildAuthHeader(token),
  });
}

/**
 * Fetches the current user's paginated ledger, newest first.
 * The `data.balance` field in the response is the authoritative backend balance.
 * Do not sum the paginated transactions to derive a balance — the list is paginated.
 */
export async function getPointsLedger(
  token?: string,
  page = 1,
  pageSize = 20,
): Promise<PaginatedPointsLedgerResponse> {
  const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) });
  return requestJson<PaginatedPointsLedgerResponse>(
    `${POINTS_ROUTE_PATHS.ledger}?${params.toString()}`,
    {
      method: 'GET',
      headers: buildAuthHeader(token),
    },
  );
}
