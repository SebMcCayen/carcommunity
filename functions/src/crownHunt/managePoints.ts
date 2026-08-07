/**
 * crownHunt.createPoint / updatePoint / activatePoint / pausePoint —
 * admin callables (contracts/functions/functions.json).
 *
 * Deployed via the `crownHunt` export group. Requires an active admin via
 * requireAdminActor. Legacy crown-hunt-service parity:
 *
 * - Points are created as drafts; strict field validation (geofence 20-150 m,
 *   reward 1-1000 KP, repeat rule enum, availability-window ordering). A Crown
 *   is a map collectable, not a titled document — no title/description is
 *   accepted; the stored doc carries title '' / description null for reader
 *   back-compat.
 * - Only draft or paused points may be edited.
 * - Activation is a SAFETY GATE: it requires an explicit
 *   safeLocationConfirmed flag and an approval note (>=3 chars) that lands
 *   in the audit record; approvedAt/approvedByUserId are stamped.
 * - Ended points can be neither activated nor paused.
 * - deletePoint HARD-DELETES a point from ANY status (draft/active/paused/
 *   ended): it removes the crownHuntPoints/{id} doc and the point's
 *   distinct-collector markers (crownHuntPointCollectors). Deleting the doc
 *   removes a live crown from the map instantly (members read only
 *   status=='active'); historical crownHuntClaims are kept as an audit trail
 *   (the admin claims view tolerates a missing point). This is the ONE place a
 *   point may be hard-deleted — the rest of the flow prefers pause/end.
 * - Every operation writes an adminAuditEvents record (legacy audit-log
 *   actions preserved as crownHunt.*).
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireAdminActor } from '../admin/actorContext';
import { buildAdminAuditEvent } from '../admin/claims-core';
import {
  guardPointFields,
  parseActivatePointInput,
  parseCreatePointInput,
  parseDeletePointInput,
  parsePausePointInput,
  parseUpdatePointInput,
  type CrownHuntPointStatus,
} from './crownhunt-core';
import { MAX_INSTANCES_ADMIN, CPU_ADMIN } from '../shared/instanceLimits';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_ADMIN,
  cpu: CPU_ADMIN,
  concurrency: 1,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export interface PointIdResponse {
  pointId: string;
  status: CrownHuntPointStatus;
}

export interface PointDeletedResponse {
  pointId: string;
  deleted: true;
  /** Distinct-collector markers removed alongside the point (0 for unlimited). */
  removedCollectors: number;
}

/** Firestore batches cap at 500 writes; stay under it with headroom. */
const DELETE_BATCH_SIZE = 400;

/**
 * Deletes every crownHuntPointCollectors marker for a point, paged. Only
 * LIMITED crowns ever create these (one per distinct collector, so the set is
 * bounded by maxCollectors), but page defensively regardless. Returns the count
 * removed for the audit record.
 */
async function deletePointCollectorMarkers(pointId: string): Promise<number> {
  let removed = 0;
  for (;;) {
    const snap = await db
      .collection('crownHuntPointCollectors')
      .where('pointId', '==', pointId)
      .limit(DELETE_BATCH_SIZE)
      .get();
    if (snap.empty) break;
    const batch = db.batch();
    snap.docs.forEach((doc) => batch.delete(doc.ref));
    await batch.commit();
    removed += snap.size;
    if (snap.size < DELETE_BATCH_SIZE) break;
  }
  return removed;
}

const toTimestampOrNull = (iso: string | null | undefined) =>
  iso ? Timestamp.fromDate(new Date(iso)) : null;

