/**
 * live.sendWave (wave-to-nearby-live-users) emulator integration tests.
 *
 * Exercises the deployed-in-emulator callable end-to-end plus the liveWaves
 * rules:
 * - a live sharer's wave is DELIVERED to a nearby live sharer's own inbox
 *   (liveWaves/{recipient}/waves/{waveId}) with the sender's public display name,
 *   and recipientCount reflects who was in range;
 * - a caller who is NOT sharing live location is failed-precondition (there is no
 *   trustworthy origin to broadcast from);
 * - the SERVER-ENFORCED anti-spam cooldown: a second wave inside the 45s window is
 *   resource-exhausted (with retryAfterMs) and delivers nothing more;
 * - idempotency on clientId (a retry replays the same waveId without a second
 *   delivered doc);
 * - a far-away sharer receives nothing (proximity broadcast, not global);
 * - rules: the recipient may READ their own inbox; a stranger may NOT read someone
 *   else's inbox; the cooldown doc is backend-only.
 *
 * Requires the Functions + Firestore + Database + Auth emulators — run via:
 *   pnpm emulators:test
 */

process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.FIREBASE_DATABASE_EMULATOR_HOST ??= '127.0.0.1:9000';
process.env.GCLOUD_PROJECT ??= 'demo-test';

import { deleteApp, FirebaseError, initializeApp, type FirebaseApp } from 'firebase/app';
import {
  connectAuthEmulator,
  createUserWithEmailAndPassword,
  getAuth,
  signInWithEmailAndPassword,
  type Auth,
} from 'firebase/auth';
import {
  connectFunctionsEmulator,
  getFunctions,
  httpsCallable,
  type Functions,
} from 'firebase/functions';
import {
  collection,
  connectFirestoreEmulator,
  getDocs,
  getFirestore,
  type Firestore,
} from 'firebase/firestore';
import { getApps as getAdminApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getFirestore as getAdminFirestore } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

// Unique-per-file suffix: the emulator suite shares ONE Firestore across files.
const SFX = 'wave';

const adminApp =
  getAdminApps()[0] ??
  initializeAdminApp(
    {
      projectId: PROJECT_ID,
      databaseURL: `http://${EMULATOR_HOST}:9000?ns=${PROJECT_ID}-default-rtdb`,
    },
    `live-wave-emulator-${SFX}`,
  );
const adminDb = getAdminFirestore(adminApp);

let app: FirebaseApp;
let auth: Auth;
let functions: Functions;
let firestore: Firestore;

interface TestUser {
  uid: string;
  email: string;
  password: string;
}

async function pollUntil<T>(
  read: () => Promise<T | undefined>,
  timeoutMs = 30_000,
  intervalMs = 250,
): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await read();
    if (value !== undefined) return value;
    if (Date.now() > deadline) throw new Error('Timed out waiting for condition');
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
}

