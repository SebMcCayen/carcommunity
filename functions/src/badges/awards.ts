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
 * recordEventAttendance below deliberately needs NO change to feed Träffräv:
 * the counter it writes lives on badgeProgress/{uid}, and the
 * badges-onBadgeProgressWritten trigger evaluates the ladders off that write.
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
  awardedByUserId?: string | null;
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
 * Records one completed-event attendance for a user (going-RSVP proxy,
 * legacy parity) on the backend-only badgeProgress/{uid} counter, then
 * awards any newly qualified event badges.
 *
 * Called (via creditEventAttendance) for each going attendee by BOTH paths
 * that complete an event: the events.complete callable and the events-autoClose
 * scheduled sweep. Each event increments a given user at most once because
 * `completed` is terminal, so only the one writer that actually performed the
 * published→completed transition credits — see creditEventAttendance's caller
 * contract in events/eventLifecycle.ts, which is where that guarantee lives.
 */
export async function recordEventAttendance(targetUid: string): Promise<void> {
  const progressRef = db.collection('badgeProgress').doc(targetUid);
  const attendanceCount = await db.runTransaction(async (tx) => {
    const snap = await tx.get(progressRef);
    const stored = snap.data()?.completedEventsAttended;
    // Guard against a corrupted counter (e.g. a string) — '3' + 1 would
    // concatenate and permanently break the thresholds.
    const current = typeof stored === 'number' && Number.isFinite(stored) ? stored : 0;
    const next = current + 1;
    tx.set(
      progressRef,
      { completedEventsAttended: next, updatedAt: FieldValue.serverTimestamp() },
      { merge: true },
    );
    return next;
  });

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
