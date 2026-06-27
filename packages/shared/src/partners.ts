/**
 * Shared contracts for the KCC Företagspartner (Business Partner) feature.
 *
 * Design rules encoded here:
 *  - Backend is the sole authority for application status, approval, and publication.
 *  - A partner must be explicitly approved and then explicitly activated before becoming public.
 *  - Public responses never include application contact details, admin notes, billing info, or
 *    rejection reasons.
 *  - Internal contact data is kept separate from the public company profile.
 *  - Partners are placed at their actual business location — not arbitrary ad locations.
 *  - Digital billboards and partner analytics are out of scope for this foundation.
 *  - No offer or discount-code contracts in this step.
 *
 * Excluded from public mobile/web contracts:
 *  - Application contact email, contact person, contact phone
 *  - Review reason, rejection reason, admin notes
 *  - Billing or subscription information
 *  - Internal application or company identifiers beyond the opaque public ID
 *  - Audit metadata
 */

// ---------------------------------------------------------------------------
// Partner application status
// ---------------------------------------------------------------------------

export const PARTNER_APPLICATION_STATUSES = [
  'submitted',
  'under_review',
  'approved',
  'rejected',
  'withdrawn',
] as const;
export type PartnerApplicationStatus = (typeof PARTNER_APPLICATION_STATUSES)[number];

// ---------------------------------------------------------------------------
// Partner company status
// ---------------------------------------------------------------------------

export const PARTNER_COMPANY_STATUSES = ['draft', 'active', 'paused', 'ended'] as const;
export type PartnerCompanyStatus = (typeof PARTNER_COMPANY_STATUSES)[number];

// ---------------------------------------------------------------------------
// Partner category
// ---------------------------------------------------------------------------

export const PARTNER_CATEGORIES = [
  'workshop',
  'car_care',
  'parts',
  'tires',
  'charging',
  'restaurant',
  'retail',
  'other',
] as const;
export type PartnerCategory = (typeof PARTNER_CATEGORIES)[number];

// ---------------------------------------------------------------------------
// Limits
// ---------------------------------------------------------------------------

export const MAX_PARTNER_COMPANY_NAME_LENGTH = 150;
export const MAX_PARTNER_CONTACT_NAME_LENGTH = 120;
export const MAX_PARTNER_EMAIL_LENGTH = 254;
export const MAX_PARTNER_DESCRIPTION_LENGTH = 1_000;
export const MAX_PARTNER_MESSAGE_LENGTH = 2_000;
export const MAX_PARTNER_ADDRESS_LENGTH = 300;
export const MAX_PARTNER_PHONE_LENGTH = 30;
export const MAX_PARTNER_WEBSITE_URL_LENGTH = 500;

export const DEFAULT_PARTNER_PAGE_SIZE = 20;
export const MAX_PARTNER_PAGE_SIZE = 100;

// ---------------------------------------------------------------------------
// Route paths
// ---------------------------------------------------------------------------

export const PARTNER_ROUTE_PATHS = {
  /** POST — submit an application (authenticated user) */
  submitApplication: '/v1/partner-applications',

  /** GET — list active public partners */
  partners: '/v1/partners',

  /** GET — active public partner map markers */
  partnerMapMarkers: '/v1/partners/map-markers',

  /** GET — list partner applications (admin) */
  adminApplications: '/v1/admin/partner-applications',

  /** GET, POST — admin partner list + create */
  adminPartners: '/v1/admin/partners',
} as const;

export function buildPartnerPath(partnerId: string): string {
  return `/v1/partners/${partnerId}`;
}

export function buildAdminApplicationPath(applicationId: string): string {
  return `/v1/admin/partner-applications/${applicationId}`;
}

export function buildAdminApplicationStartReviewPath(applicationId: string): string {
  return `/v1/admin/partner-applications/${applicationId}/start-review`;
}

export function buildAdminApplicationApprovePath(applicationId: string): string {
  return `/v1/admin/partner-applications/${applicationId}/approve`;
}

export function buildAdminApplicationRejectPath(applicationId: string): string {
  return `/v1/admin/partner-applications/${applicationId}/reject`;
}

