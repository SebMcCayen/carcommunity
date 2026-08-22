/**
 * live.sendWave — a live sharer broadcasts a transient WAVE to every OTHER live
 * sharer within range, popping a mid-screen "👋 <name> waved" on each nearby
 * recipient's map (contracts/functions/functions.json: live.sendWave).
 *
 * Deployed via the `live` export group (functions/src/index.ts) as
 * `live-sendWave` (europe-west1).
 *
 * ## What it does, in order
 *  1. requireActiveActor — signed-in, non-suspended. Waving is free (social),
 *     like sharing your own position (requireActiveActor, not requireMemberActor).
 *  2. IDEMPOTENT REPLAY FAST PATH: a retry with the same clientId returns the
 *     committed result immediately — before the sharing gate, so a legitimate
 *     retry still replays even if the caller has since stopped sharing.
 *  3. AUTHORITATIVE position + SHARING GATE (before the cooldown is charged): the
 *     sender's own `liveSessions/{uid}` discovery doc (written by
 *     live.updatePosition) supplies the geoCell + lat/lng + public displayName. No
 *     such active doc → failed-precondition (you must be sharing live to wave),
 *     and a rejected attempt does NOT burn the cooldown. The client can NEVER
 *     supply a position, so it cannot spoof a far-away blast radius.
 *  4. ANTI-SPAM: a single transaction on `liveWaveCooldowns/{uid}` — re-check
 *     idempotency, refuse a send inside the 45s window (resource-exhausted,
 *     details {retryAfterMs}), otherwise stamp the cooldown. Reached only after
 *     the sharing gate and BEFORE the geo-query, so a not-sharing attempt never
 *     charges the cooldown and a throttled spammer never triggers the nearby scan
 *     or the fan-out.
 *  5. RECIPIENTS: the SAME geo-cell query live.listNearby uses over
 *     `liveSessions` (status=='active', expiresAt>now, exact Haversine radius),
 *     minus self, minus anyone in a block relationship in either direction
 *     (resolvePeerBlockPairs — the shared convoy block matrix). Radius is the
 *     FIXED WAVE_RADIUS_METERS (clamped), never client-controlled.
 *  6. DELIVERY: one short-lived doc fanned out (batched) to each recipient's OWN
 *     `liveWaves/{uid}/waves/{waveId}` inbox — the SAME per-user-inbox shape as
 *     notifications/{uid}/items (owner-only read, backend-only write). Each
 *     recipient's client holds a Firestore listener on its own inbox and pops the
 *     wave via the shared ReactionOverlay. The transient wave doc is short-lived
 *     (a live nudge a short `expireAt` TTL sweeps), but each recipient ALSO gets
 *     one PERSISTENT "{name} waved at you" item on their Notifications page,
 *     written best-effort under the social `wave` category (idempotently, before
 *     the completion marker so a retry never drops it). Convoy reactions/waves are
 *     a separate surface and never notify.
 *
 * Invariants:
 *  - Backend is the sole writer of `liveWaves/**` and `liveWaveCooldowns/**`
 *    (firebase/firestore.rules: owner-only read of the inbox, deny-all on the
 *    cooldown).
 *  - No PII beyond the sender's already-public display name.
 *  - Idempotent on the optional `clientId` (the shared wave doc id): a retry
 *    replays the committed result WITHOUT re-fanning-out or re-charging the
 *    cooldown, so a flaky network never double-pops receivers.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldPath, FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import {
  FIRESTORE_IN_CHUNK,
  chunk,
  clampRadiusMeters,
  geoCellsForRadius,
  isWithinRadius,
} from '../incidents/incidents-core';
import { isBlockedAgainstAnyPeer, resolvePeerBlockPairs } from '../convoy/convoy-core';
import { writeInAppNotification } from '../notifications/deliver';
import { LIVE_SESSION_ACTIVE_STATUS } from './nearby-core';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';
import { badgeProgressRef } from '../badges/tierAwards';
import { BADGE_METRIC_FIELD } from '../badges/badge-tiers';
import { MEMBER_MONTHLY_STAT_FIELDS } from '../leaderboard/leaderboard-core';
import { memberMonthlyStatsRef, memberMonthlyStatPayload } from '../leaderboard/monthly-stats';
import { seasonIdForInstant } from '../crownHunt/crown-hunt-stats-core';
import {
  WAVE_NOT_SHARING_MESSAGE,
  WAVE_RADIUS_METERS,
  WAVE_RATE_LIMITED_MESSAGE,
  buildWaveDocument,
  isWithinWaveCooldown,
  parseSendWaveInput,
  waveCooldownExpiry,
  waveCooldownRemainingMs,
  waveExpiry,
  waveNotificationId,
} from './wave-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

/** Upper bound on recipients one wave fans out to (a nearby map does not hold more). */
const MAX_RECIPIENTS = 200;

