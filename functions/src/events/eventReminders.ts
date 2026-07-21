/**
 * events-remindUpcoming — the scheduled RSVP reminder sweep
 * (contracts/functions/functions.json; grouped export → `events-remindUpcoming`).
 *
 * Follows the onSchedule conventions of the other scheduled sweeps
 * (events-autoClose, notifications-cleanupExpired, communityChat-digest):
 * europe-west1, Europe/Stockholm, `runEventReminders(now)` exported so emulator
 * tests can drive it deterministically. The per-event decision is the pure,
 * hard-unit-tested logic in eventReminders-core.ts; this file is only the I/O
 * around it.
 *
 * WHAT IT DOES. Every 15 minutes it finds published events starting within the
 * next EVENT_REMINDER_LEAD_MS (2h) that have not yet been reminded, and writes
 * one `event_reminder` in-app notification to each member who RSVP'd `going`.
 * Push follows automatically for every delivered item via the
 * notifications-onNotificationCreated trigger (PR #496).
 *
 * COST BOUND. The candidate query filters
 * `status == 'published' AND startsAt > now AND startsAt <= now + lead`, ordered
 * by startsAt — served by the EXISTING `status ASC, startsAt ASC` composite
 * index (firebase/firestore.indexes.json), the same index the auto-close sweep
 * reuses. Equality on status + a range on startsAt + ordering by startsAt is
 * exactly that index's shape, so NO new index is required. Each run therefore
 * reads only the events in a 2h window (near-zero on most ticks for a single-town
 * calendar), plus the `going` RSVP subcollection ONLY for events not already
 * reminded. Already-reminded events cost ~one already-in-hand read (their marker
 * is on the query result). Paged + capped like the auto-close sweep.
 *
 * IDEMPOTENCY. Primary: a per-event marker events/{eventId}.eventReminderSentAt,
 * CLAIMED in a transaction (claimEventReminder) that re-derives decideEventReminder
 * against the freshly-read document BEFORE any fan-out — so exactly one run ever
 * fans an event out, and a start-time edit between the query and the claim is
 * honoured (the transaction, not the stale query row, decides). Secondary: a
 * deterministic per-event notificationId collapses any re-fan-out into one inbox
 * item per recipient. See eventReminders-core.ts for the full argument, including
 * why an edited start time never double-reminds.
 *
 * ORDERING / FAILURE MODE. The marker is claimed BEFORE the fan-out (same idiom
 * as the auto-close sweep's closeEvent and the community digest's marker). A crash
 * between the claim and completing the fan-out therefore costs at most a MISSED
 * reminder for the not-yet-processed attendees — never a duplicate. For a
 * low-value "starts soon" nudge a missed reminder is invisible, whereas a
 * duplicate is precisely the notification fatigue this sweep must not create.
 *
 * OPT-OUT / BLOCKING. Opt-out, suspended and deleted eligibility is OWNED by
 * writeInAppNotification (decideInAppDelivery) — never re-checked here. Blocking
 * is irrelevant: a reminder is about an event the recipient chose to attend, not
 * member-to-member social activity, so there is no blocking interaction to honour.
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { FieldPath, FieldValue, Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { writeInAppNotification } from '../notifications/deliver';
import {
  EVENT_REMINDER_LEAD_MS,
  EVENT_REMINDER_TITLE,
  type EventReminderLimits,
  decideEventReminder,
  eventReminderNotificationId,
  eventReminderPreview,
  normalizeEventReminderLimits,
  reminderWindowEnd,
  reminderWindowStart,
} from './eventReminders-core';

/** Candidate events fetched per query round-trip. */
const PAGE_SIZE = 100;

/**
 * Upper bound of candidate events read per sweep, independent of how many turn
 * out to be due. Far above any single-town 2h window; a safety valve, not an
 * expected bound. There is no persisted cursor — a run always restarts from the
 * oldest candidate — so a cap that consistently binds would defer the newest
 * candidates; not a real regime here (a 2h window on a town calendar holds a
 * handful of events). If it ever binds, the fix is a persisted cursor exactly
 * like account-cleanupInactive's.
 */
const MAX_CANDIDATES_PER_RUN = 5_000;

