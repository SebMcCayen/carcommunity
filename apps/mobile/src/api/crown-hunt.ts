/**
 * Crown Hunt (Kronjakt) API client for the mobile app.
 *
 * Safety and privacy rules:
 *  - Backend is the sole authority for eligibility, claims, and Kronpoäng awards.
 *  - Clients must never calculate or award Kronpoäng.
 *  - Claims are never automatic — initiated only by explicit user action.
 *  - Tokens and coordinates are never logged.
 *  - Only the current user's claims are fetched (enforced by backend).
 *  - Internal fraud metadata is never exposed to mobile clients.
 *  - Do not accept reward amounts from the client.
 */

import {
  CROWN_HUNT_ROUTE_PATHS,
  buildCrownHuntClaimPath,
  buildCrownHuntPointPath,
  type CrownHuntClaimRequest,
  type CrownHuntClaimResponse,
  type CrownHuntPointDetail,
  type CrownHuntPointDetailResponse,
  type PaginatedCrownHuntPointsResponse,
  type PaginatedCrownHuntClaimHistoryResponse,
} from '@carcommunity/shared/crown-hunt';

import { publicEnv } from '../config/env';

const base = publicEnv.apiBaseUrl.replace(/\/$/, '');

const buildUrl = (path: string) => `${base}${path.startsWith('/') ? path : `/${path}`}`;
const buildAuthHeader = (token?: string): Record<string, string> =>
  token ? { Authorization: 'Bearer ' + token } : {};

export class CrownHuntApiError extends Error {
  constructor(
    public readonly statusCode: number,
    message: string,
  ) {
    super(message);
    this.name = 'CrownHuntApiError';
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
    throw new CrownHuntApiError(
      response.status,
      `Crown Hunt request failed with status ${response.status}`,
    );
  }

  return (await response.json()) as TResponse;
}

/**
 * Fetches the list of active, currently available Kronjakt points.
 * Returns safe map-level data only — no internal fraud metadata.
 */
export async function getCrownHuntPoints(
  token?: string,
  page = 1,
  pageSize = 50,
): Promise<PaginatedCrownHuntPointsResponse> {
  const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) });
  return requestJson<PaginatedCrownHuntPointsResponse>(
    `${CROWN_HUNT_ROUTE_PATHS.points}?${params.toString()}`,
    {
      method: 'GET',
      headers: buildAuthHeader(token),
    },
  );
}

/**
 * Fetches detail for a single Kronjakt point.
 * Includes safety instructions and reward amount.
 * Does not include internal risk rules.
 */
export async function getCrownHuntPoint(
  pointId: string,
  token?: string,
): Promise<CrownHuntPointDetail> {
  const response = await requestJson<CrownHuntPointDetailResponse>(buildCrownHuntPointPath(pointId), {
    method: 'GET',
    headers: buildAuthHeader(token),
  });
  return response.data;
}

/**
 * Claims a Kronjakt point.
 *
 * The user must be inside the geofence and safely stopped.
 * Backend performs all eligibility and fraud validation.
 * Never pass a reward amount — the backend determines the award.
 * Coordinates are sent only as claim evidence, never logged locally.
 */
export async function claimCrownHuntPoint(
  pointId: string,
  request: CrownHuntClaimRequest,
  token?: string,
): Promise<CrownHuntClaimResponse> {
  return requestJson<CrownHuntClaimResponse>(buildCrownHuntClaimPath(pointId), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...buildAuthHeader(token),
    },
    body: JSON.stringify(request),
  });
}

/**
 * Fetches the current user's Kronjakt claim history.
 * Never includes exact claim coordinates or other users' data.
 */
export async function getMyCrownHuntClaims(
  token?: string,
  page = 1,
  pageSize = 20,
): Promise<PaginatedCrownHuntClaimHistoryResponse> {
  const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) });
  return requestJson<PaginatedCrownHuntClaimHistoryResponse>(
    `${CROWN_HUNT_ROUTE_PATHS.myClaims}?${params.toString()}`,
    {
      method: 'GET',
      headers: buildAuthHeader(token),
    },
  );
}
