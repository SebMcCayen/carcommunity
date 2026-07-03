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
 */

import { FieldValue } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { isRestricted, toUserAccessState } from '../shared/access';
import { buildBadgeDocument, qualifiedEventBadges, type BadgeKey } from './badge-core';

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
        { source: params.source, awardedByUserId: params.awardedByUserId ?? null },
        () => FieldValue.serverTimestamp(),
      ),
    );
    return { badgeKey: params.badgeKey, alreadyAwarded: false };
  });
}

/**
 * Records one completed-event attendance for a user (going-RSVP proxy,
 * legacy parity) on the backend-only badgeProgress/{uid} counter, then
 * awards any newly qualified event badges. Called from events.complete for
 * each going attendee — the completed transition is single-shot, so each
 * event increments a user at most once.
 */
export async function recordEventAttendance(targetUid: string): Promise<void> {
  const progressRef = db.collection('badgeProgress').doc(targetUid);
  const attendanceCount = await db.runTransaction(async (tx) => {
    const snap = await tx.get(progressRef);
    const current = (snap.data()?.completedEventsAttended as number | undefined) ?? 0;
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
