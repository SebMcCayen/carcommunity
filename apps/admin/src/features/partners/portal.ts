/** Admin portal reads are rules-gated; every mutation is server-authorized and audited. */
import {
  collection,
  doc,
  documentId,
  getDoc,
  getDocs,
  limit,
  orderBy,
  query,
  startAfter,
  where,
  type DocumentData,
  type QueryConstraint,
  type QueryDocumentSnapshot,
} from 'firebase/firestore';
import { getBlob, getStorage, ref } from 'firebase/storage';
import { getAdminFirestore } from '@/lib/firestore';
import { getFirebaseApp } from '@/lib/firebase';
import { callAdmin } from '@/lib/callables';
import { ApiError } from '@/lib/errors';
import { translate } from '@/i18n';
import {
  toApplicationDetail,
  toApplicationSummary,
  toCompanyDetail,
  toCompanySummary,
  toOfferDetail,
  toOfferSummary,
  type AdminPartnerApplicationDetail,
  type AdminPartnerCompanyDetail,
  type AdminPartnerOfferDetail,
  type AdminPartnerApplicationSummary,
  type AdminPartnerCompanySummary,
  type AdminPartnerOfferSummary,
} from './index';

export type PortalCursor = QueryDocumentSnapshot<DocumentData>;
export interface PortalPage<T> {
  items: T[];
  nextCursor: PortalCursor | null;
}
export interface PortalApplication extends Omit<AdminPartnerApplicationDetail, 'status'> {
  status: string;
  revision: number;
  selfServiceRequested: boolean;
  billboardRequested: boolean;
  billboardPlacementRequest: string | null;
  logoPath: string | null;
  logoPreviewBase64: string | null;
  publicPhone: string | null;
}
export interface PortalCompany extends AdminPartnerCompanyDetail {
  logoPath: string | null;
}
export interface PortalOffer extends AdminPartnerOfferDetail {
  issuedCount: number;
  redemptionMode: string | null;
  capacity: number | null;
  reservationDays: number;
  reservedCount: number;
  redeemedCount: number;
  version: number;
}
export interface PortalMember {
  uid: string;
  role: 'manager' | 'redeemer';
  enabled: boolean;
}
export interface PortalStatistics {
  updatedAt: string;
  period: string;
  totalRedeemed: number;
  partners: {
    companyId: string;
    companyName: string;
    totalRedeemed: number;
    offers: { offerId: string; title: string; totalRedeemed: number }[];
  }[];
}

