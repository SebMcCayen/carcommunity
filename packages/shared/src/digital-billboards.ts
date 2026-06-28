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

export const BILLBOARD_STATUSES = ['draft', 'active', 'paused', 'ended'] as const;
export type BillboardStatus = (typeof BILLBOARD_STATUSES)[number];

// ---------------------------------------------------------------------------
// Placement type
// ---------------------------------------------------------------------------

export const BILLBOARD_PLACEMENT_TYPES = [
  'map_billboard',
  'event_area',
  'partner_area',
  'other_approved_location',
] as const;
export type BillboardPlacementType = (typeof BILLBOARD_PLACEMENT_TYPES)[number];

// ---------------------------------------------------------------------------
// Interaction type
// ---------------------------------------------------------------------------

export const BILLBOARD_INTERACTION_TYPES = [
  'impression',
  'open',
  'navigate',
  'phone',
  'website',
  'offer_view',
] as const;
export type BillboardInteractionType = (typeof BILLBOARD_INTERACTION_TYPES)[number];

// ---------------------------------------------------------------------------
// CTA types
// ---------------------------------------------------------------------------

export const BILLBOARD_CTA_TYPES = [
  'navigate',
  'phone',
  'website',
  'offer_view',
  'partner_profile',
] as const;
export type BillboardCtaType = (typeof BILLBOARD_CTA_TYPES)[number];

// ---------------------------------------------------------------------------
// Route paths
// ---------------------------------------------------------------------------

export const DIGITAL_BILLBOARD_ROUTE_PATHS = {
  list: '/v1/digital-billboards',
  mapMarkers: '/v1/digital-billboards/map-markers',
  adminList: '/v1/admin/digital-billboards',
} as const;

export function buildBillboardPath(billboardId: string): string {
  return `/v1/digital-billboards/${billboardId}`;
}
export function buildBillboardInteractionPath(billboardId: string): string {
  return `/v1/digital-billboards/${billboardId}/interactions`;
}
export function buildAdminBillboardPath(billboardId: string): string {
  return `/v1/admin/digital-billboards/${billboardId}`;
}
export function buildAdminBillboardActivatePath(billboardId: string): string {
  return `/v1/admin/digital-billboards/${billboardId}/activate`;
}
export function buildAdminBillboardPausePath(billboardId: string): string {
  return `/v1/admin/digital-billboards/${billboardId}/pause`;
}
export function buildAdminBillboardEndPath(billboardId: string): string {
  return `/v1/admin/digital-billboards/${billboardId}/end`;
}

// ---------------------------------------------------------------------------
// Limits
// ---------------------------------------------------------------------------

export const MAX_BILLBOARD_HEADLINE_LENGTH = 100;
export const MAX_BILLBOARD_MESSAGE_LENGTH = 300;
export const MAX_BILLBOARD_SAFETY_NOTE_LENGTH = 500;
export const MAX_BILLBOARD_CTA_VALUE_LENGTH = 500;
export const DEFAULT_BILLBOARD_PAGE_SIZE = 20;
export const MAX_BILLBOARD_PAGE_SIZE = 50;

// ---------------------------------------------------------------------------
// Public map marker (safe — no internal metadata)
// ---------------------------------------------------------------------------

export interface PublicBillboardMapMarker {
  billboardId: string;
  partnerId: string;
  partnerCompanyName: string;
  headline: string;
  message: string;
  latitude: number;
  longitude: number;
  /** Always 'Sponsrad placering' */
  sponsorLabel: string;
  availableUntil: string | null;
  callToActionType: BillboardCtaType | null;
}

export interface PublicBillboardMapMarkersResponse {
  ok: true;
  data: {
    markers: PublicBillboardMapMarker[];
    generatedAt: string;
  };
  meta: {
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
  };
}

// ---------------------------------------------------------------------------
// Public billboard list
// ---------------------------------------------------------------------------

export interface PublicBillboardDetail {
  billboardId: string;
  partnerId: string;
  partnerCompanyName: string;
  headline: string;
  message: string;
  latitude: number;
  longitude: number;
  sponsorLabel: string;
  availableFrom: string | null;
  availableUntil: string | null;
  callToActionType: BillboardCtaType | null;
  /** Non-null only for phone/website CTA. Never exposes codes. */
  callToActionValue: string | null;
  placementType: BillboardPlacementType;
}

export interface PaginatedPublicBillboardsResponse {
  ok: true;
  data: {
    billboards: PublicBillboardDetail[];
  };
  meta: {
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
  };
}

export interface PublicBillboardDetailResponse {
  ok: true;
  data: PublicBillboardDetail;
}

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

// ---------------------------------------------------------------------------
// Pause request
// ---------------------------------------------------------------------------

export interface AdminPauseBillboardRequest {
  reason: string;
}

export interface AdminPauseBillboardResponse {
  ok: true;
  data: AdminBillboardSummary;
}

// ---------------------------------------------------------------------------
// End request
// ---------------------------------------------------------------------------

export interface AdminEndBillboardRequest {
  reason: string;
}

export interface AdminEndBillboardResponse {
  ok: true;
  data: AdminBillboardSummary;
}

// ---------------------------------------------------------------------------
// Billboard preview
// ---------------------------------------------------------------------------

export interface BillboardPreview {
  headline: string;
  message: string;
  sponsorLabel: string;
  partnerCompanyName: string;
  callToActionType: BillboardCtaType | null;
}

// ---------------------------------------------------------------------------
// Record interaction
// ---------------------------------------------------------------------------

export interface RecordBillboardInteractionRequest {
  interactionType: BillboardInteractionType;
  idempotencyKey?: string;
}

export interface RecordBillboardInteractionResponse {
  ok: true;
  data: { recorded: boolean };
}
