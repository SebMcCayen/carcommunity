/**
 * Partner domain emulator integration tests (Phase 9i).
 *
 * Exercises the callables end-to-end: application submit → review →
 * approve creates the draft company; company/offer lifecycle; the
 * three-tier offer privacy split; showOfferCode gating.
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
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'partners-emulator-tests');
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
let member: TestUser;
let freeUser: TestUser;

const validOffer = {
  title: '10% på service',
  teaserText: 'Medlemsrabatt hos verkstan.',
  offerType: 'percentage_discount',
  description: 'Full servicerabatt för medlemmar.',
  percentageDiscount: 10,
  discountCode: 'KCC10',
};

async function createActiveCompany(): Promise<string> {
  await signInAs(adminUser);
  const created = (
    await call('partners-createCompany', { name: 'Verkstan AB', category: 'workshop' })
  ).data as { companyId: string };
  await call('partners-setCompanyStatus', { companyId: created.companyId, action: 'activate' });
  return created.companyId;
}

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'partners-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  adminUser = await createProvisionedUser('pt-admin');
  await adminAuth.setCustomUserClaims(adminUser.uid, { admin: true });
  await adminDb.collection('users').doc(adminUser.uid).set({ role: 'admin' }, { merge: true });
  member = await createProvisionedUser('pt-member');
  await adminDb.collection('users').doc(member.uid).set({ activeMember: true }, { merge: true });
  freeUser = await createProvisionedUser('pt-free');
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('partners-updateCompany (Phase 16 coverage audit)', () => {
  it('edits draft companies, rejects active ones, and denies non-admins', async () => {
    await signInAs(adminUser);
    const created = (
      await call('partners-createCompany', { name: 'Redigera AB', category: 'parts' })
    ).data as { companyId: string };

    await call('partners-updateCompany', {
      companyId: created.companyId,
      name: 'Redigerad AB',
    });
    expect(
      (await adminDb.collection('companies').doc(created.companyId).get()).data()!.name,
    ).toBe('Redigerad AB');

    // Active companies are edit-locked (shared lifecycle).
    await call('partners-setCompanyStatus', { companyId: created.companyId, action: 'activate' });
    expect(
      await callableErrorCode(
        call('partners-updateCompany', { companyId: created.companyId, name: 'Nyare AB' }),
      ),
    ).toBe('functions/failed-precondition');

    await signInAs(member);
    expect(
      await callableErrorCode(
        call('partners-updateCompany', { companyId: created.companyId, name: 'Hacker AB' }),
      ),
    ).toBe('functions/permission-denied');
  });
});

describe('partner applications', () => {
  it('submits once per user, and approval creates the draft company atomically', async () => {
    await signInAs(freeUser);
    const application = {
      companyName: 'Däckfirman i Norr',
      category: 'tires',
      contactName: 'Anna Andersson',
      contactEmail: `anna-${Date.now()}@dack.se`,
    };
    const submitted = (await call('partners-submitApplication', application)).data as {
      applicationId: string;
    };

    // Duplicate-spam guard: same user cannot have two active applications.
    expect(
      await callableErrorCode(
        call('partners-submitApplication', {
          ...application,
          contactEmail: `other-${Date.now()}@dack.se`,
        }),
      ),
    ).toBe('functions/already-exists');

    // Non-admin cannot review.
    expect(
      await callableErrorCode(
        call('partners-reviewApplication', {
          applicationId: submitted.applicationId,
          action: 'approve',
        }),
      ),
    ).toBe('functions/permission-denied');

    await signInAs(adminUser);
    await call('partners-reviewApplication', {
      applicationId: submitted.applicationId,
      action: 'start_review',
    });
    const approved = (
      await call('partners-reviewApplication', {
        applicationId: submitted.applicationId,
        action: 'approve',
        note: 'Ser seriösa ut.',
      })
    ).data as { partnerCompanyId: string };
    expect(typeof approved.partnerCompanyId).toBe('string');

    const company = (
      await adminDb.collection('companies').doc(approved.partnerCompanyId).get()
    ).data()!;
    expect(company.status).toBe('draft');
    expect(company.name).toBe('Däckfirman i Norr');
    expect(company.sourceApplicationId).toBe(submitted.applicationId);

    // Approving again is idempotent — same company, no duplicate.
    const replay = (
      await call('partners-reviewApplication', {
        applicationId: submitted.applicationId,
        action: 'approve',
      })
    ).data as { partnerCompanyId: string };
    expect(replay.partnerCompanyId).toBe(approved.partnerCompanyId);
  });

  it('rejection requires a note', async () => {
    await signInAs(freeUser);
    const submitted = (
      await call('partners-submitApplication', {
        companyName: 'Laddbolaget',
        category: 'charging',
        contactName: 'Bo',
        contactEmail: `bo-${Date.now()}@ladd.se`,
      })
    ).data as { applicationId: string };

    await signInAs(adminUser);
    expect(
      await callableErrorCode(
        call('partners-reviewApplication', {
          applicationId: submitted.applicationId,
          action: 'reject',
        }),
      ),
    ).toBe('functions/invalid-argument');
    await call('partners-reviewApplication', {
      applicationId: submitted.applicationId,
      action: 'reject',
      note: 'Utanför regionen.',
    });
    const app2 = (
      await adminDb.collection('partnerApplications').doc(submitted.applicationId).get()
    ).data()!;
    expect(app2.status).toBe('rejected');
    expect(app2.reviewNote).toBe('Utanför regionen.');
  });
});

describe('offers and the three-tier privacy split', () => {
  it('creates the three documents and edit-locks active offers', async () => {
    const companyId = await createActiveCompany();
    await signInAs(adminUser);
    const created = (
      await call('partners-createOffer', { ...validOffer, companyId })
    ).data as { offerId: string; status: string };
    expect(created.status).toBe('draft');

    const offerRef = adminDb.collection('offers').doc(created.offerId);
    const teaser = (await offerRef.get()).data()!;
    expect(teaser.partnerCompanyName).toBe('Verkstan AB');
    expect(teaser.description).toBeUndefined();
    expect(teaser.discountCode).toBeUndefined();
    const memberDoc = (await offerRef.collection('details').doc('member').get()).data()!;
    expect(memberDoc.description).toBe(validOffer.description);
    expect(memberDoc.discountCode).toBeUndefined();
    const secret = (await offerRef.collection('secret').doc('code').get()).data()!;
    expect(secret.discountCode).toBe('KCC10');

    await call('partners-setOfferStatus', { offerId: created.offerId, action: 'activate' });
    expect(
      await callableErrorCode(
        call('partners-updateOffer', { offerId: created.offerId, title: 'Ny titel' }),
      ),
    ).toBe('functions/failed-precondition');
  });

  it('reveals the code only to members for active offers', async () => {
    const companyId = await createActiveCompany();
    await signInAs(adminUser);
    const created = (
      await call('partners-createOffer', { ...validOffer, companyId })
    ).data as { offerId: string };

    // Draft offer: even members get not-found.
    await signInAs(member);
    expect(
      await callableErrorCode(call('partners-showOfferCode', { offerId: created.offerId })),
    ).toBe('functions/not-found');

    await signInAs(adminUser);
    await call('partners-setOfferStatus', { offerId: created.offerId, action: 'activate' });

    // Was: permission-denied for a free user. Member gating is disabled, so
    // the code is revealed to any signed-in, non-suspended user.
    await signInAs(freeUser);
    const freeReveal = (
      await call('partners-showOfferCode', { offerId: created.offerId })
    ).data as { code: string | null };
    expect(freeReveal.code).toBe('KCC10');

    // Teeth: a suspended user is still refused the code.
    const suspended = await createProvisionedUser('partners-suspended');
    await adminDb.collection('users').doc(suspended.uid).set({ suspended: true }, { merge: true });
    await signInAs(suspended);
    expect(
      await callableErrorCode(call('partners-showOfferCode', { offerId: created.offerId })),
    ).toBe('functions/permission-denied');

    await signInAs(member);
    const revealed = (
      await call('partners-showOfferCode', { offerId: created.offerId })
    ).data as { code: string | null };
    expect(revealed.code).toBe('KCC10');
  });

  it('with partnerMemberOffersRequirePaid ON: only PAID subscribers (or admin) reveal the code', async () => {
    const companyId = await createActiveCompany();
    await signInAs(adminUser);
    const created = (
      await call('partners-createOffer', { ...validOffer, companyId })
    ).data as { offerId: string };
    await call('partners-setOfferStatus', { offerId: created.offerId, action: 'activate' });

    // Flip the dark flag ON for the duration of this test only.
    await adminDb
      .collection('config')
      .doc('featureFlags')
      .set({ partnerMemberOffersRequirePaid: true }, { merge: true });
    try {
      // A free (Community) user — even one carrying the legacy activeMember flag
      // but with no paid subscriptions/{uid} record — is now DENIED. `member`
      // holds activeMember:true yet has no paid subscription, so it fails too.
      await signInAs(freeUser);
      expect(
        await callableErrorCode(call('partners-showOfferCode', { offerId: created.offerId })),
      ).toBe('functions/permission-denied');
      await signInAs(member);
      expect(
        await callableErrorCode(call('partners-showOfferCode', { offerId: created.offerId })),
      ).toBe('functions/permission-denied');

      // A PAID subscriber (Plus) — an active member_monthly subscriptions record —
      // reveals the code.
      const paid = await createProvisionedUser('pt-paid');
      await adminDb
        .collection('subscriptions')
        .doc(paid.uid)
        .set({
          userId: paid.uid,
          platform: 'google',
          status: 'active',
          entitlement: 'member_monthly',
          tier: 'plus',
        });
      await signInAs(paid);
      const paidReveal = (
        await call('partners-showOfferCode', { offerId: created.offerId })
      ).data as { code: string | null };
      expect(paidReveal.code).toBe('KCC10');

      // An admin is never blocked by the paid gate.
      await signInAs(adminUser);
      const adminReveal = (
        await call('partners-showOfferCode', { offerId: created.offerId })
      ).data as { code: string | null };
      expect(adminReveal.code).toBe('KCC10');
    } finally {
      // Reset the shared flag so other tests see the contract default (OFF).
      await adminDb
        .collection('config')
        .doc('featureFlags')
        .set({ partnerMemberOffersRequirePaid: false }, { merge: true });
    }
  });
});
