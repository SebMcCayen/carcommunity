/**
 * Event RSVP reminders — pure decision logic (no Firebase Admin SDK imports),
 * unit-tested in eventReminders-core.test.ts. The scheduled wrapper
 * (eventReminders.ts) owns all Firestore I/O and calls into here.
 *
 * WHAT THIS PRODUCES
 * ------------------
 * A single "your event starts soon" IN-APP notification to every member who
 * RSVP'd `going`, delivered once, shortly before the event begins. It writes
 * through the SAME writeInAppNotification / `event_reminder` category the admin
 * broadcast path already uses, so the per-category opt-out AND the FCM push path
 * (notifications-onNotificationCreated, PR #496) are inherited for free — this
 * domain never re-implements delivery eligibility.
 *
 * CADENCE + LEAD TIME (see eventReminders.ts for the onSchedule wiring).
 *
 * The sweep runs every 15 minutes and reminds an attendee once, at most
 * EVENT_REMINDER_LEAD_MS (2 hours) before the event's start. The reminder fires
 * on the FIRST tick an event enters the window `(now, now + lead]`, so an
 * attendee is nudged between ~1h45m and 2h before a normally-scheduled event
 * (worst case one 15-minute tick after the 2h mark). A short-notice event —
 * created or published less than 2h before it starts (member events publish
 * immediately) — is simply picked up on the next tick and gets a "starts soon"
 * nudge rather than nothing: the window is "starts within the next 2h and not
 * yet reminded", which covers both cases without a separate code path.
 *
 * 2 hours is chosen for a single-town (Kungsbacka) community calendar: enough
 * lead to actually set off for a local meetup, not so early the nudge is
 * forgotten by the time it matters. 15-minute granularity keeps the delivered
 * lead close to 2h without a tight, wasteful cadence (every-minute would be
 * needless invocations; hourly would smear the lead across a full 1h–2h band).
 *
 * IDEMPOTENCY — AT MOST ONE REMINDER PER (EVENT, MEMBER), EVER.
 *
 * A reminder that fires every scheduler tick is exactly the spam to avoid. Two
 * independent guards stop it:
 *
 *   - PRIMARY: a per-EVENT marker, events/{eventId}.eventReminderSentAt, CLAIMED
 *     in a transaction (see claimEventReminder in eventReminders.ts) before any
 *     fan-out. The transaction re-derives THIS decision against the freshly-read
 *     document, so exactly one sweep run ever fans a given event out; every later
 *     tick sees the marker set and skips at ~one already-in-hand read. This is
 *     both the at-most-once guard and the cost bound.
 *   - SECONDARY (belt-and-suspenders): a deterministic per-event notificationId
 *     (eventReminderNotificationId). writeInAppNotification's create-if-absent
 *     collapses any re-fan-out (e.g. two adjacent invocations that both read the
 *     marker as null before either transaction committed) into the first inbox
 *     item, so a member cannot be double-notified even if the primary guard were
 *     somehow re-entered.
 *
 * EDITED START TIME. The marker is set ONCE and never cleared — no write path
 * clears it, and events.update does not touch it. So moving an event's start,
 * earlier OR later, never triggers a second reminder: the member already got
 * their "starts soon" nudge for this event. This is the deliberate anti-spam
 * choice. An event edited from far-future INTO the window that was never
 * reminded still fires normally (its marker is null).
 *
 * The pure decision here takes the stored `status`, the start instant and
 * whether the marker is already set, all as primitive values, and returns
 * remind / skip-with-reason. It assumes NOTHING about the query that produced
 * its inputs, so every branch is exercised in isolation by the unit test.
 */

/**
 * How long before an event's start an RSVP'd member is reminded — the upper
 * bound of the reminder window. Two hours (see the cadence note above).
 */
export const EVENT_REMINDER_LEAD_MS = 2 * 60 * 60 * 1000;

/**
 * The candidate query's lower bound: an event that has ALREADY started is never
 * reminded (a "starts soon" nudge for a past instant is stale and misleading),
 * so the query filters `startsAt > reminderWindowStart(now)`.
 */
export function reminderWindowStart(now: Date): Date {
  return now;
}

/**
 * The candidate query's upper bound: events starting within `leadMs` of `now`.
 * Defaults to EVENT_REMINDER_LEAD_MS; the runner threads its own `leadMs` so a
 * test can shrink the window.
 */
export function reminderWindowEnd(now: Date, leadMs: number = EVENT_REMINDER_LEAD_MS): Date {
  return new Date(now.getTime() + leadMs);
}

export interface EventReminderInputs {
  /** The stored events/{eventId}.status. */
  status: string;
  /** The event's start instant, epoch millis. */
  startsAtMs: number;
  /** True when events/{eventId}.eventReminderSentAt is already set. */
  reminderAlreadySent: boolean;
  /** The sweep instant, epoch millis. */
  nowMs: number;
  /** The reminder lead window width (EVENT_REMINDER_LEAD_MS in production). */
  leadMs: number;
}

export type EventReminderDecision =
  | { remind: true }
  | {
      remind: false;
      reason: 'not_published' | 'already_sent' | 'already_started' | 'outside_window';
    };

/**
 * Whether an event is due a reminder at `now` — pure and total.
 *
 *  - not_published:  draft events were never live; cancelled/completed are
 *                    terminal. Only `published` events are ever reminded, which
 *                    (with the marker) is what makes the sweep idempotent.
 *  - already_sent:   the per-event marker is set — a prior run reminded this
 *                    event's attendees. Never re-fires, including after an edit
 *                    to the start time.
 *  - already_started: the start is at/before `now`; a "starts soon" nudge would
 *                    be stale. Bounds the window below.
 *  - outside_window: the start is more than `leadMs` away — too early to remind.
 *  - remind:         published, unreminded, and starting within (now, now+lead].
 */
export function decideEventReminder(inputs: EventReminderInputs): EventReminderDecision {
  if (inputs.status !== 'published') {
    return { remind: false, reason: 'not_published' };
  }
  if (inputs.reminderAlreadySent) {
    return { remind: false, reason: 'already_sent' };
  }
  if (inputs.startsAtMs <= inputs.nowMs) {
    return { remind: false, reason: 'already_started' };
  }
  if (inputs.startsAtMs > inputs.nowMs + inputs.leadMs) {
    return { remind: false, reason: 'outside_window' };
  }
  return { remind: true };
}

/**
 * Deterministic per-event notification id — the SECONDARY idempotency guard.
 * Stable for the life of the event so writeInAppNotification's create-if-absent
 * collapses any re-fan-out into one inbox item per recipient. No recipient
 * component is needed (the inbox is already per-recipient,
 * notifications/{uid}/items/{id}); the charset (a-z, digits, '-') is within what
 * the notifications.markRead callable accepts, and an event id is a Firestore
 * auto-id so the result stays well under the id length cap.
 */
export function eventReminderNotificationId(eventId: string): string {
  return `event-reminder-${eventId}`;
}

/** Notification title (sv) — well under the notification title cap. */
export const EVENT_REMINDER_TITLE = 'Snart dags för ditt event';

/**
 * Localized (sv) preview body for an event reminder. Pure + tested so the
 * wording (and the empty-title fallback) cannot silently regress; the
 * notification builder truncates to its own limit, this keeps it short by
 * design.
 */
export function eventReminderPreview(eventTitle: string): string {
  const trimmed = eventTitle.trim();
  return trimmed.length > 0 ? `"${trimmed}" börjar snart.` : 'Ditt event börjar snart.';
}
