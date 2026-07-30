/**
 * Event auto-close sweep.
 *
 * events-autoClose (hourly, Europe/Stockholm) completes every published event
 * whose effective end is more than AUTO_CLOSE_GRACE_MS in the past, so a
 * finished event leaves the upcoming list on its own instead of sitting there
 * until an admin clicks "complete" (which, in practice, nobody ever did — a
 * 9 August event was still listed as upcoming in mid-July of the next year).
 *
 * The USER-VISIBLE outcome matches the events.complete callable: published →
 * completed, with going-RSVP attendees credited badge attendance through the
 * shared creditEventAttendance. `completed` is terminal, and rules gate the
 * event's chat, member-gated detail and group-drive roster on `published`, so
 * completing an event closes it in the full sense.
 *
 * Two deliberate differences from the callable, both because there is no actor:
 * - No adminAuditEvents record. That log is a record of ADMIN actions, and an
 *   unattended sweep is not one; `autoClosedAt` below is the trace instead.
 * - `autoClosedAt` is stamped (the callable never sets it), so an operator can
 *   always tell an auto-close from a hand-completed event.
 *
 * WHY NO NEW COMPOSITE INDEX: the candidate query filters
 * `status == 'published' AND startsAt <= autoCloseCandidateCutoff(now)`,
 * ordered by startsAt — served by the EXISTING `status ASC, startsAt ASC`
 * index (firebase/firestore.indexes.json). Because guardEventTimes enforces
 * `end > start` on every write path, no due event can have a start after that
 * cutoff (see autoCloseCandidateCutoff), so the coarse startsAt bound never
 * hides one; the precise `endsAt`-based test (isAutoCloseDue) is then applied
 * in memory to each candidate. A `status, endsAt` index would have been the
 * obvious shape but would need a hand-deploy no CI workflow performs.
 *
 * PAGED + IDEMPOTENT: candidates are walked in startsAt order with a document
 * cursor (a page of PAGE_SIZE at a time, at most MAX_CLOSURES_PER_RUN closed
 * and MAX_CANDIDATES_SCANNED_PER_RUN read per run — the latter bounds cost even
 * when candidates match but are skipped); the cursor is what lets the sweep step
 * over still-running events that stay published rather than re-reading them
 * forever. Each closure
 * re-checks `status == 'published'` inside its transaction, so a concurrent
 * events.complete / events.cancel wins and the sweep neither double-closes nor
 * resurrects a cancelled event. Re-running the sweep is always safe: closed
 * events no longer match the query.
 *
 * runEventAutoClose is exported for deterministic emulator tests.
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { autoCloseCandidateCutoff, isAutoCloseDue, type EventStatus } from './events-core';
import { creditEventAttendance } from './eventLifecycle';
import { MAX_INSTANCES_SCHEDULED } from '../shared/instanceLimits';
import { withServerErrorReporting } from '../errors/serverErrors';

/** Candidate documents fetched per query round-trip. */
const PAGE_SIZE = 100;
/**
 * Upper bound of events closed per sweep — keeps a first-run backlog (every
 * historical published event) from pushing the run past its timeout. The
 * hourly sweep drains any remainder on later runs, oldest first.
 */
const MAX_CLOSURES_PER_RUN = 200;
/**
 * Upper bound of candidate documents READ per sweep, independent of how many
 * turn out to be due. Without it the only bound on reads is MAX_CLOSURES_PER_RUN,
 * which bounds nothing when candidates are matched but skipped: the sweep would
 * page through every published-and-started event each hour to close none.
 *
 * WHY IT CANNOT STARVE A DUE EVENT. Candidates are read in `startsAt` order, so
 * the question is how many not-yet-due events can sort AHEAD of a due one. A
 * candidate is skipped only while it is still inside its own end+grace window,
 * and guardEventTimes caps an event at MAX_EVENT_DURATION_MS (3 days) — so every
 * skipped candidate must have started within roughly (3 days + grace) of `now`.
 * Reaching this cap therefore needs >2000 events whose starts fall in a ~3-day
 * window, and it would have to hold every hour to defer a due event
 * indefinitely. For a single-town community calendar that is far outside any
 * real regime; the cap exists so an unexpected one degrades into "closes a bit
 * later" instead of an unbounded hourly scan.
 */
const MAX_CANDIDATES_SCANNED_PER_RUN = 2000;

interface StoredEventTimes {
  status: EventStatus;
  startsAt: Timestamp;
  endsAt: Timestamp | null;
}

/**
 * Closes one event if it is still published. Returns true when this call
 * performed the transition — false when someone else already moved it out of
 * `published` (concurrent complete/cancel), which is not an error.
 *
 * Exported for tests: the status re-check below only matters when the status
 * changes between the candidate query and this transaction, which a sweep-level
 * test cannot stage (its query would simply not return the event).
 */