async function page<T>(
  name: string,
  date: string,
  mapper: (id: string, data: DocumentData) => T,
  cursor?: PortalCursor | null,
  filters: QueryConstraint[] = [],
): Promise<PortalPage<T>> {
  const constraints: QueryConstraint[] = [
    ...filters,
    orderBy(date, 'desc'),
    orderBy(documentId(), 'desc'),
  ];
  if (cursor) constraints.push(startAfter(cursor));
  const snap = await getDocs(
    query(collection(getAdminFirestore(), name), ...constraints, limit(21)),
  );
  const visible = snap.docs.slice(0, 20);
  return {
    items: visible.map((d) => mapper(d.id, d.data())),
    nextCursor: snap.docs.length > 20 ? visible.at(-1)! : null,
  };
}
export function listApplications(
  cursor?: PortalCursor | null,
  status?: string,
): Promise<PortalPage<AdminPartnerApplicationSummary>> {
  return page(
    'partnerApplications',
    'submittedAt',
    toApplicationSummary,
    cursor,
    status ? [where('status', '==', status)] : [],
  );
}
export function listCompanies(
  cursor?: PortalCursor | null,
  status?: string,
): Promise<PortalPage<AdminPartnerCompanySummary>> {
  return page(
    'companies',
    'createdAt',
    toCompanySummary,
    cursor,
    status ? [where('status', '==', status)] : [],
  );
}
export function listOffers(
  cursor?: PortalCursor | null,
  companyId?: string,
  status?: string,
): Promise<PortalPage<AdminPartnerOfferSummary>> {
  return page('offers', 'createdAt', toOfferSummary, cursor, [
    ...(companyId ? [where('companyId', '==', companyId)] : []),
    ...(status ? [where('status', '==', status)] : []),
  ]);
}
export async function getApplication(applicationId: string): Promise<PortalApplication> {
  const snapshot = await getDoc(doc(getAdminFirestore(), 'partnerApplications', applicationId));
  if (!snapshot.exists()) throw new ApiError(404, 'not-found', 'Partner application not found.');
  const data = snapshot.data();
  return {
    ...toApplicationDetail(snapshot.id, data),
    status: data.status as string,
    revision: Number(data.revision ?? 0),
    reviewReason:
      (data.reviewReason as string | undefined) ?? (data.reviewNote as string | undefined) ?? null,
    selfServiceRequested: data.selfServiceRequested === true,
    billboardRequested: data.billboardRequested === true,
    billboardPlacementRequest: (data.billboardPlacementRequest as string | undefined) ?? null,
    logoPath: (data.logoPath as string | undefined) ?? null,
    logoPreviewBase64: (data.logoPreviewBase64 as string | undefined) ?? null,
    publicPhone: (data.publicPhone as string | undefined) ?? null,
  };
}
export async function getCompany(companyId: string): Promise<PortalCompany> {
  const snapshot = await getDoc(doc(getAdminFirestore(), 'companies', companyId));
  if (!snapshot.exists()) throw new ApiError(404, 'not-found', 'Partner company not found.');
  return {
    ...toCompanyDetail(snapshot.id, snapshot.data()),
    logoPath: (snapshot.data().logoPath as string | undefined) ?? null,
  };
}
export async function getOffer(offerId: string): Promise<PortalOffer> {
  const db = getAdminFirestore();
  const [snapshot, member] = await Promise.all([
    getDoc(doc(db, 'offers', offerId)),
    getDoc(doc(db, 'offers', offerId, 'details', 'member')),
  ]);
  if (!snapshot.exists()) throw new ApiError(404, 'not-found', 'Partner offer not found.');
  const data = snapshot.data();
  return {
    ...toOfferDetail(snapshot.id, data, member.data()),
    issuedCount: (data.issuedCount as number | undefined) ?? 0,
    redemptionMode: (data.redemptionMode as string | undefined) ?? null,
    capacity: (data.capacity as number | undefined) ?? null,
    reservationDays: (data.reservationDays as number | undefined) ?? 7,
    reservedCount: (data.reservedCount as number | undefined) ?? 0,
    redeemedCount: (data.redeemedCount as number | undefined) ?? 0,
    version: (data.version as number | undefined) ?? 1,
  };
}
export function reviewApplication(
  applicationId: string,
  decision: 'publish' | 'request_changes',
  options: {
    reason?: string;
    expectedRevision?: number;
    grantSelfService?: boolean;
    latitude?: number;
    longitude?: number;
  } = {},
) {
  return callAdmin<{ applicationId: string; companyId?: string; status: string }>(
    'partners-portal',
    { action: 'reviewApplication', applicationId, decision, ...options },
  );
}
export async function getMembers(companyId: string): Promise<PortalMember[]> {
  const result = await callAdmin<{ members: PortalMember[] }>('partners-portal', {
    action: 'members',
    companyId,
  });
  return result.members;
}
export function setMembership(
  companyId: string,
  uid: string,
  role: PortalMember['role'],
  enabled: boolean,
) {
  return callAdmin('partners-portal', { action: 'membership', companyId, uid, role, enabled });
}
export function updateProfile(
  companyId: string,
  description: string,
  phone: string | null,
  website: string | null,
) {
  return callAdmin('partners-portal', {
    action: 'updateProfile',
    companyId,
    description,
    phone,
    website,
  });
}
export function uploadLogo(companyId: string, logoBase64: string) {
  return callAdmin<{ logoPath: string }>('partners-portal', {
    action: 'uploadLogo',
    companyId,
    logoBase64,
    logoRightsConfirmed: true,
  });
}
export function getStatistics(companyId?: string, period = 'all') {
  return callAdmin<PortalStatistics>('partners-portal', {
    action: 'adminStatistics',
    ...(companyId ? { companyId } : {}),
    period,
  });
}
export async function readLogo(path: string): Promise<Blob> {
  // Download via the authenticated SDK, never construct public download-token URLs.
  return getBlob(ref(getStorage(getFirebaseApp()), path));
}
export async function logoFileBase64(file: File): Promise<string> {
  if (file.type !== 'image/png' || file.size > 5 * 1024 * 1024)
    throw new Error(translate('sv', 'partnerPortal.fileGuidance'));
  const bytes = new Uint8Array(await file.arrayBuffer());
  let binary = '';
  for (let i = 0; i < bytes.length; i += 32768)
    binary += String.fromCharCode(...bytes.subarray(i, i + 32768));
  return btoa(binary);
}
/** Only closed UTC months: no live monthly drill-down. */
export function statisticsPeriods(now = new Date()): string[] {
  return [
    'all',
    ...Array.from({ length: 12 }, (_, i) =>
      new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1 - i, 1))
        .toISOString()
        .slice(0, 7),
    ),
  ];
}

export interface ManagedOfferInput {
  companyId: string;
  offerId?: string;
  title: string;
  teaserText: string;
  description: string;
  terms: string;
  percentageDiscount?: number | null;
  availableFrom?: string | null;
  availableUntil?: string | null;
  capacity?: number | null;
  reservationDays?: number;
}
export function submitManagedOffer(input: ManagedOfferInput) {
  return callAdmin<{ offerId: string; status: 'draft' }>('partners-portal', {
    action: 'submitOffer',
    ...input,
  });
}
