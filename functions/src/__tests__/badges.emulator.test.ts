/**
 * Badges emulator integration tests (Phase 9f).
 *
 * Exercises the badge hooks and callable end-to-end:
 * - garage-addVehicle → garage_created award (once)
 * - events-complete → attendance credit + first_event award
 * - badges-awardHelpfulMember (admin gate, reason, audit, idempotency)
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
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'badges-emulator-tests');
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

const badgeDoc = (uid: string, key: string) =>
  adminDb.collection('users').doc(uid).collection('badges').doc(key);

let adminUser: TestUser;
let member: TestUser;

const futureStart = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString();

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'badges-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  adminUser = await createProvisionedUser('badges-admin');
  await adminAuth.setCustomUserClaims(adminUser.uid, { admin: true });
  await adminDb.collection('users').doc(adminUser.uid).set({ role: 'admin' }, { merge: true });
  member = await createProvisionedUser('badges-member');
  await adminDb.collection('users').doc(member.uid).set({ activeMember: true }, { merge: true });
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('garage_created badge', () => {
  it('is awarded on first vehicle and never duplicated', async () => {
    await signInAs(member);
    await call('garage-addVehicle', {
      make: 'Saab',
      model: '900 Turbo',
      modelYear: 1987,
      powertrain: 'petrol',
    });

    const first = await pollUntil(async () => {
      const snap = await badgeDoc(member.uid, 'garage_created').get();
      return snap.exists ? snap.data() : undefined;
    });
    expect(first!.source).toBe('automatic');
    expect(first!.name).toBe('Garageprofil skapad');
    const firstAwardedAt = first!.awardedAt;

    await call('garage-addVehicle', {
      make: 'Saab',
      model: '9000 Aero',
      modelYear: 1994,
      powertrain: 'petrol',
    });
    const after = (await badgeDoc(member.uid, 'garage_created').get()).data()!;
    expect(after.awardedAt).toEqual(firstAwardedAt);
  });
});

describe('event attendance badges', () => {
  it('credits going attendees on completion and awards first_event', async () => {
    await signInAs(adminUser);
    const eventId = (
      (
        await call('events-create', {
          title: 'Badge test event',
          startsAt: futureStart,
          approximateArea: 'Test area',
        })
      ).data as { eventId: string }
    ).eventId;
    await call('events-publish', { eventId });

    // RSVPs: member going, admin maybe (no credit for maybe).
    await adminDb
      .collection('events')
      .doc(eventId)
      .collection('rsvps')
      .doc(member.uid)
      .set({ status: 'going', updatedAt: new Date() });
    await adminDb
      .collection('events')
      .doc(eventId)
      .collection('rsvps')
      .doc(adminUser.uid)
      .set({ status: 'maybe', updatedAt: new Date() });

    await call('events-complete', { eventId });

    const progress = (await adminDb.collection('badgeProgress').doc(member.uid).get()).data()!;
    expect(progress.completedEventsAttended).toBe(1);

    const badge = (await badgeDoc(member.uid, 'first_event').get()).data()!;
    expect(badge.badgeKey).toBe('first_event');
    expect(badge.source).toBe('automatic');

    // maybe-RSVP gets no credit and no badge.
    expect((await adminDb.collection('badgeProgress').doc(adminUser.uid).get()).exists).toBe(
      false,
    );
    expect((await badgeDoc(adminUser.uid, 'first_event').get()).exists).toBe(false);

    // five_events requires five completions — not awarded after one.
    expect((await badgeDoc(member.uid, 'five_events').get()).exists).toBe(false);
  });
});

describe('badges-awardHelpfulMember', () => {
  it('rejects non-admin callers and missing reasons', async () => {
    await signInAs(member);
    expect(
      await callableErrorCode(
        call('badges-awardHelpfulMember', { targetUid: member.uid, reason: 'Self award' }),
      ),
    ).toBe('functions/permission-denied');

    await signInAs(adminUser);
    expect(
      await callableErrorCode(call('badges-awardHelpfulMember', { targetUid: member.uid })),
    ).toBe('functions/invalid-argument');
    expect(
      await callableErrorCode(
        call('badges-awardHelpfulMember', { targetUid: 'missing-user', reason: 'x' }),
      ),
    ).toBe('functions/not-found');
  });

  it('awards once with an audit record; repeats are idempotent without new audit entries', async () => {
    await signInAs(adminUser);
    const first = (
      await call('badges-awardHelpfulMember', {
        targetUid: member.uid,
        reason: 'Organiserade träffen',
      })
    ).data as { alreadyAwarded: boolean };
    expect(first.alreadyAwarded).toBe(false);

    const badge = (await badgeDoc(member.uid, 'helpful_member').get()).data()!;
    expect(badge.source).toBe('admin_manual');
    expect(badge.awardedByUserId).toBe(adminUser.uid);

    const second = (
      await call('badges-awardHelpfulMember', {
        targetUid: member.uid,
        reason: 'Igen',
      })
    ).data as { alreadyAwarded: boolean };
    expect(second.alreadyAwarded).toBe(true);

    const audits = await adminDb
      .collection('adminAuditEvents')
      .where('action', '==', 'badge.awardHelpfulMember')
      .where('targetId', '==', member.uid)
      .get();
    expect(audits.size).toBe(1);
    // size asserted === 1 above, so docs[0] is present.
    expect(audits.docs[0]!.data().reason).toBe('Organiserade träffen');
  });
});

describe('badges-adminSummary', () => {
  it('rejects non-admin callers', async () => {
    await signInAs(member);
    expect(await callableErrorCode(call('badges-adminSummary', {}))).toBe(
      'functions/permission-denied',
    );
  });

  it('returns aggregate per-key counts for admins (no user data)', async () => {
    await signInAs(adminUser);
    // Guarantee at least one helpful_member award exists (idempotent).
    await call('badges-awardHelpfulMember', { targetUid: member.uid, reason: 'Summary fixture' });

    const result = await call('badges-adminSummary', {});
    const summary = (
      result.data as {
        summary: Array<{ key: string; name: string; totalCount: number; recentCount: number }>;
      }
    ).summary;

    expect(summary.map((s) => s.key)).toEqual([
      'first_event',
      'five_events',
      'helpful_member',
      'early_member',
      'garage_created',
    ]);
    for (const item of summary) {
      expect(typeof item.name).toBe('string');
      expect(item.name.length).toBeGreaterThan(0);
      expect(item.totalCount).toBeGreaterThanOrEqual(item.recentCount);
      expect(item.recentCount).toBeGreaterThanOrEqual(0);
    }
    const helpful = summary.find((s) => s.key === 'helpful_member')!;
    expect(helpful.totalCount).toBeGreaterThanOrEqual(1);
    expect(helpful.recentCount).toBeGreaterThanOrEqual(1);
    // Aggregate only — never a per-user leaderboard.
    expect(result.data).not.toHaveProperty('users');
  });
});
