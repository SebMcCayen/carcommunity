/**
 * billboards.sweepVisibility — the scheduled owner of `mapVisible`.
 *
 * Follows the onSchedule conventions of the other scheduled sweeps
 * (events/scheduled.ts, notifications/scheduled.ts): europe-west1,
 * Europe/Stockholm, the scheduled instance tier, one summary log per run, and
 * a pure core the unit tests drive with an injected clock.
 *
 * ## What this exists to do
 *
 * `billboards/{id}.mapVisible` is the single field the member map queries on
 * and the security rule reads — see `BILLBOARD_MAP_VISIBLE_FIELD` in
 * billboards-core.ts for why the availability window is resolved to a boolean
 * instead of being compared inside the rule.
 *
 * The lifecycle callables already keep that field in step with everything an
 * ADMIN does, transactionally: activating computes it, pausing and ending clear
 * it. What no callable can cover is the CLOCK — `availableFrom` arriving, or
 * `availableUntil` passing, while nobody is touching the record. That is this
 * sweep's entire job, and it is the reason "if it isn't activated it shouldn't
 * be shown" holds for scheduled billboards without any client cooperation.
 *
 * ## Two passes, because there are two ways to be wrong
 *
 * 1. **Actives** — every `status == 'active'` billboard has its `mapVisible`
 *    recomputed from its window. This turns a scheduled billboard on when its
 *    window opens and off when it expires.
 * 2. **Repair** — every `mapVisible == true` billboard that is NOT active has
 *    the flag cleared. Nothing should ever produce that state (the callables
 *    write both fields in one transaction), so this pass normally does nothing
 *    at all. It exists because the read rule requires `status == 'active'` AND
 *    `mapVisible == true`: a document in that impossible state would make the
 *    map's list query fail for EVERY member rather than merely mis-drawing one
 *    marker, and a sweep that repairs it within one interval is much better
 *    than a support call. It also picks up documents written before this field
 *    existed, so no manual backfill is needed.
 *
 * Both passes write only where the stored value actually differs, so a steady
 * state costs the reads and no writes — and, because a no-op write would still
 * push a snapshot delta to every listening client, "only on change" also keeps
 * the sweep off members' devices.
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { logger } from 'firebase-functions';
import type { Query } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { withServerErrorReporting } from '../errors/serverErrors';
import { MAX_INSTANCES_SCHEDULED } from '../shared/instanceLimits';
import { decideVisibility, type VisibilityChange } from './billboards-core';

/**
 * How many documents one pass will scan.
 *
 * Billboards are an admin-curated set — every one of them is a human decision
 * that went through a six-point safety gate — so the live population is
 * realistically dozens, not thousands. This is a runaway guard, not a paging
 * budget: exceeding it means something is very wrong (a bulk import, a runaway
 * script), and the sweep logs that rather than scanning without bound.
 */
export const SWEEP_SCAN_LIMIT = 500;

/** Firestore `Timestamp | Date | null | undefined` → `Date | null`. */
function toDateOrNull(value: unknown): Date | null {
  if (value == null) return null;
  if (value instanceof Date) return value;
  const maybeTimestamp = value as { toDate?: () => Date };
  if (typeof maybeTimestamp.toDate === 'function') {
    try {
      return maybeTimestamp.toDate();
    } catch {
      return null;
    }
  }
  if (typeof value === 'number') return new Date(value);
  return null;
}

async function sweepQuery(
  query: Query,
  now: Date,
): Promise<{ scanned: number; changes: VisibilityChange[] }> {
  const snapshot = await query.limit(SWEEP_SCAN_LIMIT).get();
  const changes: VisibilityChange[] = [];
  for (const doc of snapshot.docs) {
    const data = doc.data();
    const change = decideVisibility(
      doc.id,
      data.status,
      toDateOrNull(data.availableFrom),
      toDateOrNull(data.availableUntil),
      data.mapVisible,
      now,
    );
    if (change != null) changes.push(change);
  }
  return { scanned: snapshot.size, changes };
}

/**
 * The sweep body, clock-injected. Returns its counters so the emulator test can
 * assert them without reading logs.
 */
export async function runBillboardVisibilitySweep(now: Date): Promise<{
  scanned: number;
  updated: number;
  repaired: number;
}> {
  const collection = db.collection('billboards');

  const actives = await sweepQuery(collection.where('status', '==', 'active'), now);

  // Anything flagged visible that is NOT active. Firestore has no `!=` that
  // composes usefully with another equality here, so the pass fetches all
  // visible documents and filters in memory — a set bounded by the same
  // curation argument as the actives pass, and normally identical to it.
  const visible = await collection
    .where('mapVisible', '==', true)
    .limit(SWEEP_SCAN_LIMIT)
    .get();
  const repairs: VisibilityChange[] = visible.docs
    .filter((doc) => doc.data().status !== 'active')
    .map((doc) => ({ id: doc.id, mapVisible: false }));

  const changes = [...actives.changes, ...repairs];
  if (changes.length > 0) {
    const batch = db.batch();
    for (const change of changes) {
      batch.update(collection.doc(change.id), { mapVisible: change.mapVisible });
    }
    await batch.commit();
  }

  const scanned = actives.scanned + visible.size;
  // One summary per run, like every other sweep here — and only when something
  // happened or the scan budget was hit, so a quiet hour is silent.
  if (changes.length > 0 || scanned >= SWEEP_SCAN_LIMIT) {
    logger.info('Billboard visibility sweep complete', {
      scanned,
      updated: actives.changes.length,
      repaired: repairs.length,
    });
  }
  return { scanned, updated: actives.changes.length, repaired: repairs.length };
}

/**
 * Every ten minutes.
 *
 * The interval is the WORST-CASE lag between an availability window opening or
 * closing and the map agreeing, so it is a product decision, not a cost one:
 * ten minutes is small against a sponsorship window measured in days, and 144
 * runs a day of a query-two-collections-and-usually-write-nothing function is
 * negligible next to the callables. Admin actions do not wait for it at all —
 * activate/pause/end write `mapVisible` themselves.
 */
export const sweepVisibility = onSchedule(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_SCHEDULED,
    timeZone: 'Europe/Stockholm',
    memory: '256MiB' as const,
    timeoutSeconds: 120,
    schedule: '*/10 * * * *',
  },
  withServerErrorReporting('billboards.sweepVisibility', async () => {
    await runBillboardVisibilitySweep(new Date());
  }),
);