export const createPoint = onCall(CALLABLE_OPTS, async (request): Promise<PointIdResponse> => {
  const actor = await requireAdminActor(request);

  const parsed = parseCreatePointInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const input = parsed.input;
  const guard = guardPointFields(input);
  if (!guard.ok) {
    throw new HttpsError(guard.code, guard.message);
  }

  const pointRef = db.collection('crownHuntPoints').doc();
  const serverTimestamp = () => FieldValue.serverTimestamp();
  const batch = db.batch();
  batch.set(pointRef, {
    // A Crown is a map COLLECTABLE (Pokémon GO–style), not a titled document,
    // so create accepts no title/description. The stored doc still carries both
    // fields for reader back-compat (e.g. the map crown popup): title is always
    // '' and description always null.
    title: '',
    description: null,
    latitude: input.latitude,
    longitude: input.longitude,
    geofenceRadiusMeters: input.geofenceRadiusMeters,
    rewardPoints: input.rewardPoints,
    repeatRule: input.repeatRule,
    // Distinct-collector cap: null = unlimited (default, best for events); a
    // positive integer caps the headcount. `collectorCount` is the running
    // distinct-collector tally, maintained by submitClaim inside the award
    // transaction and seeded to 0 here.
    maxCollectors: input.maxCollectors ?? null,
    collectorCount: 0,
    status: 'draft',
    availableFrom: toTimestampOrNull(input.availableFrom),
    availableUntil: toTimestampOrNull(input.availableUntil),
    approvedAt: null,
    approvedByUserId: null,
    createdByUserId: actor.uid,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });
  batch.set(
    db.collection('adminAuditEvents').doc(),
    buildAdminAuditEvent(
      {
        adminId: actor.uid,
        action: 'crownHunt.createPoint',
        targetType: 'crownHuntPoint',
        targetId: pointRef.id,
        reason: 'Point created (draft).',
        details: {
          rewardPoints: input.rewardPoints,
          geofenceRadiusMeters: input.geofenceRadiusMeters,
          maxCollectors: input.maxCollectors ?? null,
        },
      },
      serverTimestamp,
    ),
  );
  await batch.commit();

  return { pointId: pointRef.id, status: 'draft' };
});

