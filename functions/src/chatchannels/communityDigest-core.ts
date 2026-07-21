/**
 * Community-chat DIGEST — pure decision logic (no Firebase Admin SDK imports),
 * unit-tested in communityDigest-core.test.ts. The scheduled wrapper
 * (communityDigest.ts) owns all Firestore I/O and calls into here.
 *
 * WHY A DIGEST EXISTS (and why it is NOT a per-message producer).
 *
 * The community channel deliberately has NO per-message notification producer:
 * its audience is EVERY active member, so notifying per message is an
 * O(members × messages) fan-out that buries every inbox and trains users to mute
 * notifications wholesale (see communityChat.ts + notifications-core.ts). The two
 * sanctioned ways a member still hears about the channel are:
 *   1. @mentions — "someone is talking TO me" (already built, communityChat.post).
 *   2. this DIGEST — "the channel has been busy" — a periodic, low-frequency
 *      ONE-notice-per-member roll-up, cost O(members) per PERIOD, not per message.
 *
 * The digest reuses the EXISTING per-user last-read marker
 * (userPrivate/{uid}.communityChatLastReadAt, stamped by communityChat.markRead)
 * as the "has this member fallen behind" signal, and writes through the SAME
 * writeInAppNotification / `community_chat` category the mention producer uses, so
 * the per-category opt-out AND the (future, #496) push path are inherited for
 * free — this module never re-implements delivery eligibility.
 *
 * IDEMPOTENCY / NO DOUBLE-NOTIFY — the load-bearing part of a digest.
 *
 * A digest that fires twice for the same unread run is exactly the spam it exists
 * to prevent. Two independent guards stop that:
 *
 *   - PRIMARY: a per-member DIGEST MARKER, userPrivate/{uid}.communityChatDigestedUpTo
 *     (backend-only, like communityChatLastReadAt). When a member is digested, the
 *     wrapper stamps it to the run's newest-message instant. The effective baseline
 *     for "what counts as unread" is therefore max(lastReadAt, digestedUpTo): a
 *     member is re-digested ONLY when at least `threshold` messages arrive AFTER
 *     the last digest they were sent. No new messages since the last digest ⇒ no
 *     re-notify, however many daily runs pass. Reading the chat (which advances
 *     lastReadAt past digestedUpTo) also clears the unread and ends re-digesting.
 *
 *   - SECONDARY (belt-and-suspenders): a deterministic notificationId per UTC day
 *     (communityDigestNotificationId). writeInAppNotification's create-if-absent
 *     collapses a same-day replay (a retried/duplicated run) into the first item,
 *     so a mid-run crash before the marker write can't double-deliver within a day.
 *
 * The pure decision here takes an already-computed `unreadCount` (the wrapper runs
 * the cheap count() aggregation) and the three instants as epoch-millis, and
 * returns notify / skip-with-reason. It assumes NOTHING about the query that
 * produced its inputs, so every branch is exercised in isolation by the unit test.
 */

/**
 * Minimum unread messages before a member is worth a digest.
 *
 * A digest says "the channel has been BUSY", so one or two stray messages is not
 * a digest — it's the noise the town-square design is trying to avoid pushing into
 * every inbox. Three distinct new messages since a member last engaged is the
 * floor at which "there's a conversation happening you're missing" is true rather
 * than "someone said hej". Deliberately conservative toward silence: a digest that
 * under-fires costs a member a glance at a channel they can already open; a digest
 * that over-fires is the exact failure mode (notification fatigue) that killed the
 * per-message producer.
 */
export const COMMUNITY_DIGEST_MIN_UNREAD = 3;

/**
 * The instant a member has effectively "seen up to" for digest purposes: the LATER
 * of their last-read marker and the last point they were already digested to.
 *
 * Nulls are treated as -infinity (never read / never digested). Returns null only
 * when BOTH are null — a member who has neither opened the chat nor been digested.
 * (In practice the wrapper only ever feeds this a member with a non-null
 * lastReadAt, since the candidate query filters on that field existing and being
 * behind — but the pure function does not rely on that.)
 */
export function digestBaseline(
  lastReadAtMs: number | null,
  digestedUpToMs: number | null,
): number | null {
  if (lastReadAtMs === null) return digestedUpToMs;
  if (digestedUpToMs === null) return lastReadAtMs;
  return Math.max(lastReadAtMs, digestedUpToMs);
}