/**
 * `going`-RSVP attendees notified CONCURRENTLY per event. Each
 * writeInAppNotification is its own transaction against a distinct per-user
 * inbox, so the fan-out is parallelisable — but NOT all at once: a large going
 * list would otherwise open one transaction per attendee simultaneously. Chunked
 * exactly like the auto-close sweep's ATTENDANCE_CREDIT_CONCURRENCY and the
 * community digest's MEMBER_CONCURRENCY: bounded concurrency, still parallel
 * enough to stay well inside the timeout. Individual failures log per attendee
 * and never propagate.
 */
const FANOUT_CONCURRENCY = 15;

function eventRef(eventId: string) {
  return db.collection('events').doc(eventId);
}

export interface EventReminderSummary {
  /** Candidate events examined this run (the paged 2h window). */
  candidates: number;
  /** Events skipped in memory because their marker was already set. */
  alreadyReminded: number;
  /** Events this run CLAIMED and fanned out (one reminder set delivered each). */
  remindedEvents: number;
  /**
   * Events that passed the in-memory pre-filter but whose transactional claim
   * declined (a concurrent claim, cancel/complete, or a start-time edit moved
   * them out of the window between the query and the claim).
   */
  claimSkipped: number;
  /** In-app notifications actually written across all reminded events. */
  notificationsDelivered: number;
  /**
   * Recipients writeInAppNotification declined (opted out / suspended / deleted /
   * a deterministic-id duplicate) — folded across events for observability.
   */
  notificationsSkipped: number;
  /** True when MAX_CANDIDATES_PER_RUN bound the scan (see its note). */
  capped: boolean;
}

/**
 * Injectable I/O — production uses the real notification writer; a test can pass
 * a stub. Same test-seam intent as `limits`; the scheduled entry point never
 * passes it.
 */
export interface EventReminderDeps {
  deliver: typeof writeInAppNotification;
}

/**
 * Claims an event for reminding: sets events/{eventId}.eventReminderSentAt iff
 * the freshly-read document is STILL due (decideEventReminder), so a start-time
 * edit or a status change racing the query is honoured rather than acted on from
 * the stale query row. Returns true only when THIS call set the marker — exactly
 * one caller ever does, which is what bounds the fan-out to once per event.
 *
 * Exported for tests: the re-derivation inside the transaction only matters when
 * the document changes between the candidate query and this claim, which a
 * sweep-level test cannot stage (its query would simply not return the event).
 */
export async function claimEventReminder(
  eventId: string,
  now: Date,
  leadMs: number,
): Promise<boolean> {
  return db.runTransaction(async (tx) => {
    const snap = await tx.get(eventRef(eventId));
    if (!snap.exists) {
      return false;
    }
    const startsAt = snap.get('startsAt');
    if (!(startsAt instanceof Timestamp)) {
      return false;
    }
    const decision = decideEventReminder({
      status: String(snap.get('status')),
      startsAtMs: startsAt.toMillis(),
      reminderAlreadySent: snap.get('eventReminderSentAt') != null,
      nowMs: now.getTime(),
      leadMs,
    });
    if (!decision.remind) {
      return false;
    }
    tx.update(eventRef(eventId), { eventReminderSentAt: FieldValue.serverTimestamp() });
    return true;
  });
}

/** Notifies every `going`-RSVP attendee of one just-claimed event. */
async function fanOutEventReminder(
  eventId: string,
  eventTitle: string,
  concurrency: number,
  deps: EventReminderDeps,
): Promise<{ delivered: number; skipped: number }> {
  const goingRsvps = await eventRef(eventId)
    .collection('rsvps')
    .where('status', '==', 'going')
    .get();

  const previewText = eventReminderPreview(eventTitle);
  const notificationId = eventReminderNotificationId(eventId);
  let delivered = 0;
  let skipped = 0;

  for (let i = 0; i < goingRsvps.docs.length; i += concurrency) {
    const chunk = goingRsvps.docs.slice(i, i + concurrency);
    const results = await Promise.allSettled(
      chunk.map((rsvp) =>
        deps.deliver(
          rsvp.id,
          {
            category: 'event_reminder',
            title: EVENT_REMINDER_TITLE,
            previewText,
            actionType: 'open_event',
            relatedEntityId: eventId,
          },
          notificationId,
        ),
      ),
    );
    results.forEach((result, index) => {
      if (result.status === 'fulfilled') {
        if (result.value.delivered) {
          delivered += 1;
        } else {
          skipped += 1;
        }
      } else {
        // A per-attendee delivery failure must not abort the event's fan-out or
        // the sweep; the marker is already claimed, so this attendee simply
        // misses the nudge (the accepted failure mode, see the file header).
        logger.error('Event reminder delivery failed for attendee', {
          eventId,
          uid: chunk[index]?.id,
          error: String(result.reason),
        });
      }
    });
  }

  return { delivered, skipped };
}

