/**
 * Partner Offers API client for the mobile app.
 *
 * Privacy and security rules:
 *  - discountCode is NEVER in teasers, member list, or member detail responses.
 *  - discountCode is ONLY returned from the explicit show-code endpoint, via user action.
 *  - Teaser responses are safe for all authenticated non-suspended users.
 *  - Member offer routes require active member_monthly subscription (enforced server-side).
 *  - Tokens are never logged.
 */

import {
  PARTNER_OFFER_ROUTE_PATHS,
  buildMemberOfferPath,
  buildMemberOfferShowCodePath,
  buildMemberOfferSavePath,
  buildPartnerOfferTeasersPath,
  type PublicPartnerOfferTeaser,
  type MemberPartnerOfferDetail,
  type ShowCodeResponse,
  type SaveOfferResponse,
  type PaginatedPartnerOfferTeasersResponse,
  type PaginatedMemberPartnerOffersResponse,
} from '@carcommunity/shared/partner-offers';

import { publicEnv } from '../config/env';

const base = publicEnv.apiBaseUrl.replace(/\/$/, '');

const buildUrl = (path: string) => `${base}${path.startsWith('/') ? path : `/${path}`}`;
const buildAuthHeader = (token?: string): Record<string, string> =>
  token ? { Authorization: 'Bearer ' + token } : {};

export class PartnerOfferApiError extends Error {
  constructor(
    public readonly statusCode: number,
    message: string,
  ) {
    super(message);
    this.name = 'PartnerOfferApiError';
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
    throw new PartnerOfferApiError(response.status, message);
  }
  return (await response.json()) as TResponse;
}

// ---------------------------------------------------------------------------
// Teaser API (safe for all authenticated users)
// ---------------------------------------------------------------------------

/**
 * Fetches active offer teasers (safe fields only — no discount codes).
 * Optionally filter by partnerId to show offers for a specific partner.
 * Any authenticated non-suspended user may call this.
 */
export async function getPartnerOfferTeasers(
  page = 1,
  partnerId?: string,
  token?: string,
): Promise<PaginatedPartnerOfferTeasersResponse> {
  const params = new URLSearchParams({ page: String(page) });
  if (partnerId) {
    const path = `${buildPartnerOfferTeasersPath(partnerId)}?${params.toString()}`;
    return requestJson<PaginatedPartnerOfferTeasersResponse>(path, {
      headers: { ...buildAuthHeader(token) },
    });
  }
  return requestJson<PaginatedPartnerOfferTeasersResponse>(
    `${PARTNER_OFFER_ROUTE_PATHS.teasers}?${params.toString()}`,
    { headers: { ...buildAuthHeader(token) } },
  );
}

// ---------------------------------------------------------------------------
// Member offer API (requires active member_monthly subscription)
// ---------------------------------------------------------------------------

/**
 * Fetches full list of active member offers for the authenticated user.
 * Requires active member_monthly subscription (enforced server-side).
 * discountCode is NOT included — use showOfferCode for that.
 */
export async function getMemberOffers(
  token: string,
  page = 1,
): Promise<PaginatedMemberPartnerOffersResponse> {
  const params = new URLSearchParams({ page: String(page) });
  return requestJson<PaginatedMemberPartnerOffersResponse>(
    `${PARTNER_OFFER_ROUTE_PATHS.memberOffers}?${params.toString()}`,
    {
      headers: {
        ...buildAuthHeader(token),
      },
    },
  );
}

/**
 * Fetches full detail for a single partner offer.
 * Requires active member_monthly subscription (enforced server-side).
 * discountCode is NOT included — use showOfferCode for that.
 * Returns null if the offer is not found.
 */
export async function getMemberOfferDetail(
  offerId: string,
  token: string,
): Promise<MemberPartnerOfferDetail | null> {
  try {
    const response = await requestJson<{ ok: true; data: MemberPartnerOfferDetail }>(
      buildMemberOfferPath(offerId),
      {
        headers: {
          ...buildAuthHeader(token),
        },
      },
    );
    return response.data;
  } catch (err) {
    if (err instanceof PartnerOfferApiError && err.statusCode === 404) return null;
    throw err;
  }
}

/**
 * Reveals the discount code and redemption instructions for the specified offer.
 * Requires explicit user action before calling — never call automatically.
 * Requires active member_monthly subscription (enforced server-side).
 * Backend rate-limits this endpoint per user.
 */
export async function showOfferCode(
  offerId: string,
  token: string,
): Promise<ShowCodeResponse> {
  return requestJson<ShowCodeResponse>(buildMemberOfferShowCodePath(offerId), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...buildAuthHeader(token),
    },
    body: JSON.stringify({}),
  });
}

/**
 * Saves an offer to the user's saved list. Idempotent.
 * Requires active member_monthly subscription (enforced server-side).
 */
export async function saveOffer(
  offerId: string,
  token: string,
): Promise<SaveOfferResponse> {
  return requestJson<SaveOfferResponse>(buildMemberOfferSavePath(offerId), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...buildAuthHeader(token),
    },
    body: JSON.stringify({}),
  });
}

/**
 * Removes an offer from the user's saved list. Idempotent.
 * Requires active member_monthly subscription (enforced server-side).
 */
export async function unsaveOffer(
  offerId: string,
  token: string,
): Promise<void> {
  await requestJson<{ ok: boolean }>(buildMemberOfferSavePath(offerId), {
    method: 'DELETE',
    headers: {
      ...buildAuthHeader(token),
    },
  });
}

/**
 * Fetches the user's saved partner offers.
 * Requires active member_monthly subscription (enforced server-side).
 */
export async function getSavedOffers(
  token: string,
  page = 1,
): Promise<PaginatedMemberPartnerOffersResponse> {
  const params = new URLSearchParams({ page: String(page) });
  return requestJson<PaginatedMemberPartnerOffersResponse>(
    `${PARTNER_OFFER_ROUTE_PATHS.savedOffers}?${params.toString()}`,
    {
      headers: {
        ...buildAuthHeader(token),
      },
    },
  );
}

export type {
  PublicPartnerOfferTeaser,
  MemberPartnerOfferDetail,
  ShowCodeResponse,
  SaveOfferResponse,
};
