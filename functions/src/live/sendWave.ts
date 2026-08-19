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
 *  2. ANTI-SPAM FIRST: a single transaction on `liveWaveCooldowns/{uid}` gates the
 *     whole send. An idempotent replay (same clientId) returns the prior result
 *     without doing anything; a send inside the 45s window is refused
 *     (resource-exhausted, details {retryAfterMs}); otherwise it stamps the
 *     cooldown and proceeds. Doing this BEFORE the geo-query means a throttled
 *     spammer never triggers the read-bearing nearby scan or the fan-out.
 *  3. AUTHORITATIVE position: the sender's own `liveSessions/{uid}` discovery doc
 *     (written by live.updatePosition) supplies the geoCell + lat/lng + public
 *     displayName. No such active doc → failed-precondition (you must be sharing
 *     live to wave). The client can NEVER supply a position, so it cannot spoof a
 *     far-away blast radius.
 *  4. RECIPIENTS: the SAME geo-cell query live.listNearby uses over
 *     `liveSessions` (status=='active', expiresAt>now, exact Haversine radius),
 *     minus self, minus anyone in a block relationship in either direction
 *     (resolvePeerBlockPairs — the shared convoy block matrix). Radius is the
 *     FIXED WAVE_RADIUS_METERS (clamped), never client-controlled.
 *  5. DELIVERY: one short-lived doc fanned out (batched) to each recipient's OWN
 *     `liveWaves/{uid}/waves/{waveId}` inbox — the SAME per-user-inbox shape as
 *     notifications/{uid}/items (owner-only read, backend-only write). Each
 *     recipient's client holds a Firestore listener on its own inbox and pops the
 *     wave via the shared ReactionOverlay. No notification fan-out, no long-term
 *     storage: a wave is a live nudge, and a short `expireAt` TTL sweeps it.
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
import { LIVE_SESSION_ACTIVE_STATUS } from './nearby-core';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';
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

/** Outcome of the cooldown transaction: replay, or a fresh send cleared to proceed. */
type CooldownGate =
  | { kind: 'replay'; waveId: string; recipientCount: number }
  | { kind: 'send'; waveId: string };

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

  // ANTI-SPAM GATE (server source of truth), run FIRST so a throttled caller
  // never reaches the read-bearing geo-query below. The cooldown read + stamp
  // share ONE transaction, so two rapid taps serialise on the cooldown doc: the
  // first stamps and proceeds, the second reads the fresh stamp and is refused.
  const gate = await db.runTransaction<CooldownGate>(async (tx) => {
    const snap = await tx.get(cooldownDocRef);
    const data = snap.data();

    // Idempotent replay: a retry with the SAME clientId returns the committed
    // result and does NOT re-fan-out or re-charge the cooldown.
    if (clientId !== undefined && data?.lastWaveId === clientId) {
      const priorCount =
        typeof data?.lastRecipientCount === 'number' ? data.lastRecipientCount : 0;
      return { kind: 'replay', waveId: clientId, recipientCount: priorCount };
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

    const waveId = clientId ?? db.collection('liveWaves').doc().id;
    tx.set(
      cooldownDocRef,
      {
        uid: actor.uid,
        lastSentAt: createdAt,
        lastWaveId: waveId,
        expireAt: waveCooldownExpiry(now),
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true },
    );
    return { kind: 'send', waveId };
  });

  if (gate.kind === 'replay') {
    return { waveId: gate.waveId, recipientCount: gate.recipientCount };
  }
  const { waveId } = gate;

  // AUTHORITATIVE position: the sender's own discovery doc (written by
  // live.updatePosition). Absent/expired → they are not actively sharing, so
  // there is no trustworthy origin to broadcast from.
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

  // Record the delivered count on the cooldown doc so an idempotent replay reports
  // the same number. Outside the gate transaction: within the 45s window this
  // caller is the only writer of its own cooldown doc, so there is no race.
  await cooldownDocRef.set({ lastRecipientCount: recipients.length }, { merge: true });

  return { waveId, recipientCount: recipients.length };
});
