/**
 * Partners (KCC Företagspartner) API client for the mobile app.
 *
 * Privacy and security rules:
 *  - Backend is the sole authority for partner approval and publication.
 *  - Only active partner companies are returned through public APIs.
 *  - Application contact details are internal — never returned publicly.
 *  - Tokens are never logged.
 *  - No offers, discount codes, analytics, or billboard data in this step.
 *  - Free users and members may view public partner information.
 *  - Partner map markers contain only safe public fields.
 */

import {
  PARTNER_ROUTE_PATHS,
  buildPartnerPath,
  type PartnerCompanyPublicSummary,
  type PartnerCompanyPublicDetail,
  type PartnerMapMarker,
  type SubmitPartnerApplicationRequest,
  type PartnerApplicationAck,
  type PaginatedPartnerCompaniesResponse,
  type PartnerMapMarkersResponse,
  type PartnerCompanyPublicDetailResponse,
  type PartnerApplicationAckResponse,
  type PartnerCategory,
} from '@carcommunity/shared/partners';

import { publicEnv } from '../config/env';

const base = publicEnv.apiBaseUrl.replace(/\/$/, '');

const buildUrl = (path: string) => `${base}${path.startsWith('/') ? path : `/${path}`}`;
const buildAuthHeader = (token?: string): Record<string, string> =>
  token ? { Authorization: 'Bearer ' + token } : {};

export class PartnerApiError extends Error {
  constructor(
    public readonly statusCode: number,
    message: string,
  ) {
    super(message);
    this.name = 'PartnerApiError';
  }
}

async function requestJson<TResponse>(
  path: string,
  init: RequestInit = {},
): Promise<TResponse> {
  const response = await fetch(buildUrl(path), init);
  if (!response.ok) {
    interface ErrorBody {
      error?: { message?: string };
    }
    let message = `HTTP ${response.status}`;
    try {
      const body = (await response.json()) as ErrorBody;
      if (body?.error?.message) message = body.error.message;
    } catch {
      // non-JSON body — ignore
    }
    throw new PartnerApiError(response.status, message);
  }
  return (await response.json()) as TResponse;
}

// ---------------------------------------------------------------------------
// Public API functions (no auth required)
// ---------------------------------------------------------------------------

/**
 * Fetches the list of active public partner companies.
 * Only active partners are returned — backend enforces this.
 * Safe to call without authentication.
 */
export async function getActivePartners(
  page = 1,
  category?: PartnerCategory,
): Promise<PaginatedPartnerCompaniesResponse> {
  const params = new URLSearchParams({ page: String(page) });
  if (category) params.set('category', category);
  return requestJson<PaginatedPartnerCompaniesResponse>(
    `${PARTNER_ROUTE_PATHS.partners}?${params.toString()}`,
  );
}

/**
 * Fetches public detail for a single active partner company.
 * Returns only public-safe fields.
 * Returns null if the partner is not found or not active.
 */
export async function getPartnerDetail(
  partnerId: string,
): Promise<PartnerCompanyPublicDetail | null> {
  try {
    const response = await requestJson<PartnerCompanyPublicDetailResponse>(
      buildPartnerPath(partnerId),
    );
    return response.data;
  } catch (err) {
    if (err instanceof PartnerApiError && err.statusCode === 404) return null;
    throw err;
  }
}

/**
 * Fetches the partner map markers for all active partners.
 * Returns only: partnerId, companyName, category, latitude, longitude, label.
 * Safe to call without authentication.
 * Result is bounded by the backend (max 500 markers).
 */
export async function getPartnerMapMarkers(): Promise<PartnerMapMarker[]> {
  const response = await requestJson<PartnerMapMarkersResponse>(
    PARTNER_ROUTE_PATHS.partnerMapMarkers,
  );
  return response.data.markers;
}

// ---------------------------------------------------------------------------
// Application submission (requires authentication)
// ---------------------------------------------------------------------------

/**
 * Submits a new partner application on behalf of the authenticated user.
 * Contact details are used only for the application process — never exposed publicly.
 * Backend rate-limits and blocks duplicate active applications.
 */
export async function submitPartnerApplication(
  request: SubmitPartnerApplicationRequest,
  token?: string,
): Promise<PartnerApplicationAck> {
  const response = await requestJson<PartnerApplicationAckResponse>(
    PARTNER_ROUTE_PATHS.submitApplication,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...buildAuthHeader(token),
      },
      body: JSON.stringify(request),
    },
  );
  return response.data;
}

export type {
  PartnerCompanyPublicSummary,
  PartnerCompanyPublicDetail,
  PartnerMapMarker,
  PartnerApplicationAck,
  PartnerCategory,
};