function cooldownRef(uid: string) {
  return db.collection('liveWaveCooldowns').doc(uid);
}

function inboxRef(recipientUid: string, waveId: string) {
  return db.collection('liveWaves').doc(recipientUid).collection('waves').doc(waveId);
}

/** Uids this blocker has blocked, out of `blockedUids` (<= FIRESTORE_IN_CHUNK). */
async function queryBlockedSubset(blockerUid: string, blockedUids: string[]): Promise<string[]> {
  const snap = await db
    .collection('userBlocks')
    .doc(blockerUid)
    .collection('blocked')
    .where(FieldPath.documentId(), 'in', blockedUids)
    .get();
  return snap.docs.map((doc) => doc.id);
}

export interface SendWaveResponse {
  waveId: string;
  /** How many nearby live sharers the wave was delivered to (0 = nobody in range). */
  recipientCount: number;
}

/**
 * Outcome of the cooldown transaction:
 *  - `replay`  — a COMPLETED send (delivery already confirmed via
 *    `lastRecipientCount`); return the stored result, do nothing.
 *  - `send`    — a fresh send; the cooldown was stamped, proceed to delivery.
 *  - `resume`  — an IN-FLIGHT send (the cooldown was stamped by a prior attempt
 *    that crashed BEFORE recording delivery); proceed to delivery WITHOUT
 *    re-charging the cooldown so the wave is never silently dropped nor
 *    double-charged. Delivery is idempotent (fan-out overwrites the same
 *    per-recipient `{waveId}` doc), so a raced double-resume is harmless.
 */
type CooldownGate =
  | { kind: 'replay'; waveId: string; recipientCount: number }
  // `stampedAtMs` is the wave's authoritative send instant (the cooldown stamp):
  // `nowMs` for a fresh send, the ORIGINAL crashed attempt's stamp for a resume.
  // Stable across retries, so the per-pair-per-window notification id reproduces.
  | { kind: 'send'; waveId: string; stampedAtMs: number }
  | { kind: 'resume'; waveId: string; stampedAtMs: number };