export async function closeEvent(eventId: string): Promise<boolean> {
  const eventRef = db.collection('events').doc(eventId);
  return db.runTransaction(async (tx) => {
    const snap = await tx.get(eventRef);
    if (!snap.exists) {
      return false;
    }
    // Re-read inside the transaction: the status may have changed between the
    // candidate query and now. This is the idempotency guard — it also makes a
    // second sweep over the same event a no-op.
    if ((snap.data() as StoredEventTimes).status !== 'published') {
      return false;
    }
    tx.update(eventRef, {
      status: 'completed',
      autoClosedAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp(),
    });
    return true;
  });
}

/** Per-run bounds. Defaults are the production values; tests may shrink them. */
export interface AutoCloseLimits {
  /** Max events completed in one run. */
  maxClosures: number;
  /** Max candidate documents read in one run, due or not. */
  maxCandidatesScanned: number;
}

/** What one sweep did. `scanned` counts candidates READ, due or not. */
export interface AutoCloseResult {
  closed: number;
  scanned: number;
}

/**
 * Runs one auto-close sweep against `now`. Exported (rather than inlined in the
 * schedule handler) so emulator tests can drive it at a deterministic instant.
 *
 * Returns both counts: `closed` is the outcome, `scanned` is the work done to
 * get there. `scanned` is reported rather than kept internal because it is the
 * only way to observe the read bound — a run that scans many and closes none is
 * indistinguishable from a cheap one by its outcome alone.
 *
 * `limits` exists so the bounds can be exercised at a scale a test can seed —
 * the scheduled entry point never passes it, so production always runs on the
 * constants above.
 */
export async function runEventAutoClose(
  now: Date,
  limits: AutoCloseLimits = {
    maxClosures: MAX_CLOSURES_PER_RUN,
    maxCandidatesScanned: MAX_CANDIDATES_SCANNED_PER_RUN,
  },
): Promise<AutoCloseResult> {
  const cutoff = autoCloseCandidateCutoff(now);
  let cursor: FirebaseFirestore.QueryDocumentSnapshot | undefined;
  let closed = 0;
  let scanned = 0;

  while (closed < limits.maxClosures && scanned < limits.maxCandidatesScanned) {
    // Never fetch more than the remaining scan budget allows.
    const pageLimit = Math.min(PAGE_SIZE, limits.maxCandidatesScanned - scanned);
    let query = db
      .collection('events')
      .where('status', '==', 'published')
      .where('startsAt', '<=', Timestamp.fromDate(cutoff))
      .orderBy('startsAt', 'asc')
      .limit(pageLimit);
    if (cursor) {
      query = query.startAfter(cursor);
    }

    const page = await query.get();
    if (page.empty) {
      break;
    }

    for (const doc of page.docs) {
      if (closed >= limits.maxClosures || scanned >= limits.maxCandidatesScanned) {
        break;
      }
      scanned += 1;
      const event = doc.data() as StoredEventTimes;
      // The query's startsAt bound is a sound but coarse filter; the real
      // decision is the event's effective end + grace.
      if (
        !isAutoCloseDue(
          {
            status: event.status,
            startsAt: event.startsAt.toDate(),
            endsAt: event.endsAt?.toDate() ?? null,
          },
          now,
        )
      ) {
        continue;
      }
      if (await closeEvent(doc.id)) {
        closed += 1;
        // Same attendance credit the events.complete callable gives, so a
        // badge never depends on who (or what) ended the event.
        await creditEventAttendance(doc.id);
      }
    }

    // A short page means the query is exhausted — compare against the limit
    // ACTUALLY used, not PAGE_SIZE, which the scan budget may have shrunk.
    if (page.size < pageLimit) {
      break;
    }
    cursor = page.docs[page.docs.length - 1];
  }

  // Per-RUN summary only — no per-event line. Every other scheduled sweep in
  // this codebase logs exactly one summary (account-purgeDeleted,
  // notifications-cleanupExpired, partnerInsights-*, incidents-*), and a
  // backlog drain would otherwise emit up to MAX_CLOSURES_PER_RUN log lines an
  // hour. The durable per-event trace is `autoClosedAt` on the document itself.
  // `scanned` is included so a sweep that reads a lot and closes little (the
  // MAX_CANDIDATES_SCANNED_PER_RUN regime) is visible without a code change.
  if (closed > 0 || scanned >= limits.maxCandidatesScanned) {
    logger.info('Event auto-close sweep complete', { closed, scanned });
  }
  return { closed, scanned };
}

export const autoClose = onSchedule(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_SCHEDULED,
    timeZone: 'Europe/Stockholm',
    memory: '256MiB' as const,
    timeoutSeconds: 540,
    schedule: '0 * * * *',
  },
  withServerErrorReporting('events.autoClose', async () => {
    await runEventAutoClose(new Date());
  }),
);
