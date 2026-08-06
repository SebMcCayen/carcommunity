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
import { FieldPath, FieldValue } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { toUserAccessState } from '../shared/access';
import { buildNotificationDocument, decideInAppDelivery } from '../notifications/notifications-core';
import {
  EVENT_CREATED_TITLE,
  type EventCreatedFanOutSummary,
  eventCreatedNotificationId,
  eventCreatedPreview,
  isEventPublishedTransition,
  recipientsWithinCap,
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

/** gRPC ALREADY_EXISTS — a BulkWriter create() on an existing inbox item (an
 * idempotent replay), which is a DUPLICATE (skip), never a real failure. */
const GRPC_ALREADY_EXISTS = 6;

/**
 * Fans the "new event" notice out to every active member (bar the creator) for
 * one just-published event. Exported so the emulator test can drive it directly
 * against seeded users + an event doc — the same test seam as the reminder
 * sweep's runEventReminders, so the untested surface stays just the onDocumentWritten
 * glue below.
 *
 * EFFICIENT BY DESIGN — this is a BROADCAST, not a per-recipient callable, so it
 * must not cost one round-trip per member. Prod audiences are tiny (~tens of
 * members) but the write pattern still matters at test scale (the shared emulator
 * accumulates hundreds of seeded users and MANY test files publish events, so a
 * naive per-recipient transaction fan-out melts it). Per page it therefore:
 *  - reuses the page's OWN users/{uid} document for the access state
 *    (deleted/suspended) — no second read of a doc it already holds;
 *  - batch-reads the page's userPrivate/{uid} preference docs in ONE getAll;
 *  - writes the inbox items through a single BulkWriter (create() = idempotent
 *    create-if-absent on the deterministic id, so a replay is an ALREADY_EXISTS
 *    duplicate rather than an overwrite).
 * It reuses the SAME pure decision + document builder writeInAppNotification uses
 * (decideInAppDelivery / buildNotificationDocument), so eligibility (deleted /
 * suspended / per-category opt-out) and the stored shape are identical — only the
 * I/O is batched. Push follows for every created item via
 * notifications-onNotificationCreated exactly as for any inbox write.
 *
 * Reads the event doc itself (title, createdByUserId) and re-checks status is
 * still `published` — a defensive re-derivation against the fresh document, so a
 * cancel racing the trigger notifies nobody rather than announcing a dead event.
 */
export async function runEventCreatedFanOut(
  eventId: string,
): Promise<EventCreatedFanOutSummary> {
  const summary: EventCreatedFanOutSummary = { delivered: 0, skipped: 0, failed: 0, capped: false };

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
  // The creator is excluded from the broadcast, so a trustworthy creator uid is a
  // PRECONDITION of a correct fan-out: events.create always writes
  // createdByUserId as the actor uid (a string; rules deny all client writes to
  // events/{eventId}), so an absent/non-string value means a corrupt event
  // document. Rather than broadcast to the whole community with a broken
  // exclusion (which could notify the creator about their own event), ABORT and
  // log — a data-integrity anomaly, not the normal path.
  const rawCreator = eventSnap.get('createdByUserId');
  if (typeof rawCreator !== 'string' || rawCreator.length === 0) {
    logger.error('Event-created fan-out aborted: event has no valid createdByUserId', { eventId });
    return summary;
  }
  const creatorUid = rawCreator;
  const notificationId = eventCreatedNotificationId(eventId);
  const previewText = eventCreatedPreview(title);
  const usersRef = db.collection('users');

  const bulk = db.bulkWriter();
  // An ALREADY_EXISTS (idempotent replay) is a duplicate, NOT retryable; anything
  // else gets the default bounded retry. Returning false stops the retry and
  // rejects that op's promise, which the per-write handler classifies below.
  bulk.onWriteError((error) => error.code !== GRPC_ALREADY_EXISTS && error.failedAttempts < 3);

  // Fan out inside try/finally so the BulkWriter is ALWAYS released. bulk.close()
  // must run even if a query.get() / db.getAll() / bulk.flush() throws mid-run —
  // otherwise the writer leaks its pending operations. See the finally below.
  try {
    let cursor: string | null = null;
    for (;;) {
      const processed = summary.delivered + summary.skipped + summary.failed;
      if (processed >= MAX_RECIPIENTS) {
        summary.capped = true;
        break;
      }
      // Single-equality query (the same one notifications.adminSend's `members`
      // audience uses) — NO composite index. Ordered by documentId for a stable
      // cursor.
      let query = usersRef
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

      const byUid = new Map(page.docs.map((doc) => [doc.id, doc]));
      const { recipients, capped } = recipientsWithinCap(
        page.docs.map((doc) => doc.id),
        { creatorUid, processed, maxRecipients: MAX_RECIPIENTS },
      );
      if (capped) {
        summary.capped = true;
      }

      // Batch-read the preference docs for this page in one round-trip (rather
      // than one per member); a member with no userPrivate doc reads as "enabled".
      const privateSnaps = recipients.length
        ? await db.getAll(...recipients.map((uid) => db.collection('userPrivate').doc(uid)))
        : [];

      // Enqueue this page's writes, then flush + settle before the next page so
      // the attempts cap (recipientsWithinCap above) is computed against
      // up-to-date counts.
      const pageWrites: Promise<void>[] = [];
      recipients.forEach((uid, index) => {
        const userDoc = byUid.get(uid);
        const decision = decideInAppDelivery(
          'event_created',
          toUserAccessState(userDoc?.data()),
          privateSnaps[index]?.data()?.notificationPreferences,
        );
        if (!decision.deliver) {
          summary.skipped += 1;
          return;
        }
        const ref = db
          .collection('notifications')
          .doc(uid)
          .collection('items')
          .doc(notificationId);
        const document = buildNotificationDocument(
          {
            category: 'event_created',
            title: EVENT_CREATED_TITLE,
            previewText,
            actionType: 'open_event',
            relatedEntityId: eventId,
          },
          () => FieldValue.serverTimestamp(),
        );
        pageWrites.push(
          bulk
            .create(ref, document)
            .then(() => {
              summary.delivered += 1;
            })
            .catch((error: unknown) => {
              const code = (error as { code?: number } | null)?.code;
              if (code === GRPC_ALREADY_EXISTS) {
                // Idempotent replay — the item already existed.
                summary.skipped += 1;
                return;
              }
              summary.failed += 1;
              // PII-free: the member's position in the run + the gRPC error CODE
              // only. NEVER String(error) — a Firestore write error embeds the
              // failed document path (notifications/{uid}/…), i.e. the recipient's
              // uid. failedAttempts is a plain count, safe to include.
              const failedAttempts = (error as { failedAttempts?: number } | null)
                ?.failedAttempts;
              logger.error('Event-created notification write failed for a member', {
                eventId,
                memberIndex: processed + index,
                code: code ?? null,
                ...(typeof failedAttempts === 'number' ? { failedAttempts } : {}),
              });
            }),
        );
      });
      await bulk.flush();
      await Promise.all(pageWrites);

      if (summary.capped) {
        break;
      }
      if (page.size < PAGE_SIZE) {
        break;
      }
      cursor = page.docs[page.docs.length - 1]!.id;
    }
  } finally {
    // ALWAYS release the BulkWriter — even if a query/getAll/flush threw
    // mid-fan-out — so it can never leak pending operations. Every enqueued
    // create() already has its own .catch above, so close() settles cleanly;
    // swallow a close-time error so it cannot mask the original failure being
    // propagated out of this function.
    await bulk.close().catch(() => {});
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
    // Under the shared Firestore emulator this trigger is a cross-test
    // amplifier: EVERY test file that publishes an event fires it, and it then
    // broadcasts to the WHOLE accumulated active-member set (hundreds of users
    // other files seeded), writing one notification doc per member — each of
    // which fires the notifications-onNotificationCreated push trigger. Across
    // the suite that is tens of thousands of trigger dispatches piling into one
    // shared functions runtime, congesting UNRELATED trigger-propagation waits
    // (e.g. onRsvpWrite) until they time out. No emulator test relies on this
    // trigger's broadcast — the feature's own coverage drives the exported
    // runEventCreatedFanOut runner DIRECTLY (see events-created-notification
    // .emulator.test.ts) and the transition guard above is unit-tested — so the
    // trigger's fan-out is pure cross-test noise here. Skip it in the emulator
    // ONLY. Production never sets FUNCTIONS_EMULATOR, so the broadcast to all
    // active members is unchanged there. (Same env guard the callables use for
    // enforceAppCheck.)
    if (process.env.FUNCTIONS_EMULATOR === 'true') {
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