/**
 * Whether it is even worth running the per-member count() aggregation: true iff
 * the channel has a message strictly newer than the member's baseline. Used by the
 * wrapper to GATE the count query — a caught-up or already-digested member costs no
 * aggregation read at all.
 */
export function hasNewSinceBaseline(
  latestMessageAtMs: number | null,
  baselineMs: number | null,
): boolean {
  if (latestMessageAtMs === null) return false;
  if (baselineMs === null) return true;
  return latestMessageAtMs > baselineMs;
}

export interface MemberDigestInputs {
  /** Newest message's createdAt in the channel (ms), or null for an empty channel. */
  latestMessageAtMs: number | null;
  /** The member's communityChatLastReadAt (ms), or null if never opened. */
  lastReadAtMs: number | null;
  /** The member's communityChatDigestedUpTo marker (ms), or null if never digested. */
  digestedUpToMs: number | null;
  /**
   * Count of messages with createdAt STRICTLY AFTER the baseline
   * (max(lastReadAt, digestedUpTo)). Only meaningful when hasNewSinceBaseline is
   * true; the wrapper passes 0 when it skipped the count because the member was
   * caught up / already digested, and the decision ignores it in that case.
   */
  unreadCount: number;
  /** Minimum unread to digest (COMMUNITY_DIGEST_MIN_UNREAD in production). */
  threshold: number;
}

export type MemberDigestDecision =
  | { notify: true; unreadCount: number }
  | { notify: false; reason: 'caught_up' | 'already_digested' | 'below_threshold' };

/**
 * The whole per-member digest decision, pure and total.
 *
 *  - caught_up:        the channel is empty, OR the member's own last-read is at/after
 *                      the newest message (they've read everything).
 *  - already_digested: there is nothing newer than the member's baseline because a
 *                      PRIOR digest already covered up to (or past) the newest
 *                      message — the primary no-double-notify guard.
 *  - below_threshold:  there ARE new messages since the baseline, but fewer than
 *                      `threshold` — accumulate silently until it's worth a notice.
 *  - notify:           `threshold`+ new messages since the baseline → send one digest.
 */
export function decideMemberDigest(inputs: MemberDigestInputs): MemberDigestDecision {
  const { latestMessageAtMs, lastReadAtMs, digestedUpToMs, unreadCount, threshold } = inputs;

  const baseline = digestBaseline(lastReadAtMs, digestedUpToMs);

  if (!hasNewSinceBaseline(latestMessageAtMs, baseline)) {
    // Distinguish the two silent cases for observability. If the member's OWN
    // last-read reaches the newest message they are simply caught up; otherwise
    // the baseline can only have reached it via a prior digest marker.
    const readCaughtUp =
      latestMessageAtMs === null ||
      (lastReadAtMs !== null && lastReadAtMs >= latestMessageAtMs);
    return { notify: false, reason: readCaughtUp ? 'caught_up' : 'already_digested' };
  }

  if (unreadCount < threshold) {
    return { notify: false, reason: 'below_threshold' };
  }
  return { notify: true, unreadCount };
}

/**
 * Deterministic per-member, per-UTC-day notification id — the SECONDARY
 * idempotency guard. Stable within a calendar day so writeInAppNotification's
 * create-if-absent collapses a same-day retried/duplicated run into one inbox
 * item, independent of the digestedUpTo marker write. No recipient component is
 * needed (the inbox is already per-recipient, notifications/{uid}/items/{id}); the
 * charset (a-z, digits, '-') is within what the notifications.markRead callable
 * accepts.
 */
export function communityDigestNotificationId(now: Date): string {
  return `community-digest-${now.toISOString().slice(0, 10)}`;
}

/** Chars of the localized digest preview — well under the notification cap. */
export const COMMUNITY_DIGEST_TITLE = 'Nytt i community-chatten';

/**
 * Localized (sv) preview body for a digest of `unreadCount` unread messages.
 * Pure + tested so the singular/plural wording can't silently regress. The
 * notification builder truncates to its own limit; this keeps it short by design.
 */
export function communityDigestPreview(unreadCount: number): string {
  const noun = unreadCount === 1 ? 'nytt meddelande' : 'nya meddelanden';
  return `${unreadCount} ${noun} i community-chatten sedan du var här senast.`;
}
