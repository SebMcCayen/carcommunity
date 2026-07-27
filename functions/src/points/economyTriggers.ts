/**
 * The points-economy FIRESTORE TRIGGERS.
 *
 * Every rule that can hang off something the backend already writes does,
 * rather than off a new callable. That is the whole anti-forgery strategy:
 *
 *  - `rides/{rideId}` is only ever created by drives.save, which computes
 *    distanceMeters server-side from the submitted track. A client cannot
 *    write the document and cannot write the distance, so `drive_5km` cannot
 *    be minted by lying to an endpoint;
 *  - `incidents/{id}/confirmations/{uid}` is only ever created by
 *    incidents.confirm, which already refuses self-confirmation, imported
 *    incidents and repeats. `incident_report_confirmed` therefore requires a
 *    DIFFERENT member to act;
 *  - `vehicles/{vehicleId}` is only ever created by garage.addVehicle;
 *  - `eventAttendance/{id}` only flips `verified` inside the server's own
 *    geofence+dwell evaluation (events/checkIn.ts).
 *
 * Firestore rules deny client writes to all four collections, so there is no
 * second writer to spoof any of them.
 *
 * Triggers are at-least-once; every award is keyed on a deterministic
 * idempotency key derived from the document, so a redelivery is a no-op.
 * All of them are best-effort: a gamification side effect must never fail (or
 * retry) the user action that already succeeded.
 */

import { onDocumentCreated, onDocumentWritten } from 'firebase-functions/v2/firestore';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { tryAwardEconomyPoints } from './economy-award';
import {
  DRIVE_AWARD_MIN_DISTANCE_METERS,
  EVENT_ATTENDANCE_COUNTS_COLLECTION,
  HOST_SUCCESS_MIN_VERIFIED_ATTENDEES,
  POINTS_DAILY_TOTALS_COLLECTION,
  POINTS_LEDGER_FOLDS_COLLECTION,
  dailyTotalDocId,
  economyIdempotencyKey,
  ledgerFoldDocId,
  readCount,
  stockholmDayKey,
} from './points-economy-core';

const TRIGGER_OPTS = {
  region: 'europe-west1',
  memory: '256MiB' as const,
  timeoutSeconds: 60,
};

// ---------------------------------------------------------------------------
// drive_5km — a saved drive of at least 5 km
// ---------------------------------------------------------------------------

export const onDriveSaved = onDocumentCreated(
  { ...TRIGGER_OPTS, document: 'rides/{rideId}' },
  async (event) => {
    const data = event.data?.data();
    if (!data) {
      return;
    }
    const uid = data.userId;
    const distanceMeters = data.distanceMeters;
    if (typeof uid !== 'string' || uid.length === 0) {
      return;
    }
    // Server-computed by drives.save. A drive with no usable distance (an
    // empty or unparseable track) earns nothing rather than defaulting.
    if (typeof distanceMeters !== 'number' || !Number.isFinite(distanceMeters)) {
      return;
    }
    if (distanceMeters < DRIVE_AWARD_MIN_DISTANCE_METERS) {
      return;
    }

    const rideId = event.params.rideId;
    const idempotencyKey = economyIdempotencyKey('drive_5km', rideId);
    if (!idempotencyKey) {
      logger.warn('drive_5km: unusable rideId for an award key', { rideId });
      return;
    }
    await tryAwardEconomyPoints({
      uid,
      rule: 'drive_5km',
      now: event.data?.createTime?.toDate() ?? new Date(),
      idempotencyKey,
      relatedEntityType: 'ride',
      relatedEntityId: rideId,
      detail: `${Math.round(distanceMeters / 1000)} km`,
    });
  },
);

// ---------------------------------------------------------------------------
// incident_report_confirmed — someone else corroborated your report
// ---------------------------------------------------------------------------