export function buildAdminPartnerPath(partnerId: string): string {
  return `/v1/admin/partners/${partnerId}`;
}

export function buildAdminPartnerActivatePath(partnerId: string): string {
  return `/v1/admin/partners/${partnerId}/activate`;
}

export function buildAdminPartnerPausePath(partnerId: string): string {
  return `/v1/admin/partners/${partnerId}/pause`;
}

export function buildAdminPartnerEndPath(partnerId: string): string {
  return `/v1/admin/partners/${partnerId}/end`;
}

// ---------------------------------------------------------------------------
// Application request (submitted by authenticated user or future website form)
// ---------------------------------------------------------------------------

/**
 * Body sent when a user or the website submits a partner application.
 * Contact details are for the application process only — never returned publicly.
 */
export interface SubmitPartnerApplicationRequest {
  companyName: string;
  organizationNumber?: string | null;
  category: PartnerCategory;
  contactName: string;
  contactEmail: string;
  contactPhone?: string | null;
  websiteUrl?: string | null;
  proposedDescription?: string | null;
  proposedAddress?: string | null;
  message?: string | null;
}

// ---------------------------------------------------------------------------
// Application response
// ---------------------------------------------------------------------------

/**
 * Safe acknowledgement returned to the applicant.
 * Never includes admin notes, review reason, or reviewer identity.
 */
export interface PartnerApplicationAck {
  applicationId: string;
  status: PartnerApplicationStatus;
  submittedAt: string;
}

export interface PartnerApplicationAckResponse {
  ok: true;
  data: PartnerApplicationAck;
}

// ---------------------------------------------------------------------------
// Admin — application summary (list)
// ---------------------------------------------------------------------------

/**
 * One row in the admin application list.
 * Includes contact details for internal review only.
 * Must never be exposed through public partner APIs.
 */
export interface AdminPartnerApplicationSummary {
  applicationId: string;
  companyName: string;
  category: PartnerCategory;
  contactName: string;
  contactEmail: string;
  contactPhone: string | null;
  status: PartnerApplicationStatus;
  submittedAt: string;
  reviewedAt: string | null;
}

export interface PaginatedAdminPartnerApplicationsResponse {
  ok: true;
  data: { applications: AdminPartnerApplicationSummary[] };
  meta: { page: number; pageSize: number; total: number; hasNext: boolean };
}

// ---------------------------------------------------------------------------
// Admin — application detail
// ---------------------------------------------------------------------------

/**
 * Full application detail for an admin reviewer.
 * Includes all contact and message fields — never return this to mobile clients.
 */
export interface AdminPartnerApplicationDetail {
  applicationId: string;
  companyName: string;
  organizationNumber: string | null;
  category: PartnerCategory;
  contactName: string;
  contactEmail: string;
  contactPhone: string | null;
  websiteUrl: string | null;
  proposedDescription: string | null;
  proposedAddress: string | null;
  message: string | null;
  status: PartnerApplicationStatus;
  submittedByUserId: string | null;
  reviewedByUserId: string | null;
  reviewedAt: string | null;
  reviewReason: string | null;
  createdAt: string;
  updatedAt: string;
  /** If approved and a partner company was created, the draft company ID. */
  partnerCompanyId: string | null;
}

export interface AdminPartnerApplicationDetailResponse {
  ok: true;
  data: AdminPartnerApplicationDetail;
}

// ---------------------------------------------------------------------------
// Admin — reject application request
// ---------------------------------------------------------------------------

export interface RejectPartnerApplicationRequest {
  /** Required reason for rejection — recorded in reviewReason. */
  reason: string;
}

// ---------------------------------------------------------------------------
// Public partner company summary (list)
// ---------------------------------------------------------------------------

/**
 * Safe public partner summary.
 * Never includes contact emails, internal notes, billing information,
 * rejection reasons, or audit data.
 */
export interface PartnerCompanyPublicSummary {
  partnerId: string;
  companyName: string;
  category: PartnerCategory;
  publicDescription: string;
  address: string;
  latitude: number;
  longitude: number;
  publicPhone: string | null;
  publicWebsiteUrl: string | null;
  /** Always "Samarbetspartner" for UI labelling. */
  statusLabel: string;
  isPartner: true;
}

