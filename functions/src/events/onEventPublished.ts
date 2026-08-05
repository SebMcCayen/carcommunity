/**
 * events-onEventPublished — Firestore trigger fanning a "new event" notice out
 * to the community the moment an event becomes visible (Seb's request:
 * "When an Event has been created, create a notification about it in the
 * notification window with a link that the user can use to get to it").
 *
 * HOOK — the published TRANSITION on events/{eventId}, not "on create".
 * ---------------------------------------------------------------------
 * onDocumentWritten covers every write to the teaser doc; [isEventPublishedTransition]
 * fires the fan-out only when the write moved status INTO `published` (from a
 * draft, or from not-existing — the member-create case). See
 * eventCreatedNotification-core.ts for why the transition, not the create, is
 * the correct single hook for BOTH the member path (created `published`) and the
 * admin path (created `draft`, published later by events.publish), and why no
 * other write (rsvpCounts bump, reminder marker, an edit or cancel of an
 * already-live event) fires anything.
 *
 * AUDIENCE — all ACTIVE members, minus the event's own creator.
 * ------------------------------------------------------------
 * A community-wide meetup announcement, so the audience mirrors the admin
 * broadcast's `members` audience: users/{uid}.activeMember == true. Per-recipient
 * eligibility (deleted / suspended / per-category `event_created` opt-out) is
 * OWNED by writeInAppNotification (decideInAppDelivery) — never re-checked here —
 * and push follows automatically via notifications-onNotificationCreated. The
 * creator is skipped: they already know about their own event.
 *
 * IDEMPOTENCY. A deterministic per-event notificationId
 * ([eventCreatedNotificationId]) makes each recipient's write create-if-absent,
 * so a redelivered trigger (Firestore triggers are at-least-once) or a retry
 * after a partial fan-out never double-notifies.
 *
 * COST. `activeMember == true` is a single-equality query (the same one
 * notifications.adminSend uses for the `members` audience) — no composite index.
 * Paged with a documentId cursor and capped at MAX_RECIPIENTS, with the
 * per-member writes chunked exactly like the reminder sweep's fan-out. For a
 * single-town community this is a handful of members; the paging/cap is a safety
 * valve, not an expected bound.
 */

import { onDocumentWritten } from 'firebase-functions/v2/firestore';
import { FieldPath } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { writeInAppNotification } from '../notifications/deliver';
import {
  EVENT_CREATED_TITLE,
  eventCreatedNotificationId,
  eventCreatedPreview,
  isEventPublishedTransition,
} from './eventCreatedNotification-core';
import { MAX_INSTANCES_TRIGGER } from '../shared/instanceLimits';

/** Active members fetched per query round-trip. */
const PAGE_SIZE = 200;

/**
 * Upper bound of recipients notified per event, independent of member count. Far
 * above any single-town community; a safety valve, not an expected bound. If it
 * ever binds, the fix is a background queue (the same follow-up the admin
 * broadcast's MAX_SYNC_AUDIENCE_SIZE documents), not a larger constant here.
 */
const MAX_RECIPIENTS = 10_000;

/**
 * Active members notified CONCURRENTLY. Each writeInAppNotification is its own
 * transaction against a distinct per-user inbox, so the fan-out is
 * parallelisable — but NOT all at once: a large audience would otherwise open
 * one transaction per member simultaneously. Chunked exactly like the reminder
 * sweep's FANOUT_CONCURRENCY: bounded concurrency, still parallel enough to stay
 * well inside the timeout. Individual failures log per member and never propagate.
 */
const FANOUT_CONCURRENCY = 15;

export interface EventCreatedFanOutSummary {
  /** In-app notifications actually written across the audience. */
  delivered: number;
  /**
   * Recipients writeInAppNotification DECLINED (opted out / suspended / deleted /
   * a deterministic-id duplicate). A delivery that THROWS is logged per member,
   * not counted here.
   */
  skipped: number;
  /** True when MAX_RECIPIENTS bound the scan. */
  capped: boolean;
}

/**
 * Injectable I/O — production uses the real notification writer; a test can pass
 * a stub. Same test-seam intent as the reminder sweep's deps.
 */
export interface EventCreatedFanOutDeps {
  deliver: typeof writeInAppNotification;
}

