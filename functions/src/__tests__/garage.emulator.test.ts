/**
 * Garage emulator integration tests (Phase 9e).
 *
 * Exercises the deployed-in-emulator callables end-to-end:
 * - `garage-addVehicle` (member gating, per-user cap)
 * - `garage-updateVehicle` (ownership, imagePath prefix validation)
 * - `garage-deleteVehicle` (storage prefix cleanup)
 *
 * Requires the Functions + Storage emulators — run via:
 *   pnpm emulators:test
 */

process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.FIREBASE_STORAGE_EMULATOR_HOST ??= '127.0.0.1:9199';
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
import { getApps as getAdminApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getFirestore as getAdminFirestore } from 'firebase-admin/firestore';
import { getStorage as getAdminStorage } from 'firebase-admin/storage';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'garage-emulator-tests');
const adminDb = getAdminFirestore(adminApp);
const adminBucket = getAdminStorage(adminApp).bucket(`${PROJECT_ID}.appspot.com`);

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

async function callableErrorCode(promise: Promise<unknown>): Promise<string> {
  try {
    await promise;
    return 'no-error';
  } catch (error) {
    if (error instanceof FirebaseError) return error.code;
    throw error;
  }
}

async function createProvisionedUser(prefix: string): Promise<TestUser> {
  const email = `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
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

let member: TestUser;
let otherMember: TestUser;
let freeUser: TestUser;

const validAdd = {
  make: 'Volvo',
  model: '240 Turbo',
  modelYear: 1984,
  powertrain: 'petrol',
  description: 'Gruppe A homologation',
};

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'garage-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  member = await createProvisionedUser('garage-member');
  otherMember = await createProvisionedUser('garage-other');
  freeUser = await createProvisionedUser('garage-free');
  for (const u of [member, otherMember]) {
    await adminDb.collection('users').doc(u.uid).set({ activeMember: true }, { merge: true });
  }
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('garage-addVehicle', () => {
  it('rejects unauthenticated and non-member callers', async () => {
    await auth.signOut();
    expect(await callableErrorCode(call('garage-addVehicle', validAdd))).toBe(
      'functions/unauthenticated',
    );
    await signInAs(freeUser);
    expect(await callableErrorCode(call('garage-addVehicle', validAdd))).toBe(
      'functions/permission-denied',
    );
  });

  it('adds a vehicle with null optionals and rejects invalid input', async () => {
    await signInAs(member);
    const result = await call('garage-addVehicle', validAdd);
    const { vehicleId } = result.data as { vehicleId: string };

    const docData = (await adminDb.collection('vehicles').doc(vehicleId).get()).data()!;
    expect(docData.userId).toBe(member.uid);
    expect(docData.make).toBe('Volvo');
    expect(docData.powertrain).toBe('petrol');
    expect(docData.engineDescription).toBeNull();
    expect(docData.imagePath).toBeNull();

    expect(
      await callableErrorCode(
        call('garage-addVehicle', { ...validAdd, registrationPlate: 'ABC123' }),
      ),
    ).toBe('functions/invalid-argument');
    expect(
      await callableErrorCode(call('garage-addVehicle', { ...validAdd, modelYear: 1700 })),
    ).toBe('functions/invalid-argument');
  });

  it('enforces the per-user cap of 5 vehicles', async () => {
    await signInAs(otherMember);
    for (let i = 0; i < 5; i += 1) {
      await call('garage-addVehicle', { ...validAdd, model: `Model ${i}` });
    }
    expect(await callableErrorCode(call('garage-addVehicle', validAdd))).toBe(
      'functions/failed-precondition',
    );
  });
});

describe('garage-updateVehicle / garage-deleteVehicle', () => {
  let vehicleId: string;

  beforeAll(async () => {
    await signInAs(member);
    vehicleId = ((await call('garage-addVehicle', validAdd)).data as { vehicleId: string })
      .vehicleId;
  });

  it('updates owned vehicles and validates the imagePath prefix', async () => {
    await signInAs(member);
    await call('garage-updateVehicle', {
      vehicleId,
      color: 'Polar White',
      imagePath: `vehicleImages/${member.uid}/${vehicleId}/front.jpg`,
    });
    const docData = (await adminDb.collection('vehicles').doc(vehicleId).get()).data()!;
    expect(docData.color).toBe('Polar White');
    expect(docData.imagePath).toBe(`vehicleImages/${member.uid}/${vehicleId}/front.jpg`);

    expect(
      await callableErrorCode(
        call('garage-updateVehicle', {
          vehicleId,
          imagePath: `vehicleImages/${otherMember.uid}/${vehicleId}/spoof.jpg`,
        }),
      ),
    ).toBe('functions/invalid-argument');
    expect(await callableErrorCode(call('garage-updateVehicle', { vehicleId }))).toBe(
      'functions/invalid-argument',
    );
  });

  it("returns not-found for another member's vehicle (no existence probing)", async () => {
    await signInAs(otherMember);
    expect(
      await callableErrorCode(call('garage-updateVehicle', { vehicleId, color: 'Stolen Red' })),
    ).toBe('functions/not-found');
    expect(await callableErrorCode(call('garage-deleteVehicle', { vehicleId }))).toBe(
      'functions/not-found',
    );
  });

  it('marks one main car and enforces at most one per user', async () => {
    // A fresh member so the max-1 assertions are isolated from other tests.
    const owner = await createProvisionedUser('garage-main');
    await adminDb.collection('users').doc(owner.uid).set({ activeMember: true }, { merge: true });
    await signInAs(owner);

    const first = (
      (await call('garage-addVehicle', { ...validAdd, model: 'First' })).data as {
        vehicleId: string;
      }
    ).vehicleId;
    const second = (
      (await call('garage-addVehicle', { ...validAdd, model: 'Second' })).data as {
        vehicleId: string;
      }
    ).vehicleId;

    await call('garage-setMainVehicle', { vehicleId: first, isMain: true });
    expect((await adminDb.collection('vehicles').doc(first).get()).data()!.isMainCar).toBe(true);

    // Setting a second main clears the first (max 1 per user).
    await call('garage-setMainVehicle', { vehicleId: second, isMain: true });
    expect((await adminDb.collection('vehicles').doc(first).get()).data()!.isMainCar).toBe(false);
    expect((await adminDb.collection('vehicles').doc(second).get()).data()!.isMainCar).toBe(true);

    // Clearing the flag leaves no main car.
    await call('garage-setMainVehicle', { vehicleId: second, isMain: false });
    expect((await adminDb.collection('vehicles').doc(second).get()).data()!.isMainCar).toBe(false);
  });

  it("returns not-found when setting another member's vehicle as main", async () => {
    await signInAs(otherMember);
    expect(
      await callableErrorCode(call('garage-setMainVehicle', { vehicleId, isMain: true })),
    ).toBe('functions/not-found');
  });

  it('deletes an owned vehicle together with its image files', async () => {
    const imagePath = `vehicleImages/${member.uid}/${vehicleId}/front.jpg`;
    await adminBucket.file(imagePath).save(Buffer.from([0xff, 0xd8, 0xff, 0xe0]), {
      contentType: 'image/jpeg',
    });

    await signInAs(member);
    await call('garage-deleteVehicle', { vehicleId });

    expect((await adminDb.collection('vehicles').doc(vehicleId).get()).exists).toBe(false);
    const [imageExists] = await adminBucket.file(imagePath).exists();
    expect(imageExists).toBe(false);
  });
});
