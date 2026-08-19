/**
 * crownHunt.createSpawnArea / updateSpawnArea / deleteSpawnArea / listSpawnAreas
 * — the admin CRUD for MARKED AREAS (contracts/functions/functions.json).
 *
 * The wider half of the auto-spawn safety model. `setSpawnCellApproval`
 * (spawnCells.ts) approves ONE ~1.1 km grid cell; these callables let an admin
 * draw a BIG shape — a polygon, a circle, or a rectangle — and have the spawner
 * place crowns at random points INSIDE it, density-weighted per cell exactly as
 * the single-cell path is.
 *
 * The gate is preserved one level wider and unchanged in spirit: a drawn area
 * does nothing until an admin sets `active: true` AND, in the same request,
 * `safeAreaConfirmed: true` — a literal, so no default or truthy accident can
 * satisfy it (crown-area-core.ts). Every call is admin-gated via
 * `requireAdminActor`, App-Check-enforced (CALLABLE_OPTS), and audited to
 * `adminAuditEvents`, so every area that was ever switched on has a named admin
 * and a reason attached — the same trail `setSpawnCellApproval` leaves.
 *
 * Turning an area OFF (deactivating, deleting, or re-drawing its shape) DRAINS
 * that area's live auto-spawned crowns immediately, rather than waiting out
 * their TTL — the same reasoning as revoking a cell: deactivation is the lever
 * an admin reaches for after a near-miss, and up to a day of standing crowns
 * (a rare lives 24 h) in an area just declared unsafe would make it useless. Crowns are
 * tagged with `areaId` at spawn time (spawnScheduled.ts) precisely so this drain
 * can find exactly them.
 *
 * Writes are backend-only (firestore.rules denies every client write to
 * `crownSpawnAreas`; it is admin-READ for the portal). The whole automatic half
 * stays behind the `crownHuntSpawn` feature flag, contract default OFF — these
 * callables only shape the allow-list; nothing here spawns.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp, type DocumentData } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireAdminActor } from '../admin/actorContext';
import { buildAdminAuditEvent } from '../admin/claims-core';
import { MAX_INSTANCES_ADMIN, CPU_ADMIN } from '../shared/instanceLimits';
import {
  parseCreateSpawnAreaInput,
  parseDeleteSpawnAreaInput,
  parseListSpawnAreasInput,
  parseUpdateSpawnAreaInput,
  type CrownSpawnAreaShapeInput,
} from './crown-area-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_ADMIN,
  cpu: CPU_ADMIN,
  concurrency: 1,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

const AREAS_COLLECTION = 'crownSpawnAreas';

/** See spawnCells.ts — same bounds, same reasoning, for the area drain. */
const DRAIN_PAGE_SIZE = 200;
const MAX_DRAINED_CROWNS = 2000;

/** Cap on how many areas one `listSpawnAreas` call returns without a page arg. */
const DEFAULT_LIST_LIMIT = 200;

export interface SpawnAreaMutationResponse {
  areaId: string;
  active: boolean;
  safeAreaConfirmed: boolean;
  /** Live auto-spawned crowns removed by a deactivation/deletion/reshape (0 otherwise). */
  removedCrowns: number;
}

export interface SpawnAreaSummary {
  areaId: string;
  name: string | null;
  shape: CrownSpawnAreaShapeInput;
  active: boolean;
  safeAreaConfirmed: boolean;
  createdByUserId: string;
  createdAt: string | null;
  updatedAt: string | null;
  approvedByUserId: string | null;
  approvedAt: string | null;
  /**
   * Count of cached OpenStreetMap safe-stop POIs (parking / fuel / charging)
   * found inside this area — what the spawner anchors crowns to. 0 until the
   * area's POI ingestion has run (poiIngestion.ts). The admin UI surfaces this
   * as "N safe spots found in this area" and MUST show the OSM attribution
   * (© OpenStreetMap contributors) alongside it.
   */
  poiCount: number;
  /** When the POI cache was last refreshed, or null if it never has been. */
  poisRefreshedAt: string | null;
}

