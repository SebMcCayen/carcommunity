/**
 * Partners (KCC Företagspartner) feature module for the admin portal.
 *
 * Provides API client functions for managing partner applications, companies, and offers.
 *
 * Security notes:
 *  - Backend is the sole authority for approval, publication, and all status transitions.
 *  - All operations are validated and authorised server-side.
 *  - New companies and offers start as draft — public activation is a separate explicit admin action.
 *  - Important changes are audited server-side.
 *  - Application contact details are internal — never returned through public APIs.
 *  - Partners are never hard-deleted after being active; use pause or end.
 *  - No personal data, live location, or individual user tracking data is exposed.
 *  - discountCode is NEVER included in list/detail responses — only via show-code endpoint (member-facing).
 */

import {
  PARTNER_ROUTE_PATHS,
  PARTNER_CATEGORIES,
  buildAdminApplicationPath,
  buildAdminApplicationStartReviewPath,
  buildAdminApplicationApprovePath,
  buildAdminApplicationRejectPath,
  buildAdminPartnerPath,
  buildAdminPartnerActivatePath,
  buildAdminPartnerPausePath,
  buildAdminPartnerEndPath,
  type AdminPartnerApplicationSummary,
  type AdminPartnerApplicationDetail,
  type AdminPartnerCompanySummary,
  type AdminPartnerCompanyDetail,
  type AdminCreatePartnerRequest,
  type AdminUpdatePartnerRequest,
  type PaginatedAdminPartnerApplicationsResponse,
  type PaginatedAdminPartnerCompaniesResponse,
  type AdminPartnerApplicationDetailResponse,
  type AdminPartnerCompanyDetailResponse,
  type PartnerApplicationStatus,
  type PartnerCompanyStatus,
  type PartnerCategory,
} from '@carcommunity/shared/partners';

import {
  PARTNER_OFFER_ROUTE_PATHS,
  PARTNER_OFFER_STATUSES,
  PARTNER_OFFER_TYPES,
  buildAdminOfferPath,
  buildAdminOfferActivatePath,
  buildAdminOfferPausePath,
  buildAdminOfferEndPath,
  buildAdminCreateOfferPath,
  type AdminPartnerOfferSummary,
  type AdminPartnerOfferDetail,
  type CreatePartnerOfferRequest,
  type UpdatePartnerOfferRequest,
  type PaginatedAdminPartnerOffersResponse,
  type AdminPartnerOfferDetailResponse,
  type PartnerOfferStatus,
  type PartnerOfferType,
} from '@carcommunity/shared/partner-offers';

import { ApiError, apiRequest } from '../../lib/api';

export type {
  AdminPartnerApplicationSummary,
  AdminPartnerApplicationDetail,
  AdminPartnerCompanySummary,
  AdminPartnerCompanyDetail,
  AdminCreatePartnerRequest,
  AdminUpdatePartnerRequest,
  PartnerApplicationStatus,
  PartnerCompanyStatus,
  PartnerCategory,
  // Offer types
  AdminPartnerOfferSummary,
  AdminPartnerOfferDetail,
  CreatePartnerOfferRequest,
  UpdatePartnerOfferRequest,
  PartnerOfferStatus,
  PartnerOfferType,
};
export { ApiError, PARTNER_CATEGORIES, PARTNER_OFFER_STATUSES, PARTNER_OFFER_TYPES };

// ---------------------------------------------------------------------------
// Application API functions
// ---------------------------------------------------------------------------

/**
 * Lists all partner applications for the admin view.
 * Requires admin or owner role (enforced server-side).
 * Returns application contact details — never forward to public APIs.
 */
export async function adminListPartnerApplications(
  page = 1,
  token?: string,
): Promise<PaginatedAdminPartnerApplicationsResponse> {
  return apiRequest<PaginatedAdminPartnerApplicationsResponse>(
    `${PARTNER_ROUTE_PATHS.adminApplications}?page=${page}`,
    { token },
  );
}

/**
 * Returns full detail for a single partner application including contact fields.
 * Admin use only — contact details must never be forwarded to public responses.
 */
export async function adminGetPartnerApplication(
  applicationId: string,
  token?: string,
): Promise<AdminPartnerApplicationDetail> {
  const response = await apiRequest<AdminPartnerApplicationDetailResponse>(
    buildAdminApplicationPath(applicationId),
    { token },
  );
  return response.data;
}

