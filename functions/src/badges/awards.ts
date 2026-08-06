/**
 * Badge award primitives (Phase 9f) — Admin SDK writers used by the badge
 * callable and by the automatic evaluation hooks in other domains
 * (garage.addVehicle, events.complete).
 *
 * Awards are idempotent by construction: the document ID equals the badge
 * key and the write is a transactional create-if-absent, so concurrent
 * evaluations serialize instead of duplicating (same pattern as
 * drives.save). Suspended and deleted users are never awarded (legacy
 * assertEligibleForAward); a badge is never revoked here.
 *
 * TIERED LADDERS. `awardBadge` is also the write used by the tier evaluator
 * (badges/tierAwards.ts) — the ladders needed no new award primitive, and get
 * their idempotency from exactly this create-if-absent transaction. Note that
 * creditVerifiedEventAttendance below feeds Träffräv the same way: the counter
 * it writes lives on badgeProgress/{uid}, and the badges-onBadgeProgressWritten
 * trigger evaluates the ladders off that write.
 */

import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { isRestricted, toUserAccessState } from '../shared/access';
import {
  buildBadgeDocument,
  parseEarlyMemberCutoff,
  qualifiedEventBadges,
  qualifiesAsEarlyMember,
  type BadgeKey,
} from './badge-core';

export interface AwardResult {
  badgeKey: BadgeKey;
  alreadyAwarded: boolean;
}

/**
 * Awards a badge if the target is eligible. Returns null when the target is
 * missing, suspended, or deleted (no-op, matching legacy evaluators).
 */
export async function awardBadge(params: {
  targetUid: string;
  badgeKey: BadgeKey;
  source: 'automatic' | 'admin_manual';
  // No awarder parameter by design: the badge document is publicly readable
  // and Firestore has no field-level read security, so an admin UID must never
  // be persisted here. Callers that award manually record the actor in
  // adminAuditEvents instead (see awardHelpfulMember).
}): Promise<AwardResult | null> {
  const userSnap = await db.collection('users').doc(params.targetUid).get();
  if (!userSnap.exists || isRestricted(toUserAccessState(userSnap.data()))) {
    return null;
  }

  const badgeRef = db
    .collection('users')
    .doc(params.targetUid)
    .collection('badges')
    .doc(params.badgeKey);

  return db.runTransaction(async (tx) => {
    const existing = await tx.get(badgeRef);
    if (existing.exists) {
      return { badgeKey: params.badgeKey, alreadyAwarded: true };
    }
    tx.set(
      badgeRef,
      buildBadgeDocument(
        params.badgeKey,
        { source: params.source },
        () => FieldValue.serverTimestamp(),
      ),
    );
    return { badgeKey: params.badgeKey, alreadyAwarded: false };
  });
}

/**
 * Credits one VERIFIED event attendance — a member who physically checked in
 * at the meet, not merely RSVP'd — on the backend-only badgeProgress/{uid}
 * counter, then awards any newly qualified event badges (first_event /
 * five_events / Träffräv). This is the ONLY writer of `completedEventsAttended`:
 * the attendance badge counts proof-of-presence, exactly as Seb asked ("that
 * verified check-in is what counts toward a badge — not just RSVP'ing").
 *
 * Called from the points-onAttendanceVerified trigger on the false→true
 * `verified` edge of eventAttendance/{eventId}__{uid}. Because a trigger is
 * AT-LEAST-ONCE, the increment is guarded to fire exactly once per
 * (uid, event): a `badgeProgress/{uid}/attendanceCredits/{eventId}` marker is
 * claimed in the SAME transaction that increments the counter, so a redelivery
 * of the same edge reads the marker and no-ops. The counter and its markers are
 * Admin-SDK-only (firestore.rules denies badgeProgress and every subcollection
 * to clients), so neither can be minted from a device.
 */
export async function creditVerifiedEventAttendance(
  targetUid: string,
  eventId: string,
): Promise<void> {
  const progressRef = db.collection('badgeProgress').doc(targetUid);
  const creditRef = progressRef.collection('attendanceCredits').doc(eventId);
  const attendanceCount = await db.runTransaction(async (tx) => {
    const [progressSnap, creditSnap] = await Promise.all([tx.get(progressRef), tx.get(creditRef)]);
    // Already credited for THIS event — a trigger redelivery, or a second
    // verified write. Return null so the caller awards nothing further; the
    // counter must not double-count a single attendance.
    if (creditSnap.exists) {
      return null;
    }
    const stored = progressSnap.data()?.completedEventsAttended;
    // Guard against a corrupted counter (e.g. a string) — '3' + 1 would
    // concatenate and permanently break the thresholds.
    const current = typeof stored === 'number' && Number.isFinite(stored) ? stored : 0;
    const next = current + 1;
    tx.set(
      progressRef,
      { completedEventsAttended: next, updatedAt: FieldValue.serverTimestamp() },
      { merge: true },
    );
    // `create`, not `set`: the marker is a one-shot idempotency guard, so an
    // unexpected second writer must fail loudly rather than silently overwrite
    // it. Correctness does not rest on this — creditRef is READ above, so a
    // concurrent claim invalidates the read set and Firestore aborts the loser
    // — but a create says what is meant.
    tx.create(creditRef, { eventId, createdAt: FieldValue.serverTimestamp() });
    return next;
  });

  if (attendanceCount === null) {
    return;
  }
  for (const badgeKey of qualifiedEventBadges(attendanceCount)) {
    await awardBadge({ targetUid, badgeKey, source: 'automatic' });
  }
}

/**
 * Evaluates the early_member badge (legacy evaluateEarlyMember): awarded to
 * accounts created strictly before the EARLY_MEMBER_CUTOFF_DATE
 * configuration value. Unset/invalid cutoff → never awarded (safe default).
 * Called from auth.completeOnboarding; bounded to one document read.
 */
export async function evaluateEarlyMember(targetUid: string): Promise<void> {
  const cutoff = parseEarlyMemberCutoff(process.env.EARLY_MEMBER_CUTOFF_DATE);
  if (!cutoff) {
    return;
  }
  const userSnap = await db.collection('users').doc(targetUid).get();
  const createdAt = userSnap.data()?.createdAt;
  if (!(createdAt instanceof Timestamp)) {
    return;
  }
  if (!qualifiesAsEarlyMember(createdAt.toDate(), cutoff)) {
    return;
  }
  await awardBadge({ targetUid, badgeKey: 'early_member', source: 'automatic' });
}

/**
 * Fire-and-log wrapper for automatic evaluations hooked into other domains:
 * a badge failure must never fail the primary operation (legacy parity —
 * the API logged and continued).
 */
export async function tryAutomaticAward(
  targetUid: string,
  badgeKey: BadgeKey,
  context: string,
): Promise<void> {
  try {
    await awardBadge({ targetUid, badgeKey, source: 'automatic' });
  } catch (error) {
    logger.error('Automatic badge award failed', {
      targetUid,
      badgeKey,
      context,
      error: String(error),
    });
  }
}