export interface ListSpawnAreasResponse {
  areas: SpawnAreaSummary[];
}

// ---------------------------------------------------------------------------
// Drain (mirrors the cell revoke drain in spawnCells.ts)
// ---------------------------------------------------------------------------

/**
 * Deletes the live auto-spawned crowns tagged with `areaId`, in bounded pages.
 *
 * `status == 'live'` with NO expiry filter, deliberately: an expired-but-not-yet
 * swept crown still carries `status: 'live'` and must go too, and the sweeper
 * backlog means "at most 5 per cell" was never a page-sizeable invariant — same
 * argument as the cell drain. Bounded by pages of {@link DRAIN_PAGE_SIZE} up to
 * {@link MAX_DRAINED_CROWNS}, so a pathological area degrades to "nearly all of
 * it removed now, sweeper gets the rest" rather than timing the callable out.
 */
async function drainAreaCrowns(areaId: string): Promise<number> {
  let removed = 0;
  while (removed < MAX_DRAINED_CROWNS) {
    const pageSize = Math.min(DRAIN_PAGE_SIZE, MAX_DRAINED_CROWNS - removed);
    const live = await db
      .collection('crownSpawns')
      .where('areaId', '==', areaId)
      .where('status', '==', 'live')
      .limit(pageSize)
      .get();
    if (live.empty) break;

    const batch = db.batch();
    for (const doc of live.docs) batch.delete(doc.ref);
    await batch.commit();
    removed += live.size;

    if (live.size < pageSize) break;
  }
  return removed;
}

function toIso(value: unknown): string | null {
  return value instanceof Timestamp ? value.toDate().toISOString() : null;
}

function toSummary(id: string, data: DocumentData): SpawnAreaSummary {
  return {
    areaId: id,
    name: (data.name as string | null) ?? null,
    shape: data.shape as CrownSpawnAreaShapeInput,
    active: data.active === true,
    safeAreaConfirmed: data.safeAreaConfirmed === true,
    createdByUserId: (data.createdByUserId as string) ?? '',
    createdAt: toIso(data.createdAt),
    updatedAt: toIso(data.updatedAt),
    approvedByUserId: (data.approvedByUserId as string | null) ?? null,
    approvedAt: toIso(data.approvedAt),
    poiCount: typeof data.poiCount === 'number' ? data.poiCount : 0,
    poisRefreshedAt: toIso(data.poisRefreshedAt),
  };
}

// ---------------------------------------------------------------------------
// Create
// ---------------------------------------------------------------------------

export const createSpawnArea = onCall(
  CALLABLE_OPTS,
  async (request): Promise<SpawnAreaMutationResponse> => {
    const actor = await requireAdminActor(request);

    const parsed = parseCreateSpawnAreaInput(request.data);
    if (!parsed.ok) throw new HttpsError('invalid-argument', parsed.message);
    const input = parsed.input;

    const active = input.active === true;
    // The invariant active ⇒ safeAreaConfirmed is guaranteed by the input schema
    // (activating requires the literal), so an active area is always confirmed;
    // but an INACTIVE area may legitimately be created already-confirmed
    // (safeAreaConfirmed:true, active:false) so a later activation is one flag
    // flip. This is the single source of truth for the stored + audited value.
    const storedSafe = active || input.safeAreaConfirmed === true;
    const now = FieldValue.serverTimestamp();

    const ref = db.collection(AREAS_COLLECTION).doc();
    const batch = db.batch();
    batch.set(ref, {
      areaId: ref.id,
      name: input.name ?? null,
      shape: input.shape,
      active,
      safeAreaConfirmed: storedSafe,
      createdByUserId: actor.uid,
      createdAt: now,
      updatedAt: now,
      approvedByUserId: active ? actor.uid : null,
      approvedAt: active ? now : null,
      deactivatedByUserId: null,
      deactivatedAt: null,
      deactivationReason: null,
      // Round-robin cursors the area spawner orders by / advances. Seeded to the
      // epoch so a never-served area sorts to the FRONT of the least-recently-
      // served queue (Firestore also excludes docs missing the orderBy field, so
      // this must exist), and to offset 0 so its cells are walked from the start.
      lastSpawnPassAt: Timestamp.fromMillis(0),
      nextCellOffset: 0,
    });
    batch.set(
      db.collection('adminAuditEvents').doc(),
      buildAdminAuditEvent(
        {
          adminId: actor.uid,
          action: 'crownHunt.createSpawnArea',
          targetType: 'crownSpawnArea',
          targetId: ref.id,
          reason: `Created ${input.shape.type} area${active ? ' (active)' : ''}.`,
          details: { shapeType: input.shape.type, active, safeAreaConfirmed: storedSafe },
        },
        () => FieldValue.serverTimestamp(),
      ),
    );
    await batch.commit();

    return {
      areaId: ref.id,
      active,
      safeAreaConfirmed: storedSafe,
      removedCrowns: 0,
    };
  },
);

