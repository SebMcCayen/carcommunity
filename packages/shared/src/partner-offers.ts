/**
 * Shared contracts for the Partner Offers / Member Benefits feature.
 *
 * Design rules encoded here:
 *  - Backend is the sole authority for entitlement, activation, and status transitions.
 *  - Discount codes are NEVER included in teaser or list/detail responses.
 *  - Codes are ONLY returned from the dedicated show-code endpoint.
 *  - Discount codes must never appear in logs, audit metadata, or URL parameters.
 *  - New offers always start as `draft`. Status is never set via create/update body.
 *  - Offers require active partner company before activation.
 *  - Suspension always overrides member entitlement for protected offer access.
 *  - All offer text is plain text — never rendered as HTML.
 *
 * Security notes for mobile clients:
 *  - teasers: safe for free users, no code, no protected fields.
 *  - member detail: members only, still no code.
 *  - show-code: members only, returns code for active offers only.
 *  - Admin contracts are only for the admin portal — never used in mobile.
 */

// ---------------------------------------------------------------------------
// Status
// ---------------------------------------------------------------------------

export const PARTNER_OFFER_STATUSES = ['draft', 'active', 'paused', 'ended', 'expired'] as const;
export type PartnerOfferStatus = (typeof PARTNER_OFFER_STATUSES)[number];

// ---------------------------------------------------------------------------
// Offer type
// ---------------------------------------------------------------------------

export const PARTNER_OFFER_TYPES = [
  'discount_code',
  'percentage_discount',
  'fixed_discount',
  'member_benefit',
  'special_offer',
  'other',
] as const;
export type PartnerOfferType = (typeof PARTNER_OFFER_TYPES)[number];

// ---------------------------------------------------------------------------
// Limits
// ---------------------------------------------------------------------------

export const MAX_OFFER_TITLE_LENGTH = 150;
export const MAX_OFFER_TEASER_TEXT_LENGTH = 250;
export const MAX_OFFER_DESCRIPTION_LENGTH = 2_000;
export const MAX_OFFER_REDEMPTION_INSTRUCTIONS_LENGTH = 1_000;
export const MAX_OFFER_TERMS_LENGTH = 2_000;
export const MAX_OFFER_DISCOUNT_CODE_LENGTH = 100;
export const MAX_OFFER_PERCENTAGE_DISCOUNT = 100;
export const DEFAULT_PARTNER_OFFER_PAGE_SIZE = 20;
export const MAX_PARTNER_OFFER_PAGE_SIZE = 100;

// ---------------------------------------------------------------------------
// Route paths
// ---------------------------------------------------------------------------

export const PARTNER_OFFER_ROUTE_PATHS = {
  teasers: '/v1/partners/offers/teasers',
  memberOffers: '/v1/partner-offers',
  savedOffers: '/v1/users/me/saved-partner-offers',
  adminOffers: '/v1/admin/partner-offers',
} as const;

// ---------------------------------------------------------------------------
// Route path builders
// ---------------------------------------------------------------------------

export function buildPartnerOfferTeasersPath(partnerId: string): string {
  return `/v1/partners/${partnerId}/offers/teasers`;
}

export function buildMemberOfferPath(offerId: string): string {
  return `/v1/partner-offers/${offerId}`;
}

export function buildMemberOfferShowCodePath(offerId: string): string {
  return `/v1/partner-offers/${offerId}/show-code`;
}

export function buildMemberOfferSavePath(offerId: string): string {
  return `/v1/partner-offers/${offerId}/save`;
}

export function buildAdminOfferPath(offerId: string): string {
  return `/v1/admin/partner-offers/${offerId}`;
}

export function buildAdminOfferActivatePath(offerId: string): string {
  return `/v1/admin/partner-offers/${offerId}/activate`;
}

export function buildAdminOfferPausePath(offerId: string): string {
  return `/v1/admin/partner-offers/${offerId}/pause`;
}

export function buildAdminOfferEndPath(offerId: string): string {
  return `/v1/admin/partner-offers/${offerId}/end`;
}

export function buildAdminCreateOfferPath(partnerId: string): string {
  return `/v1/admin/partners/${partnerId}/offers`;
}

// ---------------------------------------------------------------------------
// Mobile: safe teaser (no code, no protected fields)
// ---------------------------------------------------------------------------

/**
 * Safe offer teaser for display to all authenticated users.
 *
 * Does NOT contain discountCode, description, redemptionInstructions, or terms.
 * Suitable for showing the offer title/teaser on a partner detail screen.
 */
export interface PublicPartnerOfferTeaser {
  offerId: string;
  partnerId: string;
  partnerCompanyName: string;
  title: string;
  teaserText: string;
  offerType: PartnerOfferType;
  availableUntil: string | null;
  requiresMembership: true;
}

// ---------------------------------------------------------------------------
// Mobile: full member offer detail (members only, still NO code)
// ---------------------------------------------------------------------------

/**
 * Full offer detail for active members.
 *
 * Does NOT contain discountCode — use the show-code endpoint for that.
 * Returns description, redemptionInstructions, terms, and discount metadata.
 */
