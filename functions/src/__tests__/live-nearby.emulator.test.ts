/**
 * live.listNearby (nearby live-sharer DISCOVERY) emulator integration tests.
 *
 * The two-user contract this feature exists for:
 *   userA startSession + updatePosition near a point → userB listNearby from a
 *   nearby point SEES userA; userB from far away does NOT; userA hideMeNow /
 *   stopSession → userB stops seeing userA; a blocked pair → not visible; the
 *   caller never sees their own session.
 *
 * Requires the Functions + Firestore + Database + Auth emulators — run via:
 *   pnpm emulators:test
 */

process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.FIREBASE_DATABASE_EMULATOR_HOST ??= '127.0.0.1:9000';
process.env.GCLOUD_PROJECT ??= 'demo-test';

import { deleteApp, initializeApp, type FirebaseApp } from 'firebase/app';
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
import { getApps as getAdminApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getFirestore as getAdminFirestore } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

// Unique-per-file suffix: the emulator suite shares ONE Firestore across files,
// so display names / ids are suffixed to avoid cross-file collisions.
const SFX = 'nearby';

const adminApp =
  getAdminApps()[0] ??
  initializeAdminApp(
    {
      projectId: PROJECT_ID,
      databaseURL: `http://${EMULATOR_HOST}:9000?ns=${PROJECT_ID}-default-rtdb`,
    },
    `live-nearby-emulator-${SFX}`,
  );
const adminDb = getAdminFirestore(adminApp);

let app: FirebaseApp;
let auth: Auth;
let functions: Functions;

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

