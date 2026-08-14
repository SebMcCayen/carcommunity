/**
 * Garage emulator integration tests (Phase 9e).
 *
 * Exercises the deployed-in-emulator callables end-to-end:
 * - `garage-addVehicle` (auth-only — NOT member-gated — per-user cap)
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
  it('rejects unauthenticated and suspended callers, but lets any signed-in (non-member) user add their own car', async () => {
    await auth.signOut();
    expect(await callableErrorCode(call('garage-addVehicle', validAdd))).toBe(
      'functions/unauthenticated',
    );

    // Managing your OWN garage is no longer member-gated: a non-member (no
    // activeMember entitlement) may add their own car.
    await signInAs(freeUser);
    const { vehicleId } = (await call('garage-addVehicle', validAdd)).data as {
      vehicleId: string;
    };
    expect((await adminDb.collection('vehicles').doc(vehicleId).get()).data()!.userId).toBe(
      freeUser.uid,
    );

    // Suspension still overrides access (requireActiveActor keeps the
    // suspended/deleted guard even though membership is no longer required).
    const suspended = await createProvisionedUser('garage-suspended');
    await adminDb.collection('users').doc(suspended.uid).set({ suspended: true }, { merge: true });
    await signInAs(suspended);
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
    // Plate omitted -> stored as null (deliberately-public field, optional).
    expect(docData.registrationPlate).toBeNull();

    expect(
      await callableErrorCode(call('garage-addVehicle', { ...validAdd, modelYear: 1700 })),
    ).toBe('functions/invalid-argument');
  });

  it('stores a CATALOGUE selection and derives the display text server-side', async () => {
    // The point of the whole feature, end-to-end: the client sends only ids and
    // the DEPLOYED callable resolves the text. A client that could send the text
    // too could label a `volvo` id "Ferrari" and the per-manufacturer counts
    // would stop meaning anything.
    await signInAs(member);
    const { vehicleId } = (
      await call('garage-addVehicle', {
        makeId: 'volvo',
        modelId: '740',
        modelYear: 1988,
        powertrain: 'petrol',
      })
    ).data as { vehicleId: string };
    const docData = (await adminDb.collection('vehicles').doc(vehicleId).get()).data()!;
    expect(docData.makeId).toBe('volvo');
    expect(docData.modelId).toBe('740');
    expect(docData.make).toBe('Volvo');
    expect(docData.model).toBe('740');
    expect(typeof docData.catalogueVersion).toBe('string');
    await call('garage-deleteVehicle', { vehicleId }); // keep under the 10-car cap
  });

  it('rejects ids the catalogue does not know, and a mixed request', async () => {
    // The dropdown is UX; THIS is the enforcement. A hand-rolled request must not
    // be able to invent a manufacturer, borrow another brand's model, or send
    // both identity forms at once.
    await signInAs(member);
    const base = { modelYear: 1988, powertrain: 'petrol' };
    expect(
      await callableErrorCode(
        call('garage-addVehicle', { ...base, makeId: 'ferrarri', modelId: 'other' }),
      ),
    ).toBe('functions/invalid-argument');
    expect(
      await callableErrorCode(call('garage-addVehicle', { ...base, makeId: 'mazda', modelId: 'mgb' })),
    ).toBe('functions/invalid-argument');
    expect(
      await callableErrorCode(
        call('garage-addVehicle', { ...base, makeId: 'volvo', modelId: '740', make: 'Ferrari' }),
      ),
    ).toBe('functions/invalid-argument');
    // A structured write is also held to the OFFERED year window, which is
    // tighter than the legacy path's.
    expect(
      await callableErrorCode(
        call('garage-addVehicle', {
          makeId: 'volvo',
          modelId: '740',
          modelYear: new Date().getFullYear() + 2,
          powertrain: 'petrol',
        }),
      ),
    ).toBe('functions/invalid-argument');
  });

  it('keeps a pre-catalogue label when its owner selects "Other"', async () => {
    // The migration promise, end-to-end: a car added before the catalogue keeps
    // its free text and gains no ids; when its owner later edits it and their
    // model genuinely is not listed, the text they originally typed SURVIVES
    // rather than being flattened to a placeholder.
    await signInAs(member);
    const { vehicleId } = (
      await call('garage-addVehicle', { ...validAdd, make: 'Volvo', model: 'Duett' })
    ).data as { vehicleId: string };
    let docData = (await adminDb.collection('vehicles').doc(vehicleId).get()).data()!;
    expect(docData.makeId).toBeNull();
    expect(docData.model).toBe('Duett');

    await call('garage-updateVehicle', { vehicleId, makeId: 'volvo', modelId: 'other' });
    docData = (await adminDb.collection('vehicles').doc(vehicleId).get()).data()!;
    expect(docData.makeId).toBe('volvo');
    expect(docData.modelId).toBe('other');
    expect(docData.make).toBe('Volvo');
    expect(docData.model).toBe('Duett');
    await call('garage-deleteVehicle', { vehicleId }); // keep under the 10-car cap
  });

  it('stores the intentionally-public registration plate, normalised', async () => {
    await signInAs(member);
    const { vehicleId } = (
      await call('garage-addVehicle', { ...validAdd, model: 'Plated', registrationPlate: '  abc 123 ' })
    ).data as { vehicleId: string };
    let docData = (await adminDb.collection('vehicles').doc(vehicleId).get()).data()!;
    // trim + collapse whitespace + uppercase.
    expect(docData.registrationPlate).toBe('ABC 123');

    // An explicit blank on update clears it back to null.
    await call('garage-updateVehicle', { vehicleId, registrationPlate: '   ' });
    docData = (await adminDb.collection('vehicles').doc(vehicleId).get()).data()!;
    expect(docData.registrationPlate).toBeNull();
  });

  it('round-trips each of the four offered powertrains verbatim', async () => {
    await signInAs(member);
    for (const powertrain of ['petrol', 'diesel', 'hybrid', 'electric']) {
      const { vehicleId } = (
        await call('garage-addVehicle', { ...validAdd, model: `PT ${powertrain}`, powertrain })
      ).data as { vehicleId: string };
      const docData = (await adminDb.collection('vehicles').doc(vehicleId).get()).data()!;
      expect(docData.powertrain).toBe(powertrain);
      await call('garage-deleteVehicle', { vehicleId }); // keep under the 10-car cap
    }
  });

  it('still stores a RETIRED powertrain verbatim, never remapping it', async () => {
    // Backward compatibility, end-to-end: a shipped client (<= v0.8.0) still
    // offers "Laddhybrid"/"Annat". The add must succeed and the stored value
    // must be exactly what was sent — no silent plug_in_hybrid -> hybrid
    // rewrite, which would misreport the owner's car.
    await signInAs(member);
    for (const powertrain of ['plug_in_hybrid', 'other']) {
      const { vehicleId } = (
        await call('garage-addVehicle', { ...validAdd, model: `Legacy ${powertrain}`, powertrain })
      ).data as { vehicleId: string };
      const docData = (await adminDb.collection('vehicles').doc(vehicleId).get()).data()!;
      expect(docData.powertrain).toBe(powertrain);

      // ...and editing another field leaves the retired value untouched, so a
      // pre-existing car is not corrupted by an unrelated edit.
      await call('garage-updateVehicle', { vehicleId, make: 'Saab' });
      const afterEdit = (await adminDb.collection('vehicles').doc(vehicleId).get()).data()!;
      expect(afterEdit.make).toBe('Saab');
      expect(afterEdit.powertrain).toBe(powertrain);

      // ...and the owner can migrate it forward to one of the four.
      await call('garage-updateVehicle', { vehicleId, powertrain: 'hybrid' });
      expect((await adminDb.collection('vehicles').doc(vehicleId).get()).data()!.powertrain).toBe(
        'hybrid',
      );
      await call('garage-deleteVehicle', { vehicleId }); // keep under the 10-car cap
    }
  });

  it('enforces the per-user cap of 10 vehicles', async () => {
    await signInAs(otherMember);
    for (let i = 0; i < 10; i += 1) {
      await call('garage-addVehicle', { ...validAdd, model: `Model ${i}` });
    }
    // The 11th add is rejected by the transactional cap.
    expect(await callableErrorCode(call('garage-addVehicle', validAdd))).toBe(
      'functions/failed-precondition',
    );
  });
});

/**
 * The ungate applies to ALL FOUR garage callables, not just addVehicle: a
 * signed-in user with no activeMember entitlement may fully manage their OWN
 * cars, while suspension still closes every path (requireActiveActor).
 */
