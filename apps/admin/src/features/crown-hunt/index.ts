/**
 * Crown Hunt (Kronjakt) feature module for the admin portal (Phase 13 vertical).
 *
 * Reads come straight from Firestore (admin rules-gated: this PR adds the
 * `|| isAdmin()` grant on crownHuntPoints and crownHuntClaims so admins see
 * all statuses / every claim, not just active/own records). Mutations go
 * through the safety-gated crownHunt.* admin callables. Exported signatures
 * and shared response-envelope types are unchanged.
 *
 * Security notes:
 *  - Backend is the sole authority for eligibility, claims, and Kronpoäng awards.
 *  - New points start as draft — activation requires explicit admin confirmation.
 *  - No exact user claim coordinates are exposed (only coarse distanceMeters).
 *  - Anti-fraud risk scores/reasons live in the backend-only crownHuntClaimRisk
 *    collection and are NEVER read by the client (see riskReasonCategories note
 *    below).
 *  - Do not hard-delete active or previously claimed points; prefer pause/end.
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
  type AdminCreateCrownHuntPointRequest,
  type AdminUpdateCrownHuntPointRequest,
  type AdminCrownHuntPointResponse,
  type AdminCrownHuntPointSummary,
  type AdminCrownHuntClaimSummary,
  type CrownHuntClaimResult,
  type CrownHuntPointStatus,
  type CrownHuntRepeatRule,
  type PaginatedAdminCrownHuntPointsResponse,
  type PaginatedAdminCrownHuntClaimsResponse,
} from '@carcommunity/shared/crown-hunt';

import { ApiError } from '../../lib/api';
import { callAdmin } from '../../lib/callables';
import { getAdminFirestore } from '../../lib/firestore';

export type {
  AdminCreateCrownHuntPointRequest,
  AdminUpdateCrownHuntPointRequest,
  AdminCrownHuntPointSummary,
  AdminCrownHuntClaimSummary,
  CrownHuntClaimResult,
  CrownHuntPointStatus,
  PaginatedAdminCrownHuntPointsResponse,
  PaginatedAdminCrownHuntClaimsResponse,
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

/** Maps a crownHuntPoints/{id} document to the admin point summary. */
function toAdminPointSummary(id: string, data: DocumentData): AdminCrownHuntPointSummary {
  return {
    pointId: id,
    title: (data.title as string | undefined) ?? '',
    description: (data.description as string | null | undefined) ?? null,
    latitude: (data.latitude as number | undefined) ?? 0,
    longitude: (data.longitude as number | undefined) ?? 0,
    geofenceRadiusMeters: (data.geofenceRadiusMeters as number | undefined) ?? 0,
    rewardPoints: (data.rewardPoints as number | undefined) ?? 0,
    status: data.status as CrownHuntPointStatus,
    repeatRule: data.repeatRule as CrownHuntRepeatRule,
    availableFrom: toIso(data.availableFrom),
    availableUntil: toIso(data.availableUntil),
    approvedAt: toIso(data.approvedAt),
    approvedByUserId: (data.approvedByUserId as string | null | undefined) ?? null,
    createdByUserId: (data.createdByUserId as string | undefined) ?? '',
    createdAt: toIsoRequired(data.createdAt),
    updatedAt: toIsoRequired(data.updatedAt),
    // The awarded-claim rollup is a backend aggregate with no stored field on
    // the point document and is not surfaced in the admin list; report 0
    // rather than issuing a per-point count query.
    totalClaims: 0,
  };
}

/** Resolves a point title from crownHuntPoints/{id}.title, cached per call. */
async function resolvePointTitle(
  db: Firestore,
  pointId: string,
  cache: Map<string, string>,
): Promise<string> {
  const cached = cache.get(pointId);
  if (cached !== undefined) return cached;
  let title = pointId;
  try {
    const snap = await getDoc(doc(db, 'crownHuntPoints', pointId));
    const stored = snap.data()?.title as string | undefined;
    if (stored) title = stored;
  } catch {
    // Point unreadable/missing — fall back to the id.
  }
  cache.set(pointId, title);
  return title;
}

/** Maps a crownHuntClaims/{id} document to the admin claim summary. */
function toAdminClaimSummary(
  id: string,
  data: DocumentData,
  pointTitle: string,
): AdminCrownHuntClaimSummary {
  return {
    claimId: id,
    pointId: (data.pointId as string | undefined) ?? '',
    pointTitle,
    userId: (data.userId as string | undefined) ?? '',
    result: data.result as CrownHuntClaimResult,
    distanceMeters: (data.distanceMeters as number | null | undefined) ?? null,
    // Anti-fraud risk reasons live in the backend-only crownHuntClaimRisk
    // collection, which is intentionally NOT admin-readable — so no reason
    // categories reach the client. The `result` field (incl. 'risk_review')
    // still lets admins triage; see the PR note.
    riskReasonCategories: [],
    claimedAt: toIsoRequired(data.claimedAt),
  };
}

