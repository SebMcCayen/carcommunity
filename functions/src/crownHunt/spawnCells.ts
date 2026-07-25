/**
 * crownHunt.setSpawnCellApproval — admin callable
 * (contracts/functions/functions.json).
 *
 * The human half of the auto-spawn safety model.
 *
 * A hand-placed Kronjakt point cannot go live until an admin ticks
 * `safeLocationConfirmed` for that exact coordinate: someone has looked at the
 * spot and decided it is safe to stop at. Auto-spawn picks coordinates by
 * itself, so that per-point gate is impossible by construction — and its
 * natural replacement, "spawn wherever people already are", is NOT equivalent.
 * Presence is not safety. A motorway is one of the most visited places in the
 * country.
 *
 * So the approval moves up one level, to the AREA. `crownSpawnCells/{cellKey}`
 * is an allow-list of ~1.1 km grid cells an admin has looked at and approved
 * for automatic placement. The scheduled spawner reads ONLY this collection: a
 * cell that is not on the list, or whose approval has been revoked, spawns
 * nothing no matter how much activity it accumulates. The algorithm decides how
 * many crowns and roughly where inside an approved area; it can never open a
 * new area.
 *
 * Two further filters sit under this one and are described where they live:
 * the activity floor (`A < 1` → no spawn, crown-spawn-core.ts) and the
 * slow-sighting filter (only sub-8 m/s presence scores at all, spawnActivity.ts)
 * — the latter is what stops an approved cell that happens to contain a
 * through-road from spawning beside it.
 *
 * Writes are backend-only (firestore.rules denies every client write to
 * `crownSpawnCells`), admin-gated via requireAdminActor, and audited to
 * adminAuditEvents exactly like the point lifecycle, so every area that was
 * ever opened has a named admin and a note attached to it.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireAdminActor } from '../admin/actorContext';
import { buildAdminAuditEvent } from '../admin/claims-core';
import {
  SPAWN_CELL_NEVER_SERVED_AT_MS,
  parseSetSpawnCellApprovalInput,
  resolveSpawnCellKey,
} from './crown-spawn-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

/**
 * Documents deleted per batch when a revocation drains a cell. Firestore caps a
 * write batch at 500 operations; 200 keeps each commit small enough that a slow
 * one still leaves room inside the 30 s callable timeout for the next page.
 */
const REVOCATION_PAGE_SIZE = 200;

/**
 * Defensive ceiling on one revocation. A cell should hold single digits of live
 * crowns, so reaching this means something upstream is wrong; stop and let the
 * sweeper finish rather than time the admin's callable out mid-drain.
 */
const MAX_REVOKED_CROWNS = 2000;

export interface SetSpawnCellApprovalResponse {
  cellKey: string;
  approved: boolean;
  /** Live auto-spawned crowns removed by a revocation (0 when approving). */
  removedCrowns: number;
}

export const setSpawnCellApproval = onCall(
  CALLABLE_OPTS,
  async (request): Promise<SetSpawnCellApprovalResponse> => {
    const actor = await requireAdminActor(request);

    const parsed = parseSetSpawnCellApprovalInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const input = parsed.input;
    const cellKey = resolveSpawnCellKey(input);
    if (!cellKey) {
      throw new HttpsError('invalid-argument', 'Could not resolve a spawn cell key.');
    }

    const cellRef = db.collection('crownSpawnCells').doc(cellKey);
    const serverTimestamp = () => FieldValue.serverTimestamp();
    const batch = db.batch();

    if (input.approved) {
      batch.set(
        cellRef,
        {
          cellKey,
          approved: true,
          approvalNote: input.approvalNote,
          approvedByUserId: actor.uid,
          approvedAt: serverTimestamp(),
          // Seeded so the spawner's least-recently-served ordering has a value
          // for a brand-new cell; without it the orderBy would silently skip
          // the document (Firestore excludes docs missing the sort field) and a
          // freshly approved area would never be served. The seed is the EPOCH
          // sentinel, not `now`: this cell has never been served, so it belongs
          // at the FRONT of a least-recently-served queue, and "now" would both
          // misstate the field's meaning and sort it to the back behind every
          // already-served cell. See SPAWN_CELL_NEVER_SERVED_AT_MS.
          lastSpawnPassAt: Timestamp.fromMillis(SPAWN_CELL_NEVER_SERVED_AT_MS),
          revokedAt: null,
          revokedByUserId: null,
          updatedAt: serverTimestamp(),
        },
        { merge: true },
      );
    } else {
      batch.set(
        cellRef,
        {
          cellKey,
          approved: false,
          revokedAt: serverTimestamp(),
          revokedByUserId: actor.uid,
          revocationReason: input.reason?.trim() ?? null,
          updatedAt: serverTimestamp(),
        },
        { merge: true },
      );
    }

    batch.set(
      db.collection('adminAuditEvents').doc(),
      buildAdminAuditEvent(
        {
          adminId: actor.uid,
          action: input.approved
            ? 'crownHunt.approveSpawnCell'
            : 'crownHunt.revokeSpawnCell',
          targetType: 'crownSpawnCell',
          targetId: cellKey,
          reason: input.approved
            ? input.approvalNote
            : (input.reason?.trim() || 'Spawn cell revoked.'),
          details: { safeAreaConfirmed: input.approved },
        },
        serverTimestamp,
      ),
    );

    await batch.commit();

    // Revoking must take effect NOW, not at the end of the longest TTL.
    // Revocation is the lever an admin reaches for after a near-miss or a
    // complaint; leaving up to 48 hours of legendary crowns standing in an area
    // just declared unsafe would make it useless for the one job it has.
    //
    // So this DRAINS the cell rather than deleting one fixed page of it. The
    // spawner tops a cell up to at most MAX_CROWNS_PER_CELL crowns that are
    // live AND unexpired, but this query is deliberately `status == 'live'`
    // with no expiry filter, and expired-but-not-yet-swept crowns stay in that
    // set until the 15-minute sweeper reaches them — so "at most 5 documents"
    // was never an invariant a single page could be sized against. Neither a
    // sweeper backlog nor a future change to the target curve may leave a crown
    // standing in an area an admin has just declared unsafe.
    //
    // Still bounded: pages of REVOCATION_PAGE_SIZE up to MAX_REVOKED_CROWNS in
    // total, so a pathological cell degrades to "nearly all of it removed now,
    // sweeper gets the rest" rather than running the callable out of time.
    let removedCrowns = 0;
    if (!input.approved) {
      while (removedCrowns < MAX_REVOKED_CROWNS) {
        const pageSize = Math.min(REVOCATION_PAGE_SIZE, MAX_REVOKED_CROWNS - removedCrowns);
        const live = await db
          .collection('crownSpawns')
          .where('cellKey', '==', cellKey)
          .where('status', '==', 'live')
          .limit(pageSize)
          .get();
        if (live.empty) break;

        const removal = db.batch();
        for (const doc of live.docs) removal.delete(doc.ref);
        await removal.commit();
        removedCrowns += live.size;

        // A short page means the cell is drained. The next query would come
        // back empty anyway; this saves a round trip on every revocation.
        if (live.size < pageSize) break;
      }
    }

    return { cellKey, approved: input.approved, removedCrowns };
  },
);
