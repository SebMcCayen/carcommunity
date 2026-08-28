/**
 * "Event cancelled" attendee notification — pure decision logic (no Firebase
 * Admin SDK imports), unit-tested in eventCancelledNotification-core.test.ts.
 * The Firestore trigger (onEventCancelled.ts) owns all I/O and calls into here.
 *
 * WHAT THIS PRODUCES
 * ------------------
 * A single "the event was cancelled" IN-APP notification to every member who
 * RSVP'd `going`, fanned out the moment an event's status transitions INTO
 * `cancelled` — with a deep-link (actionType open_event, relatedEntityId =
 * eventId) that opens the (now cancelled) event's detail on tap. It writes
 * through the SAME writeInAppNotification / `event_cancelled` category, so the
 * per-category opt-out AND the FCM push path (notifications-onNotificationCreated)
 * are inherited for free — this domain never re-implements delivery eligibility.
 *
 * WHEN IT FIRES — the transition INTO `cancelled`, not "on cancel write".
 * ----------------------------------------------------------------------
 * onDocumentWritten covers every write to the teaser doc;
 * [isEventCancelledTransition] fires the fan-out only when the write moved
 * status INTO `cancelled` from anything else. `cancelled` is terminal (no write
 * path leaves it — guardCancellable rejects a second cancel, guardUpdatableStatus
 * freezes edits), so the transition happens at most once per event; every other
 * write — an edit, an rsvpCounts bump, a publish, a complete — is a cheap no-op.
 *
 * NOTIFICATION ONLY ON CANCELLATION. Edits are deliberately SILENT (owner
 * decision): only a cancellation notifies. There is no `event_updated` producer.
 *
 * NOT THE CREATOR. The member (or admin) who created the event is excluded from
 * the fan-out — they took the action (or are the organiser) and do not need to
 * be told their own event is off. `createdByUserId` on the teaser doc is the
 * trustworthy owner record (client-immutable, callable-written).
 *
 * IDEMPOTENCY — AT MOST ONE NOTICE PER (EVENT, ATTENDEE), EVER.
 * ------------------------------------------------------------
 * The cancelled transition is itself single-shot, but a Firestore trigger can
 * be redelivered (at-least-once). The guard is a DETERMINISTIC per-event
 * notificationId ([eventCancelledNotificationId]): writeInAppNotification's
 * create-if-absent collapses any re-fan-out into the first inbox item per
 * recipient, so a redelivered trigger — or a retry after a partial fan-out —
 * never double-notifies. Same idiom as the reminder sweep's secondary guard.
 *
 * The pure decision here takes the before/after `status` as primitive strings
 * and returns notify / skip. It assumes NOTHING about the write that produced
 * its inputs, so every branch is exercised in isolation by the unit test.
 */

export interface EventCancelledNotificationInputs {
  /** The events/{eventId}.status BEFORE the write (undefined on a create). */
  beforeStatus: string | undefined;
  /** The events/{eventId}.status AFTER the write (undefined on a delete). */
  afterStatus: string | undefined;
}

/**
 * Whether this write is the moment the event was cancelled — i.e. its status
 * transitioned INTO `cancelled` from anything else. Pure and total.
 *
 * A write where the event was ALREADY `cancelled` before returns false: that is
 * a later write on a dead event, never its cancellation. A delete (afterStatus
 * undefined) returns false. A create written straight to `cancelled` (not a
 * real path today) would fire once — harmless, no going RSVPs exist yet.
 */
export function isEventCancelledTransition(inputs: EventCancelledNotificationInputs): boolean {
  return inputs.afterStatus === 'cancelled' && inputs.beforeStatus !== 'cancelled';
}

/**
 * Deterministic per-event notification id — the idempotency guard. Stable for
 * the life of the event so writeInAppNotification's create-if-absent collapses
 * any re-fan-out into one inbox item per recipient. No recipient component is
 * needed (the inbox is already per-recipient, notifications/{uid}/items/{id});
 * its charset — the literal `event-cancelled-` prefix plus a Firestore auto-id
 * (A-Za-z0-9) — stays within the `^[A-Za-z0-9._-]+$` the notifications.markRead
 * callable accepts, and a Firestore auto-id keeps the result under the id cap.
 */
export function eventCancelledNotificationId(eventId: string): string {
  return `event-cancelled-${eventId}`;
}

/** Notification title (sv) — well under the notification title cap. */
export const EVENT_CANCELLED_TITLE = 'Event inställt';

/**
 * Localized (sv) preview body for a cancellation notice — same sv-copy
 * convention as eventReminderPreview / eventCreatedPreview. Pure + tested so the
 * wording (and the empty-title fallback) cannot silently regress; the
 * notification builder truncates to its own limit, this keeps it short by
 * design.
 */
export function eventCancelledPreview(eventTitle: string): string {
  const trimmed = eventTitle.trim();
  return trimmed.length > 0
    ? `"${trimmed}" har ställts in.`
    : 'Ett event du anmält dig till har ställts in.';
}

// ---------------------------------------------------------------------------
// Fan-out accounting (pure — no Firebase Admin SDK imports)
// ---------------------------------------------------------------------------

export interface EventCancelledFanOutSummary {
  /** In-app notifications actually written across the going attendees. */
  delivered: number;
  /**
   * Recipients delivery DECLINED — opted out / suspended / deleted, OR a
   * deterministic-id duplicate (an idempotent replay: the item already existed).
   */
  skipped: number;
  /** Recipients whose write THREW for a non-duplicate reason (logged PII-free). */
  failed: number;
}

/**
 * The going-RSVP uids to notify, minus the creator. Pure so the creator
 * exclusion is unit-testable without the emulator. A null `creatorUid` (a
 * corrupt/unattributed event) excludes nobody — attendees are still notified
 * their event is off, which matters more than a perfect self-exclusion in that
 * anomaly.
 */
export function cancelledRecipients(
  goingUids: readonly string[],
  creatorUid: string | null,
): string[] {
  return goingUids.filter((uid) => uid !== creatorUid);
}
