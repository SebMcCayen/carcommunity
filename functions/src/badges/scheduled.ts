/**
 * badges-evaluateBacklog — bounded self-healing sweep.
 *
 * The triggers in progressTriggers.ts are the primary award path and cover the
 * overwhelming majority of awards within seconds. This sweep exists for the
 * four cases a trigger cannot cover:
 *
 *  1. A trigger invocation that failed permanently (its errors are swallowed
 *     so a badge can never fail the member's actual action).
 *  2. Counters that already existed before this phase shipped — every member
 *     with a `badgeProgress` document from the Phase 9f attendance path starts
 *     with a real `completedEventsAttended` and has never been evaluated
 *     against Träffräv.
 *  3. A threshold or ladder being added/lowered later: existing members become
 *     eligible without any counter changing, so no trigger would ever fire.
 *  4. SNAPSHOT counters, which no increment can reconstruct. `vehiclesInGarage`
 *     is re-derived here (reconcileDerivedBadgeCounters) because its trigger
 *     fires only on a vehicle CREATE: a member whose garage was already full
 *     before the ladders shipped, and who therefore can never create another
 *     vehicle, would otherwise never earn a Samlare tier at all.
 *
 * WHAT IS *NOT* BACK-FILLED. Four ladders measure counters that this phase
 * introduces — Kronjägare, Vägfarare, Trogen and Konvojledare start from zero
 * at deploy and are earned from activity onwards; historic crowns, rides and
 * convoys are not replayed (there is no bounded way to do so, and re-scanning
 * them would double-count against the live triggers). Träffräv is retroactive
 * because its counter predates the ladders, and Samlare is retroactive because
 * it is re-derived above. This is a deliberate product decision, not an
 * oversight.
 *
 * REACH. The sweep walks `badgeProgress`, so it only sees members who have such
 * a document — but auth.recordLogin stamps `userLifecycle/{uid}.lastLoginAt` on
 * every app start, and onUserLifecycleWritten creates the member's
 * `badgeProgress` document from that. Every member who opens the app after
 * deploy is therefore in scope within one sweep cycle.
 *
 * COST IS BOUNDED AND INDEX-FREE. Each run walks `badgeProgress` in document-ID
 * order from a stored cursor, reading at most SWEEP_PAGE_SIZE documents and
 * evaluating each once, then saves the new cursor; the next run resumes there
 * and wraps to the beginning when the collection is exhausted. Ordering by
 * `FieldPath.documentId()` needs no composite index and — unlike ordering by a
 * timestamp field — cannot silently skip documents that lack the field. A
 * member with nothing new costs one `count()` aggregation, one `badgeProgress`
 * read and one batched `getAll` of the tiers they already hold — and a `getAll`
 * is BILLED PER DOCUMENT, so that is 1 + tiersHeld document reads, not two —
 * with NO writes. Cheap per member, but not constant per member.
 *
 * IDEMPOTENT: it calls exactly the same evaluateAndAwardBadgeTiers the
 * triggers do, so re-running it (or overlapping a trigger) awards nothing
 * twice — see the idempotency layers in tierAwards.ts.
 *
 * `runBadgeBacklogSweep(limit)` is exported for deterministic emulator tests.
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { FieldPath, FieldValue } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { evaluateAndAwardBadgeTiers, reconcileDerivedBadgeCounters } from './tierAwards';

/** Members evaluated per run. */
export const SWEEP_PAGE_SIZE = 200;

/** Backend-only cursor document (denied to every client in firestore.rules). */
const SWEEP_STATE_PATH = ['badgeSweepState', 'backlog'] as const;

export interface BadgeBacklogSweepResult {
  scanned: number;
  awarded: number;
  /** True when this run reached the end of the collection and wrapped. */
  wrapped: boolean;
}

export async function runBadgeBacklogSweep(
  limit: number = SWEEP_PAGE_SIZE,
): Promise<BadgeBacklogSweepResult> {
  const stateRef = db.collection(SWEEP_STATE_PATH[0]).doc(SWEEP_STATE_PATH[1]);
  const stateSnap = await stateRef.get();
  const storedCursor = stateSnap.data()?.lastUid;
  const cursor = typeof storedCursor === 'string' && storedCursor.length > 0 ? storedCursor : null;

  let query = db
    .collection('badgeProgress')
    .orderBy(FieldPath.documentId())
    .limit(limit);
  if (cursor) {
    query = query.startAfter(cursor);
  }
  const page = await query.get();

  let awarded = 0;
  for (const doc of page.docs) {
    try {
      // Snapshot-style counters first: `vehiclesInGarage` is re-derived rather
      // than accumulated, and its trigger only fires on a vehicle CREATE, so
      // this is the ONLY path by which a member whose garage predates the
      // ladders — or who sits at the vehicle cap and can never create another —
      // ever gets a Samlare tier. It writes nothing when already up to date.
      await reconcileDerivedBadgeCounters(doc.id);
      awarded += (await evaluateAndAwardBadgeTiers(doc.id)).length;
    } catch (error) {
      // One bad member must not abort the page; the cursor still advances so
      // the sweep makes progress, and the next pass retries them.
      logger.error('Badge backlog evaluation failed', { uid: doc.id, error: String(error) });
    }
  }

  // Fewer results than requested means the collection is exhausted — wrap so
  // the next run starts over rather than stalling at the end forever.
  const wrapped = page.size < limit;
  const nextCursor = wrapped ? null : (page.docs[page.docs.length - 1]?.id ?? null);
  await stateRef.set(
    { lastUid: nextCursor, updatedAt: FieldValue.serverTimestamp() },
    { merge: true },
  );

  logger.info('Badge backlog sweep complete', { scanned: page.size, awarded, wrapped });
  return { scanned: page.size, awarded, wrapped };
}

export const evaluateBacklog = onSchedule(
  {
    region: 'europe-west1',
    schedule: 'every 6 hours',
    timeZone: 'Europe/Stockholm',
    memory: '512MiB',
    timeoutSeconds: 540,
  },
  async () => {
    await runBadgeBacklogSweep();
  },
);
