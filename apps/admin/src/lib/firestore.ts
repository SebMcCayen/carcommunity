/**
 * Firestore client for admin direct reads (Phase 13a).
 *
 * Admin list/detail READS are direct rules-gated SDK reads (`isAdmin()`
 * in firestore.rules) — the 9m/9n precedent; mutations go through
 * callables (see callables.ts).
 */

import {
  connectFirestoreEmulator,
  getFirestore,
  type Firestore,
} from 'firebase/firestore';
import { getFirebaseApp } from './firebase';

let firestoreInstance: Firestore | null = null;
let emulatorConnected = false;

/**
 * Whether admin reads are (or will be) routed to the local Firestore emulator
 * rather than the production database. Mirrors the connect condition below so
 * UI copy can describe the real data source in dev/emulator builds.
 */
export function isFirestoreEmulatorEnabled(): boolean {
  const emulatorHost = import.meta.env.VITE_FIRESTORE_EMULATOR_HOST as string | undefined;
  return Boolean(emulatorHost) && !import.meta.env.PROD;
}

export function getAdminFirestore(): Firestore {
  if (!firestoreInstance) {
    firestoreInstance = getFirestore(getFirebaseApp());
  }
  const emulatorHost = import.meta.env.VITE_FIRESTORE_EMULATOR_HOST as string | undefined;
  if (emulatorHost && !emulatorConnected && !import.meta.env.PROD) {
    const [host, port] = emulatorHost.split(':');
    connectFirestoreEmulator(firestoreInstance, host ?? '127.0.0.1', Number(port ?? 8080));
    emulatorConnected = true;
  }
  return firestoreInstance;
}
