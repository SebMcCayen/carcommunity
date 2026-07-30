/**
 * notifications.registerPushToken / unregisterPushToken — push device
 * registration callables (contracts/functions/functions.json).
 *
 * Storage model:
 * - The SHA-256 hash of the FCM token is the
 *   `userPrivate/{uid}/pushTokens/{tokenId}` document ID, so registration is
 *   naturally idempotent — a re-register bumps lastSeenAt. One document per
 *   device means a member may hold several (phone + tablet), up to
 *   MAX_PUSH_TOKENS_PER_USER.
 * - That cap is enforced here, on the new-token path only. The token is
 *   client-supplied, so idempotency alone bounds nothing: a client can mint
 *   arbitrarily many DISTINCT tokens under its own uid. Since the send trigger
 *   reads this whole collection for every inbox item, an uncapped registry
 *   turns one notification into an unbounded fan-out. Registering past the cap
 *   evicts the least-recently-seen registration instead of failing, so a
 *   legitimate member with many devices is never locked out of push.
 * - The document ALSO stores the raw token, because FCM addresses a device by
 *   the token itself and the previous hash-only registry could never actually
 *   send. The raw token is still never logged and never returned (the response
 *   carries only the tokenId hash), and `pushTokens` denies ALL client access
 *   in firebase/firestore.rules — only the Admin SDK reads it.
 * - It is erased with `userPrivate/{uid}` on account deletion, and the send
 *   path (sendPush.ts) prunes tokens FCM reports as permanently dead.
 * - Registration is gated by the pushNotifications feature flag and
 *   requires an active (non-suspended) account, like the legacy
 *   registerDevice route.
 * - Unregistration only requires authentication — suspended users must be
 *   able to clean up their devices (legacy requireAuthenticatedHook) — and
 *   is idempotent.
 *
 * Delivery itself lives in sendPush.ts (the notifications-onNotificationCreated
 * Firestore trigger), which reads this registry.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { logger } from 'firebase-functions';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { readFeatureFlag } from '../shared/featureFlags';
import { requireActiveActor } from '../shared/memberActor';
import {
  MAX_PUSH_TOKENS_PER_USER,
  PUSH_NOTIFICATIONS_FLAG_KEY,
  buildPushTokenDocument,
  hashPushToken,
  parseRegisterPushTokenInput,
  parseUnregisterPushTokenInput,
  selectEvictableTokenIds,
  type PushTokenPlatform,
} from './notifications-core';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

/**
 * lastSeenAt as epoch millis. Rows written before the field existed — and rows
 * whose serverTimestamp has not resolved yet — read as null, which
 * selectEvictableTokenIds sorts oldest (i.e. evicts first).
 */
function toMillisOrNull(value: unknown): number | null {
  return value instanceof Timestamp ? value.toMillis() : null;
}

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

/** pushNotifications flag via the shared reader (Phase 9m). */
async function isPushEnabled(): Promise<boolean> {
  return readFeatureFlag(PUSH_NOTIFICATIONS_FLAG_KEY);
}

export interface RegisterPushTokenResponse {
  tokenId: string;
  platform: PushTokenPlatform;
}

export const registerPushToken = onCall(
  CALLABLE_OPTS,
  async (request): Promise<RegisterPushTokenResponse> => {
    const actor = await requireActiveActor(request);

    const parsed = parseRegisterPushTokenInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    if (!(await isPushEnabled())) {
      throw new HttpsError('failed-precondition', 'Push notifications feature is disabled.');
    }

    const tokenId = hashPushToken(parsed.input.token);
    const collection = db.collection('userPrivate').doc(actor.uid).collection('pushTokens');
    const ref = collection.doc(tokenId);

    // Idempotent: first registration writes the full document; re-registers
    // keep createdAt and bump lastSeenAt (and platform/app metadata).
    let evictedCount = 0;
    await db.runTransaction(async (tx) => {
      const existing = await tx.get(ref);
      if (existing.exists) {
        tx.update(ref, {
          // `token` is rewritten on every re-register, not just on create.
          // Registrations written before the raw token was stored are
          // hash-only and therefore unsendable; rewriting it here upgrades
          // them in place the first time the device checks in, so no member
          // has to unregister/reinstall to start receiving push.
          token: parsed.input.token,
          platform: parsed.input.platform,
          appVersion: parsed.input.appVersion ?? null,
          buildNumber: parsed.input.buildNumber ?? null,
          lastSeenAt: FieldValue.serverTimestamp(),
        });
        return;
      }

      // A NEW token is the only path that can grow the collection, so the cap
      // is enforced here and nowhere else — the re-register path above returns
      // before this read, keeping the common case a single document get.
      // (Firestore requires all reads before all writes; this read is still
      // ahead of every tx.delete/tx.set below.)
      const registry = await tx.get(collection);
      const evictable = selectEvictableTokenIds(
        registry.docs.map((doc) => ({
          tokenId: doc.id,
          lastSeenAtMs: toMillisOrNull(doc.data()?.lastSeenAt),
        })),
      );
      for (const staleId of evictable) {
        tx.delete(collection.doc(staleId));
      }
      tx.set(ref, buildPushTokenDocument(parsed.input, () => FieldValue.serverTimestamp()));
      // Recorded, not logged, here: Firestore re-runs this callback on
      // contention, so logging inside it would emit once per ATTEMPT. The
      // assignment is idempotent, so the value that survives is the one from
      // the attempt that actually committed.
      evictedCount = evictable.length;
    });

    if (evictedCount > 0) {
      // Not an error: the member simply has more devices/reinstalls than the
      // cap, and the least-recently-seen registration makes way. Logged
      // without the ids, which are token hashes.
      logger.info('Evicted least-recently-seen push tokens to stay within cap', {
        evicted: evictedCount,
        cap: MAX_PUSH_TOKENS_PER_USER,
      });
    }

    return { tokenId, platform: parsed.input.platform };
  },
);

export interface UnregisterPushTokenResponse {
  removed: boolean;
}

export const unregisterPushToken = onCall(
  CALLABLE_OPTS,
  async (request): Promise<UnregisterPushTokenResponse> => {
    // Deliberately NOT requireActiveActor: suspended users may clean up
    // their device registrations (legacy requireAuthenticatedHook).
    const uid = request.auth?.uid;
    if (!uid) {
      throw new HttpsError('unauthenticated', 'Sign in to continue.');
    }

    const parsed = parseUnregisterPushTokenInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const ref = db
      .collection('userPrivate')
      .doc(uid)
      .collection('pushTokens')
      .doc(parsed.input.tokenId);

    const removed = await db.runTransaction(async (tx) => {
      const snap = await tx.get(ref);
      if (!snap.exists) {
        return false;
      }
      tx.delete(ref);
      return true;
    });

    return { removed };
  },
);
