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

const PARTNER_APPLICATION_STATUSES = [
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

const PARTNER_COMPANY_STATUSES = ['draft', 'active', 'paused', 'ended'] as const;
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
