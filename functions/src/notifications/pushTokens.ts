/**
 * notifications.registerPushToken / unregisterPushToken — push device
 * registration callables (contracts/functions/functions.json).
 *
 * Legacy notification-service parity on the Firestore model:
 * - Only the SHA-256 hash of the FCM token is ever stored (the migration
 *   mapping's "FCM token hash only" option) and it doubles as the
 *   `userPrivate/{uid}/pushTokens/{tokenId}` document ID, so registration
 *   is naturally idempotent — a re-register bumps lastSeenAt. The raw
 *   token never appears in documents, logs, or responses.
 * - Registration is gated by the pushNotifications feature flag and
 *   requires an active (non-suspended) account, like the legacy
 *   registerDevice route.
 * - Unregistration only requires authentication — suspended users must be
 *   able to clean up their devices (legacy requireAuthenticatedHook) — and
 *   is idempotent.
 *
 * Actual FCM delivery (`sendPushNotification`) is deliberately NOT part of
 * this phase: it requires the Firebase console / FCM project setup that
 * the migration schedules at the end of the MVP.
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
