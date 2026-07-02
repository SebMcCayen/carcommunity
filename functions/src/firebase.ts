/**
 * Firebase Admin SDK bootstrap.
 *
 * Import this module only from function entry points (triggers/callables) —
 * never from pure logic modules — so unit tests can run without initialising
 * the Admin SDK. Credentials come from Application Default Credentials in
 * production and from the Emulator Suite locally; no service account JSON is
 * ever committed.
 */

import { getApps, initializeApp } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';

if (getApps().length === 0) {
  initializeApp();
}

export const db = getFirestore();
