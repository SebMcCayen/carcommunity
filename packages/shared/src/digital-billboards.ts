/**
 * Shared contracts for the sponsored digital billboard feature.
 *
 * Design rules:
 *  - Billboards are separate from the partner's real business location marker.
 *  - All billboards start as draft; activation requires explicit admin safety confirmation.
 *  - Public responses must never contain: safetyNote, approvalReason, createdByUserId,
 *    updatedByUserId, approvedByUserId, approvedAt, activatedAt, pausedAt, endedAt.
 *  - No bidding, billing, scripts, video, HTML, or ad-network tracking fields.
 *  - No personalisation or targeting fields.
 *  - Sponsorship must always be clearly labelled ("Sponsrad placering").
 *  - Safe driving must be enforced client-side; admin confirms at activation.
 */

// ---------------------------------------------------------------------------
// Status
// ---------------------------------------------------------------------------

const BILLBOARD_STATUSES = ['draft', 'active', 'paused', 'ended'] as const;
export type BillboardStatus = (typeof BILLBOARD_STATUSES)[number];

// ---------------------------------------------------------------------------
// Placement type
// ---------------------------------------------------------------------------

const BILLBOARD_PLACEMENT_TYPES = [
  'map_billboard',
  'event_area',
  'partner_area',
  'other_approved_location',
] as const;
export type BillboardPlacementType = (typeof BILLBOARD_PLACEMENT_TYPES)[number];

// ---------------------------------------------------------------------------
// CTA types
// ---------------------------------------------------------------------------

const BILLBOARD_CTA_TYPES = [
  'navigate',
  'phone',
  'website',
  'offer_view',
  'partner_profile',
] as const;
export type BillboardCtaType = (typeof BILLBOARD_CTA_TYPES)[number];

// ---------------------------------------------------------------------------
// Admin billboard summary
// ---------------------------------------------------------------------------

export interface AdminBillboardSummary {
  billboardId: string;
  partnerId: string;
  partnerCompanyName: string;
  headline: string;
  message: string;
  placementType: BillboardPlacementType;
  latitude: number;
  longitude: number;
  status: BillboardStatus;
  availableFrom: string | null;
  availableUntil: string | null;
  callToActionType: BillboardCtaType | null;
  callToActionValue: string | null;
  approvedAt: string | null;
  approvedByUserId: string | null;
  activatedAt: string | null;
  pausedAt: string | null;
  endedAt: string | null;
  safetyNote: string | null;
  createdByUserId: string;
  updatedByUserId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface PaginatedAdminBillboardsResponse {
  ok: true;
  data: {
    billboards: AdminBillboardSummary[];
  };
  meta: {
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
  };
}

export interface AdminBillboardDetailResponse {
  ok: true;
  data: AdminBillboardSummary;
}

// ---------------------------------------------------------------------------
// Create billboard request
// ---------------------------------------------------------------------------

export interface AdminCreateBillboardRequest {
  partnerCompanyId: string;
  headline: string;
  message: string;
  placementType: BillboardPlacementType;
  latitude: number;
  longitude: number;
  availableFrom?: string | null;
  availableUntil?: string | null;
  callToActionType?: BillboardCtaType | null;
  callToActionValue?: string | null;
  safetyNote?: string | null;
}

// ---------------------------------------------------------------------------
// Update billboard request (only draft/paused)
// ---------------------------------------------------------------------------

export interface AdminUpdateBillboardRequest {
  headline?: string;
  message?: string;
  placementType?: BillboardPlacementType;
  latitude?: number;
  longitude?: number;
  availableFrom?: string | null;
  availableUntil?: string | null;
  callToActionType?: BillboardCtaType | null;
  callToActionValue?: string | null;
  safetyNote?: string | null;
}

// ---------------------------------------------------------------------------
// Activate request
// ---------------------------------------------------------------------------

export interface AdminActivateBillboardRequest {
  /** Must be true: admin confirms placement is NOT the partner's business location marker */
  notBusinessLocationConfirmed: boolean;
  /** Must be true: admin confirms not in active road lane */
  notRoadLaneConfirmed: boolean;
  /** Must be true: admin confirms content does not imitate road sign */
  notRoadSignConfirmed: boolean;
  /** Must be true: admin confirms placement does not obstruct map information */
  notObstructingMapConfirmed: boolean;
  /** Must be true: admin confirms content is clearly marked as advertising */
  markedAsAdvertisingConfirmed: boolean;
  /** Must be true: admin confirms placement is suitable for map experience */
  suitableForMapConfirmed: boolean;
  /** Mandatory approval reason */
  approvalReason: string;
}

export interface AdminActivateBillboardResponse {
  ok: true;
  data: AdminBillboardSummary;
}

export interface AdminPauseBillboardResponse {
  ok: true;
  data: AdminBillboardSummary;
}

export interface AdminEndBillboardResponse {
  ok: true;
  data: AdminBillboardSummary;
}

