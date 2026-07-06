/**
 * Partners (KCC Företagspartner) feature module for the admin portal
 * (Phase 13 vertical).
 *
 * Reads come straight from Firestore (admin rules-gated: this PR adds the
 * `|| isAdmin()` grants on companies, offers (teaser) and its member-gated
 * details/member subdoc, plus an admin read on partnerApplications).
 * Mutations go through the audited partners.* admin callables. Exported
 * signatures and shared response-envelope types are unchanged.
 *
 * Security notes:
 *  - Backend is the sole authority for approval, publication, and all status
 *    transitions; every mutation is audited server-side.
 *  - Application contact details are internal — never forward to public APIs.
 *  - discountCode is NEVER read here — it lives in offers/{id}/secret/code
 *    (backend-only) and is served exclusively by partners.showOfferCode.
 */

import {
  collection,
  doc,
  getDoc,
  getDocs,
  limit as fsLimit,
  orderBy,
  query,
  type DocumentData,
  type Timestamp,
} from 'firebase/firestore';
import {
  PARTNER_CATEGORIES,
  type AdminPartnerApplicationSummary,
  type AdminPartnerApplicationDetail,
  type AdminPartnerCompanySummary,
  type AdminPartnerCompanyDetail,
  type AdminCreatePartnerRequest,
  type AdminUpdatePartnerRequest,
  type PaginatedAdminPartnerApplicationsResponse,
  type PaginatedAdminPartnerCompaniesResponse,
  type PartnerApplicationStatus,
  type PartnerCompanyStatus,
  type PartnerCategory,
} from '@carcommunity/shared/partners';

import {
  PARTNER_OFFER_STATUSES,
  PARTNER_OFFER_TYPES,
  type AdminPartnerOfferSummary,
  type AdminPartnerOfferDetail,
  type CreatePartnerOfferRequest,
  type UpdatePartnerOfferRequest,
  type PaginatedAdminPartnerOffersResponse,
  type PartnerOfferStatus,
  type PartnerOfferType,
} from '@carcommunity/shared/partner-offers';

import { ApiError } from '../../lib/api';
import { callAdmin } from '../../lib/callables';
import { getAdminFirestore } from '../../lib/firestore';

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

const DEFAULT_PAGE_SIZE = 20;

/** Firestore Timestamp | Date | null → ISO string (or null). */
function toIso(value: unknown): string | null {
  if (value == null) return null;
  if (value instanceof Date) return value.toISOString();
  const ts = value as Timestamp;
  if (typeof ts.toDate === 'function') return ts.toDate().toISOString();
  if (typeof value === 'string') return value;
  return null;
}

function toIsoRequired(value: unknown): string {
  return toIso(value) ?? new Date(0).toISOString();
}

// ---------------------------------------------------------------------------
// Document → contract mappers
// ---------------------------------------------------------------------------

function toApplicationSummary(id: string, data: DocumentData): AdminPartnerApplicationSummary {
  return {
    applicationId: id,
    companyName: (data.companyName as string | undefined) ?? '',
    category: data.category as PartnerCategory,
    contactName: (data.contactName as string | undefined) ?? '',
    contactEmail: (data.contactEmail as string | undefined) ?? '',
    contactPhone: (data.contactPhone as string | null | undefined) ?? null,
    status: data.status as PartnerApplicationStatus,
    submittedAt: toIsoRequired(data.submittedAt),
    reviewedAt: toIso(data.decidedAt),
  };
}

