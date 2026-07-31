/**
 * Digital billboards emulator integration tests (Phase 9k).
 *
 * Exercises the lifecycle end-to-end: draft creation, the six-point safety
 * gate, partner-must-be-active activation rule, edit locking, and the
 * billboard-tap → partner-insights bridge.
 *
 * Requires the Functions emulator — run via:
 *   pnpm emulators:test
 */

process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
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
import { getAuth as getAdminAuth } from 'firebase-admin/auth';
import { getFirestore as getAdminFirestore } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'billboards-emulator-tests');
const adminAuth = getAdminAuth(adminApp);
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

let adminUser: TestUser;
let user: TestUser;
let activePartnerId: string;
let draftPartnerId: string;

const validCreate = {
  headline: 'Fika hos Verkstan',
  message: 'Stanna till och säg hej — kaffe till alla medlemmar.',
  placementType: 'map_billboard',
  latitude: 59.33,
  longitude: 18.07,
};

const allConfirmations = {
  notBusinessLocationConfirmed: true,
  notRoadLaneConfirmed: true,
  notRoadSignConfirmed: true,
  notObstructingMapConfirmed: true,
  markedAsAdvertisingConfirmed: true,
  suitableForMapConfirmed: true,
  approvalReason: 'Placering granskad på karta och foto.',
};

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'billboards-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  adminUser = await createProvisionedUser('bb-admin');
  await adminAuth.setCustomUserClaims(adminUser.uid, { admin: true });
  await adminDb.collection('users').doc(adminUser.uid).set({ role: 'admin' }, { merge: true });
  user = await createProvisionedUser('bb-user');

  await signInAs(adminUser);
  activePartnerId = (
    (await call('partners-createCompany', { name: 'Verkstan AB', category: 'workshop' })).data as {
      companyId: string;
    }
  ).companyId;
  await call('partners-setCompanyStatus', { companyId: activePartnerId, action: 'activate' });
  draftPartnerId = (
    (await call('partners-createCompany', { name: 'Utkast AB', category: 'parts' })).data as {
      companyId: string;
    }
  ).companyId;
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('billboards lifecycle and the safety gate', () => {
  it('creates drafts, enforces all six confirmations, and stamps approval', async () => {
    await signInAs(adminUser);
    const created = (
      await call('billboards-create', { ...validCreate, partnerCompanyId: activePartnerId })
    ).data as { billboardId: string; status: string };
    expect(created.status).toBe('draft');

    // Missing/false confirmation → invalid-argument (schema-enforced).
    expect(
      await callableErrorCode(
        call('billboards-activate', {
          billboardId: created.billboardId,
          ...allConfirmations,
          notRoadSignConfirmed: false,
        }),
      ),
    ).toBe('functions/invalid-argument');

    await call('billboards-activate', { billboardId: created.billboardId, ...allConfirmations });
    const active = (await adminDb.collection('billboards').doc(created.billboardId).get()).data()!;
    expect(active.status).toBe('active');
    expect(active.approvedByUserId).toBe(adminUser.uid);
    expect(active.approvedAt).not.toBeNull();
    // Activation puts it on the map in the SAME write — not at the next sweep.
    // `mapVisible` is what the member query filters on and what the read rule
    // requires, so an activation that forgot it would be an activation that
    // silently did nothing visible.
    expect(active.mapVisible).toBe(true);

    const audit = await adminDb
      .collection('adminAuditEvents')
      .where('action', '==', 'billboards.activate')
      .where('targetId', '==', created.billboardId)
      .get();
    expect(audit.size).toBe(1);
    // size asserted === 1 above, so docs[0] is present.
    expect(audit.docs[0]!.data().reason).toBe(allConfirmations.approvalReason);

    // Active billboards are edit-locked; pause unlocks; end is terminal.
    expect(
      await callableErrorCode(
        call('billboards-update', { billboardId: created.billboardId, headline: 'Ny rubrik' }),
      ),
    ).toBe('functions/failed-precondition');
    await call('billboards-setStatus', { billboardId: created.billboardId, action: 'pause' });
    // Pausing takes the marker off every member's map immediately — "I paused
    // it" must not mean "gone within ten minutes".
    const paused = (await adminDb.collection('billboards').doc(created.billboardId).get()).data()!;
    expect(paused.status).toBe('paused');
    expect(paused.mapVisible).toBe(false);
    await call('billboards-update', { billboardId: created.billboardId, headline: 'Ny rubrik' });
    await call('billboards-setStatus', { billboardId: created.billboardId, action: 'end' });
    const ended = (await adminDb.collection('billboards').doc(created.billboardId).get()).data()!;
    expect(ended.mapVisible).toBe(false);
    expect(
      await callableErrorCode(
        call('billboards-setStatus', { billboardId: created.billboardId, action: 'pause' }),
      ),
    ).toBe('functions/failed-precondition');
  });

  it('refuses activation while the sponsoring partner is not active', async () => {
    await signInAs(adminUser);
    const created = (
      await call('billboards-create', { ...validCreate, partnerCompanyId: draftPartnerId })
    ).data as { billboardId: string };
    expect(
      await callableErrorCode(
        call('billboards-activate', { billboardId: created.billboardId, ...allConfirmations }),
      ),
    ).toBe('functions/failed-precondition');
  });

  it('rejects non-admin management', async () => {
    await signInAs(user);
    expect(
      await callableErrorCode(
        call('billboards-create', { ...validCreate, partnerCompanyId: activePartnerId }),
      ),
    ).toBe('functions/permission-denied');
  });
});

