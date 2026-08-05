/**
 * Live location TTL sweep (Phase 10).
 *
 * live-cleanupExpired (every 5 minutes — the mapping's cadence):
 * - sessions past expiresAt flip to status `expired` and their `latest`
 *   marker is removed;
 * - `latest` nodes whose recordedAt is older than 15 minutes are removed
 *   even if the session is nominally active (client went silent — the
 *   mapping's "15-minute maximum" position TTL);
 * - the queryable nearby-discovery docs (Firestore liveSessions/{uid}) are
 *   swept too: any doc past its own expiresAt is deleted, so a sharer who
 *   went silent stops being discoverable by live.listNearby (which already
 *   HIDES expired docs, but they must not accumulate). This is the
 *   Firestore-side TTL, the counterpart to the RTDB marker removal above.
 *
 * A full scan of liveLocation/ is deliberate: at MVP scale (tens of
 * concurrent sharers, lean nodes) it is cheaper and simpler than
 * maintaining index nodes. runLiveCleanup is exported for tests.
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { logger } from 'firebase-functions';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { adminRtdb, db } from '../firebase';
import {
  decideConvoyRideFinalize,
  isLatestStale,
  isSessionActive,
  type LiveSession,
} from './live-core';
import {
  buildRideDocument,
  computeDriveStats,
  type SaveDriveInput,
} from '../drives/drives-core';
import { MAX_INSTANCES_SCHEDULED } from '../shared/instanceLimits';
import { withServerErrorReporting } from '../errors/serverErrors';

/** Max discovery docs deleted per sweep (bounded; the 5-min cadence catches up). */
const DISCOVERY_SWEEP_LIMIT = 400;

/**
 * Max convoy-auto sessions finalized into a ride per sweep. Bounds the extra
 * Firestore work one run can fan out to; anything over the cap is caught by the
 * next 5-minute run (an un-finalized session is not lost — it simply waits).
 */
const CONVOY_RIDE_FINALIZE_LIMIT = 200;

/**
 * Writes the server-side baseline "convoy run" ride for a member whose app did
 * NOT save it client-side (backgrounded/killed at session end), then marks the
 * session finalized so it is never processed again. Returns how many rides were
 * newly written (an existing ride — the client already saved — counts as 0 but
 * is still flagged).
 *
 * The ride is keyed `rides/{uid}_{sessionId}`, the SAME id the client's
 * live-session save uses (SingleSessionRecording), so this and the client save
 * DEDUPE: a transaction only creates the doc when it is absent, so a client save
 * that already landed is preserved untouched (its richer route + stats stay) and
 * this becomes a flag-only no-op. Summary-only (no route points): duration comes
 * from the session's startedAt→stoppedAt, distance/speed are null — exactly a
 * duration-only client save.
 *
 * Best-effort per session: one failure is logged and skipped so a single bad
 * session cannot fail the whole sweep. The finalize flag is only written on a
 * successful ride reconcile, so a failed session is retried next run.
 */
async function finalizeConvoyRides(
  candidates: { uid: string; sessionId: string; startedAt: string; endedAt: string }[],
): Promise<number> {
  let written = 0;
  for (const { uid, sessionId, startedAt, endedAt } of candidates) {
    try {
      const rideRef = db.collection('rides').doc(`${uid}_${sessionId}`);
      const created = await db.runTransaction(async (tx) => {
        const existing = await tx.get(rideRef);
        if (existing.exists) {
          return false;
        }
        // Summary-only: no routePoints, so computeDriveStats derives duration
        // from the times and leaves distance/speed null (drives-core).
        const input: SaveDriveInput = { startedAt, endedAt, sourceSessionId: sessionId };
        tx.set(
          rideRef,
          buildRideDocument(
            input,
            { userId: uid, rideId: rideRef.id, stats: computeDriveStats(input), routeThumbnail: null },
            () => FieldValue.serverTimestamp(),
          ),
        );
        return true;
      });
      if (created) written += 1;
      // Mark finalized ONLY after the ride is reconciled, so a throw above leaves
      // the session unflagged and it is retried on the next sweep.
      //
      // GUARDED so the flag can only ever land on the SESSION WE FINALIZED: the
      // sweep read its snapshot earlier, and the user may have started a BRAND
      // NEW session in between (startSession / a new convoy auto-start replace the
      // node with a fresh id). A blind set on the `session` path would then flag
      // that new session and silently suppress ITS future finalize — the exact
      // lost-run bug this feature exists to prevent. The transaction only writes
      // when the node still carries the same session id; a replacement (different
      // id) aborts and is left untouched (the old session is already gone, so
      // there is nothing to flag). Mirrors stopConvoyAutoSession's null-vs-abort
      // convention: null re-runs against the server value, undefined aborts.
      await adminRtdb.ref(`liveLocation/${uid}/session`).transaction((current) => {
        const s = current as (LiveSession & { convoyRideFinalized?: boolean }) | null;
        if (s === null) {
          return null;
        }
        if (s.id !== sessionId) {
          return; // a new session replaced the one we finalized — do not flag it
        }
        return { ...s, convoyRideFinalized: true };
      });
    } catch (error) {
      logger.warn('convoy ride finalize failed', { uid, sessionId, error: String(error) });
    }
  }
  return written;
}