function toApplicationDetail(id: string, data: DocumentData): AdminPartnerApplicationDetail {
  return {
    applicationId: id,
    companyName: (data.companyName as string | undefined) ?? '',
    organizationNumber: (data.organizationNumber as string | null | undefined) ?? null,
    category: data.category as PartnerCategory,
    contactName: (data.contactName as string | undefined) ?? '',
    contactEmail: (data.contactEmail as string | undefined) ?? '',
    contactPhone: (data.contactPhone as string | null | undefined) ?? null,
    websiteUrl: (data.websiteUrl as string | null | undefined) ?? null,
    proposedDescription: (data.proposedDescription as string | null | undefined) ?? null,
    proposedAddress: (data.proposedAddress as string | null | undefined) ?? null,
    message: (data.message as string | null | undefined) ?? null,
    status: data.status as PartnerApplicationStatus,
    submittedByUserId: (data.submittedByUserId as string | null | undefined) ?? null,
    reviewedByUserId: (data.reviewedByUserId as string | null | undefined) ?? null,
    reviewedAt: toIso(data.decidedAt),
    reviewReason: (data.reviewNote as string | null | undefined) ?? null,
    createdAt: toIsoRequired(data.submittedAt),
    updatedAt: toIsoRequired(data.updatedAt),
    partnerCompanyId: (data.partnerCompanyId as string | null | undefined) ?? null,
  };
}

function toCompanySummary(id: string, data: DocumentData): AdminPartnerCompanySummary {
  return {
    partnerId: id,
    companyName: (data.name as string | undefined) ?? '',
    category: data.category as PartnerCategory,
    status: data.status as PartnerCompanyStatus,
    address: (data.address as string | undefined) ?? '',
    latitude: (data.latitude as number | undefined) ?? 0,
    longitude: (data.longitude as number | undefined) ?? 0,
    // activated/paused/ended timestamps were legacy SQL columns with no
    // Firestore field; the doc tracks lifecycle via status + updatedAt.
    activatedAt: null,
    createdAt: toIsoRequired(data.createdAt),
    updatedAt: toIsoRequired(data.updatedAt),
  };
}

function toCompanyDetail(id: string, data: DocumentData): AdminPartnerCompanyDetail {
  return {
    partnerId: id,
    applicationId: (data.sourceApplicationId as string | null | undefined) ?? null,
    companyName: (data.name as string | undefined) ?? '',
    category: data.category as PartnerCategory,
    publicDescription: (data.description as string | undefined) ?? '',
    address: (data.address as string | undefined) ?? '',
    latitude: (data.latitude as number | undefined) ?? 0,
    longitude: (data.longitude as number | undefined) ?? 0,
    publicPhone: (data.phone as string | null | undefined) ?? null,
    publicWebsiteUrl: (data.website as string | null | undefined) ?? null,
    status: data.status as PartnerCompanyStatus,
    activatedAt: null,
    pausedAt: null,
    endedAt: null,
    createdByUserId: (data.createdByUserId as string | undefined) ?? '',
    updatedByUserId: (data.updatedByUserId as string | null | undefined) ?? null,
    createdAt: toIsoRequired(data.createdAt),
    updatedAt: toIsoRequired(data.updatedAt),
  };
}

function toOfferSummary(id: string, data: DocumentData): AdminPartnerOfferSummary {
  return {
    offerId: id,
    partnerId: (data.companyId as string | undefined) ?? '',
    partnerCompanyName: (data.partnerCompanyName as string | undefined) ?? '',
    title: (data.title as string | undefined) ?? '',
    offerType: data.offerType as PartnerOfferType,
    status: data.status as PartnerOfferStatus,
    availableFrom: toIso(data.availableFrom),
    availableUntil: toIso(data.availableUntil),
    activatedAt: null,
    createdAt: toIsoRequired(data.createdAt),
    updatedAt: toIsoRequired(data.updatedAt),
  };
}

