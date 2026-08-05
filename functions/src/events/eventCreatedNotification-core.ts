/**
 * "New event" community notification — pure decision logic (no Firebase Admin
 * SDK imports), unit-tested in eventCreatedNotification-core.test.ts. The
 * Firestore trigger (onEventPublished.ts) owns all I/O and calls into here.
 *
 * WHAT THIS PRODUCES
 * ------------------
 * A single "a new event was added" IN-APP notification to every ACTIVE member,
 * fanned out the moment an event becomes visible to the community — with a
 * deep-link (actionType open_event, relatedEntityId = eventId) that opens the
 * event's detail on tap. It writes through the SAME writeInAppNotification /
 * a new `event_created` category the reminder path already uses, so the
 * per-category opt-out AND the FCM push path (notifications-onNotificationCreated)
 * are inherited for free — this domain never re-implements delivery eligibility.
 *
 * WHEN IT FIRES — the transition INTO `published`, not "on create".
 * ----------------------------------------------------------------
 * An event reaches the community exactly once, when its status becomes
 * `published`, and it gets there by TWO paths (events-core.ts initialEventStatus):
 *  - a MEMBER event is created already `published` (create write:
 *    before-status absent, after-status `published`);
 *  - an ADMIN event is created as a `draft` and published later by
 *    events.publish (update write: before-status `draft`, after-status
 *    `published`).
 * Keying off the published TRANSITION (not the create) covers both with one
 * hook, and — crucially — fires NOTHING on the many other writes an event
 * receives: the rsvpCounts trigger's counter bumps, the reminder marker, an
 * admin edit of an already-published event, a cancel/complete. A `draft` that
 * is never published notifies nobody. `published` is terminal-forward (no write
 * path returns to `draft`), so the transition happens at most once per event.
 *
 * IDEMPOTENCY — AT MOST ONE NOTICE PER (EVENT, MEMBER), EVER.
 * ----------------------------------------------------------
 * The published transition is itself single-shot, but a Firestore trigger can
 * be redelivered (at-least-once). The guard is a DETERMINISTIC per-event
 * notificationId ([eventCreatedNotificationId]): writeInAppNotification's
 * create-if-absent collapses any re-fan-out into the first inbox item per
 * recipient, so a redelivered trigger — or a retry after a partial fan-out —
 * never double-notifies. Same belt-and-suspenders idiom as the reminder sweep's
 * secondary guard and the admin broadcast's batchId.
 *
 * NOT THE CREATOR. The member (or admin) who created the event is excluded from
 * the fan-out — they know about their own event; telling them it exists is
 * noise. `createdByUserId` on the teaser doc is the trustworthy owner record
 * (client-immutable, callable-written).
 *
 * The pure decision here takes the before/after `status` as primitive strings
 * and returns notify / skip. It assumes NOTHING about the write that produced
 * its inputs, so every branch is exercised in isolation by the unit test.
 */

export interface EventCreatedNotificationInputs {
  /** The events/{eventId}.status BEFORE the write (undefined on a create). */
  beforeStatus: string | undefined;
  /** The events/{eventId}.status AFTER the write (undefined on a delete). */
  afterStatus: string | undefined;
}

/**
 * Whether this write is the moment the event became visible to the community —
 * i.e. its status transitioned INTO `published` from anything else (including
 * "did not exist", the member-create case). Pure and total.
 *
 * A write where the event was ALREADY `published` before returns false: that is
 * an edit / counter bump / lifecycle change on a live event, never its debut.
 * A delete (afterStatus undefined) returns false. A `draft` create returns
 * false — it is not live yet.
 */
export function isEventPublishedTransition(inputs: EventCreatedNotificationInputs): boolean {
  return inputs.afterStatus === 'published' && inputs.beforeStatus !== 'published';
}

/**
 * Deterministic per-event notification id — the idempotency guard. Stable for
 * the life of the event so writeInAppNotification's create-if-absent collapses
 * any re-fan-out into one inbox item per recipient. No recipient component is
 * needed (the inbox is already per-recipient, notifications/{uid}/items/{id});
 * its charset — the literal `event-created-` prefix plus a Firestore auto-id
 * (A-Za-z0-9) — stays within the `^[A-Za-z0-9._-]+$` the notifications.markRead
 * callable accepts, and a Firestore auto-id keeps the result under the id cap.
 */
export function eventCreatedNotificationId(eventId: string): string {
  return `event-created-${eventId}`;
}

/** Notification title (sv) — well under the notification title cap. */
export const EVENT_CREATED_TITLE = 'Nytt event';

/**
 * Localized (sv) preview body for a new-event notice. Pure + tested so the
 * wording (and the empty-title fallback) cannot silently regress; the
 * notification builder truncates to its own limit, this keeps it short by
 * design.
 */
export function eventCreatedPreview(eventTitle: string): string {
  const trimmed = eventTitle.trim();
  return trimmed.length > 0
    ? `"${trimmed}" har lagts till. Tryck för att se eventet.`
    : 'Ett nytt event har lagts till. Tryck för att se det.';
}
