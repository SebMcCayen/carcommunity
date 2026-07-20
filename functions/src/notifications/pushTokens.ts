/**
 * notifications.registerPushToken / unregisterPushToken — push device
 * registration callables (contracts/functions/functions.json).
 *
 * Storage model:
 * - The SHA-256 hash of the FCM token is the
 *   `userPrivate/{uid}/pushTokens/{tokenId}` document ID, so registration is
 *   naturally idempotent — a re-register bumps lastSeenAt. One document per
 *   device means a member may hold several (phone + tablet).
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
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { readFeatureFlag } from '../shared/featureFlags';
import { requireActiveActor } from '../shared/memberActor';
import {
  PUSH_NOTIFICATIONS_FLAG_KEY,
  buildPushTokenDocument,
  hashPushToken,
  parseRegisterPushTokenInput,
  parseUnregisterPushTokenInput,
  type PushTokenPlatform,
} from './notifications-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
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
    const ref = db
      .collection('userPrivate')
      .doc(actor.uid)
      .collection('pushTokens')
      .doc(tokenId);

    // Idempotent: first registration writes the full document; re-registers
    // keep createdAt and bump lastSeenAt (and platform/app metadata).
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
      } else {
        tx.set(ref, buildPushTokenDocument(parsed.input, () => FieldValue.serverTimestamp()));
      }
    });

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