export const onIncidentConfirmed = onDocumentCreated(
  { ...TRIGGER_OPTS, document: 'incidents/{incidentId}/confirmations/{confirmerUid}' },
  async (event) => {
    const incidentId = event.params.incidentId;
    const confirmerUid = event.params.confirmerUid;

    // Best-effort by contract (see the module header): the confirmation is
    // already committed, so an unreadable incident must end this invocation
    // quietly rather than escape as a function failure. Retries are off for
    // these triggers (firebase-functions v2 defaults `retry` to false and no
    // trigger in this repo opts in), so an escaping throw would not be
    // re-delivered — it would only be noise in the error budget. The award is
    // re-earnable by replaying the source event, and the idempotency key makes
    // that safe.
    let incident: FirebaseFirestore.DocumentData | undefined;
    try {
      incident = (await db.collection('incidents').doc(incidentId).get()).data();
    } catch (error) {
      logger.warn('incident_report_confirmed: incident unreadable', {
        incidentId,
        error: String(error),
      });
      return;
    }
    if (!incident) {
      return;
    }
    // Imported (Trafikverket) incidents have no member reporter to reward;
    // incidents.confirm refuses them anyway, so this is belt-and-braces.
    if (incident.source !== 'user') {
      return;
    }
    const reporterUid = incident.reporterUid;
    if (typeof reporterUid !== 'string' || reporterUid.length === 0) {
      return;
    }
    // Self-corroboration is not evidence. incidents.confirm already rejects
    // it; re-checking here means a future writer of the subcollection cannot
    // reintroduce the loophole.
    if (reporterUid === confirmerUid) {
      return;
    }

    const idempotencyKey = economyIdempotencyKey(
      'incident_report_confirmed',
      incidentId,
      confirmerUid,
    );
    if (!idempotencyKey) {
      logger.warn('incident_report_confirmed: unusable ids for an award key', {
        incidentId,
        confirmerUid,
      });
      return;
    }
    await tryAwardEconomyPoints({
      uid: reporterUid,
      rule: 'incident_report_confirmed',
      now: event.data?.createTime?.toDate() ?? new Date(),
      idempotencyKey,
      relatedEntityType: 'incident',
      relatedEntityId: incidentId,
    });
  },
);

// ---------------------------------------------------------------------------
// garage_first_car — once ever, on the first car in the garage
// ---------------------------------------------------------------------------

export const onVehicleCreated = onDocumentCreated(
  { ...TRIGGER_OPTS, document: 'vehicles/{vehicleId}' },
  async (event) => {
    const uid = event.data?.data()?.userId;
    if (typeof uid !== 'string' || uid.length === 0) {
      return;
    }
    // ONCE EVER, by construction: the idempotency key is the uid alone, so
    // deleting the car and adding another cannot re-earn it. The rule's
    // `forever` limit counter is the second, independent guard.
    const idempotencyKey = economyIdempotencyKey('garage_first_car', uid);
    if (!idempotencyKey) {
      logger.warn('garage_first_car: unusable uid for an award key', { uid });
      return;
    }
    await tryAwardEconomyPoints({
      uid,
      rule: 'garage_first_car',
      now: event.data?.createTime?.toDate() ?? new Date(),
      idempotencyKey,
      relatedEntityType: 'vehicle',
      relatedEntityId: event.params.vehicleId,
    });
  },
);

// ---------------------------------------------------------------------------
// event_attend_verified + event_host_success
// ---------------------------------------------------------------------------

/**
 * Fires when an attendance record flips to `verified` (events/checkIn.ts sets
 * it only after the server's own geofence + dwell evaluation passes).
 *
 * Doing the award HERE rather than inline in the callable is what makes it
 * durable: the callable's job ends when the evidence is recorded, and the
 * trigger's at-least-once delivery plus the deterministic idempotency key
 * mean the award happens exactly once even if the callable's caller
 * disconnects the millisecond after the record is written.
 */
export const onAttendanceVerified = onDocumentWritten(
  { ...TRIGGER_OPTS, document: 'eventAttendance/{attendanceId}' },
  async (event) => {
    const before = event.data?.before.data();
    const after = event.data?.after.data();
    if (!after || after.verified !== true || before?.verified === true) {
      return;
    }
    const eventId = after.eventId;
    const uid = after.userId;
    if (typeof eventId !== 'string' || typeof uid !== 'string' || !eventId || !uid) {
      return;
    }
    const now = event.data?.after.updateTime?.toDate() ?? new Date();

    const attendKey = economyIdempotencyKey('event_attend_verified', eventId, uid);
    if (!attendKey) {
      logger.warn('event_attend_verified: unusable ids for an award key', { eventId, uid });
    } else {
      await tryAwardEconomyPoints({
        uid,
        rule: 'event_attend_verified',
        now,
        idempotencyKey: attendKey,
        limitWindowKey: eventId,
        relatedEntityType: 'event',
        relatedEntityId: eventId,
      });
    }

    await maybeAwardHost(eventId, uid, now);
  },
);

/**
 * Counts this verified attendee and, once the event has at least
 * HOST_SUCCESS_MIN_VERIFIED_ATTENDEES of them, pays the host once.
 *
 * The count is a COUNTER document, not a query: `eventAttendanceCounts/{id}`
 * holds the total and a `counted/{uid}` subdocument claimed with `tx.create`
 * makes each attendee count exactly once even under redelivery. That also
 * means no composite index is needed for this feature.
 */