/**
 * Runs one reminder sweep against `now`.
 *
 * `limits` exists so a test can seed a small scale and exercise the bounds; the
 * scheduled entry point never passes it, so production runs on the constants
 * above.
 */
export async function runEventReminders(
  now: Date,
  limits: EventReminderLimits = {
    leadMs: EVENT_REMINDER_LEAD_MS,
    pageSize: PAGE_SIZE,
    maxCandidates: MAX_CANDIDATES_PER_RUN,
    concurrency: FANOUT_CONCURRENCY,
  },
  deps: EventReminderDeps = { deliver: writeInAppNotification },
): Promise<EventReminderSummary> {
  const summary: EventReminderSummary = {
    candidates: 0,
    alreadyReminded: 0,
    remindedEvents: 0,
    claimSkipped: 0,
    notificationsDelivered: 0,
    notificationsSkipped: 0,
    capped: false,
  };

  const { leadMs, pageSize, maxCandidates, concurrency } = normalizeEventReminderLimits(limits);
  const windowStart = Timestamp.fromDate(reminderWindowStart(now));
  const windowEnd = Timestamp.fromDate(reminderWindowEnd(now, leadMs));

  // Cursor carries BOTH the ordering value and the doc-id tiebreaker: several
  // events can share an identical startsAt, so a single-field startAfter could
  // skip or repeat them at a page boundary (same idiom as the digest / inactivity
  // sweeps).
  let cursor: { startsAt: Timestamp; id: string } | null = null;

  for (;;) {
    if (summary.candidates >= maxCandidates) {
      summary.capped = true;
      break;
    }
    let query = db
      .collection('events')
      .where('status', '==', 'published')
      .where('startsAt', '>', windowStart)
      .where('startsAt', '<=', windowEnd)
      .orderBy('startsAt', 'asc')
      .orderBy(FieldPath.documentId())
      .limit(Math.min(pageSize, maxCandidates - summary.candidates));
    if (cursor !== null) {
      query = query.startAfter(cursor.startsAt, cursor.id);
    }
    const page = await query.get();
    if (page.empty) {
      break;
    }
    summary.candidates += page.docs.length;

    for (const doc of page.docs) {
      const startsAt = doc.get('startsAt');
      const preDecision = decideEventReminder({
        status: String(doc.get('status')),
        startsAtMs: startsAt instanceof Timestamp ? startsAt.toMillis() : 0,
        reminderAlreadySent: doc.get('eventReminderSentAt') != null,
        nowMs: now.getTime(),
        leadMs,
      });
      // Cheap in-memory pre-filter on the query row: an event already reminded
      // (marker set) is skipped WITHOUT opening a claim transaction. The query
      // already constrained status + the window, so the only pre-filter that
      // bites here is `already_sent`; the authoritative re-check is in
      // claimEventReminder.
      if (!preDecision.remind) {
        if (preDecision.reason === 'already_sent') {
          summary.alreadyReminded += 1;
        }
        continue;
      }

      const claimed = await claimEventReminder(doc.id, now, leadMs);
      if (!claimed) {
        summary.claimSkipped += 1;
        continue;
      }

      const { delivered, skipped } = await fanOutEventReminder(
        doc.id,
        String(doc.get('title') ?? ''),
        concurrency,
        deps,
      );
      summary.remindedEvents += 1;
      summary.notificationsDelivered += delivered;
      summary.notificationsSkipped += skipped;
    }

    if (page.size < pageSize) {
      break;
    }
    const lastDoc = page.docs[page.docs.length - 1]!;
    cursor = { startsAt: lastDoc.get('startsAt') as Timestamp, id: lastDoc.id };
  }

  if (summary.capped) {
    logger.warn('Event reminder candidate cap reached; later candidates not examined this run', {
      maxCandidates,
    });
  }
  logger.info('Event reminder sweep complete', { ...summary });
  return summary;
}

/** Reminder sweep every 15 minutes (Europe/Stockholm). */
export const remindUpcoming = onSchedule(
  {
    region: 'europe-west1',
    timeZone: 'Europe/Stockholm',
    memory: '256MiB' as const,
    timeoutSeconds: 300,
    schedule: '*/15 * * * *',
  },
  async () => {
    await runEventReminders(new Date());
  },
);