export interface MemberPartnerOfferDetail {
  offerId: string;
  partnerId: string;
  partnerCompanyName: string;
  title: string;
  teaserText: string;
  offerType: PartnerOfferType;
  description: string;
  redemptionInstructions: string | null;
  terms: string | null;
  percentageDiscount: number | null;
  fixedDiscountMinorUnits: number | null;
  currencyCode: string | null;
  availableFrom: string | null;
  availableUntil: string | null;
  // NOTE: discountCode is NOT included here — use show-code endpoint only
}

// ---------------------------------------------------------------------------
// Mobile: paginated responses
// ---------------------------------------------------------------------------

export interface PaginatedPartnerOfferTeasersResponse {
  ok: true;
  data: {
    offers: PublicPartnerOfferTeaser[];
  };
  meta: {
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
  };
}

export interface PaginatedMemberPartnerOffersResponse {
  ok: true;
  data: {
    offers: MemberPartnerOfferDetail[];
  };
  meta: {
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
  };
}

// ---------------------------------------------------------------------------
// Mobile: show-code response
// ---------------------------------------------------------------------------

/**
 * Response for the show-code endpoint.
 *
 * code is null when the offer type does not use a discount code.
 * Only returned for active offers to active members.
 * Never cached or logged.
 */
export interface ShowCodeResponse {
  offerId: string;
  code: string | null;
  redemptionInstructions: string | null;
  expiresAt: string | null;
}

// ---------------------------------------------------------------------------
// Mobile: save offer response
// ---------------------------------------------------------------------------

export interface SaveOfferResponse {
  ok: true;
  data: { offerId: string; savedAt: string };
}

// ---------------------------------------------------------------------------
// Admin: offer summary (list view)
// ---------------------------------------------------------------------------

/**
 * Admin-level offer summary for the list view.
 * Does NOT include discountCode.
 */
export interface AdminPartnerOfferSummary {
  offerId: string;
  partnerId: string;
  partnerCompanyName: string;
  title: string;
  offerType: PartnerOfferType;
  status: PartnerOfferStatus;
  availableFrom: string | null;
  availableUntil: string | null;
  activatedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

// ---------------------------------------------------------------------------
// Admin: offer detail (single view)
// ---------------------------------------------------------------------------

/**
 * Full admin offer detail.
 * Does NOT include discountCode — intentionally excluded from all list/detail views.
 * Use the show-code endpoint (which is member-facing) to retrieve codes.
 */
export interface AdminPartnerOfferDetail {
  offerId: string;
  partnerId: string;
  partnerCompanyName: string;
  title: string;
  teaserText: string;
  description: string | null;
  offerType: PartnerOfferType;
  status: PartnerOfferStatus;
  redemptionInstructions: string | null;
  terms: string | null;
  percentageDiscount: number | null;
  fixedDiscountMinorUnits: number | null;
  currencyCode: string | null;
  availableFrom: string | null;
  availableUntil: string | null;
  activatedAt: string | null;
  pausedAt: string | null;
  endedAt: string | null;
  createdByUserId: string;
  updatedByUserId: string | null;
  createdAt: string;
  updatedAt: string;
  // NOTE: discountCode is intentionally NOT included — never in list/detail, only show-code
}

// ---------------------------------------------------------------------------
// Admin: paginated response
// ---------------------------------------------------------------------------

export interface PaginatedAdminPartnerOffersResponse {
  ok: true;
  data: {
    offers: AdminPartnerOfferSummary[];
  };
  meta: {
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
  };
}

export interface AdminPartnerOfferDetailResponse {
  ok: true;
  data: AdminPartnerOfferDetail;
}

// ---------------------------------------------------------------------------
// Admin: create/update request
// ---------------------------------------------------------------------------

/**
 * Request body for POST /v1/admin/partners/:partnerId/offers.
 *
 * New offers always start as draft.
 * Status is never settable via this endpoint.
 */
export interface CreatePartnerOfferRequest {
  title: string;
  teaserText: string;
  description: string;
  offerType: PartnerOfferType;
  redemptionInstructions?: string | null;
  terms?: string | null;
  discountCode?: string | null;
  percentageDiscount?: number | null;
  fixedDiscountMinorUnits?: number | null;
  currencyCode?: string | null;
  availableFrom?: string | null;
  availableUntil?: string | null;
}

/**
 * Request body for PATCH /v1/admin/partner-offers/:offerId.
 *
 * Only draft or paused offers may be edited.
 * All fields are optional.
 * Status is never settable via this endpoint.
 */
export interface UpdatePartnerOfferRequest {
  title?: string;
  teaserText?: string;
  description?: string;
  offerType?: PartnerOfferType;
  redemptionInstructions?: string | null;
  terms?: string | null;
  discountCode?: string | null;
  percentageDiscount?: number | null;
  fixedDiscountMinorUnits?: number | null;
  currencyCode?: string | null;
  availableFrom?: string | null;
  availableUntil?: string | null;
}

// ---------------------------------------------------------------------------
// Admin: status action requests
// ---------------------------------------------------------------------------

/**
 * Activation requires explicit confirmation.
 * Backend validates offer completeness and partner company status.
 */
export interface AdminActivateOfferRequest {
  confirmed: boolean;
}

/**
 * Pausing requires a non-empty reason for audit purposes.
 */
export interface AdminPauseOfferRequest {
  reason: string;
}

/**
 * Ending requires a non-empty reason for audit purposes.
 */
export interface AdminEndOfferRequest {
  reason: string;
}