// ---------------------------------------------------------------------------
// Update
// ---------------------------------------------------------------------------

export const updateSpawnArea = onCall(
  CALLABLE_OPTS,
  async (request): Promise<SpawnAreaMutationResponse> => {
    const actor = await requireAdminActor(request);

    const parsed = parseUpdateSpawnAreaInput(request.data);
    if (!parsed.ok) throw new HttpsError('invalid-argument', parsed.message);
    const input = parsed.input;

    const ref = db.collection(AREAS_COLLECTION).doc(input.areaId);
    const snap = await ref.get();
    if (!snap.exists) throw new HttpsError('not-found', 'Marked area not found.');
    const existing = snap.data() as DocumentData;

    const wasActive = existing.active === true;
    const wasSafe = existing.safeAreaConfirmed === true;
    const newSafe =
      input.safeAreaConfirmed !== undefined
        ? input.safeAreaConfirmed === true
        : existing.safeAreaConfirmed === true;
    const requestedActive = input.active !== undefined ? input.active === true : wasActive;
    // The invariant, enforced structurally: an area can only be active while it
    // is confirmed safe. Clearing the confirmation therefore deactivates it too.
    const newActive = requestedActive && newSafe;

    const shapeChanged = input.shape !== undefined;
    const deactivating = wasActive && !newActive;
    // Re-drawing the shape can leave a live crown outside the NEW area, so drain
    // and let the next pass re-place inside the new shape — the same safe reset
    // as a deactivation. Draining when there are no live crowns is a no-op.
    const mustDrain = deactivating || shapeChanged;

    const now = FieldValue.serverTimestamp();
    const activating = !wasActive && newActive;

    const patch: DocumentData = { updatedAt: now };
    if (input.name !== undefined) patch.name = input.name ?? null;
    if (input.shape !== undefined) patch.shape = input.shape;
    patch.active = newActive;
    patch.safeAreaConfirmed = newSafe;
    if (activating) {
      patch.approvedByUserId = actor.uid;
      patch.approvedAt = now;
      patch.deactivatedByUserId = null;
      patch.deactivatedAt = null;
      patch.deactivationReason = null;
      // Reseed the round-robin cursor so a just-activated area is repopulated on
      // the next pass rather than waiting out a full cycle — the same reasoning
      // as re-approving a cell.
      patch.lastSpawnPassAt = Timestamp.fromMillis(0);
      patch.nextCellOffset = 0;
    }
    if (deactivating) {
      patch.deactivatedByUserId = actor.uid;
      patch.deactivatedAt = now;
      patch.deactivationReason = 'Area deactivated.';
    }

    await ref.set(patch, { merge: true });

    await db
      .collection('adminAuditEvents')
      .doc()
      .set(
        buildAdminAuditEvent(
          {
            adminId: actor.uid,
            action: 'crownHunt.updateSpawnArea',
            targetType: 'crownSpawnArea',
            targetId: input.areaId,
            // Report ACTUAL state transitions, not which input keys were present,
            // so a SAFETY-critical change is never lost from the trail: clearing
            // safeAreaConfirmed shows as both `safeAreaConfirmed=false` AND the
            // `active=false` it implicitly forces, rather than reading as a no-op.
            reason: `Updated area (${
              [
                shapeChanged ? 'shape' : null,
                input.name !== undefined ? 'name' : null,
                newActive !== wasActive ? `active=${newActive}` : null,
                newSafe !== wasSafe ? `safeAreaConfirmed=${newSafe}` : null,
              ]
                .filter(Boolean)
                .join(', ') || 'no-op'
            }).`,
            details: { active: newActive, safeAreaConfirmed: newSafe, shapeChanged, deactivating },
          },
          () => FieldValue.serverTimestamp(),
        ),
      );

    // Drain AFTER the flag flip is committed, so the spawner's in-transaction
    // re-check already sees active:false and no new crown can land behind us.
    const removedCrowns = mustDrain ? await drainAreaCrowns(input.areaId) : 0;

    return { areaId: input.areaId, active: newActive, safeAreaConfirmed: newSafe, removedCrowns };
  },
);

