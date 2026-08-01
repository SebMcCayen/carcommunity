/**
 * Kronjakt AUTO-SPAWN cell approval — admin data layer + pure grid helpers.
 *
 * This is the admin half of the auto-spawn safety model. The scheduled spawner
 * (`functions/src/crownHunt/spawnScheduled.ts`) only ever places crowns in
 * `crownSpawnCells` documents where `approved == true`; the ONLY way to set
 * that flag is the audited, admin-gated `crownHunt.setSpawnCellApproval`
 * callable. Approving a cell here is what lets crowns spawn there for real
 * members to drive to — but it spawns NOTHING until the separate
 * `crownHuntSpawn` feature flag is turned on (that stays a deliberate,
 * out-of-band step and is not touched by this screen).
 *
 * How a cell comes to exist: there is no auto-discovery. A `crownSpawnCells`
 * document is CREATED by the approval callable itself (create-or-update). The
 * activity heat that sizes spawn density lives in a separate, fully
 * backend-only collection (`crownCellActivity`, denied to every client), so the
 * admin cannot browse "where activity is" — an operator instead SPECIFIES an
 * area by picking a point (or entering a grid key) and approving it.
 *
 * Reads: `crownSpawnCells` is admin-readable in firestore.rules
 * (`allow read: if isAdmin();`), so the list is a direct rules-gated SDK read,
 * matching the crownHuntPoints/crownHuntClaims precedent in this feature.
 *
 * The grid geometry below MIRRORS `functions/src/crownHunt/crown-spawn-core.ts`
 * (0.01° ≈ 1.1 km cells, `${latIdx}_${lonIdx}` keys) rather than importing it —
 * the functions core is not published to the admin bundle — and is unit-tested
 * so the cell key / centre the operator sees matches exactly what the backend
 * resolves and what the spawner will place in.
 */

import {
  collection,
  getDocs,
  limit as fsLimit,
  orderBy,
  query,
  type DocumentData,
  type Timestamp,
} from 'firebase/firestore';

import { callAdmin } from '../../lib/callables';
import { getAdminFirestore } from '../../lib/firestore';

// ---------------------------------------------------------------------------
// Pure grid geometry (mirror of crown-spawn-core.ts)
// ---------------------------------------------------------------------------

/** Spawn grid cell size in degrees (~1.1 km). Mirrors CROWN_CELL_DEGREES. */
export const CROWN_CELL_DEGREES = 0.01;

const clampLat = (lat: number) => Math.min(90, Math.max(-90, lat));
const clampLon = (lon: number) => Math.min(180, Math.max(-180, lon));

const MAX_LAT_IDX = Math.round(90 / CROWN_CELL_DEGREES);
const MAX_LON_IDX = Math.round(180 / CROWN_CELL_DEGREES);

export interface SpawnCellIndices {
  latIdx: number;
  lonIdx: number;
}

export interface LatLng {
  lat: number;
  lng: number;
}

export interface SpawnCellBounds {
  minLat: number;
  maxLat: number;
  minLon: number;
  maxLon: number;
}

/**
 * Deterministic spawn-grid key for a coordinate — `${latIdx}_${lonIdx}`.
 * Identical construction to the backend `crownCellKey`, so the key an operator
 * previews is the key the callable will resolve.
 */
export function cellKeyForCoords(latitude: number, longitude: number): string {
  const latIdx = Math.floor(clampLat(latitude) / CROWN_CELL_DEGREES);
  const lonIdx = Math.floor(clampLon(longitude) / CROWN_CELL_DEGREES);
  return `${latIdx}_${lonIdx}`;
}

/**
 * Parses a cell key back to its grid indices; null when malformed OR off the
 * globe. Same regex + range check as the backend `parseCrownCellKey`, so a key
 * the admin cannot preview is exactly a key the callable would reject.
 */
export function parseSpawnCellKey(cellKey: string): SpawnCellIndices | null {
  const match = /^(-?\d{1,6})_(-?\d{1,6})$/.exec(cellKey.trim());
  if (!match) return null;
  const latIdx = Number(match[1]);
  const lonIdx = Number(match[2]);
  if (!Number.isSafeInteger(latIdx) || !Number.isSafeInteger(lonIdx)) return null;
  if (latIdx < -MAX_LAT_IDX || latIdx > MAX_LAT_IDX) return null;
  if (lonIdx < -MAX_LON_IDX || lonIdx > MAX_LON_IDX) return null;
  return { latIdx, lonIdx };
}

/** The half-open [min, max) coordinate box a cell key covers; null when invalid. */
export function spawnCellBounds(cellKey: string): SpawnCellBounds | null {
  const parsed = parseSpawnCellKey(cellKey);
  if (!parsed) return null;
  return {
    minLat: clampLat(parsed.latIdx * CROWN_CELL_DEGREES),
    maxLat: clampLat((parsed.latIdx + 1) * CROWN_CELL_DEGREES),
    minLon: clampLon(parsed.lonIdx * CROWN_CELL_DEGREES),
    maxLon: clampLon((parsed.lonIdx + 1) * CROWN_CELL_DEGREES),
  };
}

/**
 * Approximate centre of a cell, for a human "where is this?" hint. Null when
 * the key does not parse. Edge cells (lat 90 / lon 180) degenerate to their
 * clamped edge, which is the correct answer — there is no strip of Earth beyond.
 */
