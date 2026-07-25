/**
 * Badge progress triggers — how a tiered badge is actually earned.
 *
 * Badges are awarded from Firestore triggers, NOT from a callable, so a badge
 * cannot be forged: every counter below is derived from a document the backend
 * itself wrote after its own validation, and `badgeProgress/{uid}` denies all
 * client reads and writes in firestore.rules. There is no client-reported
 * number anywhere in this chain.
 *
 * The chain is deliberately split in two:
 *
 *   source write  →  counter bump  →  badgeProgress write  →  EVALUATION
 *   (5 triggers below)                (onBadgeProgressWritten)
 *
 * Each source trigger only increments its own counter. A SINGLE trigger on
 * `badgeProgress/{uid}` then evaluates every ladder for that one member. That
 * split is what keeps evaluation cheap and complete at the same time:
 *  - cheap — evaluation is per-user and fires only when that user's counters
 *    actually changed; nothing ever scans all users on a write;
 *  - complete — it also picks up the completed-event counter maintained by the
 *    PRE-EXISTING attendance path (awards.ts::recordEventAttendance), so
 *    Träffräv needed no change to the events domain at all;
 *  - loop-free — evaluation writes to `users/{uid}/badges` and `pointsLedger`,
 *    never back to `badgeProgress`, so it cannot retrigger itself.
 *
 * COUNTER SOURCES AND THEIR ANTI-ABUSE PROPERTY
 *  - Kronjägare  ← `crownHuntClaims/{id}` reaching `result: 'awarded'`. A
 *                  `risk_review` claim (the anti-fraud outcome, which also
 *                  awards no Kronpoäng) NEVER counts.
 *  - Vägfarare   ← `rides/{id}.distanceMeters`, computed server-side by
 *                  drives.save from the submitted route.
 *  - Träffräv    ← `badgeProgress/{uid}.completedEventsAttended`, credited by
 *                  the event lifecycle for a `going` RSVP on an event that
 *                  reached `completed` — the existing verified-attendance
 *                  signal (see the SEAM note below).
 *  - Trogen      ← `userLifecycle/{uid}.lastLoginAt`, a trusted server write
 *                  (auth.recordLogin uses the Admin SDK; rules deny every
 *                  client write to that document).
 *  - Konvojledare← `convoys/{id}` created by the member and actually started.
 *  - Samlare     ← a server-side `count()` of the member's `vehicles`.
 *
 * SEAM — VERIFIED EVENT ATTENDANCE. A richer `event_attend_verified` record
 * type (per-attendee check-in verification) does not exist on `main` at the
 * time of writing. Träffräv is therefore coded against the counter that DOES
 * exist and is already authoritative and server-written:
 * `badgeProgress/{uid}.completedEventsAttended`. If that richer signal lands
 * later, the ONLY change needed is for its writer to increment this same
 * counter (or for BADGE_METRIC_FIELD.verifiedEventsAttended in badge-tiers.ts
 * to point at the new field) — the ladder, thresholds, evaluation and awards
 * need no change whatsoever.
 */

import { onDocumentCreated, onDocumentWritten } from 'firebase-functions/v2/firestore';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import {
  advanceStreak,
  claimUserId,
  convoyLedOwnerUid,
  crownClaimCrownDelta,
  readStreakState,
  rideDistanceDelta,
  streakDayKey,
} from './badge-tiers';
import {
  badgeProgressRef,
  bumpBadgeCounter,
  reconcileDerivedBadgeCounters,
  tryEvaluateBadgeTiers,
} from './tierAwards';

const TRIGGER_OPTS = {
  region: 'europe-west1',
  memory: '256MiB' as const,
  timeoutSeconds: 60,
};

// ---------------------------------------------------------------------------
// Evaluation
// ---------------------------------------------------------------------------

/**
 * The one place tiers are evaluated. Fires on any change to a member's
 * server-verified counters and awards whatever they have newly reached.
 *
 * A delete is ignored (a counter document going away is not an achievement),
 * and evaluation never writes back to `badgeProgress`, so this trigger cannot
 * cascade into itself.
 */
export const onBadgeProgressWritten = onDocumentWritten(
  { ...TRIGGER_OPTS, document: 'badgeProgress/{uid}' },
  async (firestoreEvent) => {
    if (!firestoreEvent.data?.after.exists) {
      return;
    }
    // The event payload IS the badgeProgress document — pass it straight to
    // the evaluator so the hot path (every counter bump) re-reads nothing.
    await tryEvaluateBadgeTiers(
      firestoreEvent.params.uid,
      'badgeProgress.write',
      firestoreEvent.data.after.data(),
    );
  },
);

// ---------------------------------------------------------------------------
// Counter sources
// ---------------------------------------------------------------------------

