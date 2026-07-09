/**
 * Digital Billboards feature module for the admin portal (Phase 13 vertical).
 *
 * Reads come straight from Firestore (admin rules-gated: this PR adds the
 * `|| isAdmin()` grant on the billboards collection so drafts/paused/ended
 * billboards are visible to admins, not only active ones). Mutations go
 * through the audited billboards.* callables (create/update/activate/
 * setStatus). Exported signatures and shared response-envelope types are
 * unchanged, so the billboards page compiles and runs without edits.
 *
 * Security notes:
 *  - Backend enforces all validation, auth, and audit logging.
 *  - New billboards start as draft — activation requires 6 explicit safety confirmations.
 *  - Do not hard-delete active or previously-active billboards.
 *  - Billboards must always be clearly labelled as advertising.
 *  - Content is never rendered as HTML; plain text only.
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
  type Firestore,
  type Timestamp,
} from 'firebase/firestore';
import {
  type AdminActivateBillboardRequest,
  type AdminActivateBillboardResponse,
  type AdminBillboardDetailResponse,
  type AdminBillboardSummary,
  type AdminCreateBillboardRequest,
  type AdminEndBillboardResponse,
  type AdminPauseBillboardResponse,
  type AdminUpdateBillboardRequest,
  type BillboardCtaType,
  type BillboardPlacementType,
  type BillboardStatus,
  type PaginatedAdminBillboardsResponse,
} from '@carcommunity/shared/digital-billboards';

import { ApiError } from '../../lib/errors';
import { callAdmin } from '../../lib/callables';
import { getAdminFirestore } from '../../lib/firestore';

export type {
  AdminActivateBillboardRequest,
  AdminBillboardDetailResponse,
  AdminBillboardSummary,
  AdminCreateBillboardRequest,
  AdminUpdateBillboardRequest,
  BillboardCtaType,
  BillboardPlacementType,
  BillboardStatus,
  PaginatedAdminBillboardsResponse,
};
export { ApiError };

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

/**
 * Resolves a sponsoring partner's display name from companies/{id}.name.
 * Best-effort and cached per call: the companies admin-read grant lands
 * with the partners vertical (Phase 13f), and only ACTIVE companies are
 * readable before then, so a non-active partner falls back to its id rather
 * than failing the whole list.
 */
async function resolveCompanyName(
  db: Firestore,
  companyId: string,
  cache: Map<string, string>,
): Promise<string> {
  const cached = cache.get(companyId);
  if (cached !== undefined) return cached;
  let name = companyId;
  try {
    const snap = await getDoc(doc(db, 'companies', companyId));
    const stored = snap.data()?.name as string | undefined;
    if (stored) name = stored;
  } catch {
    // Company not admin-readable yet (pre-13f) or missing — fall back to id.
  }
  cache.set(companyId, name);
  return name;
}

/** Maps a billboards/{id} document to the admin summary contract. */
function toAdminBillboardSummary(
  id: string,
  data: DocumentData,
  partnerCompanyName: string,
): AdminBillboardSummary {
  return {
    billboardId: id,
    partnerId: (data.partnerCompanyId as string | undefined) ?? '',
    partnerCompanyName,
    headline: (data.headline as string | undefined) ?? '',
    message: (data.message as string | undefined) ?? '',
    placementType: data.placementType as BillboardPlacementType,
    latitude: (data.latitude as number | undefined) ?? 0,
    longitude: (data.longitude as number | undefined) ?? 0,
    status: data.status as BillboardStatus,
    availableFrom: toIso(data.availableFrom),
    availableUntil: toIso(data.availableUntil),
    callToActionType: (data.callToActionType as BillboardCtaType | null | undefined) ?? null,
    callToActionValue: (data.callToActionValue as string | null | undefined) ?? null,
    approvedAt: toIso(data.approvedAt),
    approvedByUserId: (data.approvedByUserId as string | null | undefined) ?? null,
    // The Firestore data model tracks lifecycle via status + approvedAt/
    // updatedAt; the granular activated/paused/ended timestamps and
    // updatedByUserId were legacy SQL columns with no Firestore field.
    activatedAt: null,
    pausedAt: null,
    endedAt: null,
    safetyNote: (data.safetyNote as string | null | undefined) ?? null,
    createdByUserId: (data.createdByUserId as string | undefined) ?? '',
    updatedByUserId: null,
    createdAt: toIsoRequired(data.createdAt),
    updatedAt: toIsoRequired(data.updatedAt),
  };
}