/**
 * Starts the review process for a submitted application.
 * Backend writes an audit entry.
 */
export async function adminStartApplicationReview(
  applicationId: string,
  token?: string,
): Promise<void> {
  await apiRequest<{ ok: boolean }>(buildAdminApplicationStartReviewPath(applicationId), {
    method: 'POST',
    body: {},
    token,
  });
}

/**
 * Approves a partner application.
 * Creates a DRAFT partner company — public activation is a separate step.
 * Backend writes an audit entry.
 */
export async function adminApproveApplication(
  applicationId: string,
  token?: string,
): Promise<{ partnerCompanyId: string }> {
  const response = await apiRequest<{ ok: boolean; data: { partnerCompanyId: string } }>(
    buildAdminApplicationApprovePath(applicationId),
    {
      method: 'POST',
      body: {},
      token,
    },
  );
  return response.data;
}

/**
 * Rejects a partner application.
 * A non-empty reason is required by the backend.
 * Backend writes an audit entry.
 */
export async function adminRejectApplication(
  applicationId: string,
  reason: string,
  token?: string,
): Promise<void> {
  await apiRequest<{ ok: boolean }>(buildAdminApplicationRejectPath(applicationId), {
    method: 'POST',
    body: { reason },
    token,
  });
}

// ---------------------------------------------------------------------------
// Company API functions
// ---------------------------------------------------------------------------

/**
 * Lists all partner companies (all statuses) for the admin view.
 * Requires admin or owner role (enforced server-side).
 */
export async function adminListPartnerCompanies(
  page = 1,
  token?: string,
): Promise<PaginatedAdminPartnerCompaniesResponse> {
  return apiRequest<PaginatedAdminPartnerCompaniesResponse>(
    `${PARTNER_ROUTE_PATHS.adminPartners}?page=${page}`,
    { token },
  );
}

/**
 * Returns full admin detail for a single partner company.
 */
export async function adminGetPartnerCompany(
  partnerId: string,
  token?: string,
): Promise<AdminPartnerCompanyDetail> {
  const response = await apiRequest<AdminPartnerCompanyDetailResponse>(
    buildAdminPartnerPath(partnerId),
    { token },
  );
  return response.data;
}

/**
 * Creates a new partner company in draft status.
 * Backend enforces: draft status on creation, validation, and audit logging.
 */
export async function adminCreatePartnerCompany(
  request: AdminCreatePartnerRequest,
  token?: string,
): Promise<AdminPartnerCompanyDetail> {
  const response = await apiRequest<AdminPartnerCompanyDetailResponse>(
    PARTNER_ROUTE_PATHS.adminPartners,
    {
      method: 'POST',
      body: request,
      token,
    },
  );
  return response.data;
}

/**
 * Updates an existing draft or paused partner company.
 * Active companies cannot be edited via this endpoint — pause first.
 * Status cannot be changed here; use dedicated activate/pause/end actions.
 */
export async function adminUpdatePartnerCompany(
  partnerId: string,
  request: AdminUpdatePartnerRequest,
  token?: string,
): Promise<AdminPartnerCompanyDetail> {
  const response = await apiRequest<AdminPartnerCompanyDetailResponse>(
    buildAdminPartnerPath(partnerId),
    {
      method: 'PATCH',
      body: request,
      token,
    },
  );
  return response.data;
}

/**
 * Activates a partner company, making it visible to the public.
 * Requires confirmation that coordinates represent the actual business location.
 * Backend validates address, coordinates, and category before activation.
 * Backend writes an audit entry.
 */
export async function adminActivatePartner(
  partnerId: string,
  token?: string,
): Promise<AdminPartnerCompanyDetail> {
  const response = await apiRequest<AdminPartnerCompanyDetailResponse>(
    buildAdminPartnerActivatePath(partnerId),
    {
      method: 'POST',
      body: { actualLocationConfirmed: true },
      token,
    },
  );
  return response.data;
}

/**
 * Pauses an active partner company, removing it from public view temporarily.
 * Backend writes an audit entry.
 */
export async function adminPausePartner(
  partnerId: string,
  reason?: string,
  token?: string,
): Promise<AdminPartnerCompanyDetail> {
  const response = await apiRequest<AdminPartnerCompanyDetailResponse>(
    buildAdminPartnerPausePath(partnerId),
    {
      method: 'POST',
      body: { reason },
      token,
    },
  );
  return response.data;
}