export function spawnCellCenter(cellKey: string): LatLng | null {
  const bounds = spawnCellBounds(cellKey);
  if (!bounds) return null;
  return {
    lat: (bounds.minLat + bounds.maxLat) / 2,
    lng: (bounds.minLon + bounds.maxLon) / 2,
  };
}

/** Human-readable centre string, e.g. "57.48720, 12.07610"; '—' when invalid. */
export function formatCellCenter(cellKey: string): string {
  const center = spawnCellCenter(cellKey);
  if (!center) return '—';
  return `${center.lat.toFixed(5)}, ${center.lng.toFixed(5)}`;
}

// ---------------------------------------------------------------------------
// Summary type + approval-state mapping (pure, unit-tested)
// ---------------------------------------------------------------------------

export interface AdminSpawnCellSummary {
  cellKey: string;
  approved: boolean;
  approvalNote: string | null;
  approvedByUserId: string | null;
  approvedAt: string | null;
  revokedByUserId: string | null;
  revokedAt: string | null;
  revocationReason: string | null;
  updatedAt: string | null;
}

/**
 * The state a cell is in for display purposes. A `crownSpawnCells` document
 * only exists because it was approved at least once, so a non-approved doc is
 * always a REVOKED area (it was on the list and taken off), never a fresh one.
 */
export type SpawnCellState = 'approved' | 'revoked';

export function spawnCellState(cell: Pick<AdminSpawnCellSummary, 'approved'>): SpawnCellState {
  return cell.approved ? 'approved' : 'revoked';
}

/** Firestore Timestamp | Date | ISO string | null → ISO string (or null). */
function toIso(value: unknown): string | null {
  if (value == null) return null;
  if (value instanceof Date) return value.toISOString();
  if (typeof value === 'string') return value;
  const ts = value as Timestamp;
  if (typeof ts.toDate === 'function') return ts.toDate().toISOString();
  return null;
}

/** Maps a crownSpawnCells/{cellKey} document to the admin summary. */
export function toSpawnCellSummary(id: string, data: DocumentData): AdminSpawnCellSummary {
  return {
    cellKey: (data.cellKey as string | undefined) ?? id,
    approved: data.approved === true,
    approvalNote: (data.approvalNote as string | null | undefined) ?? null,
    approvedByUserId: (data.approvedByUserId as string | null | undefined) ?? null,
    approvedAt: toIso(data.approvedAt),
    revokedByUserId: (data.revokedByUserId as string | null | undefined) ?? null,
    revokedAt: toIso(data.revokedAt),
    revocationReason: (data.revocationReason as string | null | undefined) ?? null,
    updatedAt: toIso(data.updatedAt),
  };
}

// ---------------------------------------------------------------------------
// Read (direct Firestore, admin rules-gated)
// ---------------------------------------------------------------------------

const DEFAULT_PAGE_SIZE = 100;

/**
 * Lists spawn cells (approved and revoked) for the admin approval view, most
 * recently changed first. `crownSpawnCells` is a small operational collection,
 * so a single capped page is enough; both write paths of the callable set
 * `updatedAt`, so the single-field order is always populated.
 */
export async function adminListSpawnCells(): Promise<AdminSpawnCellSummary[]> {
  const snapshot = await getDocs(
    query(
      collection(getAdminFirestore(), 'crownSpawnCells'),
      orderBy('updatedAt', 'desc'),
      fsLimit(DEFAULT_PAGE_SIZE),
    ),
  );
  return snapshot.docs.map((d) => toSpawnCellSummary(d.id, d.data()));
}

// ---------------------------------------------------------------------------
// Mutations (safety-gated crownHunt.setSpawnCellApproval callable)
// ---------------------------------------------------------------------------

/** Response of the crownHunt-setSpawnCellApproval callable. */
export interface SetSpawnCellApprovalResponse {
  cellKey: string;
  approved: boolean;
  /** Live auto-spawned crowns removed by a revocation (0 when approving). */
  removedCrowns: number;
}

/** How the operator specified the target cell: a grid key OR a coordinate. */
export type SpawnCellTarget = { cellKey: string } | { latitude: number; longitude: number };

/**
 * Approves a spawn cell (create-or-update). The backend requires the literal
 * `safeAreaConfirmed: true` and a note (>= 3 chars) that lands in the audit
 * record; both are supplied here. Approving lets crowns spawn in the cell once
 * `crownHuntSpawn` is enabled — until then it changes nothing observable.
 */
export async function adminApproveSpawnCell(
  target: SpawnCellTarget,
  approvalNote: string,
): Promise<SetSpawnCellApprovalResponse> {
  return callAdmin<SetSpawnCellApprovalResponse>('crownHunt-setSpawnCellApproval', {
    approved: true,
    safeAreaConfirmed: true,
    approvalNote,
    ...target,
  });
}

/**
 * Revokes a spawn cell. Cheaper than approving by design (turning an area off
 * must never be harder than turning it on): only an optional reason. The
 * backend also deletes the cell's live auto-spawned crowns immediately.
 */
export async function adminRevokeSpawnCell(
  target: SpawnCellTarget,
  reason?: string,
): Promise<SetSpawnCellApprovalResponse> {
  const trimmed = reason?.trim();
  return callAdmin<SetSpawnCellApprovalResponse>('crownHunt-setSpawnCellApproval', {
    approved: false,
    ...(trimmed ? { reason: trimmed } : {}),
    ...target,
  });
}