// ---------------------------------------------------------------------------
// Reads (direct Firestore, admin rules-gated)
// ---------------------------------------------------------------------------

export async function adminListBillboards(
  page = 1,
  _token?: string,
): Promise<PaginatedAdminBillboardsResponse> {
  const db = getAdminFirestore();
  const snapshot = await getDocs(
    query(collection(db, 'billboards'), orderBy('createdAt', 'desc'), fsLimit(DEFAULT_PAGE_SIZE)),
  );
  const cache = new Map<string, string>();
  const billboards = await Promise.all(
    snapshot.docs.map(async (d) => {
      const data = d.data();
      const name = await resolveCompanyName(db, (data.partnerCompanyId as string | undefined) ?? '', cache);
      return toAdminBillboardSummary(d.id, data, name);
    }),
  );

  return {
    ok: true,
    data: { billboards },
    meta: {
      page: 1,
      pageSize: DEFAULT_PAGE_SIZE,
      total: billboards.length,
      hasNext: billboards.length === DEFAULT_PAGE_SIZE,
    },
  };
}

export async function adminGetBillboard(
  billboardId: string,
  _token?: string,
): Promise<AdminBillboardDetailResponse> {
  const db = getAdminFirestore();
  const snap = await getDoc(doc(db, 'billboards', billboardId));
  if (!snap.exists()) {
    throw new ApiError(404, 'not-found', 'Billboard not found.');
  }
  const data = snap.data();
  const name = await resolveCompanyName(
    db,
    (data.partnerCompanyId as string | undefined) ?? '',
    new Map(),
  );
  return { ok: true, data: toAdminBillboardSummary(snap.id, data, name) };
}

// ---------------------------------------------------------------------------
// Mutations (audited billboards.* callables) — re-read to return the summary
// ---------------------------------------------------------------------------

interface BillboardIdResponse {
  billboardId: string;
  status: BillboardStatus;
}

export async function adminCreateBillboard(
  request: AdminCreateBillboardRequest,
  _token?: string,
): Promise<AdminBillboardDetailResponse> {
  const { billboardId } = await callAdmin<BillboardIdResponse>('billboards-create', request);
  return adminGetBillboard(billboardId);
}

export async function adminUpdateBillboard(
  billboardId: string,
  request: AdminUpdateBillboardRequest,
  _token?: string,
): Promise<AdminBillboardDetailResponse> {
  await callAdmin<BillboardIdResponse>('billboards-update', { billboardId, ...request });
  return adminGetBillboard(billboardId);
}

export async function adminActivateBillboard(
  billboardId: string,
  request: AdminActivateBillboardRequest,
  _token?: string,
): Promise<AdminActivateBillboardResponse> {
  await callAdmin<BillboardIdResponse>('billboards-activate', { billboardId, ...request });
  const { data } = await adminGetBillboard(billboardId);
  return { ok: true, data };
}

export async function adminPauseBillboard(
  billboardId: string,
  reason: string,
  _token?: string,
): Promise<AdminPauseBillboardResponse> {
  await callAdmin<BillboardIdResponse>('billboards-setStatus', {
    billboardId,
    action: 'pause',
    reason,
  });
  const { data } = await adminGetBillboard(billboardId);
  return { ok: true, data };
}

export async function adminEndBillboard(
  billboardId: string,
  reason: string,
  _token?: string,
): Promise<AdminEndBillboardResponse> {
  await callAdmin<BillboardIdResponse>('billboards-setStatus', {
    billboardId,
    action: 'end',
    reason,
  });
  const { data } = await adminGetBillboard(billboardId);
  return { ok: true, data };
}