export const updatePoint = onCall(CALLABLE_OPTS, async (request): Promise<PointIdResponse> => {
  const actor = await requireAdminActor(request);

  const parsed = parseUpdatePointInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const input = parsed.input;
  const pointRef = db.collection('crownHuntPoints').doc(input.pointId);
  const serverTimestamp = () => FieldValue.serverTimestamp();

  const status = await db.runTransaction(async (tx) => {
    const snap = await tx.get(pointRef);
    if (!snap.exists) {
      throw new HttpsError('not-found', 'Kronjakt point not found.');
    }
    const existing = snap.data()!;
    if (existing.status !== 'draft' && existing.status !== 'paused') {
      throw new HttpsError(
        'failed-precondition',
        'Only draft or paused points may be edited.',
      );
    }

    // Unlimited → limited is only safe BEFORE anyone has collected. Unlimited
    // crowns never track distinct collectors (no crownHuntPointCollectors
    // markers, and collectorCount stays 0), so a cap added AFTER awards exist
    // could not count the prior collectors — the first N *new* collectors would
    // each still be admitted, letting far more than N distinct users collect
    // overall. That transition is unenforceable, so reject it (a backfill is out
    // of scope for MVP). Limited → limited (change N) and limited → unlimited
    // stay allowed. Read the awarded-claim probe INSIDE the transaction, before
    // any write.
    const wasUnlimited = (existing.maxCollectors as number | null | undefined) == null;
    if (input.maxCollectors != null && wasUnlimited) {
      const awarded = await tx.get(
        db
          .collection('crownHuntClaims')
          .where('pointId', '==', input.pointId)
          .where('result', '==', 'awarded')
          .limit(1),
      );
      if (!awarded.empty) {
        throw new HttpsError(
          'invalid-argument',
          'This crown has already been collected, so a collector limit cannot be added now — unlimited crowns do not track collectors. Create a new limited crown instead.',
        );
      }
    }

    // Merge incoming over existing, then validate the merged result
    // (legacy adminUpdatePoint semantics).
    const merged = {
      latitude: input.latitude ?? (existing.latitude as number),
      longitude: input.longitude ?? (existing.longitude as number),
      availableFrom:
        input.availableFrom !== undefined
          ? input.availableFrom
          : (existing.availableFrom
              ? (existing.availableFrom as Timestamp).toDate().toISOString()
              : null),
      availableUntil:
        input.availableUntil !== undefined
          ? input.availableUntil
          : (existing.availableUntil
              ? (existing.availableUntil as Timestamp).toDate().toISOString()
              : null),
    };
    const guard = guardPointFields(merged);
    if (!guard.ok) {
      throw new HttpsError(guard.code, guard.message);
    }

    const update: Record<string, unknown> = { updatedAt: serverTimestamp() };
    if (input.latitude !== undefined) update.latitude = input.latitude;
    if (input.longitude !== undefined) update.longitude = input.longitude;
    if (input.geofenceRadiusMeters !== undefined)
      update.geofenceRadiusMeters = input.geofenceRadiusMeters;
    if (input.rewardPoints !== undefined) update.rewardPoints = input.rewardPoints;
    if (input.repeatRule !== undefined) update.repeatRule = input.repeatRule;
    if (input.maxCollectors !== undefined) update.maxCollectors = input.maxCollectors;
    if (input.availableFrom !== undefined)
      update.availableFrom = toTimestampOrNull(input.availableFrom);
    if (input.availableUntil !== undefined)
      update.availableUntil = toTimestampOrNull(input.availableUntil);
    if (Object.keys(update).length === 1) {
      throw new HttpsError('invalid-argument', 'No point fields to update.');
    }

    // "Full → done": if this edit leaves a LIMITED crown with as many (or more)
    // distinct collectors as its cap, retire it now (status 'ended') — the same
    // collected-out state submitClaim sets when the Nth collector is awarded.
    // Otherwise an admin lowering maxCollectors below the live collectorCount
    // would leave a draft/paused point that can still be re-activated yet is
    // uncollectable (every new user hits "full"), which is confusing at an
    // event. Draft/paused → ended is a valid retire, and activatePoint already
    // refuses ended points.
    const resultingMaxCollectors =
      input.maxCollectors !== undefined
        ? input.maxCollectors
        : ((existing.maxCollectors as number | null | undefined) ?? null);
    const currentCollectors = (existing.collectorCount as number | undefined) ?? 0;
    if (resultingMaxCollectors !== null && currentCollectors >= resultingMaxCollectors) {
      update.status = 'ended';
    }

    tx.update(pointRef, update);
    tx.set(
      db.collection('adminAuditEvents').doc(),
      buildAdminAuditEvent(
        {
          adminId: actor.uid,
          action: 'crownHunt.updatePoint',
          targetType: 'crownHuntPoint',
          targetId: input.pointId,
          reason:
            update.status === 'ended'
              ? 'Point updated; retired (collected out) as the cap is now at or below the collector count.'
              : 'Point updated.',
          details: { changedFields: Object.keys(update).filter((k) => k !== 'updatedAt') },
        },
        serverTimestamp,
      ),
    );
    return (update.status as CrownHuntPointStatus | undefined) ?? (existing.status as CrownHuntPointStatus);
  });

  return { pointId: input.pointId, status };
});

export const activatePoint = onCall(CALLABLE_OPTS, async (request): Promise<PointIdResponse> => {
  const actor = await requireAdminActor(request);

  const parsed = parseActivatePointInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { pointId, approvalNote } = parsed.input;
  const pointRef = db.collection('crownHuntPoints').doc(pointId);
  const serverTimestamp = () => FieldValue.serverTimestamp();

  await db.runTransaction(async (tx) => {
    const snap = await tx.get(pointRef);
    if (!snap.exists) {
      throw new HttpsError('not-found', 'Kronjakt point not found.');
    }
    if (snap.data()!.status === 'ended') {
      throw new HttpsError('failed-precondition', 'Ended points cannot be activated.');
    }
    tx.update(pointRef, {
      status: 'active',
      approvedAt: serverTimestamp(),
      approvedByUserId: actor.uid,
      updatedAt: serverTimestamp(),
    });
    tx.set(
      db.collection('adminAuditEvents').doc(),
      buildAdminAuditEvent(
        {
          adminId: actor.uid,
          action: 'crownHunt.activatePoint',
          targetType: 'crownHuntPoint',
          targetId: pointId,
          reason: approvalNote,
          details: { safeLocationConfirmed: true },
        },
        serverTimestamp,
      ),
    );
  });

  return { pointId, status: 'active' };
});