async function createProvisionedUser(prefix: string): Promise<TestUser> {
  const email = `${prefix}-${SFX}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
  const password = 'password-123';
  const credential = await createUserWithEmailAndPassword(auth, email, password);
  const uid = credential.user.uid;
  await pollUntil(async () => {
    const snap = await adminDb.collection('users').doc(uid).get();
    return snap.exists ? true : undefined;
  });
  return { uid, email, password };
}

async function signInAs(user: TestUser): Promise<void> {
  await signInWithEmailAndPassword(auth, user.email, user.password);
  await auth.currentUser?.getIdToken(true);
}

const call = (name: string, data: unknown) => httpsCallable(functions, name)(data);

// Stockholm-ish centre; a point ~30 km away (still findable at wide radius but
// not at 5 km); and a far point (Malmö, ~500 km) that no radius reaches.
const HERE = { latitude: 59.334, longitude: 18.063 };
const FAR = { latitude: 55.605, longitude: 13.003 };

const coordinateAt = (lat: number, lng: number) => ({
  latitude: lat,
  longitude: lng,
  accuracyMeters: 12,
  recordedAt: new Date().toISOString(),
});

interface NearbySession {
  uid: string;
  latitude: number;
  longitude: number;
  displayName: string | null;
}

async function listNearbyFrom(
  point: { latitude: number; longitude: number },
  radiusMeters = 5000,
): Promise<NearbySession[]> {
  const res = (await call('live-listNearby', { ...point, radiusMeters })).data as {
    sessions: NearbySession[];
  };
  return res.sessions;
}

/** Start a session and publish one position near `point`, writing the discovery doc. */
async function shareAt(
  user: TestUser,
  point: { latitude: number; longitude: number },
): Promise<void> {
  await signInAs(user);
  await call('live-startSession', { duration: '1h' });
  await call('live-updatePosition', { coordinate: coordinateAt(point.latitude, point.longitude) });
  // The discovery doc is written on updatePosition; wait until it is queryable.
  await pollUntil(async () => {
    const snap = await adminDb.collection('liveSessions').doc(user.uid).get();
    return snap.exists ? true : undefined;
  });
}

let userA: TestUser;
let userB: TestUser;

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    `live-nearby-client-${SFX}`,
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  userA = await createProvisionedUser('nearby-a');
  userB = await createProvisionedUser('nearby-b');
  await adminDb.collection('users').doc(userA.uid).set({ displayName: `A-${SFX}` }, { merge: true });
  await adminDb.collection('users').doc(userB.uid).set({ displayName: `B-${SFX}` }, { merge: true });
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('live.listNearby discovery', () => {
  it('userB sees a nearby standalone sharer (userA), but not a far-away query', async () => {
    await shareAt(userA, HERE);

    await signInAs(userB);
    const near = await listNearbyFrom(HERE, 5000);
    const hit = near.find((s) => s.uid === userA.uid);
    expect(hit).toBeTruthy();
    expect(hit?.displayName).toBe(`A-${SFX}`);
    // Position round-trips through the discovery doc.
    expect(hit?.latitude).toBeCloseTo(HERE.latitude, 3);

    // From Malmö, userA (in Stockholm) is well outside any radius.
    const far = await listNearbyFrom(FAR, 5000);
    expect(far.some((s) => s.uid === userA.uid)).toBe(false);
  });

  it('never returns the caller their own session', async () => {
    // userA is still sharing from the previous test; querying as userA near
    // their own position must exclude self.
    await signInAs(userA);
    const mine = await listNearbyFrom(HERE, 5000);
    expect(mine.some((s) => s.uid === userA.uid)).toBe(false);
  });

  it('hideMeNow removes the sharer from discovery immediately', async () => {
    // Precondition: userB can see userA right now.
    await signInAs(userB);
    expect((await listNearbyFrom(HERE, 5000)).some((s) => s.uid === userA.uid)).toBe(true);

    // userA hides.
    await signInAs(userA);
    await call('live-hideMeNow', {});
    // The discovery doc is deleted synchronously by hideMeNow.
    expect((await adminDb.collection('liveSessions').doc(userA.uid).get()).exists).toBe(false);

    await signInAs(userB);
    expect((await listNearbyFrom(HERE, 5000)).some((s) => s.uid === userA.uid)).toBe(false);
  });

  it('stopSession also clears discovery', async () => {
    await shareAt(userA, HERE);
    await signInAs(userB);
    expect((await listNearbyFrom(HERE, 5000)).some((s) => s.uid === userA.uid)).toBe(true);

    await signInAs(userA);
    await call('live-stopSession', {});
    expect((await adminDb.collection('liveSessions').doc(userA.uid).get()).exists).toBe(false);

    await signInAs(userB);
    expect((await listNearbyFrom(HERE, 5000)).some((s) => s.uid === userA.uid)).toBe(false);
  });

  it('excludes a blocked pair in BOTH directions', async () => {
    // userA shares nearby again.
    await shareAt(userA, HERE);

    // Direction 1: userB blocked userA. Written directly (Admin SDK) —
    // listNearby reads userBlocks/{blocker}/blocked/{blocked}.
    await adminDb
      .collection('userBlocks')
      .doc(userB.uid)
      .collection('blocked')
      .doc(userA.uid)
      .set({ blockedUserId: userA.uid, createdAt: new Date().toISOString() });
    await signInAs(userB);
    expect((await listNearbyFrom(HERE, 5000)).some((s) => s.uid === userA.uid)).toBe(false);
    // Clean up direction 1.
    await adminDb.collection('userBlocks').doc(userB.uid).collection('blocked').doc(userA.uid).delete();

    // Sanity: without the block, userA is visible again.
    await signInAs(userB);
    expect((await listNearbyFrom(HERE, 5000)).some((s) => s.uid === userA.uid)).toBe(true);

    // Direction 2: userA blocked userB (the sharer blocked the viewer).
    await adminDb
      .collection('userBlocks')
      .doc(userA.uid)
      .collection('blocked')
      .doc(userB.uid)
      .set({ blockedUserId: userB.uid, createdAt: new Date().toISOString() });
    await signInAs(userB);
    expect((await listNearbyFrom(HERE, 5000)).some((s) => s.uid === userA.uid)).toBe(false);
    await adminDb.collection('userBlocks').doc(userA.uid).collection('blocked').doc(userB.uid).delete();

    await signInAs(userA);
    await call('live-hideMeNow', {});
  });
});