async function maybeAwardHost(eventId: string, uid: string, now: Date): Promise<void> {
  const countsRef = db.collection(EVENT_ATTENDANCE_COUNTS_COLLECTION).doc(eventId);
  const countedRef = countsRef.collection('counted').doc(uid);

  let verifiedCount: number;
  try {
    verifiedCount = await db.runTransaction(async (tx) => {
      const [countsSnap, countedSnap] = await Promise.all([tx.get(countsRef), tx.get(countedRef)]);
      // Read through the shared `readCount`, like every other economy counter.
      // A local `Number.isSafeInteger` check would accept a NEGATIVE tally,
      // and this counter is the gate on the host award rather than a ceiling:
      // a negative holds the tally under HOST_SUCCESS_MIN_VERIFIED_ATTENDEES
      // instead of granting headroom. Non-negative or nothing.
      const stored = readCount(countsSnap.data()?.verifiedCount);
      if (countedSnap.exists) {
        return stored;
      }
      // `create`, not `set`: the claim is a one-shot guard, so an unexpected
      // second writer must fail loudly rather than silently overwrite it and
      // let the tally double-count an attendee. Same shape as the crown fold
      // marker in onLedgerEntryCreated. (Correctness does not rest on this —
      // countedRef is READ in this transaction, so a concurrent claim aborts
      // and retries it — but the doc above promised `create`, so it is
      // `create`.)
      tx.create(countedRef, { userId: uid, createdAt: FieldValue.serverTimestamp() });
      // The CLAMPED value, not `FieldValue.increment(1)`. Increment would be
      // applied by the server to whatever is actually stored, so a corrupt
      // `-3` would persist as `-2` while this function returned 1 — the
      // clamp would exist only in memory, the document would stay negative,
      // and because `counted/{uid}` is a one-shot claim each attendee spent
      // climbing back to 0 is spent for good. The tally would need
      // |negative| + HOST_SUCCESS_MIN_VERIFIED_ATTENDEES attendees instead of
      // HOST_SUCCESS_MIN_VERIFIED_ATTENDEES, which for a meet that ends first
      // means the host is simply never paid.
      //
      // Writing the absolute value is safe precisely BECAUSE countsRef is
      // read in this transaction: a concurrent writer invalidates the read
      // set and Firestore aborts and retries, so no increment can be lost.
      // (`increment` earns its keep when a doc is written WITHOUT being read;
      // here it only lets the stored value diverge from the returned one.)
      tx.set(
        countsRef,
        {
          eventId,
          verifiedCount: stored + 1,
          updatedAt: FieldValue.serverTimestamp(),
        },
        { merge: true },
      );
      return stored + 1;
    });
  } catch (error) {
    logger.error('Verified-attendee count failed', { eventId, error: String(error) });
    return;
  }

  if (verifiedCount < HOST_SUCCESS_MIN_VERIFIED_ATTENDEES) {
    return;
  }

  // Same best-effort contract as the transaction above: the attendee has
  // already been counted, so an unreadable event ends this quietly. The tally
  // is durable and this runs again for every subsequent verified attendee, so
  // the host award self-heals as long as one more member checks in; if the
  // event ends on exactly HOST_SUCCESS_MIN_VERIFIED_ATTENDEES it is lost, and
  // an admin adjustment is the remedy. Losing 75 points is strictly better
  // than a trigger that fails loudly on a gamification side effect.
  let hostUid: unknown;
  try {
    hostUid = (await db.collection('events').doc(eventId).get()).data()?.createdByUserId;
  } catch (error) {
    logger.warn('event_host_success: event unreadable', { eventId, error: String(error) });
    return;
  }
  if (typeof hostUid !== 'string' || hostUid.length === 0) {
    return;
  }
  const hostKey = economyIdempotencyKey('event_host_success', eventId);
  if (!hostKey) {
    logger.warn('event_host_success: unusable eventId for an award key', { eventId });
    return;
  }
  await tryAwardEconomyPoints({
    uid: hostUid,
    rule: 'event_host_success',
    now,
    idempotencyKey: hostKey,
    limitWindowKey: eventId,
    relatedEntityType: 'event',
    relatedEntityId: eventId,
    detail: `${verifiedCount} verifierade deltagare`,
  });
}

// ---------------------------------------------------------------------------
// Kronjakt crowns -> the global daily cap
// ---------------------------------------------------------------------------