/**
 * Fans the "new event" notice out to every active member (bar the creator) for
 * one just-published event. Exported so the emulator test can drive it directly
 * against seeded users + an event doc — the same test seam as the reminder
 * sweep's runEventReminders, so the untested surface stays just the onDocumentWritten
 * glue below.
 *
 * Reads the event doc itself (title, createdByUserId) and re-checks status is
 * still `published` — a defensive re-derivation against the fresh document, so a
 * cancel racing the trigger notifies nobody rather than announcing a dead event.
 */
export async function runEventCreatedFanOut(
  eventId: string,
  deps: EventCreatedFanOutDeps = { deliver: writeInAppNotification },
): Promise<EventCreatedFanOutSummary> {
  const summary: EventCreatedFanOutSummary = { delivered: 0, skipped: 0, capped: false };

  const eventSnap = await db.collection('events').doc(eventId).get();
  if (!eventSnap.exists) {
    return summary;
  }
  // Re-check against the FRESH document, not the trigger's stale after-snapshot:
  // a cancel/complete landing between the publish and this read must abort the
  // announcement (the guard is cheap and prevents "new event!" for a dead one).
  if (eventSnap.get('status') !== 'published') {
    return summary;
  }
  const title = String(eventSnap.get('title') ?? '');
  const creatorUid = eventSnap.get('createdByUserId');
  const notificationId = eventCreatedNotificationId(eventId);
  const previewText = eventCreatedPreview(title);

  let cursor: string | null = null;
  for (;;) {
    if (summary.delivered + summary.skipped >= MAX_RECIPIENTS) {
      summary.capped = true;
      break;
    }
    let query = db
      .collection('users')
      .where('activeMember', '==', true)
      .orderBy(FieldPath.documentId())
      .limit(PAGE_SIZE);
    if (cursor !== null) {
      query = query.startAfter(cursor);
    }
    const page = await query.get();
    if (page.empty) {
      break;
    }

    // Skip the creator: they already know about their own event.
    const recipients = page.docs
      .map((doc) => doc.id)
      .filter((uid) => uid !== creatorUid);

    for (let i = 0; i < recipients.length; i += FANOUT_CONCURRENCY) {
      const chunk = recipients.slice(i, i + FANOUT_CONCURRENCY);
      const results = await Promise.allSettled(
        chunk.map((uid) =>
          deps.deliver(
            uid,
            {
              category: 'event_created',
              title: EVENT_CREATED_TITLE,
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
          // A per-member delivery failure must not abort the fan-out. Logged
          // PII-free: the member's position in the page, not their uid.
          logger.error('Event-created notification delivery failed for a member', {
            eventId,
            memberIndex: i + index,
            error: String(result.reason),
          });
        }
      });
    }

    if (page.size < PAGE_SIZE) {
      break;
    }
    cursor = page.docs[page.docs.length - 1]!.id;
  }

  if (summary.capped) {
    logger.warn('Event-created fan-out hit the recipient cap; later members not notified', {
      eventId,
      maxRecipients: MAX_RECIPIENTS,
    });
  }
  logger.info('Event-created fan-out complete', { eventId, ...summary });
  return summary;
}

/**
 * Fires the community "new event" notice on the published transition of any
 * event. onDocumentWritten (not onCreate) so an ADMIN event — created `draft`,
 * published later by events.publish — is caught by the same hook as a MEMBER
 * event created already `published`. Every other write is a cheap no-op.
 */
export const onEventPublished = onDocumentWritten(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_TRIGGER,
    document: 'events/{eventId}',
    memory: '256MiB',
    timeoutSeconds: 300,
  },
  async (firestoreEvent) => {
    const beforeStatus = firestoreEvent.data?.before.data()?.status as string | undefined;
    const afterStatus = firestoreEvent.data?.after.data()?.status as string | undefined;
    if (!isEventPublishedTransition({ beforeStatus, afterStatus })) {
      return;
    }
    const { eventId } = firestoreEvent.params;
    try {
      await runEventCreatedFanOut(eventId);
    } catch (error) {
      // The published transition already happened; a fan-out failure must not
      // retry the whole event forever. Idempotency (the deterministic id) makes
      // a redelivery safe, so log and let the platform's at-least-once retry —
      // bounded by the deterministic id — cover a transient failure.
      logger.error('Event-created fan-out failed', { eventId, error: String(error) });
      throw error;
    }
  },
);
