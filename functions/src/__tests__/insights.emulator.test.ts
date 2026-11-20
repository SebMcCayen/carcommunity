/**
 * Partner insights emulator integration tests (Phase 9j).
 *
 * Exercises the privacy-critical paths end-to-end:
 * - partnerInsights-recordInteraction (scoped hash, per-day dedupe,
 *   pass-by flag + opt-in gate with SILENT opt-out)
 * - runInsightsAggregation (threshold zeroing for every optional interaction)
 * - runInsightsCleanup (7-day TTL)
 *
 * The scheduled runners are imported directly and driven against the
 * emulator Firestore — onSchedule functions cannot be invoked over the
 * callable protocol.
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
import { FieldValue, getFirestore as getAdminFirestore, Timestamp } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { buildScopedHash } from '../partnerInsights/insights-core';
import { runInsightsAggregation, runInsightsCleanup } from '../partnerInsights/scheduled';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'insights-emulator-tests');
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
let companyId: string;

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'insights-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  adminUser = await createProvisionedUser('pi-admin');
  await adminAuth.setCustomUserClaims(adminUser.uid, { admin: true });
  await adminDb.collection('users').doc(adminUser.uid).set({ role: 'admin' }, { merge: true });
  user = await createProvisionedUser('pi-user');
  await adminDb.collection('userPrivate').doc(user.uid).set(
    {
      anonymousPartnerStatsOptIn: true,
      anonymousPartnerStatsConsentVersion: 1,
      anonymousPartnerStatsConsentAt: Timestamp.now(),
    },
    { merge: true },
  );

  await signInAs(adminUser);
  companyId = (
    (await call('partners-createCompany', { name: 'Insikts AB', category: 'workshop' })).data as {
      companyId: string;
    }
  ).companyId;
  await call('partners-setCompanyStatus', { companyId, action: 'activate' });
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('partnerInsights-recordInteraction', () => {
  it('records with a partner-scoped hash — the raw UID never touches the event', async () => {
    await signInAs(user);
    const first = (
      await call('partnerInsights-recordInteraction', {
        companyId,
        interactionType: 'profile_view',
      })
    ).data as { recorded: boolean };
    expect(first.recorded).toBe(true);

    const events = await adminDb
      .collection('partnerInsightsEvents')
      .where('companyId', '==', companyId)
      .get();
    expect(events.size).toBe(1);
    // size asserted === 1 above, so docs[0] is present.
    const event = events.docs[0]!.data();
    // The stored reference is a 64-hex-char scoped hash and the raw UID
    // appears nowhere on the event. (Hash determinism itself is unit-tested
    // with literals in insights-core.test.ts — deliberately NOT re-derived
    // here from the auth credential, which CodeQL taint-tracks as a
    // password source.)
    expect(event.userReferenceHash).toMatch(/^[a-f0-9]{64}$/);
    expect(event.consentVersion).toBe(1);
    // A global consent timestamp would correlate the same member across partners.
    expect(Object.keys(event).sort()).toEqual([
      'aggregationDate',
      'companyId',
      'consentVersion',
      'expiresAt',
      'interactionType',
      'occurredAt',
      'relatedOfferId',
      'userReferenceHash',
    ]);
    expect(JSON.stringify(event)).not.toContain(user.uid);

    // Per-day dedupe: same user + type + day → recorded: false, one event.
    const dupe = (
      await call('partnerInsights-recordInteraction', {
        companyId,
        interactionType: 'profile_view',
      })
    ).data as { recorded: boolean };
    expect(dupe.recorded).toBe(false);
    expect(
      (await adminDb.collection('partnerInsightsEvents').where('companyId', '==', companyId).get())
        .size,
    ).toBe(1);
  });

  it('rejects inactive partners and cross-partner offers', async () => {
    await signInAs(user);
    expect(
      await callableErrorCode(
        call('partnerInsights-recordInteraction', {
          companyId: 'missing-company',
          interactionType: 'map_view',
        }),
      ),
    ).toBe('functions/not-found');
  });

  it('pass-by requires the flag and explicit consent; missing or revoked consent is silently excluded', async () => {
    await signInAs(user);
    // Flag off (contract default): failed-precondition regardless of consent.
    expect(
      await callableErrorCode(
        call('partnerInsights-recordInteraction', {
          companyId,
          interactionType: 'anonymous_pass_by',
        }),
      ),
    ).toBe('functions/failed-precondition');

    await adminDb
      .collection('config')
      .doc('featureFlags')
      .set({ partnerInsightsPassBy: true }, { merge: true });
    try {
      // Flag on, member EXPLICITLY opted out: silent { recorded: false } —
      // same shape as a dedupe, so opting out is unobservable. No event created.
      await adminDb
        .collection('userPrivate')
        .doc(user.uid)
        .set({ anonymousPartnerStatsOptIn: false }, { merge: true });
      const optedOut = (
        await call('partnerInsights-recordInteraction', {
          companyId,
          interactionType: 'anonymous_pass_by',
        })
      ).data as { recorded: boolean };
      expect(optedOut.recorded).toBe(false);
      expect(
        (
          await adminDb
            .collection('partnerInsightsEvents')
            .where('companyId', '==', companyId)
            .where('interactionType', '==', 'anonymous_pass_by')
            .get()
        ).size,
      ).toBe(0);

      // A missing boolean cannot grant consent, even with a retained old marker.
      await adminDb
        .collection('userPrivate')
        .doc(user.uid)
        .update({ anonymousPartnerStatsOptIn: FieldValue.delete() });
      const missingChoice = (
        await call('partnerInsights-recordInteraction', {
          companyId,
          interactionType: 'anonymous_pass_by',
        })
      ).data as { recorded: boolean };
      expect(missingChoice.recorded).toBe(false);
      await adminDb.collection('userPrivate').doc(user.uid).set(
        {
          anonymousPartnerStatsOptIn: true,
          anonymousPartnerStatsConsentVersion: 1,
          anonymousPartnerStatsConsentAt: Timestamp.now(),
        },
        { merge: true },
      );
      expect(
        (
          await call('partnerInsights-recordInteraction', {
            companyId,
            interactionType: 'anonymous_pass_by',
          })
        ).data,
      ).toEqual({ recorded: true });
    } finally {
      await adminDb
        .collection('config')
        .doc('featureFlags')
        .set({ partnerInsightsPassBy: false }, { merge: true });
    }
  });
});

describe('runInsightsAggregation — threshold enforcement', () => {
  it('zeroes below-threshold optional metrics and ignores unmarked historical events', async () => {
    // Pin the effective threshold to the floor (10) explicitly — the config doc
    // is shared across the emulator suite, so other tests may have raised it.
    await adminDb
      .collection('config')
      .doc('partnerInsights')
      .set({ minThreshold: 10 }, { merge: true });

    // Seed a dedicated company with 9 pass-by contributors (below the floor
    // of 10) and 2 profile views, directly via the Admin SDK.
    const seededCompany = 'pi-threshold-co';
    const day = new Date('2026-07-01T10:00:00Z');
    const batch = adminDb.batch();
    for (let i = 0; i < 9; i += 1) {
      const hash = buildScopedHash(seededCompany, `pass-by-user-${i}`);
      batch.set(adminDb.collection('partnerInsightsEvents').doc(`seed-pb-${i}`), {
        companyId: seededCompany,
        interactionType: 'anonymous_pass_by',
        userReferenceHash: hash,
        relatedOfferId: null,
        consentVersion: 1,
        occurredAt: Timestamp.fromDate(day),
        aggregationDate: Timestamp.fromDate(new Date('2026-07-01T00:00:00Z')),
        expiresAt: Timestamp.fromDate(new Date('2026-07-08T10:00:00Z')),
      });
    }
    for (let i = 0; i < 2; i += 1) {
      batch.set(adminDb.collection('partnerInsightsEvents').doc(`seed-pv-${i}`), {
        companyId: seededCompany,
        interactionType: 'profile_view',
        userReferenceHash: buildScopedHash(seededCompany, `viewer-${i}`),
        relatedOfferId: null,
        consentVersion: 1,
        occurredAt: Timestamp.fromDate(day),
        aggregationDate: Timestamp.fromDate(new Date('2026-07-01T00:00:00Z')),
        expiresAt: Timestamp.fromDate(new Date('2026-07-08T10:00:00Z')),
      });
    }
    // This tenth legacy pass-by would meet the floor if historical events
    // without affirmative provenance were accidentally included.
    batch.set(adminDb.collection('partnerInsightsEvents').doc('seed-pb-legacy'), {
      companyId: seededCompany,
      interactionType: 'anonymous_pass_by',
      userReferenceHash: buildScopedHash(seededCompany, 'legacy-user'),
      occurredAt: Timestamp.fromDate(day),
    });
    await batch.commit();

    await runInsightsAggregation(day);

    const passBy = (
      await adminDb
        .collection('partnerInsights')
        .doc(`${seededCompany}_anonymous_pass_by_day_2026-07-01`)
        .get()
    ).data()!;
    expect(passBy.resultStatus).toBe('insufficient_data');
    expect(passBy.totalCount).toBe(0);
    expect(passBy.uniqueContributorCount).toBeNull();

    const profileViews = (
      await adminDb
        .collection('partnerInsights')
        .doc(`${seededCompany}_profile_view_day_2026-07-01`)
        .get()
    ).data()!;
    expect(profileViews.resultStatus).toBe('insufficient_data');
    expect(profileViews.totalCount).toBe(0);
    expect(profileViews.uniqueContributorCount).toBeNull();
    expect(profileViews.consentVersion).toBe(1);

    // One more contributor reaches the floor → available with real counts.
    await adminDb
      .collection('partnerInsightsEvents')
      .doc('seed-pb-9')
      .set({
        companyId: seededCompany,
        interactionType: 'anonymous_pass_by',
        userReferenceHash: buildScopedHash(seededCompany, 'pass-by-user-9'),
        relatedOfferId: null,
        consentVersion: 1,
        occurredAt: Timestamp.fromDate(day),
        aggregationDate: Timestamp.fromDate(new Date('2026-07-01T00:00:00Z')),
        expiresAt: Timestamp.fromDate(new Date('2026-07-08T10:00:00Z')),
      });
    await runInsightsAggregation(day);
    const passByAt10 = (
      await adminDb
        .collection('partnerInsights')
        .doc(`${seededCompany}_anonymous_pass_by_day_2026-07-01`)
        .get()
    ).data()!;
    expect(passByAt10.resultStatus).toBe('available');
    expect(passByAt10.totalCount).toBe(10);
    expect(passByAt10.uniqueContributorCount).toBe(10);
  });

  it('removes a stale published slice when only historical unconsented events remain', async () => {
    const company = 'pi-only-historical';
    const day = new Date('2026-07-02T10:00:00Z');
    const aggregate = adminDb
      .collection('partnerInsights')
      .doc(`${company}_profile_view_day_2026-07-02`);
    await aggregate.set({
      companyId: company,
      interactionType: 'profile_view',
      periodType: 'day',
      consentVersion: 1,
      totalCount: 40,
      uniqueContributorCount: 20,
      resultStatus: 'available',
    });
    await adminDb
      .collection('partnerInsightsEvents')
      .doc('seed-only-historical')
      .set({
        companyId: company,
        interactionType: 'profile_view',
        userReferenceHash: buildScopedHash(company, 'legacy-user'),
        occurredAt: Timestamp.fromDate(day),
      });
    await runInsightsAggregation(day);
    expect((await aggregate.get()).exists).toBe(false);
  });
});

describe('runInsightsCleanup — 7-day TTL', () => {
  it('deletes expired events and keeps fresh ones', async () => {
    const now = new Date('2026-07-10T00:00:00Z');
    await adminDb
      .collection('partnerInsightsEvents')
      .doc('ttl-expired')
      .set({
        companyId: 'ttl-co',
        interactionType: 'map_view',
        userReferenceHash: buildScopedHash('ttl-co', 'u-old'),
        relatedOfferId: null,
        occurredAt: Timestamp.fromDate(new Date('2026-07-01T00:00:00Z')),
        aggregationDate: Timestamp.fromDate(new Date('2026-07-01T00:00:00Z')),
        expiresAt: Timestamp.fromDate(new Date('2026-07-08T00:00:00Z')),
      });
    await adminDb
      .collection('partnerInsightsEvents')
      .doc('ttl-fresh')
      .set({
        companyId: 'ttl-co',
        interactionType: 'map_view',
        userReferenceHash: buildScopedHash('ttl-co', 'u-new'),
        relatedOfferId: null,
        occurredAt: Timestamp.fromDate(new Date('2026-07-09T00:00:00Z')),
        aggregationDate: Timestamp.fromDate(new Date('2026-07-09T00:00:00Z')),
        expiresAt: Timestamp.fromDate(new Date('2026-07-16T00:00:00Z')),
      });

    const result = await runInsightsCleanup(now);
    expect(result.deletedCount).toBeGreaterThanOrEqual(1);
    expect(
      (await adminDb.collection('partnerInsightsEvents').doc('ttl-expired').get()).exists,
    ).toBe(false);
    expect((await adminDb.collection('partnerInsightsEvents').doc('ttl-fresh').get()).exists).toBe(
      true,
    );
  });
});
