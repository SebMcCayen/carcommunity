/**
 * events-onEventCancelled — Firestore trigger notifying every going-RSVP
 * attendee the moment an event is cancelled (owner request: when an event is
 * cancelled, tell the people who said they were going).
 *
 * HOOK — the cancelled TRANSITION on events/{eventId}, not "on any write".
 * ----------------------------------------------------------------------
 * onDocumentWritten covers every write to the teaser doc;
 * [isEventCancelledTransition] fires the fan-out only when the write moved
 * status INTO `cancelled` (before != 'cancelled' && after == 'cancelled'). See
 * eventCancelledNotification-core.ts for why the transition — not the write — is
 * the correct single hook, and why every other write (edit, rsvpCounts bump,
 * publish, complete) fires nothing. Edits are deliberately SILENT: only a
 * cancellation notifies.
 *
 * AUDIENCE — the going-RSVP attendees, minus the event's own creator.
 * ------------------------------------------------------------------
 * Only members who RSVP'd `going` (events/{eventId}/rsvps where status ==
 * 'going') — the people whose plans the cancellation actually changes — bar the
 * creator (they took the action / are the organiser). This mirrors the RSVP
 * reminder sweep's audience (fanOutEventReminder), NOT the community-wide
 * event_created broadcast. Per-recipient eligibility (deleted / suspended /
 * per-category `event_cancelled` opt-out) is OWNED by writeInAppNotification —
 * never re-checked here — and push follows automatically via
 * notifications-onNotificationCreated.
 *
 * IDEMPOTENCY. A deterministic per-event notificationId
 * ([eventCancelledNotificationId]) makes each recipient's write create-if-absent,
 * so a redelivered trigger (Firestore triggers are at-least-once) or a retry
 * after a partial fan-out never double-notifies.
 *
 * COST. The going-RSVP subcollection is a single-equality query on one event —
 * a handful of docs for a town meetup. The per-attendee writes are chunked at
 * FANOUT_CONCURRENCY exactly like the reminder sweep's fan-out.
 */

import { onDocumentWritten } from 'firebase-functions/v2/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { writeInAppNotification } from '../notifications/deliver';
import {
  EVENT_CANCELLED_TITLE,
  type EventCancelledFanOutSummary,
  cancelledRecipients,
  eventCancelledNotificationId,
  eventCancelledPreview,
  isEventCancelledTransition,
} from './eventCancelledNotification-core';
import { MAX_INSTANCES_TRIGGER, CPU_TRIGGER } from '../shared/instanceLimits';

/**
 * `going`-RSVP attendees notified CONCURRENTLY. Each writeInAppNotification is
 * its own transaction against a distinct per-user inbox, so the fan-out is
 * parallelisable — but bounded, exactly like the reminder sweep's
 * FANOUT_CONCURRENCY: a large going list must not open one transaction per
 * attendee at once. Individual failures log per attendee and never propagate.
 */
const FANOUT_CONCURRENCY = 15;

/**
 * Fans the "event cancelled" notice out to every going-RSVP attendee (bar the
 * creator) for one just-cancelled event. Exported so the emulator test can
 * drive it directly against seeded RSVPs + an event doc — the same test seam as
 * the reminder sweep's runEventReminders and the event-created fan-out's
 * runEventCreatedFanOut, so the untested surface stays just the
 * onDocumentWritten glue below.
 *
 * Reads the event doc itself (title, createdByUserId) and re-checks status is
 * still `cancelled` against the FRESH document — a defensive re-derivation, so a
 * write racing the trigger cannot fan out for a non-cancelled event. Mirrors
 * fanOutEventReminder's chunked writeInAppNotification exactly; the only
 * differences are the category, the copy and the creator exclusion.
 */