describe('garage CRUD is ungated for non-members (own cars only)', () => {
  it('lets a non-member update, set-main and delete their OWN vehicle', async () => {
    const nonMember = await createProvisionedUser('garage-ungated');
    await signInAs(nonMember);

    // No activeMember entitlement is ever granted to this user.
    const { vehicleId } = (await call('garage-addVehicle', validAdd)).data as {
      vehicleId: string;
    };

    await call('garage-updateVehicle', { vehicleId, color: 'Non-member Blue' });
    expect((await adminDb.collection('vehicles').doc(vehicleId).get()).data()!.color).toBe(
      'Non-member Blue',
    );

    await call('garage-setMainVehicle', { vehicleId, isMain: true });
    expect((await adminDb.collection('vehicles').doc(vehicleId).get()).data()!.isMainCar).toBe(
      true,
    );

    await call('garage-deleteVehicle', { vehicleId });
    expect((await adminDb.collection('vehicles').doc(vehicleId).get()).exists).toBe(false);
  });

  it('still blocks a suspended user on update, set-main and delete', async () => {
    const user = await createProvisionedUser('garage-susp-crud');
    await signInAs(user);
    const { vehicleId } = (await call('garage-addVehicle', validAdd)).data as {
      vehicleId: string;
    };

    // Suspend AFTER the vehicle exists, so these are ownership-valid calls that
    // must still be refused purely because the account is suspended.
    await adminDb.collection('users').doc(user.uid).set({ suspended: true }, { merge: true });
    await signInAs(user);

    expect(
      await callableErrorCode(call('garage-updateVehicle', { vehicleId, color: 'Nope' })),
    ).toBe('functions/permission-denied');
    expect(
      await callableErrorCode(call('garage-setMainVehicle', { vehicleId, isMain: true })),
    ).toBe('functions/permission-denied');
    expect(await callableErrorCode(call('garage-deleteVehicle', { vehicleId }))).toBe(
      'functions/permission-denied',
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

    // Idempotent no-ops: re-affirming an existing state must not rewrite the
    // doc (updatedAt stays put) — both setting an already-main vehicle and
    // clearing one that was never main.
    const firstUpdatedAt = (
      await adminDb.collection('vehicles').doc(first).get()
    ).data()!.updatedAt;
    const secondUpdatedAt = (
      await adminDb.collection('vehicles').doc(second).get()
    ).data()!.updatedAt;
    await call('garage-setMainVehicle', { vehicleId: first, isMain: true });
    await call('garage-setMainVehicle', { vehicleId: second, isMain: false });
    expect((await adminDb.collection('vehicles').doc(first).get()).data()!.updatedAt).toEqual(
      firstUpdatedAt,
    );
    expect((await adminDb.collection('vehicles').doc(second).get()).data()!.updatedAt).toEqual(
      secondUpdatedAt,
    );

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

describe('garage multi-photo (add / remove / reorder)', () => {
  let owner: TestUser;
  let vehicleId: string;

  const path = (id: string) => `vehicleImages/${owner.uid}/${vehicleId}/${id}`;

  beforeAll(async () => {
    owner = await createProvisionedUser('garage-photos');
    await adminDb.collection('users').doc(owner.uid).set({ activeMember: true }, { merge: true });
    await signInAs(owner);
    vehicleId = ((await call('garage-addVehicle', { ...validAdd, model: 'Gallery' })).data as {
      vehicleId: string;
    }).vehicleId;
  });

  it('appends photos and mirrors the first as the cover (imagePath)', async () => {
    await signInAs(owner);
    await call('garage-addVehiclePhoto', { vehicleId, photoPath: path('a.jpg') });
    let doc = (await adminDb.collection('vehicles').doc(vehicleId).get()).data()!;
    expect(doc.photoPaths).toEqual([path('a.jpg')]);
    // First photo becomes the cover.
    expect(doc.imagePath).toBe(path('a.jpg'));

    await call('garage-addVehiclePhoto', { vehicleId, photoPath: path('b.jpg') });
    doc = (await adminDb.collection('vehicles').doc(vehicleId).get()).data()!;
    expect(doc.photoPaths).toEqual([path('a.jpg'), path('b.jpg')]);
    // Cover unchanged by a non-first append.
    expect(doc.imagePath).toBe(path('a.jpg'));
  });

  it('rejects a foreign prefix, a duplicate and a foreign vehicle', async () => {
    await signInAs(owner);
    expect(
      await callableErrorCode(
        call('garage-addVehiclePhoto', {
          vehicleId,
          photoPath: `vehicleImages/${member.uid}/${vehicleId}/spoof.jpg`,
        }),
      ),
    ).toBe('functions/invalid-argument');
    expect(
      await callableErrorCode(call('garage-addVehiclePhoto', { vehicleId, photoPath: path('a.jpg') })),
    ).toBe('functions/already-exists');

    // A foreign caller must supply a path under THEIR OWN prefix so the
    // own-prefix guard passes; the ownership check is then what rejects them —
    // a foreign vehicle is not-found (never permission-denied, no existence
    // probing). A path under the owner's prefix would trip invalid-argument
    // first, before ownership is ever consulted.
    await signInAs(otherMember);
    expect(
      await callableErrorCode(
        call('garage-addVehiclePhoto', {
          vehicleId,
          photoPath: `vehicleImages/${otherMember.uid}/${vehicleId}/z.jpg`,
        }),
      ),
    ).toBe('functions/not-found');
  });

  it('reorders photos (permutation only) and updates the cover', async () => {
    await signInAs(owner);
    await call('garage-reorderVehiclePhotos', {
      vehicleId,
      orderedPaths: [path('b.jpg'), path('a.jpg')],
    });
    const doc = (await adminDb.collection('vehicles').doc(vehicleId).get()).data()!;
    expect(doc.photoPaths).toEqual([path('b.jpg'), path('a.jpg')]);
    expect(doc.imagePath).toBe(path('b.jpg'));

    // A non-permutation (extra / missing member) is rejected.
    expect(
      await callableErrorCode(
        call('garage-reorderVehiclePhotos', { vehicleId, orderedPaths: [path('b.jpg')] }),
      ),
    ).toBe('functions/invalid-argument');
  });

  it('removes a photo, promotes the next cover and deletes the Storage object', async () => {
    // Upload a real object so the delete is observable.
    await adminBucket
      .file(path('b.jpg'))
      .save(Buffer.from([0xff, 0xd8, 0xff, 0xe0]), { contentType: 'image/jpeg' });

    await signInAs(owner);
    // Current order is [b, a] with b as cover; removing b promotes a.
    await call('garage-removeVehiclePhoto', { vehicleId, photoPath: path('b.jpg') });
    const doc = (await adminDb.collection('vehicles').doc(vehicleId).get()).data()!;
    expect(doc.photoPaths).toEqual([path('a.jpg')]);
    expect(doc.imagePath).toBe(path('a.jpg'));
    const [stillThere] = await adminBucket.file(path('b.jpg')).exists();
    expect(stillThere).toBe(false);

    // Removing a path not on the vehicle is not-found.
    expect(
      await callableErrorCode(call('garage-removeVehiclePhoto', { vehicleId, photoPath: path('z.jpg') })),
    ).toBe('functions/not-found');
  });

  it('enforces the 10-photo cap', async () => {
    const capOwner = await createProvisionedUser('garage-photo-cap');
    await adminDb.collection('users').doc(capOwner.uid).set({ activeMember: true }, { merge: true });
    await signInAs(capOwner);
    const capVehicleId = ((await call('garage-addVehicle', { ...validAdd, model: 'Cap' })).data as {
      vehicleId: string;
    }).vehicleId;
    for (let i = 0; i < 10; i += 1) {
      await call('garage-addVehiclePhoto', {
        vehicleId: capVehicleId,
        photoPath: `vehicleImages/${capOwner.uid}/${capVehicleId}/p${i}.jpg`,
      });
    }
    expect(
      await callableErrorCode(
        call('garage-addVehiclePhoto', {
          vehicleId: capVehicleId,
          photoPath: `vehicleImages/${capOwner.uid}/${capVehicleId}/over.jpg`,
        }),
      ),
    ).toBe('functions/failed-precondition');
  });

  it('reconciles photoPaths when the cover is changed via updateVehicle imagePath', async () => {
    const editOwner = await createProvisionedUser('garage-photo-edit');
    await adminDb.collection('users').doc(editOwner.uid).set({ activeMember: true }, { merge: true });
    await signInAs(editOwner);
    const editId = ((await call('garage-addVehicle', { ...validAdd, model: 'Edit' })).data as {
      vehicleId: string;
    }).vehicleId;
    const ep = (id: string) => `vehicleImages/${editOwner.uid}/${editId}/${id}`;

    await call('garage-addVehiclePhoto', { vehicleId: editId, photoPath: ep('a.jpg') });
    await call('garage-addVehiclePhoto', { vehicleId: editId, photoPath: ep('b.jpg') });

    // Changing the single imagePath replaces the cover but keeps the rest.
    await call('garage-updateVehicle', { vehicleId: editId, imagePath: ep('new.jpg') });
    let doc = (await adminDb.collection('vehicles').doc(editId).get()).data()!;
    expect(doc.imagePath).toBe(ep('new.jpg'));
    expect(doc.photoPaths).toEqual([ep('new.jpg'), ep('b.jpg')]);

    // Clearing imagePath empties the gallery.
    await call('garage-updateVehicle', { vehicleId: editId, imagePath: null });
    doc = (await adminDb.collection('vehicles').doc(editId).get()).data()!;
    expect(doc.imagePath).toBeNull();
    expect(doc.photoPaths).toEqual([]);
  });
});