function toOfferDetail(
  id: string,
  data: DocumentData,
  member: DocumentData | undefined,
): AdminPartnerOfferDetail {
  const m = member ?? {};
  return {
    offerId: id,
    partnerId: (data.companyId as string | undefined) ?? '',
    partnerCompanyName: (data.partnerCompanyName as string | undefined) ?? '',
    title: (data.title as string | undefined) ?? '',
    teaserText: (data.teaserText as string | undefined) ?? '',
    description: (m.description as string | null | undefined) ?? null,
    offerType: data.offerType as PartnerOfferType,
    status: data.status as PartnerOfferStatus,
    redemptionInstructions: (m.redemptionInstructions as string | null | undefined) ?? null,
    terms: (m.terms as string | null | undefined) ?? null,
    percentageDiscount: (m.percentageDiscount as number | null | undefined) ?? null,
    fixedDiscountMinorUnits: (m.fixedDiscountMinorUnits as number | null | undefined) ?? null,
    currencyCode: (m.currencyCode as string | null | undefined) ?? null,
    availableFrom: toIso(data.availableFrom),
    availableUntil: toIso(data.availableUntil),
    activatedAt: null,
    pausedAt: null,
    endedAt: null,
    createdByUserId: (data.createdByUserId as string | undefined) ?? '',
    updatedByUserId: (data.updatedByUserId as string | null | undefined) ?? null,
    createdAt: toIsoRequired(data.createdAt),
    updatedAt: toIsoRequired(data.updatedAt),
    // discountCode is intentionally omitted — never surfaced in list/detail.
  };
}

// ---------------------------------------------------------------------------
// Applications
// ---------------------------------------------------------------------------

/**
 * Lists all partner applications for the admin view (newest first, first
 * page). Returns application contact details — never forward to public APIs.
 */
export async function adminListPartnerApplications(
  _page = 1,
  _token?: string,
): Promise<PaginatedAdminPartnerApplicationsResponse> {
  const snapshot = await getDocs(
    query(
      collection(getAdminFirestore(), 'partnerApplications'),
      orderBy('submittedAt', 'desc'),
      fsLimit(DEFAULT_PAGE_SIZE),
    ),
  );
  const applications = snapshot.docs.map((d) => toApplicationSummary(d.id, d.data()));
  return {
    ok: true,
    data: { applications },
    meta: {
      page: 1,
      pageSize: DEFAULT_PAGE_SIZE,
      total: applications.length,
      hasNext: applications.length === DEFAULT_PAGE_SIZE,
    },
  };
}

/**
 * Returns full detail for a single partner application including contact
 * fields. Admin use only — never forward contact details to public responses.
 */
export async function adminGetPartnerApplication(
  applicationId: string,
  _token?: string,
): Promise<AdminPartnerApplicationDetail> {
  const snap = await getDoc(doc(getAdminFirestore(), 'partnerApplications', applicationId));
  if (!snap.exists()) {
    throw new ApiError(404, 'not-found', 'Partner application not found.');
  }
  return toApplicationDetail(snap.id, snap.data());
}

/** Starts the review process for a submitted application (audited). */
export async function adminStartApplicationReview(
  applicationId: string,
  _token?: string,
): Promise<void> {
  await callAdmin<unknown>('partners-reviewApplication', {
    applicationId,
    action: 'start_review',
  });
}

/**
 * Approves a partner application. Creates a DRAFT partner company — public
 * activation is a separate step. Audited.
 */
export async function adminApproveApplication(
  applicationId: string,
  _token?: string,
): Promise<{ partnerCompanyId: string }> {
  const result = await callAdmin<{ partnerCompanyId: string | null }>('partners-reviewApplication', {
    applicationId,
    action: 'approve',
  });
  return { partnerCompanyId: result.partnerCompanyId ?? '' };
}

/** Rejects a partner application. A non-empty reason is required. Audited. */
export async function adminRejectApplication(
  applicationId: string,
  reason: string,
  _token?: string,
): Promise<void> {
  await callAdmin<unknown>('partners-reviewApplication', {
    applicationId,
    action: 'reject',
    note: reason,
  });
}

// ---------------------------------------------------------------------------
// Companies
// ---------------------------------------------------------------------------

/** Lists all partner companies (all statuses) for the admin view. */
export async function adminListPartnerCompanies(
  _page = 1,
  _token?: string,
): Promise<PaginatedAdminPartnerCompaniesResponse> {
  const snapshot = await getDocs(
    query(
      collection(getAdminFirestore(), 'companies'),
      orderBy('createdAt', 'desc'),
      fsLimit(DEFAULT_PAGE_SIZE),
    ),
  );
  const partners = snapshot.docs.map((d) => toCompanySummary(d.id, d.data()));
  return {
    ok: true,
    data: { partners },
    meta: {
      page: 1,
      pageSize: DEFAULT_PAGE_SIZE,
      total: partners.length,
      hasNext: partners.length === DEFAULT_PAGE_SIZE,
    },
  };
}