/** Kronjägare — one crown per claim that actually resolved to `awarded`. */
export const onCrownClaimWritten = onDocumentWritten(
  { ...TRIGGER_OPTS, document: 'crownHuntClaims/{claimId}' },
  async (firestoreEvent) => {
    const before = firestoreEvent.data?.before.data();
    const after = firestoreEvent.data?.after.data();
    const delta = crownClaimCrownDelta(before, after);
    if (delta === 0) {
      return;
    }
    const uid = claimUserId(after);
    if (!uid) {
      logger.warn('Awarded crown claim without a userId', {
        claimId: firestoreEvent.params.claimId,
      });
      return;
    }
    await bumpBadgeCounter(uid, 'crownsCollected', delta);
  },
);

/** Vägfarare — lifetime metres, from the server-computed drive distance. */
export const onRideCreated = onDocumentCreated(
  { ...TRIGGER_OPTS, document: 'rides/{rideId}' },
  async (firestoreEvent) => {
    const data = firestoreEvent.data?.data();
    const metres = rideDistanceDelta(data);
    const uid = typeof data?.userId === 'string' ? data.userId : null;
    if (metres === 0 || !uid) {
      return;
    }
    await bumpBadgeCounter(uid, 'lifetimeDistanceMeters', metres);
  },
);

/** Konvojledare — convoys the member created that actually went live. */
export const onConvoyWritten = onDocumentWritten(
  { ...TRIGGER_OPTS, document: 'convoys/{convoyId}' },
  async (firestoreEvent) => {
    const ownerUid = convoyLedOwnerUid(
      firestoreEvent.data?.before.data(),
      firestoreEvent.data?.after.data(),
    );
    if (!ownerUid) {
      return;
    }
    await bumpBadgeCounter(ownerUid, 'convoysLed', 1);
  },
);

/**
 * Samlare — the member's vehicle count, re-derived server-side on each new
 * vehicle with a `count()` aggregation (one aggregation read, no document
 * fan-out) and stored as a running MAXIMUM. Deleting a car therefore never
 * strips a Samlare tier already earned, and delete-then-re-add cannot award
 * twice.
 */
export const onVehicleCreated = onDocumentCreated(
  { ...TRIGGER_OPTS, document: 'vehicles/{vehicleId}' },
  async (firestoreEvent) => {
    const uid = firestoreEvent.data?.data()?.userId;
    if (typeof uid !== 'string' || uid.length === 0) {
      return;
    }
    await reconcileDerivedBadgeCounters(uid);
  },
);

/**
 * Trogen — the consecutive-day app-open streak.
 *
 * `userLifecycle/{uid}.lastLoginAt` is stamped by auth.recordLogin, which the
 * client calls once per app start; rules deny every client write to that
 * document, so the timestamp is a trusted server write. Days are counted in
 * LOCAL Swedish calendar days (badge-tiers.ts::streakDayKey), and the ladder
 * measures `bestDayStreak` — the best run EVER — so breaking a streak costs
 * the current run but never an already-earned tier.
 *
 * The transaction writes nothing when the member has already been counted for
 * today, which is the case for every app open after the first each day.
 *
 * `userLifecycle/{uid}` is NOT written only by recordLogin: the inactivity
 * sweep merges `inactivityWarnedAt` / `inactivityDeleteAfter` into the same
 * document (account/inactivityCleanup.ts::warnAccount, ::clearWarning), leaving
 * `lastLoginAt` untouched. Those writes fire this trigger too, so it returns
 * early unless `lastLoginAt` ITSELF changed — otherwise every warn/clear would
 * pay for a `badgeProgress` transaction read to compute a day key that is
 * necessarily the one already stored.
 */
export const onUserLifecycleWritten = onDocumentWritten(
  { ...TRIGGER_OPTS, document: 'userLifecycle/{uid}' },
  async (firestoreEvent) => {
    const lastLoginAt = firestoreEvent.data?.after.data()?.lastLoginAt;
    if (!(lastLoginAt instanceof Timestamp)) {
      return;
    }
    const previousLoginAt = firestoreEvent.data?.before.data()?.lastLoginAt;
    if (previousLoginAt instanceof Timestamp && previousLoginAt.isEqual(lastLoginAt)) {
      return;
    }
    const dayKey = streakDayKey(lastLoginAt.toDate());
    const uid = firestoreEvent.params.uid;
    const ref = badgeProgressRef(uid);
    await db.runTransaction(async (tx) => {
      const snap = await tx.get(ref);
      const advanced = advanceStreak(readStreakState(snap.data()), dayKey);
      if (!advanced.changed) {
        return;
      }
      tx.set(
        ref,
        {
          currentDayStreak: advanced.state.currentDayStreak,
          bestDayStreak: advanced.state.bestDayStreak,
          lastStreakDayKey: advanced.state.lastStreakDayKey,
          updatedAt: FieldValue.serverTimestamp(),
        },
        { merge: true },
      );
    });
  },
);