export const pausePoint = onCall(CALLABLE_OPTS, async (request): Promise<PointIdResponse> => {
  const actor = await requireAdminActor(request);

  const parsed = parsePausePointInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { pointId, reason } = parsed.input;
  const pointRef = db.collection('crownHuntPoints').doc(pointId);
  const serverTimestamp = () => FieldValue.serverTimestamp();

  await db.runTransaction(async (tx) => {
    const snap = await tx.get(pointRef);
    if (!snap.exists) {
      throw new HttpsError('not-found', 'Kronjakt point not found.');
    }
    if (snap.data()!.status === 'ended') {
      throw new HttpsError('failed-precondition', 'Ended points cannot be paused.');
    }
    tx.update(pointRef, { status: 'paused', updatedAt: serverTimestamp() });
    tx.set(
      db.collection('adminAuditEvents').doc(),
      buildAdminAuditEvent(
        {
          adminId: actor.uid,
          action: 'crownHunt.pausePoint',
          targetType: 'crownHuntPoint',
          targetId: pointId,
          reason: reason?.trim() || 'Point paused.',
        },
        serverTimestamp,
      ),
    );
  });

  return { pointId, status: 'paused' };
});

export const deletePoint = onCall(CALLABLE_OPTS, async (request): Promise<PointDeletedResponse> => {
  const actor = await requireAdminActor(request);

  const parsed = parseDeletePointInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { pointId, reason } = parsed.input;
  const pointRef = db.collection('crownHuntPoints').doc(pointId);
  const serverTimestamp = () => FieldValue.serverTimestamp();

  // Confirm the point exists (and read its prior status for the audit detail)
  // before touching anything.
  const snap = await pointRef.get();
  if (!snap.exists) {
    throw new HttpsError('not-found', 'Kronjakt point not found.');
  }
  const previousStatus = snap.data()!.status as CrownHuntPointStatus;

  // If the point is still claimable (active), first flip it to a non-claimable
  // status so submitClaim stops minting new collector markers, THEN drain, THEN
  // delete — deleteSpawnArea's "disable, drain, delete" ordering. Without this a
  // claim landing between the final drain query and pointRef.delete() would leave
  // a dangling crownHuntPointCollectors marker outliving the deleted point
  // (submitClaim only writes markers while the point reads status=='active').
  if (previousStatus === 'active') {
    await pointRef.update({ status: 'paused', updatedAt: serverTimestamp() });
  }

  // Remove the distinct-collector markers first, THEN the point doc — the same
  // "drain, then delete" ordering deleteSpawnArea uses so no dangling collector
  // state can outlive the point. Deleting the doc is what removes a live active
  // crown from the map (members read only status=='active').
  const removedCollectors = await deletePointCollectorMarkers(pointId);
  await pointRef.delete();

  await db
    .collection('adminAuditEvents')
    .doc()
    .set(
      buildAdminAuditEvent(
        {
          adminId: actor.uid,
          action: 'crownHunt.deletePoint',
          targetType: 'crownHuntPoint',
          targetId: pointId,
          reason: reason?.trim() || 'Point deleted.',
          details: { previousStatus, removedCollectors },
        },
        serverTimestamp,
      ),
    );

  return { pointId, deleted: true, removedCollectors };
});