/**
 * Deletes nearby-discovery docs (liveSessions) whose expiresAt has passed, plus
 * any explicitly named uids (sessions the RTDB sweep just expired/removed).
 * Returns the number deleted.
 *
 * HARD-CAPPED at DISCOVERY_SWEEP_LIMIT deletes per run so a single sweep can
 * never fan out into an unbounded number of Firestore writes, even if
 * liveLocation/ ever accumulates a large backlog of stale nodes. Anything over
 * the cap is left for the next run: an over-cap expired doc is already HIDDEN by
 * live.listNearby (which filters expiresAt > now), so nothing stale is
 * discoverable in the meantime — it just isn't physically deleted until the
 * 5-minute cadence catches up. Named-uid deletes (sessions the RTDB sweep just
 * removed) are prioritised over the expiresAt backlog by being added first.
 */
export async function sweepDiscoveryDocs(now: Date, removedUids: Iterable<string>): Promise<number> {
  // Named uids first, capped to the per-run limit. These are sessions the RTDB
  // sweep just expired/removed, so they take priority over the expiry backlog.
  const toDelete = new Set<string>();
  for (const uid of removedUids) {
    if (toDelete.size >= DISCOVERY_SWEEP_LIMIT) break;
    toDelete.add(uid);
  }

  // Firestore-side TTL: docs past their own expiresAt, but only up to the
  // REMAINING capacity — so the query itself never reads more than the run can
  // delete. Anything over the cap waits for the next 5-minute run; it is already
  // HIDDEN by live.listNearby (expiresAt > now) in the meantime, so nothing
  // stale is discoverable, it just isn't physically deleted yet.
  const remaining = DISCOVERY_SWEEP_LIMIT - toDelete.size;
  if (remaining > 0) {
    const expired = await db
      .collection('liveSessions')
      .where('expiresAt', '<=', Timestamp.fromDate(now))
      .limit(remaining)
      .get();
    for (const doc of expired.docs) toDelete.add(doc.id);
  }

  if (toDelete.size === 0) return 0;
  // Chunk into batches (Firestore caps a write batch at 500). delete() on a
  // missing doc is a no-op, so naming an already-gone uid is harmless. The set
  // is already <= DISCOVERY_SWEEP_LIMIT, so this is bounded per run.
  const ids = [...toDelete];
  for (let i = 0; i < ids.length; i += 400) {
    const batch = db.batch();
    for (const id of ids.slice(i, i + 400)) {
      batch.delete(db.collection('liveSessions').doc(id));
    }
    await batch.commit();
  }
  return ids.length;
}

interface LiveUserNode {
  session?: LiveSession;
  latest?: { recordedAt?: string };
}

export async function runLiveCleanup(now: Date): Promise<{
  expiredSessions: number;
  removedMarkers: number;
  removedDiscoveryDocs: number;
  finalizedConvoyRides: number;
}> {
  const snapshot = await adminRtdb.ref('liveLocation').get();
  const users = (snapshot.val() ?? {}) as Record<string, LiveUserNode>;

  let expiredSessions = 0;
  let removedMarkers = 0;
  const updates: Record<string, unknown> = {};
  // Uids whose live marker was removed this sweep — their discovery doc must go
  // too, even if its own expiresAt has not been reached yet (a session that
  // expired on duration before the discovery TTL window elapsed).
  const removedUids = new Set<string>();
  // Convoy-auto sessions this sweep should back-stop into a saved ride (member
  // whose app may not have saved it client-side). Capped per run.
  const rideCandidates: { uid: string; sessionId: string; startedAt: string; endedAt: string }[] =
    [];

  for (const [uid, node] of Object.entries(users)) {
    const session = node.session;
    if (session && session.status === 'active' && !isSessionActive(session, now)) {
      updates[`${uid}/session/status`] = 'expired';
      updates[`${uid}/session/stoppedAt`] = now.toISOString();
      expiredSessions += 1;
      if (node.latest) {
        updates[`${uid}/latest`] = null;
        removedMarkers += 1;
      }
      removedUids.add(uid);
      // A session expired THIS run has stoppedAt = now, so it is within grace and
      // decideConvoyRideFinalize skips it; the NEXT sweep (after grace) finalizes
      // it. So no need to consider it here — fall through to the ended-session
      // check below only for sessions that were already stopped/expired.
      continue;
    }
    // Numeric comparison — producers may emit non-canonical ISO strings.
    if (node.latest?.recordedAt && isLatestStale(node.latest.recordedAt, now)) {
      updates[`${uid}/latest`] = null;
      removedMarkers += 1;
      removedUids.add(uid);
    }
    // Back-stop the member's run: an already-ended convoy-auto session, past its
    // grace window and not yet finalized, is a candidate for a server-side ride.
    if (session && rideCandidates.length < CONVOY_RIDE_FINALIZE_LIMIT) {
      const decision = decideConvoyRideFinalize(session, now);
      if (decision.finalize) {
        rideCandidates.push({
          uid,
          sessionId: session.id,
          startedAt: decision.startedAt!,
          endedAt: decision.endedAt!,
        });
      }
    }
  }

  if (Object.keys(updates).length > 0) {
    await adminRtdb.ref('liveLocation').update(updates);
  }

  const removedDiscoveryDocs = await sweepDiscoveryDocs(now, removedUids);
  const finalizedConvoyRides = await finalizeConvoyRides(rideCandidates);

  logger.info('Live location cleanup complete', {
    expiredSessions,
    removedMarkers,
    removedDiscoveryDocs,
    finalizedConvoyRides,
  });
  return { expiredSessions, removedMarkers, removedDiscoveryDocs, finalizedConvoyRides };
}

/** 5-minute TTL sweep (mapping cadence). */
export const cleanupExpired = onSchedule(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_SCHEDULED,
    timeZone: 'Europe/Stockholm',
    memory: '256MiB' as const,
    timeoutSeconds: 120,
    schedule: '*/5 * * * *',
  },
  withServerErrorReporting('live.cleanupExpired', async () => {
    await runLiveCleanup(new Date());
  }),
);