/**
 * Folds Kronjakt crown awards into the member's DAILY total.
 *
 * DECISION: crowns count against DAILY_POINTS_CAP, and do NOT count against
 * WEEKLY_DRIVING_POINTS_CAP.
 *
 *  - Global cap YES: the daily ceiling exists so no single day can be farmed
 *    into a leaderboard position. Crowns are worth 10-500 each; excluding
 *    them would make the ceiling trivially bypassable and turn the whole
 *    economy into a crown race, which is exactly what a ceiling is for.
 *  - Driving cap NO: that cap exists specifically to remove any incentive to
 *    drive further or more often. A crown is a destination, not a distance,
 *    in BOTH of its forms: hand-placed points are fixed coordinates an admin
 *    has reviewed as safe to stop at, with their own repeat rules; auto-spawned
 *    crowns are machine-placed inside admin-APPROVED cells, expire on their own
 *    TTL, and are collectable only while STOPPED and dwelling (crownHunt/
 *    crown-spawn-core.ts), so neither form pays for kilometres and both carry
 *    their own daily claim limit. Charging them to the driving lane would
 *    penalise a member for visiting a crown and would eat the headroom for the
 *    drive rules without capping anything that scales with kilometres.
 *
 * MECHANISM: both crown award paths — crownHunt.submitClaim (hand-placed
 * points) and crownHunt.claimSpawn (auto-spawned crowns) — credit the ledger
 * directly with `source: 'crown_hunt'` (that domain is owned elsewhere and its
 * award paths are deliberately left untouched), so the fold happens here,
 * after the fact, for both. Two consequences, stated plainly:
 *  - a crown is never CLIPPED by the cap — a 500-point crown pays 500 even on
 *    a day that already had 280 — but it does consume the day's budget, so
 *    economy awards for the rest of that local day return `cap_reached`;
 *  - the fold is eventually consistent (trigger latency, typically under a
 *    second). An economy award landing inside that window may be paid before
 *    the crown is folded in. The overshoot is bounded by one rule's value
 *    (at most 75 points) and self-corrects for the rest of the day.
 * Routing crowns through `awardEconomyPoints` would make them clipped and
 * exact; that is a small change at the two crownHunt award sites whenever that
 * team wants it.
 *
 * Non-crown sources are deliberately NOT folded: economy awards already
 * increment the counter inside their own transaction, and admin adjustments,
 * reversals and badge milestones are not member "earning" that a daily
 * grinding ceiling should apply to.
 *
 * The filter is on `transactionType === 'earn'`, so this trigger never UNDOES
 * a fold either: `points.adminReverse` writes a `reversal` entry, and the day
 * that a reversed crown consumed stays consumed. That is the same asymmetry
 * documented on DAILY_POINTS_CAP — the counter is a record of what was paid
 * out during the day, not a live mirror of the balance — and it is the
 * conservative direction: the worst case is a member paid slightly less on
 * the one day an admin corrected something.
 */
export const onLedgerEntryCreated = onDocumentCreated(
  { ...TRIGGER_OPTS, document: 'pointsLedger/{uid}/entries/{entryId}' },
  async (event) => {
    const data = event.data?.data();
    if (!data || data.source !== 'crown_hunt' || data.transactionType !== 'earn') {
      return;
    }
    const amount = data.amount;
    if (typeof amount !== 'number' || !Number.isSafeInteger(amount) || amount <= 0) {
      return;
    }
    const uid = event.params.uid;
    const entryId = event.params.entryId;
    const createdAt =
      data.createdAt instanceof Timestamp
        ? data.createdAt.toDate()
        : (event.data?.createTime?.toDate() ?? new Date());
    const dayKey = stockholmDayKey(createdAt);

    const foldRef = db.collection(POINTS_LEDGER_FOLDS_COLLECTION).doc(ledgerFoldDocId(uid, entryId));
    const dailyRef = db.collection(POINTS_DAILY_TOTALS_COLLECTION).doc(dailyTotalDocId(uid, dayKey));

    try {
      await db.runTransaction(async (tx) => {
        // EXACTLY ONCE under at-least-once delivery: the increment is not
        // idempotent, so the fold marker is claimed with `create` in the same
        // transaction. A redelivery finds it and does nothing.
        if ((await tx.get(foldRef)).exists) {
          return;
        }
        tx.create(foldRef, {
          userId: uid,
          entryId,
          amount,
          day: dayKey,
          createdAt: FieldValue.serverTimestamp(),
        });
        tx.set(
          dailyRef,
          {
            userId: uid,
            day: dayKey,
            total: FieldValue.increment(amount),
            updatedAt: FieldValue.serverTimestamp(),
          },
          { merge: true },
        );
      });
    } catch (error) {
      logger.warn('Crown fold into the daily points cap failed', {
        uid,
        entryId,
        error: String(error),
      });
    }
  },
);