export const sendWave = onCall(CALLABLE_OPTS, async (request): Promise<SendWaveResponse> => {
  const actor = await requireActiveActor(request);

  const parsed = parseSendWaveInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { clientId } = parsed.input;

  const now = new Date();
  const nowMs = now.getTime();
  const createdAt = Timestamp.fromDate(now);
  const cooldownDocRef = cooldownRef(actor.uid);

  // A COMPLETED replay: the same clientId whose delivery is CONFIRMED (a numeric
  // `lastRecipientCount` was recorded after fan-out). Only a confirmed send
  // replays — a stamped-but-undelivered (in-flight) doc must NOT be mistaken for a
  // finished one, or a crash between stamp and delivery would silently drop the
  // wave on retry.
  const completedReplay = (data: Record<string, unknown> | undefined): SendWaveResponse | null => {
    if (clientId === undefined || data?.lastWaveId !== clientId) return null;
    if (typeof data?.lastRecipientCount !== 'number') return null;
    return { waveId: clientId, recipientCount: data.lastRecipientCount };
  };

  /** True for the same clientId stamped but NOT yet delivery-confirmed — resumable. */
  const isInFlight = (data: Record<string, unknown> | undefined): boolean =>
    clientId !== undefined &&
    data?.lastWaveId === clientId &&
    typeof data?.lastRecipientCount !== 'number';

  // Idempotent replay FAST PATH: a retry of a COMPLETED send returns the committed
  // result and does nothing else — checked BEFORE the sharing gate so a legitimate
  // retry still replays even if the caller has since stopped sharing. An in-flight
  // (undelivered) retry deliberately falls through to resume delivery below.
  if (clientId !== undefined) {
    const preReplay = completedReplay((await cooldownDocRef.get()).data());
    if (preReplay) return preReplay;
  }

  // AUTHORITATIVE position + SHARING GATE, checked BEFORE the cooldown is charged:
  // a caller who is not actively sharing has no trustworthy origin to broadcast
  // from, and a rejected (not-sharing) attempt must NOT burn their wave cooldown
  // and rate-limit their next legitimate wave. The discovery doc is written by
  // live.updatePosition; the client can NEVER supply a position, so it cannot
  // spoof a far-away blast radius.
  const selfSnap = await db.collection('liveSessions').doc(actor.uid).get();
  const self = selfSnap.data();
  const selfExpires = self?.expiresAt;
  const selfActive =
    self?.status === LIVE_SESSION_ACTIVE_STATUS &&
    selfExpires instanceof Timestamp &&
    selfExpires.toMillis() > nowMs;
  const lat = self?.latitude;
  const lng = self?.longitude;
  if (!selfActive || typeof lat !== 'number' || typeof lng !== 'number') {
    throw new HttpsError('failed-precondition', WAVE_NOT_SHARING_MESSAGE);
  }
  const senderDisplayName = (self?.displayName as string | null) ?? null;

  // ANTI-SPAM GATE (server source of truth). The cooldown re-read + window check +
  // stamp share ONE transaction, so two rapid taps serialise on the cooldown doc:
  // the first stamps and proceeds, the second reads the fresh stamp and is
  // refused. Reached ONLY after the sharing gate above, so a not-sharing attempt
  // never charges the cooldown. Inside the transaction it distinguishes three
  // cases for a retried clientId: a COMPLETED send (replay), an IN-FLIGHT send
  // (resume delivery WITHOUT re-charging), or a fresh send (window-check + stamp).
  const gate = await db.runTransaction<CooldownGate>(async (tx) => {
    const data = (await tx.get(cooldownDocRef)).data();

    const txReplay = completedReplay(data);
    if (txReplay) {
      return { kind: 'replay', waveId: txReplay.waveId, recipientCount: txReplay.recipientCount };
    }

    // Resume a stamped-but-undelivered send: the cooldown was already charged by
    // the crashed attempt, so proceed to (idempotent) delivery WITHOUT stamping
    // again — the wave is neither dropped nor double-charged.
    if (isInFlight(data)) {
      const resumeStamp =
        data?.lastSentAt instanceof Timestamp ? data.lastSentAt.toMillis() : nowMs;
      return { kind: 'resume', waveId: clientId as string, stampedAtMs: resumeStamp };
    }

    const lastSentAt = data?.lastSentAt as Timestamp | undefined;
    const lastSentAtMs = lastSentAt instanceof Timestamp ? lastSentAt.toMillis() : null;
    if (isWithinWaveCooldown(lastSentAtMs, nowMs)) {
      // Hand the client the remaining time so its icon greys for exactly the
      // right window rather than guessing the server's policy.
      throw new HttpsError('resource-exhausted', WAVE_RATE_LIMITED_MESSAGE, {
        retryAfterMs: waveCooldownRemainingMs(lastSentAtMs, nowMs),
      });
    }

    const newWaveId = clientId ?? db.collection('liveWaves').doc().id;
    tx.set(
      cooldownDocRef,
      {
        uid: actor.uid,
        lastSentAt: createdAt,
        lastWaveId: newWaveId,
        // CLEAR the completion marker as this new send is stamped. The doc is
        // merge-written and reused across every wave, so the PREVIOUS wave's
        // `lastRecipientCount` would otherwise persist into this fresh send and
        // make it look already-completed — replaying a stale count and, worse,
        // making the waves-sent credit below think this new wave was already
        // counted (skipping it for every wave after the first). Deleting it makes
        // in-flight vs completed unambiguous: absent = in-flight (this send has
        // not delivered yet), present = completed by THIS wave.
        lastRecipientCount: FieldValue.delete(),
        expireAt: waveCooldownExpiry(now),
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true },
    );
    return { kind: 'send', waveId: newWaveId, stampedAtMs: nowMs };
  });

  if (gate.kind === 'replay') {
    return { waveId: gate.waveId, recipientCount: gate.recipientCount };
  }
  const { waveId, stampedAtMs } = gate;

  // RECIPIENTS: the same cell-bounded query live.listNearby uses. Radius is the
  // FIXED wave reach (clamped), never client-supplied.
  const radius = clampRadiusMeters(WAVE_RADIUS_METERS);
  const cells = geoCellsForRadius(lat, lng, radius);
  const cellChunks = chunk(cells, FIRESTORE_IN_CHUNK);
  const nowTs = Timestamp.fromMillis(nowMs);
  const perChunkLimit = Math.max(1, Math.ceil(MAX_RECIPIENTS / cellChunks.length));
  const snapshots = await Promise.all(
    cellChunks.map((cellGroup) =>
      db
        .collection('liveSessions')
        .where('geoCell', 'in', cellGroup)
        .where('status', '==', LIVE_SESSION_ACTIVE_STATUS)
        .where('expiresAt', '>', nowTs)
        .limit(perChunkLimit)
        .get(),
    ),
  );

  const seen = new Set<string>();
  const candidateUids: string[] = [];
  for (const snap of snapshots) {
    for (const doc of snap.docs) {
      const rid = doc.id;
      if (rid === actor.uid) continue; // never wave at yourself
      if (seen.has(rid)) continue;
      const data = doc.data();
      if (data.status !== LIVE_SESSION_ACTIVE_STATUS) continue;
      const expiresAt = data.expiresAt;
      if (!(expiresAt instanceof Timestamp) || expiresAt.toMillis() <= nowMs) continue;
      const rlat = data.latitude;
      const rlng = data.longitude;
      if (typeof rlat !== 'number' || typeof rlng !== 'number') continue;
      if (!isWithinRadius(lat, lng, rlat, rlng, radius)) continue;
      seen.add(rid);
      candidateUids.push(rid);
      if (candidateUids.length >= MAX_RECIPIENTS) break;
    }
    if (candidateUids.length >= MAX_RECIPIENTS) break;
  }

  // Drop anyone in a block relationship with the sender in EITHER direction —
  // the same matrix live.listNearby / the convoy invite path use.
  const blockPairs = await resolvePeerBlockPairs(candidateUids, [actor.uid], queryBlockedSubset);
  const recipients = candidateUids.filter(
    (rid) => !isBlockedAgainstAnyPeer(rid, [actor.uid], blockPairs),
  );

  // DELIVERY: fan the wave out to each recipient's own inbox in bounded batches
  // (Firestore caps a WriteBatch at 500). Nobody nearby → nothing is written, but
  // the send still succeeds (recipientCount 0) so the sender's own animation and
  // cooldown are consistent.
  const expireAt = waveExpiry(now);
  const payload = buildWaveDocument({
    waveId,
    senderUid: actor.uid,
    senderDisplayName,
    createdAt,
    expireAt,
  });
  const WRITE_BATCH_LIMIT = 400;
  for (let i = 0; i < recipients.length; i += WRITE_BATCH_LIMIT) {
    const batch = db.batch();
    for (const rid of recipients.slice(i, i + WRITE_BATCH_LIMIT)) {
      batch.set(inboxRef(rid, waveId), payload);
    }
    await batch.commit();
  }

  // PERSISTENT NOTIFICATION (best-effort, non-blocking): a wave is a transient
  // pop, but the recipient should also find "{name} waved at you" on their
  // Notifications page after the animation is gone. This is the ONLY wave surface
  // that notifies — convoy reactions/waves are a different path and stay ephemeral.
  //
  // ORDERED BEFORE the completion-marker transaction ON PURPOSE: recording
  // `lastRecipientCount` is what turns this send COMPLETED, and a completed retry
  // short-circuits at the replay fast path and never reaches this code again. So
  // the durable notices must be written (or re-written) on every path that could
  // still retry — i.e. BEFORE the marker — or a crash between the marker and the
  // notices would drop them forever on the replay. The writes are idempotent on
  // the deterministic per-pair-per-window id below (waveNotificationId), so a
  // resumed (crash-then-retry) delivery re-attempts them without duplicating.
  //
  // Written under the social `wave` category (opt-out-able, never essential) via
  // the shared writeInAppNotification helper, which owns eligibility (deleted /
  // suspended / per-category opt-out). NEVER fails the wave: Promise.allSettled
  // swallows a per-recipient write error, and the wave's delivery + cooldown are
  // already committed — a notification write must not surface as a failed wave.
  //
  // RATE-LIMITED to at most one notice per sender→recipient PAIR per
  // WAVE_NOTIF_WINDOW_MS: the id buckets the wave's authoritative stamp time
  // (waveNotificationId), and writeInAppNotification is create-if-absent on that
  // id, so a second wave from the same sender to the same recipient inside the
  // window is a no-op. Using the STAMP time (not the resume attempt's clock) is
  // what keeps a retried/resumed send in the same bucket = same id = no duplicate.
  //
  // The self-wave guard is belt-and-braces: `recipients` already excludes the
  // sender (the geo scan skips `rid === actor.uid`), but the explicit
  // senderUid !== recipientUid check keeps the invariant local and obvious.
  //
  // CONCURRENCY CAP: each notice is a create-if-absent TRANSACTION (two reads +
  // a write), and a wave can fan out to MAX_RECIPIENTS (200). Firing all of them
  // at once on the 30s request path risks a timeout / RESOURCE_EXHAUSTED that,
  // despite the best-effort intent, would surface as a failed wave. So the writes
  // run in bounded chunks — at most NOTIF_WRITE_CONCURRENCY transactions in
  // flight — each chunk awaited before the next. Still best-effort + post-commit.
  // If wave volume grows, a fully-async path (a liveWaves Firestore trigger or a
  // task queue) would move this off the request entirely; the bounded batch is
  // the fix for now.
  const waverName = senderDisplayName ?? 'En medlem';
  const notifRecipients = recipients.filter((recipientUid) => recipientUid !== actor.uid);
  const NOTIF_WRITE_CONCURRENCY = 20;
  for (let i = 0; i < notifRecipients.length; i += NOTIF_WRITE_CONCURRENCY) {
    await Promise.allSettled(
      notifRecipients.slice(i, i + NOTIF_WRITE_CONCURRENCY).map((recipientUid) =>
        writeInAppNotification(
          recipientUid,
          {
            category: 'wave',
            title: 'Ny vinkning',
            previewText: `${waverName} vinkade till dig.`,
            relatedEntityId: actor.uid,
          },
          waveNotificationId(actor.uid, recipientUid, stampedAtMs),
        ),
      ),
    );
  }

  // COMPLETION MARKER + WAVES-SENT STAT, in ONE transaction so the counter is
  // credited EXACTLY ONCE per completed send. Recording `lastRecipientCount` is
  // what turns a stamped in-flight send into a COMPLETED one — a retry before
  // this write resumes delivery (never drops the wave); a completed retry short-
  // circuits at the replay fast path above and never reaches here at all.
  //
  // The wavesSent credit must NOT double-count on a resumed delivery (a crash
  // between stamp and this write) nor on a raced double-resume, so it is gated on
  // the FIRST transition into completed for THIS waveId: the transaction reads the
  // cooldown doc and increments only when the completion marker is not already
  // recorded for this wave. Two racing resumes serialise on the cooldown doc —
  // the first credits, the second sees the marker and only re-writes the (equal)
  // count. The completion write re-stamps `lastWaveId: waveId` so the marker is
  // self-contained — the count and the id it belongs to are written together, and
  // a later wave's stamp clears both. The counter is per-SEND, never per-recipient,
  // and a wave with zero recipients still counts (the sender did wave). Raising
  // `badgeProgress.wavesSent` cascades into onBadgeProgressWritten, which awards the
  // Vinkare ladder; the monthly bucket feeds the "waves this month" leaderboard.
  await db.runTransaction(async (tx) => {
    const data = (await tx.get(cooldownDocRef)).data();
    const alreadyCounted =
      data?.lastWaveId === waveId && typeof data?.lastRecipientCount === 'number';
    tx.set(
      cooldownDocRef,
      { lastWaveId: waveId, lastRecipientCount: recipients.length },
      { merge: true },
    );
    if (alreadyCounted) {
      return;
    }
    // Bucket the monthly credit into the month the wave was STAMPED in, read from
    // the cooldown doc's authoritative `lastSentAt` — not `now`. On the crash+
    // resume path `now` is the resume attempt's time, which can fall in a later
    // month than the original send; crediting the stamp month keeps the wave in the
    // calendar month it actually happened. Falls back to `now` if unreadable.
    const stampedAt = data?.lastSentAt instanceof Timestamp ? data.lastSentAt.toDate() : now;
    const monthScope = seasonIdForInstant(stampedAt);
    tx.set(
      badgeProgressRef(actor.uid),
      {
        [BADGE_METRIC_FIELD.wavesSent]: FieldValue.increment(1),
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true },
    );
    tx.set(
      memberMonthlyStatsRef(monthScope, actor.uid),
      memberMonthlyStatPayload(monthScope, actor.uid, MEMBER_MONTHLY_STAT_FIELDS.waves, 1),
      { merge: true },
    );
  });

  return { waveId, recipientCount: recipients.length };
});