export async function runEventCancelledFanOut(
  eventId: string,
): Promise<EventCancelledFanOutSummary> {
  const summary: EventCancelledFanOutSummary = { delivered: 0, skipped: 0, failed: 0 };

  const eventSnap = await db.collection('events').doc(eventId).get();
  if (!eventSnap.exists) {
    return summary;
  }
  // Re-check against the FRESH document, not the trigger's stale after-snapshot:
  // only fan out for an event that is still cancelled.
  if (eventSnap.get('status') !== 'cancelled') {
    return summary;
  }
  const title = String(eventSnap.get('title') ?? '');
  // The creator is excluded (they cancelled it / are the organiser). Unlike the
  // community broadcast, a missing createdByUserId does NOT abort here: the
  // audience is the small going-RSVP set, so we still notify attendees their
  // event is off and simply exclude nobody. Logged as a data anomaly, PII-free.
  const rawCreator = eventSnap.get('createdByUserId');
  const creatorUid = typeof rawCreator === 'string' && rawCreator.length > 0 ? rawCreator : null;
  if (creatorUid === null) {
    logger.warn('Event-cancelled fan-out: event has no valid createdByUserId', { eventId });
  }

  const goingRsvps = await db
    .collection('events')
    .doc(eventId)
    .collection('rsvps')
    .where('status', '==', 'going')
    .get();

  const recipients = cancelledRecipients(
    goingRsvps.docs.map((doc) => doc.id),
    creatorUid,
  );
  const previewText = eventCancelledPreview(title);
  const notificationId = eventCancelledNotificationId(eventId);

  for (let i = 0; i < recipients.length; i += FANOUT_CONCURRENCY) {
    const chunk = recipients.slice(i, i + FANOUT_CONCURRENCY);
    const results = await Promise.allSettled(
      chunk.map((uid) =>
        writeInAppNotification(
          uid,
          {
            category: 'event_cancelled',
            title: EVENT_CANCELLED_TITLE,
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
          summary.delivered += 1;
        } else {
          summary.skipped += 1;
        }
      } else {
        // A per-attendee delivery failure must not abort the fan-out; this
        // attendee simply misses the notice. Logged PII-free: the attendee's
        // POSITION in the going-list and the gRPC error CODE only — NEVER
        // String(error), which can embed the failed document path
        // (notifications/{uid}/…), i.e. the recipient's uid.
        summary.failed += 1;
        const reason = result.reason as { code?: string | number } | null;
        logger.error('Event-cancelled notification delivery failed for attendee', {
          eventId,
          attendeeIndex: i + index,
          code: reason?.code ?? null,
        });
      }
    });
  }

  logger.info('Event-cancelled fan-out complete', { eventId, ...summary });
  return summary;
}

/**
 * Fires the "event cancelled" notice on the cancelled transition of any event.
 * onDocumentWritten (not onUpdate) so the guard sees both the before and after
 * status; every other write is a cheap no-op.
 */
export const onEventCancelled = onDocumentWritten(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_TRIGGER,
    cpu: CPU_TRIGGER,
    concurrency: 1,
    document: 'events/{eventId}',
    memory: '256MiB',
    timeoutSeconds: 300,
  },
  async (firestoreEvent) => {
    const beforeStatus = firestoreEvent.data?.before.data()?.status as string | undefined;
    const afterStatus = firestoreEvent.data?.after.data()?.status as string | undefined;
    if (!isEventCancelledTransition({ beforeStatus, afterStatus })) {
      return;
    }
    // Skip in the emulator ONLY (same reasoning as onEventPublished): the
    // feature's emulator coverage drives the exported runEventCancelledFanOut
    // runner DIRECTLY and the transition guard is unit-tested, so the trigger's
    // fan-out is pure cross-test noise under the shared emulator. Production
    // never sets FUNCTIONS_EMULATOR, so the attendee fan-out is unchanged there.
    if (process.env.FUNCTIONS_EMULATOR === 'true') {
      return;
    }
    const { eventId } = firestoreEvent.params;
    try {
      await runEventCancelledFanOut(eventId);
    } catch (error) {
      // PII-free: a Firestore error can embed the failed document path
      // (notifications/{uid}/…) in its message, so log only the error CODE/NAME.
      // The cancelled transition already happened; idempotency (the
      // deterministic id) makes a redelivery safe, so log and RETHROW to let the
      // platform's at-least-once retry cover a transient failure.
      logger.error('Event-cancelled fan-out failed', {
        eventId,
        code: (error as { code?: string | number } | null)?.code ?? null,
        name: error instanceof Error ? error.name : null,
      });
      throw error;
    }
  },
);