/**
 * Ends a partnership permanently.
 * Partner companies are never hard-deleted; this marks the company as ended.
 * Backend writes an audit entry.
 */
export async function adminEndPartnership(
  partnerId: string,
  reason?: string,
  token?: string,
): Promise<AdminPartnerCompanyDetail> {
  const response = await apiRequest<AdminPartnerCompanyDetailResponse>(
    buildAdminPartnerEndPath(partnerId),
    {
      method: 'POST',
      body: { reason },
      token,
    },
  );
  return response.data;
}

// ---------------------------------------------------------------------------
// Partner Offer API functions
// ---------------------------------------------------------------------------

/**
 * Lists all partner offers (all statuses) for the admin view.
 * discountCode is NEVER included in the response.
 * Requires admin or owner role (enforced server-side).
 */
export async function adminListPartnerOffers(
  options: { page?: number; partnerId?: string; status?: PartnerOfferStatus } = {},
  token?: string,
): Promise<PaginatedAdminPartnerOffersResponse> {
  const params = new URLSearchParams({ page: String(options.page ?? 1) });
  if (options.partnerId) params.set('partnerId', options.partnerId);
  if (options.status) params.set('status', options.status);
  return apiRequest<PaginatedAdminPartnerOffersResponse>(
    `${PARTNER_OFFER_ROUTE_PATHS.adminOffers}?${params.toString()}`,
    { token },
  );
}

/**
 * Returns full admin detail for a single partner offer.
 * discountCode is intentionally NOT included — never in list/detail.
 */
export async function adminGetPartnerOffer(
  offerId: string,
  token?: string,
): Promise<AdminPartnerOfferDetail> {
  const response = await apiRequest<AdminPartnerOfferDetailResponse>(
    buildAdminOfferPath(offerId),
    { token },
  );
  return response.data;
}

/**
 * Creates a new partner offer in draft status for the given partner.
 * Status cannot be set via this endpoint — always starts as draft.
 * Backend enforces validation and writes an audit entry.
 */
export async function adminCreatePartnerOffer(
  partnerId: string,
  request: CreatePartnerOfferRequest,
  token?: string,
): Promise<AdminPartnerOfferDetail> {
  const response = await apiRequest<AdminPartnerOfferDetailResponse>(
    buildAdminCreateOfferPath(partnerId),
    {
      method: 'POST',
      body: request,
      token,
    },
  );
  return response.data;
}

/**
 * Updates an existing draft or paused partner offer.
 * Active offers cannot be edited — pause first.
 * Status cannot be changed here; use dedicated activate/pause/end actions.
 */
export async function adminUpdatePartnerOffer(
  offerId: string,
  request: UpdatePartnerOfferRequest,
  token?: string,
): Promise<AdminPartnerOfferDetail> {
  const response = await apiRequest<AdminPartnerOfferDetailResponse>(
    buildAdminOfferPath(offerId),
    {
      method: 'PATCH',
      body: request,
      token,
    },
  );
  return response.data;
}

/**
 * Activates a partner offer.
 * Requires explicit confirmation. Partner company must be active.
 * Backend validates offer completeness and writes an audit entry.
 */
export async function adminActivatePartnerOffer(
  offerId: string,
  token?: string,
): Promise<AdminPartnerOfferDetail> {
  const response = await apiRequest<AdminPartnerOfferDetailResponse>(
    buildAdminOfferActivatePath(offerId),
    {
      method: 'POST',
      body: { confirmed: true },
      token,
    },
  );
  return response.data;
}

/**
 * Pauses an active partner offer. A reason is required.
 * Backend writes an audit entry with the reason.
 */
export async function adminPausePartnerOffer(
  offerId: string,
  reason: string,
  token?: string,
): Promise<AdminPartnerOfferDetail> {
  const response = await apiRequest<AdminPartnerOfferDetailResponse>(
    buildAdminOfferPausePath(offerId),
    {
      method: 'POST',
      body: { reason },
      token,
    },
  );
  return response.data;
}

/**
 * Ends a partner offer permanently. A reason is required.
 * Backend writes an audit entry with the reason.
 */
export async function adminEndPartnerOffer(
  offerId: string,
  reason: string,
  token?: string,
): Promise<AdminPartnerOfferDetail> {
  const response = await apiRequest<AdminPartnerOfferDetailResponse>(
    buildAdminOfferEndPath(offerId),
    {
      method: 'POST',
      body: { reason },
      token,
    },
  );
  return response.data;
}
