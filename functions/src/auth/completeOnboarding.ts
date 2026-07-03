/**
 * auth.completeOnboarding — callable (contracts/functions/functions.json).
 *
 * Deployed via the `auth` export group as `auth-completeOnboarding` (Firebase
 * grouped-export naming for the contract name `auth.completeOnboarding`).
 *
 * Marks onboarding complete for the calling user. The backend writes
 * `onboardingCompletedAt` (users/{uid}) and the consent timestamps
 * (userPrivate/{uid}) with FieldValue.serverTimestamp() — client-supplied
 * timestamps are never trusted. Returns the onboardingStatus shape from
 * contracts/schemas/auth.schema.json. Error codes come from
 * contracts/errors/errors.json.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { computeOnboardingWrites, parseCompleteOnboardingInput } from './onboarding-core';
import { buildUserPrivateDocument, buildUserProfileDocument } from './provisioning';
import { evaluateEarlyMember } from '../badges/awards';
import { logger } from 'firebase-functions';

/** onboardingStatus per contracts/schemas/auth.schema.json. */
export interface OnboardingStatusResponse {
  onboardingCompletedAt: string | null;
  ageConfirmedAt: string | null;
  termsAcceptedAt: string | null;
  privacyPolicyAcceptedAt: string | null;
}

function toIso(value: unknown): string | null {
  return value instanceof Timestamp ? value.toDate().toISOString() : null;
}

export const completeOnboarding = onCall(
  {
    region: 'europe-west1',
    memory: '256MiB',
    timeoutSeconds: 30,
    // App Check is enforced in production (contracts/functions/functions.json
    // appCheck: true). The emulator suite has no App Check provider, so
    // enforcement is skipped only inside the Functions emulator.
    enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
  },
  async (request): Promise<OnboardingStatusResponse> => {
    const auth = request.auth;
    if (!auth) {
      throw new HttpsError('unauthenticated', 'Sign in to complete onboarding.');
    }
    const uid = auth.uid;

    const parsed = parseCompleteOnboardingInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const input = parsed.input;

    const profileRef = db.collection('users').doc(uid);
    const privateRef = db.collection('userPrivate').doc(uid);
    const serverTimestamp = () => FieldValue.serverTimestamp();

    await db.runTransaction(async (tx) => {
      const profileSnap = await tx.get(profileRef);
      const privateSnap = await tx.get(privateRef);
      const profile = profileSnap.data();
      const priv = privateSnap.data();

      // Backend is the source of truth: suspended or soft-deleted users must
      // not proceed regardless of client state.
      if (profile?.suspended === true || profile?.deleted === true) {
        throw new HttpsError('permission-denied', 'Account access is restricted.');
      }

      // First-write-wins provisioning fallback: the onUserCreate trigger is
      // asynchronous, so a fast client may call this before the trigger has
      // committed. Provision defaults here in the same transaction.
      const provisionInput = {
        uid,
        displayName: auth.token.name ?? null,
        email: auth.token.email ?? null,
      };
      const profileBase = profileSnap.exists
        ? {}
        : buildUserProfileDocument(provisionInput, serverTimestamp);
      const privateBase = privateSnap.exists
        ? {}
        : buildUserPrivateDocument(provisionInput, serverTimestamp);

      const { profileUpdate, privateUpdate } = computeOnboardingWrites(
        input,
        {
          onboardingCompletedAt: profile?.onboardingCompletedAt ?? null,
          ageConfirmedAt: priv?.ageConfirmedAt ?? null,
          termsAcceptedAt: priv?.termsAcceptedAt ?? null,
          privacyPolicyAcceptedAt: priv?.privacyPolicyAcceptedAt ?? null,
        },
        serverTimestamp,
      );

      tx.set(profileRef, { ...profileBase, ...profileUpdate }, { merge: true });
      tx.set(privateRef, { ...privateBase, ...privateUpdate }, { merge: true });
    });

    // early_member evaluation (Phase 9f, legacy: evaluated on sign-in; here
    // once per user at onboarding completion). No-op unless the
    // EARLY_MEMBER_CUTOFF_DATE configuration is set; a badge failure never
    // fails onboarding.
    try {
      await evaluateEarlyMember(uid);
    } catch (error) {
      logger.error('early_member evaluation failed', { uid, error: String(error) });
    }

    // Re-read after commit to resolve the server timestamps into real values.
    const [profileSnap, privateSnap] = await Promise.all([profileRef.get(), privateRef.get()]);
    const profile = profileSnap.data();
    const priv = privateSnap.data();

    return {
      onboardingCompletedAt: toIso(profile?.onboardingCompletedAt),
      ageConfirmedAt: toIso(priv?.ageConfirmedAt),
      termsAcceptedAt: toIso(priv?.termsAcceptedAt),
      privacyPolicyAcceptedAt: toIso(priv?.privacyPolicyAcceptedAt),
    };
  },
);
