/**
 * onUserCreate — provisions Firestore user documents on first sign-in.
 *
 * `functions.auth.user().onCreate` is a 1st-gen trigger (there is no 2nd-gen
 * equivalent without upgrading the project to Identity Platform blocking
 * functions), per the migration plan Phase 7. Everything else in this
 * codebase stays 2nd-gen.
 *
 * Idempotent: if `users/{uid}` already exists the trigger is a no-op, so
 * retried deliveries never clobber user data.
 */

import * as functionsV1 from 'firebase-functions/v1';
import { FieldValue, type Firestore } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import {
  buildUserPrivateDocument,
  buildUserProfileDocument,
  type ProvisionUserInput,
} from './provisioning';

/**
 * Creates `users/{uid}` and `userPrivate/{uid}` in a single transaction.
 * Returns true when documents were created, false when the profile already
 * existed (no-op).
 */
export async function provisionUserDocuments(
  firestore: Firestore,
  input: ProvisionUserInput,
): Promise<boolean> {
  const profileRef = firestore.collection('users').doc(input.uid);
  const privateRef = firestore.collection('userPrivate').doc(input.uid);
  const serverTimestamp = () => FieldValue.serverTimestamp();

  return firestore.runTransaction(async (tx) => {
    const profileSnap = await tx.get(profileRef);
    if (profileSnap.exists) {
      return false;
    }
    tx.set(profileRef, buildUserProfileDocument(input, serverTimestamp));
    // Merge so a concurrent completeOnboarding call can never be overwritten.
    tx.set(privateRef, buildUserPrivateDocument(input, serverTimestamp), { merge: true });
    return true;
  });
}

export const onUserCreate = functionsV1
  .region('europe-west1')
  .runWith({ memory: '256MB', timeoutSeconds: 30 })
  .auth.user()
  .onCreate(async (user) => {
    const created = await provisionUserDocuments(db, {
      uid: user.uid,
      displayName: user.displayName,
      email: user.email,
    });
    // Never log emails, tokens, or other credentials — UID only.
    logger.info(created ? 'Provisioned user documents' : 'User documents already provisioned', {
      uid: user.uid,
    });
  });
