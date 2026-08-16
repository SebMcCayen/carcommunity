/**
 * Per-month member stat buckets — the Admin-SDK writers feeding the MONTHLY
 * social leaderboard (`leaderboards/{YYYY-MM}`).
 *
 * Each bucket is `memberMonthlyStats/{YYYY-MM}__{uid}`, a BACKEND-ONLY document
 * (denied to every client in firestore.rules) that the three activity triggers
 * increment additively and the scheduled generator reads. It is the monthly
 * mirror of the all-time `badgeProgress/{uid}` counters: the SAME source event
 * that bumps a lifetime badge counter also bumps the corresponding month's
 * bucket, in the same trigger, so the two never diverge on which activity they
 * count — only on the window they count it over.
 *
 * The month is the Kronjakt season id (`seasonIdForInstant`), so the monthly
 * board reuses the crown season's Europe/Stockholm month boundaries rather than
 * inventing its own — a member's distance for August lands in exactly the same
 * bucket the crown-points board calls August.
 *
 * IDEMPOTENCY — the SAME property the badge counters have, deliberately.
 * `FieldValue.increment` is commutative so concurrent source events cannot race,
 * but the additive path is NOT idempotent under an at-least-once trigger
 * REDELIVERY (a replayed source write increments twice). This matches
 * `bumpBadgeCounter` exactly: the bounded consequence is a monthly counter
 * drifting slightly high, never an economy exploit (the board grants nothing but
 * a rank), and redelivery is a platform event a client cannot induce. The one
 * category where over-counting was cheap to prevent — verified event attendance —
 * writes its monthly bucket INSIDE the same transaction that claims the
 * `attendanceCredits/{eventId}` one-shot marker (badges/awards.ts), so it is
 * exactly-once for free.
 */

import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { seasonIdForInstant } from '../crownHunt/crown-hunt-stats-core';
import {
  MEMBER_MONTHLY_STATS_COLLECTION,
  memberMonthlyStatsDocId,
} from './leaderboard-core';

/** `memberMonthlyStats/{scope}__{uid}` document reference. */
export function memberMonthlyStatsRef(
  scope: string,
  uid: string,
): FirebaseFirestore.DocumentReference {
  return db.collection(MEMBER_MONTHLY_STATS_COLLECTION).doc(memberMonthlyStatsDocId(scope, uid));
}

/**
 * The merge-set payload for one additive monthly-bucket increment. `scope` and
 * `uid` are (re)written on every bump so the document always carries both: the
 * generator reads them and the account-deletion purge selects the member's rows
 * by the `uid` field (PURGE_OWNED_COLLECTIONS). Exported so the verified-
 * attendance path can apply the identical write inside its own transaction.
 */
export function memberMonthlyStatPayload(
  scope: string,
  uid: string,
  field: string,
  delta: number,
): Record<string, unknown> {
  return {
    scope,
    uid,
    [field]: FieldValue.increment(delta),
    updatedAt: FieldValue.serverTimestamp(),
  };
}

/**
 * Adds `delta` to one member's month bucket for the month `instant` falls in.
 * The month is derived from the event's own timestamp via `seasonIdForInstant`,
 * so a bucket is credited to the calendar month the activity happened in even if
 * the trigger runs slightly later. A non-finite or non-positive delta is dropped
 * (a corrupt source value must not poison a counter), matching `bumpBadgeCounter`.
 */
export async function bumpMemberMonthlyStat(
  uid: string,
  instant: Date,
  field: string,
  delta: number,
): Promise<boolean> {
  if (!Number.isFinite(delta) || delta <= 0) {
    return false;
  }
  const scope = seasonIdForInstant(instant);
  await memberMonthlyStatsRef(scope, uid).set(
    memberMonthlyStatPayload(scope, uid, field, delta),
    { merge: true },
  );
  return true;
}
