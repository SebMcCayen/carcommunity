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