async function createProvisionedUser(prefix: string, displayName: string): Promise<TestUser> {
  const email = `${prefix}-${SFX}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
  const password = 'password-123';
  const credential = await createUserWithEmailAndPassword(auth, email, password);
  const uid = credential.user.uid;
  await pollUntil(async () => {
    const snap = await adminDb.collection('users').doc(uid).get();
    return snap.exists ? true : undefined;
  });
  await adminDb.collection('users').doc(uid).set({ displayName }, { merge: true });
  return { uid, email, password };
}

async function signInAs(user: TestUser): Promise<void> {
  await signInWithEmailAndPassword(auth, user.email, user.password);
  await auth.currentUser?.getIdToken(true);
}

const call = (name: string, data: unknown) => httpsCallable(functions, name)(data);

async function callableError(
  promise: Promise<unknown>,
): Promise<{ code: string; details: unknown }> {
  try {
    await promise;
    throw new Error('expected the callable to reject');
  } catch (error) {
    if (error instanceof FirebaseError) {
      return { code: error.code, details: (error as unknown as { details?: unknown }).details };
    }
    throw error;
  }
}

const HERE = { latitude: 59.334, longitude: 18.063 };
const FAR = { latitude: 55.605, longitude: 13.003 }; // Malmö, ~500 km away

const coordinateAt = (lat: number, lng: number) => ({
  latitude: lat,
  longitude: lng,
  accuracyMeters: 12,
  recordedAt: new Date().toISOString(),
});

/** Start a session and publish one position, writing the discovery doc. */
async function shareAt(user: TestUser, point: { latitude: number; longitude: number }): Promise<void> {
  await signInAs(user);
  await call('live-startSession', { duration: '1h' });
  await call('live-updatePosition', { coordinate: coordinateAt(point.latitude, point.longitude) });
  await pollUntil(async () => {
    const snap = await adminDb.collection('liveSessions').doc(user.uid).get();
    return snap.exists ? true : undefined;
  });
}

async function inboxDocs(recipientUid: string) {
  return (await adminDb.collection('liveWaves').doc(recipientUid).collection('waves').get()).docs;
}

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    `live-wave-client-${SFX}`,
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);
  firestore = getFirestore(app);
  connectFirestoreEmulator(firestore, EMULATOR_HOST, 8080);
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('live-sendWave gating', () => {
  it('rejects an unauthenticated caller', async () => {
    await auth.signOut();
    expect((await callableError(call('live-sendWave', {}))).code).toBe('functions/unauthenticated');
  });

  it('a caller who is not sharing live location is failed-precondition', async () => {
    const lurker = await createProvisionedUser('wave-lurker', `Lurker-${SFX}`);
    await signInAs(lurker);
    expect((await callableError(call('live-sendWave', {}))).code).toBe(
      'functions/failed-precondition',
    );
  });

  it('rejects a client-supplied position (strict schema)', async () => {
    const sender = await createProvisionedUser('wave-strict', `Strict-${SFX}`);
    await shareAt(sender, HERE);
    expect(
      (await callableError(call('live-sendWave', { latitude: 59.3, longitude: 18.0 }))).code,
    ).toBe('functions/invalid-argument');
  });
});

describe('live-sendWave delivery', () => {
  it('delivers a nearby live sharer a wave carrying the sender display name', async () => {
    const sender = await createProvisionedUser('wave-send-a', `WaverA-${SFX}`);
    const recipient = await createProvisionedUser('wave-recv-a', `WaverB-${SFX}`);
    await shareAt(recipient, HERE);
    await shareAt(sender, HERE);

    await signInAs(sender);
    const res = (await call('live-sendWave', {})).data as {
      waveId: string;
      recipientCount: number;
    };
    expect(res.waveId).toBeTruthy();
    expect(res.recipientCount).toBeGreaterThanOrEqual(1);

    const docs = await inboxDocs(recipient.uid);
    const mine = docs.find((d) => d.id === res.waveId);
    expect(mine).toBeTruthy();
    expect(mine!.data().senderUid).toBe(sender.uid);
    expect(mine!.data().senderDisplayName).toBe(`WaverA-${SFX}`);
    expect(mine!.data().expireAt).toBeDefined();

    // The sender never delivers a wave to their own inbox.
    expect((await inboxDocs(sender.uid)).some((d) => d.id === res.waveId)).toBe(false);
  });

  it('does not deliver to a far-away sharer (proximity broadcast, not global)', async () => {
    const sender = await createProvisionedUser('wave-send-far', `FarSender-${SFX}`);
    const distant = await createProvisionedUser('wave-recv-far', `FarRecv-${SFX}`);
    await shareAt(distant, FAR);
    await shareAt(sender, HERE);

    await signInAs(sender);
    const res = (await call('live-sendWave', {})).data as { waveId: string; recipientCount: number };
    expect((await inboxDocs(distant.uid)).some((d) => d.id === res.waveId)).toBe(false);
  });
});

describe('live-sendWave anti-spam cooldown (server-enforced)', () => {
  it('refuses a second wave inside the 45s window with retryAfterMs, delivering nothing more', async () => {
    const sender = await createProvisionedUser('wave-cd-s', `CdSender-${SFX}`);
    const recipient = await createProvisionedUser('wave-cd-r', `CdRecv-${SFX}`);
    await shareAt(recipient, HERE);
    await shareAt(sender, HERE);

    await signInAs(sender);
    await call('live-sendWave', {});
    const before = (await inboxDocs(recipient.uid)).length;

    const err = await callableError(call('live-sendWave', {}));
    expect(err.code).toBe('functions/resource-exhausted');
    const details = err.details as { retryAfterMs?: number };
    expect(details.retryAfterMs).toBeGreaterThan(0);

    // The throttled send delivered nothing.
    expect((await inboxDocs(recipient.uid)).length).toBe(before);
  });
});

describe('live-sendWave idempotency', () => {
  it('replays the same waveId for a repeated clientId without a second delivered doc', async () => {
    const sender = await createProvisionedUser('wave-idem-s', `IdemSender-${SFX}`);
    const recipient = await createProvisionedUser('wave-idem-r', `IdemRecv-${SFX}`);
    await shareAt(recipient, HERE);
    await shareAt(sender, HERE);

    await signInAs(sender);
    const first = (await call('live-sendWave', { clientId: `idem-${SFX}-1` })).data as {
      waveId: string;
      recipientCount: number;
    };
    const second = (await call('live-sendWave', { clientId: `idem-${SFX}-1` })).data as {
      waveId: string;
      recipientCount: number;
    };

    expect(second.waveId).toBe(first.waveId);
    expect(second.recipientCount).toBe(first.recipientCount);
    // Only ONE delivered doc for that waveId in the recipient's inbox.
    expect((await inboxDocs(recipient.uid)).filter((d) => d.id === first.waveId).length).toBe(1);
  });
});

describe('liveWaves Firestore rules', () => {
  it('a recipient reads their OWN inbox; a stranger cannot; the cooldown is backend-only', async () => {
    const sender = await createProvisionedUser('wave-rules-s', `RulesSender-${SFX}`);
    const recipient = await createProvisionedUser('wave-rules-r', `RulesRecv-${SFX}`);
    await shareAt(recipient, HERE);
    await shareAt(sender, HERE);

    await signInAs(sender);
    await call('live-sendWave', {});

    // Recipient reads their own wave inbox.
    await signInAs(recipient);
    const own = await getDocs(collection(firestore, 'liveWaves', recipient.uid, 'waves'));
    expect(own.docs.length).toBeGreaterThanOrEqual(1);

    // A stranger (the sender) cannot read the recipient's inbox.
    await signInAs(sender);
    expect(
      (await callableError(getDocs(collection(firestore, 'liveWaves', recipient.uid, 'waves')))).code,
    ).toContain('permission-denied');

    // The cooldown doc is backend-only — a client read is denied.
    expect(
      (await callableError(getDocs(collection(firestore, 'liveWaveCooldowns')))).code,
    ).toContain('permission-denied');
  });
});