/** Returns full admin detail for a single partner company. */
export async function adminGetPartnerCompany(
  partnerId: string,
  _token?: string,
): Promise<AdminPartnerCompanyDetail> {
  const snap = await getDoc(doc(getAdminFirestore(), 'companies', partnerId));
  if (!snap.exists()) {
    throw new ApiError(404, 'not-found', 'Partner company not found.');
  }
  return toCompanyDetail(snap.id, snap.data());
}

/** Maps the admin request contract onto the partners.createCompany input. */
function toCreateCompanyPayload(request: AdminCreatePartnerRequest): Record<string, unknown> {
  return {
    name: request.companyName,
    category: request.category,
    description: request.publicDescription,
    address: request.address,
    latitude: request.latitude,
    longitude: request.longitude,
    phone: request.publicPhone ?? null,
    website: request.publicWebsiteUrl ?? null,
  };
}

/**
 * Creates a new partner company in draft status via partners.createCompany.
 * (applicationId links are set server-side by the approve flow; the strict
 * callable schema does not accept it here.)
 */
export async function adminCreatePartnerCompany(
  request: AdminCreatePartnerRequest,
  _token?: string,
): Promise<AdminPartnerCompanyDetail> {
  const { companyId } = await callAdmin<{ companyId: string }>(
    'partners-createCompany',
    toCreateCompanyPayload(request),
  );
  return adminGetPartnerCompany(companyId);
}

/**
 * Updates an existing draft or paused partner company via
 * partners.updateCompany. Status changes use the activate/pause/end actions.
 */
export async function adminUpdatePartnerCompany(
  partnerId: string,
  request: AdminUpdatePartnerRequest,
  _token?: string,
): Promise<AdminPartnerCompanyDetail> {
  const payload: Record<string, unknown> = { companyId: partnerId };
  if (request.companyName !== undefined) payload.name = request.companyName;
  if (request.category !== undefined) payload.category = request.category;
  if (request.publicDescription !== undefined) payload.description = request.publicDescription;
  if (request.address !== undefined) payload.address = request.address;
  if (request.latitude !== undefined) payload.latitude = request.latitude;
  if (request.longitude !== undefined) payload.longitude = request.longitude;
  if (request.publicPhone !== undefined) payload.phone = request.publicPhone;
  if (request.publicWebsiteUrl !== undefined) payload.website = request.publicWebsiteUrl;

  await callAdmin<{ companyId: string }>('partners-updateCompany', payload);
  return adminGetPartnerCompany(partnerId);
}

/** Activates a partner company (public), via partners.setCompanyStatus. */
export async function adminActivatePartner(
  partnerId: string,
  _token?: string,
): Promise<AdminPartnerCompanyDetail> {
  await callAdmin<{ companyId: string }>('partners-setCompanyStatus', {
    companyId: partnerId,
    action: 'activate',
  });
  return adminGetPartnerCompany(partnerId);
}

/** Pauses an active partner company, via partners.setCompanyStatus. */
export async function adminPausePartner(
  partnerId: string,
  reason?: string,
  _token?: string,
): Promise<AdminPartnerCompanyDetail> {
  await callAdmin<{ companyId: string }>('partners-setCompanyStatus', {
    companyId: partnerId,
    action: 'pause',
    ...(reason ? { reason } : {}),
  });
  return adminGetPartnerCompany(partnerId);
}

/** Ends a partnership permanently, via partners.setCompanyStatus. */
export async function adminEndPartnership(
  partnerId: string,
  reason?: string,
  _token?: string,
): Promise<AdminPartnerCompanyDetail> {
  await callAdmin<{ companyId: string }>('partners-setCompanyStatus', {
    companyId: partnerId,
    action: 'end',
    ...(reason ? { reason } : {}),
  });
  return adminGetPartnerCompany(partnerId);
}