describe('billboards-recordInteraction → insights bridge', () => {
  it('maps taps onto insight events through the privacy pipeline', async () => {
    await signInAs(adminUser);
    const created = (
      await call('billboards-create', { ...validCreate, partnerCompanyId: activePartnerId })
    ).data as { billboardId: string };
    await call('billboards-activate', { billboardId: created.billboardId, ...allConfirmations });

    await signInAs(user);
    const first = (
      await call('billboards-recordInteraction', {
        billboardId: created.billboardId,
        interactionType: 'open',
      })
    ).data as { recorded: boolean };
    expect(first.recorded).toBe(true);

    // The insight event carries the MAPPED type and a scoped hash.
    const events = await adminDb
      .collection('partnerInsightsEvents')
      .where('companyId', '==', activePartnerId)
      .where('interactionType', '==', 'profile_view')
      .get();
    expect(events.size).toBe(1);
    // size asserted === 1 above, so docs[0] is present.
    expect(JSON.stringify(events.docs[0]!.data())).not.toContain(user.uid);

    // Same-day repeat dedupes through the same pipeline.
    const dupe = (
      await call('billboards-recordInteraction', {
        billboardId: created.billboardId,
        interactionType: 'open',
      })
    ).data as { recorded: boolean };
    expect(dupe.recorded).toBe(false);
  });

  it('rejects inactive billboards and honors the feature flag', async () => {
    await signInAs(adminUser);
    const draft = (
      await call('billboards-create', { ...validCreate, partnerCompanyId: activePartnerId })
    ).data as { billboardId: string };

    await signInAs(user);
    expect(
      await callableErrorCode(
        call('billboards-recordInteraction', {
          billboardId: draft.billboardId,
          interactionType: 'impression',
        }),
      ),
    ).toBe('functions/not-found');

    await adminDb
      .collection('config')
      .doc('featureFlags')
      .set({ digitalBillboards: false }, { merge: true });
    try {
      expect(
        await callableErrorCode(
          call('billboards-recordInteraction', {
            billboardId: draft.billboardId,
            interactionType: 'impression',
          }),
        ),
      ).toBe('functions/failed-precondition');
    } finally {
      await adminDb
        .collection('config')
        .doc('featureFlags')
        .set({ digitalBillboards: true }, { merge: true });
    }
  });
});
