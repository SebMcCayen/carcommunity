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
 * per run); the cursor is what lets the sweep step over still-running events
 * that stay published rather than re-reading them forever. Each closure
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

/** Candidate documents fetched per query round-trip. */
const PAGE_SIZE = 100;
/**
 * Upper bound of events closed per sweep — keeps a first-run backlog (every
 * historical published event) from pushing the run past its timeout. The
 * hourly sweep drains any remainder on later runs, oldest first.
 */
const MAX_CLOSURES_PER_RUN = 200;

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

/**
 * Runs one auto-close sweep against `now`. Returns the number of events
 * actually completed. Exported (rather than inlined in the schedule handler)
 * so emulator tests can drive it at a deterministic instant.
 */
export async function runEventAutoClose(now: Date): Promise<number> {
  const cutoff = autoCloseCandidateCutoff(now);
  let cursor: FirebaseFirestore.QueryDocumentSnapshot | undefined;
  let closed = 0;

  while (closed < MAX_CLOSURES_PER_RUN) {
    let query = db
      .collection('events')
      .where('status', '==', 'published')
      .where('startsAt', '<=', Timestamp.fromDate(cutoff))
      .orderBy('startsAt', 'asc')
      .limit(PAGE_SIZE);
    if (cursor) {
      query = query.startAfter(cursor);
    }

    const page = await query.get();
    if (page.empty) {
      break;
    }

    for (const doc of page.docs) {
      if (closed >= MAX_CLOSURES_PER_RUN) {
        break;
      }
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
        logger.info('Event auto-closed', { eventId: doc.id });
      }
    }

    if (page.size < PAGE_SIZE) {
      break;
    }
    cursor = page.docs[page.docs.length - 1];
  }

  if (closed > 0) {
    logger.info('Event auto-close sweep finished', { closed });
  }
  return closed;
}

export const autoClose = onSchedule(
  {
    region: 'europe-west1',
    timeZone: 'Europe/Stockholm',
    memory: '256MiB' as const,
    timeoutSeconds: 540,
    schedule: '0 * * * *',
  },
  async () => {
    await runEventAutoClose(new Date());
  },
);