// ---------------------------------------------------------------------------
// Partner Offers
// ---------------------------------------------------------------------------

/**
 * Lists partner offers (all statuses) for the admin view. discountCode is
 * NEVER included. Optional partnerId/status filters are applied over the
 * fetched page to avoid composite-index requirements.
 */
export async function adminListPartnerOffers(
  options: { page?: number; partnerId?: string; status?: PartnerOfferStatus } = {},
  _token?: string,
): Promise<PaginatedAdminPartnerOffersResponse> {
  const snapshot = await getDocs(
    query(
      collection(getAdminFirestore(), 'offers'),
      orderBy('createdAt', 'desc'),
      fsLimit(DEFAULT_PAGE_SIZE),
    ),
  );
  let offers = snapshot.docs.map((d) => toOfferSummary(d.id, d.data()));
  if (options.partnerId) offers = offers.filter((o) => o.partnerId === options.partnerId);
  if (options.status) offers = offers.filter((o) => o.status === options.status);

  return {
    ok: true,
    data: { offers },
    meta: {
      page: 1,
      pageSize: DEFAULT_PAGE_SIZE,
      total: offers.length,
      hasNext: offers.length === DEFAULT_PAGE_SIZE,
    },
  };
}

/**
 * Returns full admin detail for a single partner offer — the teaser document
 * plus its admin-readable details/member subdocument (description, terms,
 * discount fields). discountCode (secret/code) is never read.
 */
export async function adminGetPartnerOffer(
  offerId: string,
  _token?: string,
): Promise<AdminPartnerOfferDetail> {
  const db = getAdminFirestore();
  const [offerSnap, memberSnap] = await Promise.all([
    getDoc(doc(db, 'offers', offerId)),
    getDoc(doc(db, 'offers', offerId, 'details', 'member')),
  ]);
  if (!offerSnap.exists()) {
    throw new ApiError(404, 'not-found', 'Partner offer not found.');
  }
  return toOfferDetail(offerSnap.id, offerSnap.data(), memberSnap.data());
}

/** Creates a new draft partner offer for the given partner. Audited. */
export async function adminCreatePartnerOffer(
  partnerId: string,
  request: CreatePartnerOfferRequest,
  _token?: string,
): Promise<AdminPartnerOfferDetail> {
  const { offerId } = await callAdmin<{ offerId: string }>('partners-createOffer', {
    companyId: partnerId,
    ...request,
  });
  return adminGetPartnerOffer(offerId);
}

/** Updates an existing draft or paused partner offer. Audited. */
export async function adminUpdatePartnerOffer(
  offerId: string,
  request: UpdatePartnerOfferRequest,
  _token?: string,
): Promise<AdminPartnerOfferDetail> {
  await callAdmin<{ offerId: string }>('partners-updateOffer', { offerId, ...request });
  return adminGetPartnerOffer(offerId);
}

/** Activates a partner offer, via partners.setOfferStatus. */
export async function adminActivatePartnerOffer(
  offerId: string,
  _token?: string,
): Promise<AdminPartnerOfferDetail> {
  await callAdmin<{ offerId: string }>('partners-setOfferStatus', {
    offerId,
    action: 'activate',
  });
  return adminGetPartnerOffer(offerId);
}

/** Pauses an active partner offer. A reason is required. Audited. */
export async function adminPausePartnerOffer(
  offerId: string,
  reason: string,
  _token?: string,
): Promise<AdminPartnerOfferDetail> {
  await callAdmin<{ offerId: string }>('partners-setOfferStatus', {
    offerId,
    action: 'pause',
    reason,
  });
  return adminGetPartnerOffer(offerId);
}

/** Ends a partner offer permanently. A reason is required. Audited. */
export async function adminEndPartnerOffer(
  offerId: string,
  reason: string,
  _token?: string,
): Promise<AdminPartnerOfferDetail> {
  await callAdmin<{ offerId: string }>('partners-setOfferStatus', {
    offerId,
    action: 'end',
    reason,
  });
  return adminGetPartnerOffer(offerId);
}
