/** Real Auth -> callable transport -> Firestore integration, with no external image I/O. */
import { createHash } from 'node:crypto';
import { initializeApp, deleteApp, type FirebaseApp } from 'firebase/app';
import {
  connectAuthEmulator,
  getAuth,
  signInWithEmailAndPassword,
  signOut,
  type Auth,
} from 'firebase/auth';
import {
  connectFunctionsEmulator,
  getFunctions,
  httpsCallable,
  type Functions,
} from 'firebase/functions';
import { getApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getAuth as getAdminAuth } from 'firebase-admin/auth';
import { getFirestore, Timestamp } from 'firebase-admin/firestore';
import { beforeAll, beforeEach, afterAll, describe, expect, it } from 'vitest';
import { GLOBAL_PHOTO_BYTES_PER_DAY, PHOTO_RESERVED_BYTES } from './quota';

const projectId = process.env.GCLOUD_PROJECT ?? '';
const firestoreHost = process.env.FIRESTORE_EMULATOR_HOST ?? '';
const authHost = process.env.FIREBASE_AUTH_EMULATOR_HOST ?? '';
const functionsHost = process.env.FUNCTIONS_EMULATOR_HOST ?? '127.0.0.1:5001';
const isLoopback = (value: string) => /^(?:127\.0\.0\.1|localhost):\d+$/.test(value);
if (
  projectId !== 'demo-test' ||
  !isLoopback(firestoreHost) ||
  !isLoopback(authHost) ||
  !isLoopback(functionsHost)
) {
  throw new Error('Road camera transport tests require explicit local demo-test emulators.');
}
const adminApp = getApps()[0] ?? initializeAdminApp({ projectId });
if (adminApp.options.projectId !== projectId) throw new Error('Unexpected emulator project.');
const db = getFirestore(adminApp);
const prefix = `road-camera-${Date.now()}`;
const suffix = createHash('sha256').update(prefix).digest('hex').slice(0, 32);
const trafficId = `traffic_${suffix}`;
const speedId = `speed_${suffix}`;
const weatherId = `road_condition_${suffix}`;
const generation = createHash('sha256').update(`${prefix}-catalog`).digest('hex');
const flagsRef = db.doc('config/featureFlags');
const policyRef = db.doc('carCommunityPolicy/access');
const metaRef = db.doc('roadCameraCatalogMetadata/current');
const pageRef = db.doc(`roadCameraCatalogPages/${generation}_0`);
const globalRef = db.doc('roadCameraRateLimits/global');
const email = `${prefix}@example.com`;
const password = 'emulator-only-password';
let originalFlags: FirebaseFirestore.DocumentData | undefined;
let originalPolicy: FirebaseFirestore.DocumentData | undefined;
let originalMeta: FirebaseFirestore.DocumentData | undefined;
let app: FirebaseApp;
let auth: Auth;
let functions: Functions;
const uid = `${prefix}-user`;
let rateRef: FirebaseFirestore.DocumentReference;

const call = async (name: string, data: unknown = {}) =>
  (await httpsCallable(functions, name)(data)).data;
const camera = (id: string, type: string) => ({
  id,
  type,
  name: 'Emulator camera',
  latitude: 57.5,
  longitude: 12.1,
  direction: 90,
});