export interface PaginatedPartnerCompaniesResponse {
  ok: true;
  data: { partners: PartnerCompanyPublicSummary[] };
  meta: { page: number; pageSize: number; total: number; hasNext: boolean };
}

// ---------------------------------------------------------------------------
// Public partner company detail
// ---------------------------------------------------------------------------

/** Extended public partner detail. Same safe field set as the summary. */
export type PartnerCompanyPublicDetail = PartnerCompanyPublicSummary;

export interface PartnerCompanyPublicDetailResponse {
  ok: true;
  data: PartnerCompanyPublicDetail;
}

// ---------------------------------------------------------------------------
// Partner map marker
// ---------------------------------------------------------------------------

/**
 * Minimal marker payload for the Mapbox map.
 * Contains only what is needed to render and identify the marker.
 * Never exposes internal IDs, contact details, or admin notes.
 */
export interface PartnerMapMarker {
  partnerId: string;
  companyName: string;
  category: PartnerCategory;
  latitude: number;
  longitude: number;
  /** Always "Samarbetspartner" */
  label: string;
}

export interface PartnerMapMarkersResponse {
  ok: true;
  data: { markers: PartnerMapMarker[] };
}

// ---------------------------------------------------------------------------
// Admin — create partner company (manual draft)
// ---------------------------------------------------------------------------

export interface AdminCreatePartnerRequest {
  companyName: string;
  category: PartnerCategory;
  publicDescription: string;
  address: string;
  latitude: number;
  longitude: number;
  publicPhone?: string | null;
  publicWebsiteUrl?: string | null;
  /** Optional link to an approved application. */
  applicationId?: string | null;
}

// ---------------------------------------------------------------------------
// Admin — update partner company
// ---------------------------------------------------------------------------

/** Status field is NOT allowed here — use dedicated activate/pause/end actions. */
export interface AdminUpdatePartnerRequest {
  companyName?: string;
  category?: PartnerCategory;
  publicDescription?: string;
  address?: string;
  latitude?: number;
  longitude?: number;
  publicPhone?: string | null;
  publicWebsiteUrl?: string | null;
}

// ---------------------------------------------------------------------------
// Admin — activate partner request
// ---------------------------------------------------------------------------

export interface AdminActivatePartnerRequest {
  /** Must be true — confirms that coordinates represent the actual business location. */
  actualLocationConfirmed: boolean;
}

// ---------------------------------------------------------------------------
// Admin — pause / end partner requests
// ---------------------------------------------------------------------------

export interface AdminPausePartnerRequest {
  reason?: string | null;
}

export interface AdminEndPartnerRequest {
  reason?: string | null;
}

// ---------------------------------------------------------------------------
// Admin — partner company summary (admin list)
// ---------------------------------------------------------------------------

export interface AdminPartnerCompanySummary {
  partnerId: string;
  companyName: string;
  category: PartnerCategory;
  status: PartnerCompanyStatus;
  address: string;
  latitude: number;
  longitude: number;
  activatedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface PaginatedAdminPartnerCompaniesResponse {
  ok: true;
  data: { partners: AdminPartnerCompanySummary[] };
  meta: { page: number; pageSize: number; total: number; hasNext: boolean };
}

// ---------------------------------------------------------------------------
// Admin — partner company detail
// ---------------------------------------------------------------------------

export interface AdminPartnerCompanyDetail {
  partnerId: string;
  applicationId: string | null;
  companyName: string;
  category: PartnerCategory;
  publicDescription: string;
  address: string;
  latitude: number;
  longitude: number;
  publicPhone: string | null;
  publicWebsiteUrl: string | null;
  status: PartnerCompanyStatus;
  activatedAt: string | null;
  pausedAt: string | null;
  endedAt: string | null;
  createdByUserId: string;
  updatedByUserId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AdminPartnerCompanyDetailResponse {
  ok: true;
  data: AdminPartnerCompanyDetail;
}