// ---------------------------------------------------------------------------
// Delete
// ---------------------------------------------------------------------------

export const deleteSpawnArea = onCall(
  CALLABLE_OPTS,
  async (request): Promise<SpawnAreaMutationResponse> => {
    const actor = await requireAdminActor(request);

    const parsed = parseDeleteSpawnAreaInput(request.data);
    if (!parsed.ok) throw new HttpsError('invalid-argument', parsed.message);
    const input = parsed.input;

    const ref = db.collection(AREAS_COLLECTION).doc(input.areaId);
    const snap = await ref.get();
    if (!snap.exists) throw new HttpsError('not-found', 'Marked area not found.');

    // Flip the area inactive FIRST so a pass mid-flight re-reads active:false and
    // writes nothing, THEN drain, THEN delete — an ordering that cannot leave a
    // live crown standing in a deleted area.
    await ref.set(
      { active: false, safeAreaConfirmed: false, updatedAt: FieldValue.serverTimestamp() },
      { merge: true },
    );
    const removedCrowns = await drainAreaCrowns(input.areaId);
    await ref.delete();

    await db
      .collection('adminAuditEvents')
      .doc()
      .set(
        buildAdminAuditEvent(
          {
            adminId: actor.uid,
            action: 'crownHunt.deleteSpawnArea',
            targetType: 'crownSpawnArea',
            targetId: input.areaId,
            reason: input.reason?.trim() || 'Marked area deleted.',
            details: { removedCrowns },
          },
          () => FieldValue.serverTimestamp(),
        ),
      );

    return { areaId: input.areaId, active: false, safeAreaConfirmed: false, removedCrowns };
  },
);

// ---------------------------------------------------------------------------
// List
// ---------------------------------------------------------------------------

export const listSpawnAreas = onCall(
  CALLABLE_OPTS,
  async (request): Promise<ListSpawnAreasResponse> => {
    await requireAdminActor(request);

    const parsed = parseListSpawnAreasInput(request.data);
    if (!parsed.ok) throw new HttpsError('invalid-argument', parsed.message);
    const input = parsed.input ?? {};

    const limit = input.limit ?? DEFAULT_LIST_LIMIT;
    let query = db.collection(AREAS_COLLECTION).limit(limit);
    if (input.activeOnly === true) {
      query = db.collection(AREAS_COLLECTION).where('active', '==', true).limit(limit);
    }
    const snap = await query.get();

    return { areas: snap.docs.map((doc) => toSummary(doc.id, doc.data())) };
  },
);
