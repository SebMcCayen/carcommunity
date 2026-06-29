/**
 * Security rules emulator tests.
 *
 * Requires the Firebase Emulator Suite to be running:
 *   pnpm emulators:start   (from apps/functions)
 *   — or —
 *   firebase emulators:start --config ../../firebase/firebase.json
 *
 * Run with:
 *   pnpm test:emulator
 *   — or via the emulators:test script which starts emulators automatically.
 */

import {
  assertFails,
  initializeTestEnvironment,
  type RulesTestEnvironment,
} from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc } from 'firebase/firestore';
import { get as dbGet, ref as dbRef, set as dbSet } from 'firebase/database';
import { getBytes, ref as storageRef, uploadBytes } from 'firebase/storage';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterAll, beforeAll, describe, it } from 'vitest';

const FIREBASE_DIR = resolve(__dirname, '../../../../firebase');

let testEnv: RulesTestEnvironment;

beforeAll(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: 'demo-test',
    firestore: {
      rules: readFileSync(resolve(FIREBASE_DIR, 'firestore.rules'), 'utf8'),
      host: 'localhost',
      port: 8080,
    },
    database: {
      rules: readFileSync(resolve(FIREBASE_DIR, 'database.rules.json'), 'utf8'),
      host: 'localhost',
      port: 9000,
    },
    storage: {
      rules: readFileSync(resolve(FIREBASE_DIR, 'storage.rules'), 'utf8'),
      host: 'localhost',
      port: 9199,
    },
  });
});

afterAll(async () => {
  await testEnv?.cleanup();
});

// ---------------------------------------------------------------------------
// Firestore
// ---------------------------------------------------------------------------

describe('Firestore security rules – unauthenticated access', () => {
  it('denies read on /users/{id}', async () => {
    const ctx = testEnv.unauthenticatedContext();
    await assertFails(getDoc(doc(ctx.firestore(), 'users', 'some-user')));
  });

  it('denies write on /users/{id}', async () => {
    const ctx = testEnv.unauthenticatedContext();
    await assertFails(setDoc(doc(ctx.firestore(), 'users', 'some-user'), { name: 'test' }));
  });

  it('denies read on an arbitrary top-level collection', async () => {
    const ctx = testEnv.unauthenticatedContext();
    await assertFails(getDoc(doc(ctx.firestore(), 'events', 'some-event')));
  });
});

// ---------------------------------------------------------------------------
// Realtime Database
// ---------------------------------------------------------------------------

describe('Realtime Database security rules – unauthenticated access', () => {
  it('denies read at root path', async () => {
    const ctx = testEnv.unauthenticatedContext();
    await assertFails(dbGet(dbRef(ctx.database(), '/')));
  });

  it('denies write at /users/{id}', async () => {
    const ctx = testEnv.unauthenticatedContext();
    await assertFails(dbSet(dbRef(ctx.database(), 'users/some-user'), { name: 'test' }));
  });
});

// ---------------------------------------------------------------------------
// Cloud Storage
// ---------------------------------------------------------------------------

describe('Cloud Storage security rules – unauthenticated access', () => {
  it('denies unauthenticated upload', async () => {
    const ctx = testEnv.unauthenticatedContext();
    const data = new Uint8Array([1, 2, 3]);
    await assertFails(uploadBytes(storageRef(ctx.storage(), 'uploads/test.bin'), data));
  });

  it('denies unauthenticated download', async () => {
    const ctx = testEnv.unauthenticatedContext();
    await assertFails(getBytes(storageRef(ctx.storage(), 'uploads/test.bin')));
  });
});
