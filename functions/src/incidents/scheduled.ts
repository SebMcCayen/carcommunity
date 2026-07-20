/**
 * Incidents TTL sweep (navigation feature).
 *
 * incidents-cleanupExpired (every 15 minutes): deletes incidents whose
 * `expiresAt` has passed — and their `confirmations` sub-collection with them
 * (recursiveDelete). Short-lived crowd-sourced markers must
 * disappear promptly; the read rule already hides expired docs (status +
 * `expiresAt > request.time`), and this sweep reclaims them.
 *
 * BOUNDED, PAGED + OLDEST-FIRST: expired docs are walked in `expiresAt` order,
 * a page at a time, with at most DELETE_CONCURRENCY recursiveDeletes in flight
 * and at most MAX_DELETIONS_PER_RUN removed per run — the same shape as the
 * events-autoClose sweep (events/scheduled.ts).
 *
 * runIncidentsCleanup is exported so emulator tests can drive it
 * deterministically.
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';

/** Expired documents fetched per query round-trip. */
const CLEANUP_BATCH_SIZE = 400;

/**
 * Upper bound on recursiveDeletes IN FLIGHT at once.
 *
 * `db.recursiveDelete(ref)` with no writer argument constructs its OWN
 * BulkWriter, and each BulkWriter ramps its own throughput independently under
 * the 500/50/5 rule. N concurrent recursiveDeletes are therefore N independent
 * rate limiters that know nothing about each other: the previous `Promise.all`
 * over a whole page could put CLEANUP_BATCH_SIZE (400) of them in flight, each
 * also holding an open descendant-query stream. Contention and memory scaled
 * with the page size rather than with anything the sweep chose.
 *
 * Cost of ONE recursiveDelete here: the incident document plus its
 * `confirmations/{uid}` ledger — one doc per member who confirmed, which on a
 * busy road is tens, not thousands. Call it ~1..50 deletes per call. 10 in
 * flight is then a few hundred writes outstanding at the peak, the same order
 * as a SINGLE BulkWriter's own steady state (the regime the client library is
 * tuned for), and 10 concurrent streams instead of 400. The bound comes from
 * that budget (10 × ~50 ≈ 500 ≈ one BulkWriter), not from roundness. It is
 * deliberately not raised to "use the whole page": throughput is not the
 * constraint here (see MAX_DELETIONS_PER_RUN).
 */
const DELETE_CONCURRENCY = 10;

/**
 * Upper bound on incidents deleted per sweep — keeps a backlog (the first run
 * after an outage, or a busy weekend of short-TTL markers) from pushing the run
 * past its 120s timeout.
 *
 * WHY IT CANNOT STARVE AN OLD INCIDENT. The candidate query is
 * `expiresAt <= now` ordered by `expiresAt` ASC, so a capped run always drains
 * the OLDEST expired incidents first and the next run resumes behind them.
 * Unlike the events sweep, every candidate here is due by construction — there
 * is no in-memory "matched but not actually due" skip — so nothing can sort
 * ahead of an older incident without being deleted too. A backlog therefore
 * drains strictly monotonically; the cap only makes it take more 15-minute
 * runs. At 2000 per run the sweep clears 8000/hour, far above any plausible
 * report rate for a single-town community.
 */
const MAX_DELETIONS_PER_RUN = 2000;

export interface IncidentsCleanupResult {
  deletedCount: number;
  /**
   * Peak number of recursiveDeletes in flight simultaneously. Reported rather
   * than kept internal because it is the ONLY way to observe the concurrency
   * bound: a sweep that fires every delete in one unbounded `Promise.all` is
   * indistinguishable from a bounded one by `deletedCount` alone.
   */
  peakConcurrency: number;
  /** True when the run stopped on the deletion cap with work still remaining. */
  capped: boolean;
}

export interface IncidentsCleanupLimits {
  maxDeletions: number;
  concurrency: number;
}

/**
 * Runs one TTL sweep against `now`.
 *
 * `limits` exists so the bounds can be exercised at a scale a test can seed —
 * the scheduled entry point never passes it, so production always runs on the
 * constants above.
 */
export async function runIncidentsCleanup(
  now: Date,
  limits: IncidentsCleanupLimits = {
    maxDeletions: MAX_DELETIONS_PER_RUN,
    concurrency: DELETE_CONCURRENCY,
  },
): Promise<IncidentsCleanupResult> {
  const concurrency = Math.max(1, limits.concurrency);
  const cutoff = Timestamp.fromDate(now);
  let deletedCount = 0;
  let inFlight = 0;
  let peakConcurrency = 0;
  let capped = false;

  // recursiveDelete, NOT a batched `delete` of the doc: deleting a Firestore
  // document does not touch its sub-collections, so a plain batch delete would
  // leave every `confirmations/{uid}` doc behind as an unreachable orphan that
  // nothing ever collects.
  const deleteOne = async (ref: FirebaseFirestore.DocumentReference): Promise<void> => {
    inFlight += 1;
    peakConcurrency = Math.max(peakConcurrency, inFlight);
    try {
      await db.recursiveDelete(ref);
    } finally {
      inFlight -= 1;
    }
  };

  for (;;) {
    const remaining = limits.maxDeletions - deletedCount;
    if (remaining <= 0) {
      // Stopped on the cap. Only report `capped` when work actually remains, so
      // a run that lands exactly on the bound is not misreported as truncated.
      const more = await db
        .collection('incidents')
        .where('expiresAt', '<=', cutoff)
        .orderBy('expiresAt', 'asc')
        .limit(1)
        .get();
      capped = !more.empty;
      break;
    }

    // The page is capped by BOTH the query batch size and whatever the run has
    // left in its deletion budget, so the last page may be short by design.
    const pageLimit = Math.min(CLEANUP_BATCH_SIZE, remaining);
    const expired = await db
      .collection('incidents')
      .where('expiresAt', '<=', cutoff)
      .orderBy('expiresAt', 'asc')
      .limit(pageLimit)
      .get();
    if (expired.empty) {
      break;
    }

    // Chunked, NOT one Promise.all over the whole page — see DELETE_CONCURRENCY.
    for (let i = 0; i < expired.docs.length; i += concurrency) {
      await Promise.all(expired.docs.slice(i, i + concurrency).map((doc) => deleteOne(doc.ref)));
    }
    deletedCount += expired.size;

    // Compare against `pageLimit`, NOT CLEANUP_BATCH_SIZE: a full page that was
    // short only because the deletion budget truncated it means there is
    // probably MORE work, and must fall through to the cap check at the top of
    // the loop rather than exiting as if the collection were drained.
    if (expired.size < pageLimit) {
      break;
    }
  }

  logger.info('Incidents cleanup complete', { deletedCount, peakConcurrency, capped });
  return { deletedCount, peakConcurrency, capped };
}

/** 15-minute TTL sweep. */
export const cleanupExpired = onSchedule(
  {
    region: 'europe-west1',
    timeZone: 'Europe/Stockholm',
    memory: '256MiB' as const,
    timeoutSeconds: 120,
    schedule: '*/15 * * * *',
  },
  async () => {
    await runIncidentsCleanup(new Date());
  },
);