beforeAll(async () => {
  [originalFlags, originalPolicy, originalMeta] = await Promise.all([
    flagsRef.get().then((snapshot) => snapshot.data()),
    policyRef.get().then((snapshot) => snapshot.data()),
    metaRef.get().then((snapshot) => snapshot.data()),
  ]);
  app = initializeApp({ projectId, apiKey: 'demo-api-key', appId: 'demo-camera-app' }, prefix);
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${authHost}`, { disableWarnings: true });
  functions = getFunctions(app, 'europe-west1');
  const [host, port] = functionsHost.split(':');
  connectFunctionsEmulator(functions, host!, Number(port));
  // A deterministic emulator-only UID keeps the counter key independent of
  // credential objects. The real client still signs in through Auth below.
  await getAdminAuth(adminApp).createUser({ uid, email, password });
  rateRef = db.doc(`roadCameraRateLimits/${createHash('sha256').update(uid).digest('hex')}`);
  // Wait for provisioning before replacing the profile fixture. This suite
  // isolates the camera actor gate from cross-product identity initialization;
  // the dedicated access suite covers that policy, restored below after use.
  const deadline = Date.now() + 45_000;
  while (!(await db.doc(`users/${uid}`).get()).exists) {
    if (Date.now() >= deadline) throw new Error('Camera test account was not provisioned.');
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
});

beforeEach(async () => {
  await signInWithEmailAndPassword(auth, email, password);
  await auth.currentUser!.getIdToken(true);
  const batch = db.batch();
  batch.set(flagsRef, { ...originalFlags, externalDataSources: true });
  batch.set(policyRef, { ...originalPolicy, enforceInitialized: false });
  batch.set(db.doc(`users/${uid}`), {
    role: 'user',
    activeMember: true,
    suspended: false,
    deleted: false,
    displayName: 'Camera emulator user',
    onboardingCompletedAt: Timestamp.now(),
  });
  batch.set(metaRef, { generation, pageCount: 1, updatedAt: Timestamp.fromMillis(1_000) });
  batch.set(pageRef, {
    cameras: [
      { ...camera(trafficId, 'traffic'), photoUrl: 'https://private.invalid/never-expose' },
      camera(speedId, 'speed'),
      camera(weatherId, 'road_condition'),
    ],
  });
  batch.set(db.doc(`roadCameraRecords/${speedId}`), {
    ...camera(speedId, 'speed'),
    photoUrl: null,
  });
  // Deliberately unusable endpoint: the quota tests MUST stop before it is read
  // or passed to the URL sanitizer. The network guard also blocks cloud I/O.
  batch.set(db.doc(`roadCameraRecords/${trafficId}`), {
    ...camera(trafficId, 'traffic'),
    photoUrl: 'https://forbidden.invalid/never-fetch',
  });
  batch.delete(rateRef);
  batch.delete(globalRef);
  await batch.commit();
});

afterAll(async () => {
  const batch = db.batch();
  for (const [ref, original] of [
    [flagsRef, originalFlags],
    [policyRef, originalPolicy],
    [metaRef, originalMeta],
  ] as const) {
    if (original) batch.set(ref, original);
    else batch.delete(ref);
  }
  batch.delete(pageRef);
  batch.delete(db.doc(`roadCameraRecords/${trafficId}`));
  batch.delete(db.doc(`roadCameraRecords/${speedId}`));
  if (rateRef) batch.delete(rateRef);
  batch.delete(globalRef);
  await batch.commit();
  if (app) await deleteApp(app);
});

describe('road camera callable transport', () => {
  it('rejects unauthenticated catalog and photo callers before writing budgets', async () => {
    await signOut(auth);
    await expect(call('roadCameras-catalog')).rejects.toMatchObject({
      code: 'functions/unauthenticated',
    });
    await expect(call('roadCameras-photo', { cameraId: trafficId })).rejects.toMatchObject({
      code: 'functions/unauthenticated',
    });
    expect((await globalRef.get()).exists).toBe(false);
    expect((await rateRef.get()).exists).toBe(false);
  });

  it('honors the external-source flag before reserving either budget', async () => {
    await flagsRef.set({ externalDataSources: false }, { merge: true });
    await expect(call('roadCameras-catalog')).rejects.toMatchObject({
      code: 'functions/failed-precondition',
    });
    await expect(call('roadCameras-photo', { cameraId: trafficId })).rejects.toMatchObject({
      code: 'functions/failed-precondition',
    });
    expect((await globalRef.get()).exists).toBe(false);
    expect((await rateRef.get()).exists).toBe(false);
  });

  it('rejects a suspended actor before camera reservations', async () => {
    await db.doc(`users/${uid}`).set({ suspended: true }, { merge: true });
    await expect(call('roadCameras-catalog')).rejects.toMatchObject({
      code: 'functions/permission-denied',
    });
    await expect(call('roadCameras-photo', { cameraId: trafficId })).rejects.toMatchObject({
      code: 'functions/permission-denied',
    });
    expect((await rateRef.get()).exists).toBe(false);
  });

  it('returns a bounded three-type catalog without server-only endpoints', async () => {
    const result = (await call('roadCameras-catalog')) as {
      cameras: Array<{ id: string; type: string }>;
      updatedAt: string;
    };
    expect(result.cameras.map((entry) => entry.type)).toEqual([
      'traffic',
      'speed',
      'road_condition',
    ]);
    expect(result.updatedAt).toBe('1970-01-01T00:00:01.000Z');
    expect(JSON.stringify(result)).not.toContain('photoUrl');
    expect(JSON.stringify(result)).not.toContain('private.invalid');
    expect((await rateRef.get()).get('catalogsDay')).toBe(1);
    expect((await globalRef.get()).get('catalogsDay')).toBe(1);
  });

  it('rejects incomplete generations rather than returning a partial catalog', async () => {
    await metaRef.set({ pageCount: 2 }, { merge: true });
    await expect(call('roadCameras-catalog')).rejects.toMatchObject({
      code: 'functions/unavailable',
    });
    expect((await rateRef.get()).get('catalogsDay')).toBe(1);
  });

  it('never downloads an image for a fixed speed camera', async () => {
    await expect(call('roadCameras-photo', { cameraId: speedId })).rejects.toMatchObject({
      code: 'functions/not-found',
    });
    expect((await rateRef.get()).get('photosDay')).toBe(1);
    expect((await globalRef.get()).get('photoBytes')).toBe(PHOTO_RESERVED_BYTES);
  });

  it('enforces the exhausted user budget before resolving a photo endpoint', async () => {
    const now = Date.now();
    await rateRef.set({
      day: Math.floor(now / 86_400_000),
      hour: Math.floor(now / 3_600_000),
      minute: Math.floor(now / 60_000),
      photosMinute: 6,
      photosHour: 30,
      photosDay: 100,
    });
    await expect(call('roadCameras-photo', { cameraId: trafficId })).rejects.toMatchObject({
      code: 'functions/resource-exhausted',
    });
    expect((await globalRef.get()).exists).toBe(false);
    expect((await rateRef.get()).get('photosMinute')).toBe(6);
  });

  it('enforces the global encoded-byte budget before resolving a photo endpoint', async () => {
    const budget = {
      day: Math.floor(Date.now() / 86_400_000),
      photoBytes: GLOBAL_PHOTO_BYTES_PER_DAY,
    };
    await globalRef.set(budget);
    await expect(call('roadCameras-photo', { cameraId: trafficId })).rejects.toMatchObject({
      code: 'functions/resource-exhausted',
    });
    expect((await globalRef.get()).get('photoBytes')).toBe(GLOBAL_PHOTO_BYTES_PER_DAY);
    expect((await rateRef.get()).exists).toBe(false);
  });

  it('accepts only opaque camera ids, before writing any reservation', async () => {
    await expect(
      call('roadCameras-photo', { cameraId: 'https://forbidden.invalid/a.jpg' }),
    ).rejects.toMatchObject({ code: 'functions/invalid-argument' });
    await expect(call('roadCameras-catalog', { unexpected: true })).rejects.toMatchObject({
      code: 'functions/invalid-argument',
    });
    expect((await globalRef.get()).exists).toBe(false);
    expect((await rateRef.get()).exists).toBe(false);
  });
});