// ---------------------------------------------------------------------------
// Reads (direct Firestore, admin rules-gated)
// ---------------------------------------------------------------------------

/**
 * Lists all Kronjakt points (all statuses) for the admin view, newest
 * first (first page — deeper pagination deferred, per the 13a precedent).
 */
export async function adminListCrownHuntPoints(
  _page = 1,
  _token?: string,
): Promise<PaginatedAdminCrownHuntPointsResponse> {
  const snapshot = await getDocs(
    query(
      collection(getAdminFirestore(), 'crownHuntPoints'),
      orderBy('createdAt', 'desc'),
      fsLimit(DEFAULT_PAGE_SIZE),
    ),
  );
  const points = snapshot.docs.map((d) => toAdminPointSummary(d.id, d.data()));
  return {
    ok: true,
    data: { points },
    meta: {
      page: 1,
      pageSize: DEFAULT_PAGE_SIZE,
      total: points.length,
      hasNext: points.length === DEFAULT_PAGE_SIZE,
    },
  };
}

/**
 * Lists Kronjakt claims for admin review, newest first. The optional result
 * filter (e.g. risk_review) is applied over the fetched page to avoid a
 * composite-index requirement. No exact user coordinates are included.
 */
export async function adminListCrownHuntClaims(
  _page = 1,
  filterResult?: CrownHuntClaimResult,
  _token?: string,
): Promise<PaginatedAdminCrownHuntClaimsResponse> {
  const db = getAdminFirestore();
  const snapshot = await getDocs(
    query(
      collection(db, 'crownHuntClaims'),
      orderBy('claimedAt', 'desc'),
      fsLimit(DEFAULT_PAGE_SIZE),
    ),
  );

  // Filter the fetched page BEFORE resolving point titles, so we don't issue
  // title reads for claims that are discarded. hasNext is based on the
  // unfiltered fetch size — otherwise a result filter could wrongly report
  // no further pages (e.g. 20 fetched, 2 match).
  const pageDocs = filterResult
    ? snapshot.docs.filter((d) => (d.data().result as CrownHuntClaimResult) === filterResult)
    : snapshot.docs;

  const cache = new Map<string, string>();
  const claims = await Promise.all(
    pageDocs.map(async (d) => {
      const data = d.data();
      const title = await resolvePointTitle(db, (data.pointId as string | undefined) ?? '', cache);
      return toAdminClaimSummary(d.id, data, title);
    }),
  );

  return {
    ok: true,
    data: { claims },
    meta: {
      page: 1,
      pageSize: DEFAULT_PAGE_SIZE,
      total: claims.length,
      hasNext: snapshot.docs.length === DEFAULT_PAGE_SIZE,
    },
  };
}

// ---------------------------------------------------------------------------
// Mutations (safety-gated crownHunt.* callables) — re-read the fresh point
// ---------------------------------------------------------------------------

interface PointIdResponse {
  pointId: string;
  status: CrownHuntPointStatus;
}

async function getPointResponse(pointId: string): Promise<AdminCrownHuntPointResponse> {
  const snap = await getDoc(doc(getAdminFirestore(), 'crownHuntPoints', pointId));
  if (!snap.exists()) {
    throw new ApiError(404, 'not-found', 'Crown hunt point not found.');
  }
  return { ok: true, data: toAdminPointSummary(snap.id, snap.data()) };
}

/**
 * Creates a new Kronjakt point in draft status via crownHunt.createPoint.
 */
export async function adminCreateCrownHuntPoint(
  request: AdminCreateCrownHuntPointRequest,
  _token?: string,
): Promise<AdminCrownHuntPointResponse> {
  const { pointId } = await callAdmin<PointIdResponse>('crownHunt-createPoint', request);
  return getPointResponse(pointId);
}

/**
 * Updates an existing draft or paused Kronjakt point via crownHunt.updatePoint.
 */
export async function adminUpdateCrownHuntPoint(
  pointId: string,
  request: AdminUpdateCrownHuntPointRequest,
  _token?: string,
): Promise<AdminCrownHuntPointResponse> {
  await callAdmin<PointIdResponse>('crownHunt-updatePoint', { pointId, ...request });
  return getPointResponse(pointId);
}

/**
 * Activates a draft Kronjakt point via crownHunt.activatePoint. Requires a
 * safety confirmation note; the backend enforces approval and audits it.
 */
export async function adminActivateCrownHuntPoint(
  pointId: string,
  approvalNote: string,
  _token?: string,
): Promise<AdminCrownHuntPointResponse> {
  await callAdmin<PointIdResponse>('crownHunt-activatePoint', {
    pointId,
    safeLocationConfirmed: true,
    approvalNote,
  });
  return getPointResponse(pointId);
}

/**
 * Pauses an active Kronjakt point via crownHunt.pausePoint.
 */
export async function adminPauseCrownHuntPoint(
  pointId: string,
  _token?: string,
): Promise<AdminCrownHuntPointResponse> {
  await callAdmin<PointIdResponse>('crownHunt-pausePoint', { pointId });
  return getPointResponse(pointId);
}
