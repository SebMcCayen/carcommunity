/**
 * Incidents emulator integration tests (navigation feature).
 *
 * Exercises the crowd-sourced incidents domain end-to-end: member report →
 * `incidents/{id}` doc, listNearby radius + geo-cell filtering, member gating,
 * owner/admin removal, the direct security-rules read (active vs expired), the
 * TTL sweep (runIncidentsCleanup), and the Trafikverket importer runner with a
 * MOCKED response (never hits the live API).
 *
 * Requires the Functions + Firestore emulators — run via:
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
import {
  connectFirestoreEmulator,
  doc as clientDoc,
  getDoc as clientGetDoc,
  setDoc as clientSetDoc,
  getFirestore as getClientFirestore,
  type Firestore as ClientFirestore,
} from 'firebase/firestore';
import { getApps as getAdminApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getAuth as getAdminAuth } from 'firebase-admin/auth';
import { getFirestore as getAdminFirestore, Timestamp } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { runIncidentsCleanup } from '../incidents/scheduled';
import { runTrafikverketSync } from '../incidents/trafikverket';
import type { TrafikverketResponse } from '../incidents/trafikverket-core';
import { importedIncidentDocId } from '../incidents/trafikverket-core';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'incidents-emulator-tests');
const adminAuth = getAdminAuth(adminApp);
const adminDb = getAdminFirestore(adminApp);

let app: FirebaseApp;
let auth: Auth;
let functions: Functions;
let clientDb: ClientFirestore;

interface TestUser {
  uid: string;
  email: string;
  password: string;
}

async function pollUntil<T>(read: () => Promise<T | undefined>, timeoutMs = 30_000): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await read();
    if (value !== undefined) return value;
    if (Date.now() > deadline) throw new Error('Timed out waiting for condition');
    await new Promise((resolve) => setTimeout(resolve, 250));
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

async function makeMember(user: TestUser): Promise<void> {
  await adminDb.collection('users').doc(user.uid).set({ activeMember: true }, { merge: true });
}

async function signInAs(user: TestUser): Promise<void> {
  await signInWithEmailAndPassword(auth, user.email, user.password);
  await auth.currentUser?.getIdToken(true);
}

const call = (name: string, data: unknown) => httpsCallable(functions, name)(data);

// Kungsbacka centre + a point ~30 km away (well outside a 5 km query).
const KBA = { latitude: 57.4874, longitude: 12.0757 };
const FAR = { latitude: 57.75, longitude: 12.4 };

let member: TestUser;
let otherMember: TestUser;
let adminUser: TestUser;

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'incidents-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);
  clientDb = getClientFirestore(app);
  connectFirestoreEmulator(clientDb, EMULATOR_HOST, 8080);

  member = await createProvisionedUser('inc-member');
  await makeMember(member);
  otherMember = await createProvisionedUser('inc-other');
  await makeMember(otherMember);
  adminUser = await createProvisionedUser('inc-admin');
  await adminAuth.setCustomUserClaims(adminUser.uid, { admin: true });
  await adminDb.collection('users').doc(adminUser.uid).set({ role: 'admin' }, { merge: true });
});

afterAll(async () => {
  if (app) await deleteApp(app);
});

describe('incidents.report + listNearby', () => {
  it('reports an incident and returns it via listNearby within radius, excluding far ones', async () => {
    await signInAs(member);
    const created = (
      await call('incidents-report', {
        type: 'roadwork',
        latitude: KBA.latitude,
        longitude: KBA.longitude,
        note: 'Vägarbete på Storgatan',
      })
    ).data as { id: string; type: string; source: string; expiresAt: string };

    expect(created.type).toBe('roadwork');
    expect(created.source).toBe('user');

    // Persisted with a geoCell + reporterUid + future expiry.
    const stored = await adminDb.collection('incidents').doc(created.id).get();
    expect(stored.data()?.geoCell).toBeTypeOf('string');
    expect(stored.data()?.reporterUid).toBe(member.uid);
    expect(stored.data()?.status).toBe('active');

    const nearby = (
      await call('incidents-listNearby', {
        latitude: KBA.latitude,
        longitude: KBA.longitude,
        radiusMeters: 5000,
      })
    ).data as { incidents: Array<{ id: string }> };
    expect(nearby.incidents.some((i) => i.id === created.id)).toBe(true);

    const farAway = (
      await call('incidents-listNearby', {
        latitude: FAR.latitude,
        longitude: FAR.longitude,
        radiusMeters: 5000,
      })
    ).data as { incidents: Array<{ id: string }> };
    expect(farAway.incidents.some((i) => i.id === created.id)).toBe(false);
  });

  it('excludes incidents with a missing or malformed expiresAt from listNearby', async () => {
    // The Admin SDK bypasses Firestore rules, so listNearby must apply the
    // rules' intent (expiresAt > request.time, which denies a missing/
    // non-Timestamp value) in memory. Seed active docs whose expiresAt is
    // absent or the wrong type alongside a valid future one.
    const now = Date.now();
    const base = {
      type: 'hazard' as const,
      latitude: KBA.latitude,
      longitude: KBA.longitude,
      geoCell: '319_66',
      status: 'active' as const,
      source: 'user' as const,
      reporterUid: 'seed',
      note: null,
      createdAt: Timestamp.fromDate(new Date(now)),
    };

    const missingRef = adminDb.collection('incidents').doc();
    await missingRef.set({ ...base }); // no expiresAt at all
    const malformedRef = adminDb.collection('incidents').doc();
    await malformedRef.set({ ...base, expiresAt: 'not-a-timestamp' }); // wrong type
    const validRef = adminDb.collection('incidents').doc();
    await validRef.set({ ...base, expiresAt: Timestamp.fromDate(new Date(now + 60 * 60 * 1000)) });

    await signInAs(member);
    const nearby = (
      await call('incidents-listNearby', {
        latitude: KBA.latitude,
        longitude: KBA.longitude,
        radiusMeters: 5000,
      })
    ).data as { incidents: Array<{ id: string }> };

    const ids = new Set(nearby.incidents.map((i) => i.id));
    expect(ids.has(validRef.id)).toBe(true);
    expect(ids.has(missingRef.id)).toBe(false);
    expect(ids.has(malformedRef.id)).toBe(false);
  });

  it('ADMITS a non-member reporter while member gating is disabled', async () => {
    const nonMember = await createProvisionedUser('inc-nonmember');
    await signInAs(nonMember);
    const result = await call('incidents-report', {
      type: 'hazard',
      latitude: KBA.latitude,
      longitude: KBA.longitude,
    });
    expect(result.data).toBeTruthy();
  });

  it('STILL rejects a suspended reporter with permission-denied', async () => {
    const suspended = await createProvisionedUser('inc-suspended');
    await adminDb.collection('users').doc(suspended.uid).set({ suspended: true }, { merge: true });
    await signInAs(suspended);
    const code = await callableErrorCode(
      call('incidents-report', {
        type: 'hazard',
        latitude: KBA.latitude,
        longitude: KBA.longitude,
      }),
    );
    expect(code).toBe('functions/permission-denied');
  });

  it('rejects an invalid type / coordinate with invalid-argument', async () => {
    await signInAs(member);
    expect(
      await callableErrorCode(
        call('incidents-report', { type: 'nope', latitude: 1, longitude: 1 }),
      ),
    ).toBe('functions/invalid-argument');
    expect(
      await callableErrorCode(
        call('incidents-report', { type: 'accident', latitude: 200, longitude: 1 }),
      ),
    ).toBe('functions/invalid-argument');
  });

  it('lets a signed-in NON-member call listNearby (readable by all signed-in users)', async () => {
    // A member seeds an incident so there is something in range to return.
    await signInAs(member);
    const created = (
      await call('incidents-report', {
        type: 'police',
        latitude: KBA.latitude,
        longitude: KBA.longitude,
      })
    ).data as { id: string };

    // A signed-in non-member (no activeMember entitlement) must NOT be blocked:
    // listNearby is gated by requireActiveActor, not requireMemberActor.
    const nonMember = await createProvisionedUser('inc-reader');
    await signInAs(nonMember);
    const nearby = (
      await call('incidents-listNearby', {
        latitude: KBA.latitude,
        longitude: KBA.longitude,
        radiusMeters: 5000,
      })
    ).data as { incidents: Array<{ id: string }> };

    expect(Array.isArray(nearby.incidents)).toBe(true);
    expect(nearby.incidents.some((i) => i.id === created.id)).toBe(true);
  });
});

describe('incidents security rules (direct read)', () => {
  it('lets a signed-in user read an ACTIVE incident but denies an EXPIRED one', async () => {
    // Seed one active and one already-expired incident via the Admin SDK.
    const now = Date.now();
    const activeRef = adminDb.collection('incidents').doc();
    await activeRef.set({
      type: 'police',
      latitude: KBA.latitude,
      longitude: KBA.longitude,
      geoCell: '319_66',
      status: 'active',
      source: 'user',
      reporterUid: 'someone',
      note: null,
      createdAt: Timestamp.fromDate(new Date(now)),
      expiresAt: Timestamp.fromDate(new Date(now + 60 * 60 * 1000)),
    });
    const expiredRef = adminDb.collection('incidents').doc();
    await expiredRef.set({
      type: 'police',
      latitude: KBA.latitude,
      longitude: KBA.longitude,
      geoCell: '319_66',
      status: 'active',
      source: 'user',
      reporterUid: 'someone',
      note: null,
      createdAt: Timestamp.fromDate(new Date(now - 2 * 60 * 60 * 1000)),
      expiresAt: Timestamp.fromDate(new Date(now - 60 * 1000)),
    });

    await signInAs(member);
    const activeSnap = await clientGetDoc(clientDoc(clientDb, 'incidents', activeRef.id));
    expect(activeSnap.exists()).toBe(true);

    await expect(
      clientGetDoc(clientDoc(clientDb, 'incidents', expiredRef.id)),
    ).rejects.toMatchObject({ code: 'permission-denied' });
  });
});

describe('incidents.remove', () => {
  it('lets the reporter remove their own incident and denies others; admin can remove any', async () => {
    await signInAs(member);
    const mine = (
      await call('incidents-report', {
        type: 'hazard',
        latitude: KBA.latitude,
        longitude: KBA.longitude,
      })
    ).data as { id: string };

    // A different member cannot remove it.
    await signInAs(otherMember);
    expect(await callableErrorCode(call('incidents-remove', { incidentId: mine.id }))).toBe(
      'functions/permission-denied',
    );

    // Seed a confirmation ledger doc so removal is proven to take the
    // sub-collection with it (a plain doc delete would orphan it).
    const ledgerRef = adminDb
      .collection('incidents')
      .doc(mine.id)
      .collection('confirmations')
      .doc('ghost-uid');
    await ledgerRef.set({ uid: 'ghost-uid', createdAt: Timestamp.now() });

    // The owner can.
    await signInAs(member);
    const removed = (await call('incidents-remove', { incidentId: mine.id })).data as {
      removed: boolean;
    };
    expect(removed.removed).toBe(true);
    expect((await adminDb.collection('incidents').doc(mine.id).get()).exists).toBe(false);
    expect((await ledgerRef.get()).exists).toBe(false);

    // Removing again is an idempotent no-op success.
    const again = (await call('incidents-remove', { incidentId: mine.id })).data as {
      removed: boolean;
    };
    expect(again.removed).toBe(false);

    // Admin can remove another member's incident (moderation).
    await signInAs(otherMember);
    const theirs = (
      await call('incidents-report', {
        type: 'accident',
        latitude: KBA.latitude,
        longitude: KBA.longitude,
      })
    ).data as { id: string };
    await signInAs(adminUser);
    const adminRemoved = (await call('incidents-remove', { incidentId: theirs.id })).data as {
      removed: boolean;
    };
    expect(adminRemoved.removed).toBe(true);
  });

  it('rejects removal of an imported (trafikverket) incident, even for an admin', async () => {
    // Seed an importer-owned incident (as the sync would) via the Admin SDK.
    const now = Date.now();
    const tvRef = adminDb.collection('incidents').doc('tv_reject-remove-1');
    await tvRef.set({
      type: 'roadwork',
      latitude: KBA.latitude,
      longitude: KBA.longitude,
      geoCell: '319_66',
      status: 'active',
      source: 'trafikverket',
      reporterUid: null,
      note: null,
      createdAt: Timestamp.fromDate(new Date(now)),
      expiresAt: Timestamp.fromDate(new Date(now + 6 * 60 * 60 * 1000)),
    });

    // An admin cannot hand-remove it — it is sync-managed and would reappear.
    await signInAs(adminUser);
    expect(await callableErrorCode(call('incidents-remove', { incidentId: tvRef.id }))).toBe(
      'functions/failed-precondition',
    );
    // Still present.
    expect((await tvRef.get()).exists).toBe(true);
  });
});

describe('incidents.confirm', () => {
  it('lets another member confirm, counts once per user, and extends the expiry', async () => {
    // A police report (1h TTL) so the extension is unambiguous: confirming
    // later must push expiry to a full hour past the confirmation instant.
    await signInAs(member);
    const reported = (
      await call('incidents-report', {
        type: 'police',
        latitude: KBA.latitude,
        longitude: KBA.longitude,
      })
    ).data as { id: string; expiresAt: string };

    await signInAs(otherMember);
    const first = (await call('incidents-confirm', { incidentId: reported.id })).data as {
      incidentId: string;
      confirmationCount: number;
      expiresAt: string;
      alreadyConfirmed: boolean;
    };
    expect(first.incidentId).toBe(reported.id);
    expect(first.confirmationCount).toBe(1);
    expect(first.alreadyConfirmed).toBe(false);
    // Expiry moved out (the report was made moments ago, so a fresh TTL from
    // now is strictly later than the original).
    expect(Date.parse(first.expiresAt)).toBeGreaterThan(Date.parse(reported.expiresAt));

    // Persisted: counter on the doc, ledger doc keyed by the confirming uid.
    const stored = await adminDb.collection('incidents').doc(reported.id).get();
    expect(stored.data()?.confirmationCount).toBe(1);
    expect((stored.data()?.expiresAt as InstanceType<typeof Timestamp>).toMillis()).toBe(
      Date.parse(first.expiresAt),
    );
    const ledger = await adminDb
      .collection('incidents')
      .doc(reported.id)
      .collection('confirmations')
      .doc(otherMember.uid)
      .get();
    expect(ledger.exists).toBe(true);
    expect(ledger.data()?.uid).toBe(otherMember.uid);

    // Same member again → idempotent success, NOT an error, and no double count
    // or further extension.
    const repeat = (await call('incidents-confirm', { incidentId: reported.id })).data as {
      confirmationCount: number;
      expiresAt: string;
      alreadyConfirmed: boolean;
    };
    expect(repeat.alreadyConfirmed).toBe(true);
    expect(repeat.confirmationCount).toBe(1);
    expect(repeat.expiresAt).toBe(first.expiresAt);
    const afterRepeat = await adminDb.collection('incidents').doc(reported.id).get();
    expect(afterRepeat.data()?.confirmationCount).toBe(1);

    // listNearby surfaces the count to the map.
    const nearby = (
      await call('incidents-listNearby', {
        latitude: KBA.latitude,
        longitude: KBA.longitude,
        radiusMeters: 5000,
      })
    ).data as { incidents: Array<{ id: string; confirmationCount: number }> };
    expect(nearby.incidents.find((i) => i.id === reported.id)?.confirmationCount).toBe(1);
  });

  it('concurrent double-taps by the same member count exactly once', async () => {
    await signInAs(member);
    const reported = (
      await call('incidents-report', {
        type: 'hazard',
        latitude: KBA.latitude,
        longitude: KBA.longitude,
      })
    ).data as { id: string };

    // Fire several confirmations at once: the transaction's tx.create claim on
    // confirmations/{uid} must serialize them into a single counted
    // confirmation, never 2..5.
    await signInAs(otherMember);
    const results = await Promise.all(
      Array.from({ length: 5 }, () => call('incidents-confirm', { incidentId: reported.id })),
    );
    for (const r of results) {
      expect((r.data as { confirmationCount: number }).confirmationCount).toBe(1);
    }
    const stored = await adminDb.collection('incidents').doc(reported.id).get();
    expect(stored.data()?.confirmationCount).toBe(1);
  });

  it('refuses to let the reporter confirm their own report', async () => {
    await signInAs(member);
    const mine = (
      await call('incidents-report', {
        type: 'accident',
        latitude: KBA.latitude,
        longitude: KBA.longitude,
      })
    ).data as { id: string };

    expect(await callableErrorCode(call('incidents-confirm', { incidentId: mine.id }))).toBe(
      'functions/permission-denied',
    );
    const stored = await adminDb.collection('incidents').doc(mine.id).get();
    expect(stored.data()?.confirmationCount).toBeUndefined();
  });

  it('rejects a missing incident and an already-expired one', async () => {
    await signInAs(otherMember);
    expect(
      await callableErrorCode(call('incidents-confirm', { incidentId: 'no-such-incident-id' })),
    ).toBe('functions/not-found');

    // An expired doc is invisible to readers already; confirming must not
    // resurrect it.
    const now = Date.now();
    const deadRef = adminDb.collection('incidents').doc();
    await deadRef.set({
      type: 'hazard',
      latitude: KBA.latitude,
      longitude: KBA.longitude,
      geoCell: '319_66',
      status: 'active',
      source: 'user',
      reporterUid: member.uid,
      note: null,
      createdAt: Timestamp.fromDate(new Date(now - 5 * 60 * 60 * 1000)),
      expiresAt: Timestamp.fromDate(new Date(now - 60 * 1000)),
    });
    expect(await callableErrorCode(call('incidents-confirm', { incidentId: deadRef.id }))).toBe(
      'functions/failed-precondition',
    );
  });

  it('rejects confirming an imported (trafikverket) incident', async () => {
    // The importer full-overwrites each tv_ doc every 30 minutes, so a
    // confirmation written here would be silently wiped.
    const now = Date.now();
    const tvRef = adminDb.collection('incidents').doc('tv_reject-confirm-1');
    await tvRef.set({
      type: 'roadwork',
      latitude: KBA.latitude,
      longitude: KBA.longitude,
      geoCell: '319_66',
      status: 'active',
      source: 'trafikverket',
      reporterUid: null,
      note: null,
      createdAt: Timestamp.fromDate(new Date(now)),
      expiresAt: Timestamp.fromDate(new Date(now + 6 * 60 * 60 * 1000)),
    });

    await signInAs(otherMember);
    expect(await callableErrorCode(call('incidents-confirm', { incidentId: tvRef.id }))).toBe(
      'functions/failed-precondition',
    );
    expect((await tvRef.get()).data()?.confirmationCount).toBeUndefined();
  });

  it('ADMITS a non-member confirmer while member gating is disabled', async () => {
    // confirm uses requireMemberActor, the SAME gate as incidents.report — but
    // shared/memberGating.ts currently has MEMBER_GATING_ENABLED = false, so
    // the entitlement half of that gate is bypassed repo-wide and a signed-in,
    // non-suspended account passes. This mirrors the incidents.report pair
    // above ("ADMITS a non-member reporter" / "STILL rejects a suspended
    // reporter"); when the switch is flipped back to true BOTH pairs flip
    // together and this expectation becomes permission-denied.
    await signInAs(member);
    const reported = (
      await call('incidents-report', {
        type: 'hazard',
        latitude: KBA.latitude,
        longitude: KBA.longitude,
      })
    ).data as { id: string };

    const nonMember = await createProvisionedUser('inc-confirm-nonmember');
    await signInAs(nonMember);
    const confirmed = (await call('incidents-confirm', { incidentId: reported.id })).data as {
      confirmationCount: number;
      alreadyConfirmed: boolean;
    };
    expect(confirmed.alreadyConfirmed).toBe(false);
    expect(confirmed.confirmationCount).toBe(1);
  });

  it('STILL rejects a suspended confirmer with permission-denied', async () => {
    // Suspension is NOT bypassed by the gating switch (memberGateAllows
    // re-checks isRestricted first), so this door stays shut either way.
    await signInAs(member);
    const reported = (
      await call('incidents-report', {
        type: 'hazard',
        latitude: KBA.latitude,
        longitude: KBA.longitude,
      })
    ).data as { id: string };

    const suspended = await createProvisionedUser('inc-confirm-suspended');
    await adminDb.collection('users').doc(suspended.uid).set({ suspended: true }, { merge: true });
    await signInAs(suspended);
    expect(await callableErrorCode(call('incidents-confirm', { incidentId: reported.id }))).toBe(
      'functions/permission-denied',
    );
    expect(
      (await adminDb.collection('incidents').doc(reported.id).get()).data()?.confirmationCount,
    ).toBeUndefined();
  });

  /**
   * A malformed incident is unreachable through any writer today (report.ts
   * validates the type and stamps a server createdAt; the importer is rejected
   * by the source guard; rules deny all client writes), so these seed one with
   * the Admin SDK. The point is the REFUSAL: substituting `now` for a missing
   * createdAt would re-anchor the lifetime cap on every confirmation, and
   * substituting 'hazard' for a corrupt type would compute the expiry under the
   * wrong TTL. Both must fail loudly instead of writing a wrong expiry.
   */
  const seedMalformed = async (id: string, overrides: Record<string, unknown>) => {
    const ref = adminDb.collection('incidents').doc(id);
    await ref.set({
      type: 'hazard',
      latitude: KBA.latitude,
      longitude: KBA.longitude,
      geoCell: '319_66',
      status: 'active',
      source: 'user',
      reporterUid: member.uid,
      note: null,
      createdAt: Timestamp.fromDate(new Date(Date.now() - 60_000)),
      expiresAt: Timestamp.fromDate(new Date(Date.now() + 60 * 60 * 1000)),
      ...overrides,
    });
    return ref;
  };

  it('REFUSES to confirm an incident whose createdAt is missing', async () => {
    const ref = await seedMalformed('malformed-created-at', { createdAt: null });
    const before = (await ref.get()).data()!.expiresAt as InstanceType<typeof Timestamp>;

    await signInAs(otherMember);
    expect(await callableErrorCode(call('incidents-confirm', { incidentId: ref.id }))).toBe(
      'functions/internal',
    );

    // The refusal is real: nothing counted, nothing extended, no ledger claim.
    const after = await ref.get();
    expect(after.data()?.confirmationCount).toBeUndefined();
    expect((after.data()?.expiresAt as InstanceType<typeof Timestamp>).toMillis()).toBe(
      before.toMillis(),
    );
    expect((await ref.collection('confirmations').doc(otherMember.uid).get()).exists).toBe(false);
  });

  it('REFUSES to confirm on a corrupt confirmationCount, but listNearby still renders the map', async () => {
    // NaN is storable in Firestore (doubles) and survives FieldValue.increment
    // (NaN + 1 is NaN), so one corrupt value would be permanent. The write path
    // refuses; the read path must NOT, or a single bad document would take the
    // whole shared map layer down with it.
    const ref = await seedMalformed('malformed-count', { confirmationCount: Number.NaN });

    await signInAs(otherMember);
    expect(await callableErrorCode(call('incidents-confirm', { incidentId: ref.id }))).toBe(
      'functions/internal',
    );
    expect((await ref.collection('confirmations').doc(otherMember.uid).get()).exists).toBe(false);

    // Same document through listNearby: present, degraded to 0, and JSON-safe
    // (an unsanitised NaN would arrive as null and break the typed contract).
    const nearby = (
      await call('incidents-listNearby', {
        latitude: KBA.latitude,
        longitude: KBA.longitude,
        radiusMeters: 5000,
      })
    ).data as { incidents: Array<{ id: string; confirmationCount: number }> };
    const seen = nearby.incidents.find((i) => i.id === ref.id);
    expect(seen).toBeDefined();
    expect(seen?.confirmationCount).toBe(0);
  });

  it('REFUSES to confirm a user incident whose reporterUid is missing', async () => {
    // Sharper than the two below: a null reporterUid does not make the
    // self-confirmation check REJECT, it makes it never fire — so without this
    // guard the reporter could confirm and extend their own report. Asserted
    // from the reporter's own session for exactly that reason.
    const ref = await seedMalformed('malformed-reporter-uid', { reporterUid: null });
    const before = (await ref.get()).data()!.expiresAt as InstanceType<typeof Timestamp>;

    await signInAs(member);
    expect(await callableErrorCode(call('incidents-confirm', { incidentId: ref.id }))).toBe(
      'functions/internal',
    );

    const after = await ref.get();
    expect(after.data()?.confirmationCount).toBeUndefined();
    expect((after.data()?.expiresAt as InstanceType<typeof Timestamp>).toMillis()).toBe(
      before.toMillis(),
    );
    expect((await ref.collection('confirmations').doc(member.uid).get()).exists).toBe(false);
  });

  it('REFUSES to confirm an incident whose type is not a known incident type', async () => {
    const ref = await seedMalformed('malformed-type', { type: 'meteor-strike' });
    const before = (await ref.get()).data()!.expiresAt as InstanceType<typeof Timestamp>;

    await signInAs(otherMember);
    expect(await callableErrorCode(call('incidents-confirm', { incidentId: ref.id }))).toBe(
      'functions/internal',
    );

    const after = await ref.get();
    expect(after.data()?.confirmationCount).toBeUndefined();
    expect((after.data()?.expiresAt as InstanceType<typeof Timestamp>).toMillis()).toBe(
      before.toMillis(),
    );
    expect((await ref.collection('confirmations').doc(otherMember.uid).get()).exists).toBe(false);
  });

  it('denies direct client reads and writes of the confirmations ledger', async () => {
    // The ledger must be callable-only: a member must not be able to forge a
    // confirmation or enumerate who confirmed what.
    await signInAs(member);
    const reported = (
      await call('incidents-report', {
        type: 'hazard',
        latitude: KBA.latitude,
        longitude: KBA.longitude,
      })
    ).data as { id: string };

    await signInAs(otherMember);
    await call('incidents-confirm', { incidentId: reported.id });

    const ledgerDoc = clientDoc(
      clientDb,
      'incidents',
      reported.id,
      'confirmations',
      otherMember.uid,
    );
    await expect(clientGetDoc(ledgerDoc)).rejects.toThrow();
    await expect(
      clientSetDoc(clientDoc(clientDb, 'incidents', reported.id, 'confirmations', member.uid), {
        uid: member.uid,
      }),
    ).rejects.toThrow();
  });
});

describe('incidents cleanup sweep', () => {
  it('deletes expired incidents and keeps active ones', async () => {
    const now = Date.now();
    const expiredRef = adminDb.collection('incidents').doc();
    await expiredRef.set({
      type: 'roadwork',
      latitude: 0,
      longitude: 0,
      geoCell: '0_0',
      status: 'active',
      source: 'user',
      reporterUid: 'x',
      note: null,
      createdAt: Timestamp.fromDate(new Date(now - 10 * 60 * 60 * 1000)),
      expiresAt: Timestamp.fromDate(new Date(now - 60 * 1000)),
    });
    const activeRef = adminDb.collection('incidents').doc();
    await activeRef.set({
      type: 'roadwork',
      latitude: 0,
      longitude: 0,
      geoCell: '0_0',
      status: 'active',
      source: 'user',
      reporterUid: 'x',
      note: null,
      createdAt: Timestamp.fromDate(new Date(now)),
      expiresAt: Timestamp.fromDate(new Date(now + 60 * 60 * 1000)),
    });

    // A confirmation ledger doc under the expired incident: deleting a
    // document does NOT delete its sub-collections, so the sweep must
    // recursiveDelete or this row survives forever as an unreachable orphan.
    const orphanRef = expiredRef.collection('confirmations').doc('some-uid');
    await orphanRef.set({ uid: 'some-uid', createdAt: Timestamp.fromDate(new Date(now)) });

    const result = await runIncidentsCleanup(new Date());
    expect(result.deletedCount).toBeGreaterThanOrEqual(1);
    expect((await expiredRef.get()).exists).toBe(false);
    expect((await activeRef.get()).exists).toBe(true);
    expect((await orphanRef.get()).exists).toBe(false);
  });

  /**
   * These two seed their expired incidents in a FAR-PAST window and sweep with
   * a `now` inside it, so they see only their own docs — the emulator Firestore
   * is shared with ~30 other test files whose incidents all expire around real
   * "now".
   */
  const seedExpired = async (prefix: string, count: number, baseMs: number) => {
    const refs: FirebaseFirestore.DocumentReference[] = [];
    for (let i = 0; i < count; i += 1) {
      const ref = adminDb.collection('incidents').doc(`${prefix}-${i}`);
      await ref.set({
        type: 'roadwork',
        latitude: 0,
        longitude: 0,
        geoCell: '0_0',
        status: 'active',
        source: 'user',
        reporterUid: 'x',
        note: null,
        createdAt: Timestamp.fromDate(new Date(baseMs - 60_000)),
        // Distinct, increasing expiries so "oldest first" is observable.
        expiresAt: Timestamp.fromDate(new Date(baseMs + i * 1000)),
      });
      refs.push(ref);
    }
    return refs;
  };

  it('bounds recursiveDelete concurrency instead of firing the whole page at once', async () => {
    const base = Date.parse('2019-01-01T00:00:00.000Z');
    const refs = await seedExpired('sweep-conc', 25, base);

    const result = await runIncidentsCleanup(new Date(base + 60 * 60 * 1000), {
      maxDeletions: 100,
      concurrency: 4,
    });

    expect(result.deletedCount).toBe(25);
    // THE ASSERTION WITH TEETH: peak in-flight deletes never exceeds the bound.
    // Against the pre-fix `Promise.all(expired.docs.map(...))` this reads 25.
    expect(result.peakConcurrency).toBeLessThanOrEqual(4);
    // ...and the sweep is still genuinely parallel, not accidentally serial —
    // a bound of 4 that actually runs one-at-a-time would also pass the line
    // above, so it must be pinned from below too.
    expect(result.peakConcurrency).toBeGreaterThan(1);
    for (const ref of refs) {
      expect((await ref.get()).exists).toBe(false);
    }
  });

  it('caps deletions per run and drains the rest oldest-first on the next run', async () => {
    const base = Date.parse('2018-06-01T00:00:00.000Z');
    const refs = await seedExpired('sweep-cap', 6, base);
    const sweepNow = new Date(base + 60 * 60 * 1000);

    const first = await runIncidentsCleanup(sweepNow, { maxDeletions: 2, concurrency: 4 });
    expect(first.deletedCount).toBe(2);
    expect(first.capped).toBe(true);
    // Oldest expiries go first (the query orders by expiresAt ASC), which is
    // what makes the cap unable to starve an old incident.
    // seedExpired('sweep-cap', 6, …) above returns exactly 6 refs, so [0]-[5] exist.
    expect((await refs[0]!.get()).exists).toBe(false);
    expect((await refs[1]!.get()).exists).toBe(false);
    expect((await refs[2]!.get()).exists).toBe(true);
    expect((await refs[5]!.get()).exists).toBe(true);

    const second = await runIncidentsCleanup(sweepNow, { maxDeletions: 100, concurrency: 4 });
    expect(second.deletedCount).toBe(4);
    expect(second.capped).toBe(false);
    for (const ref of refs) {
      expect((await ref.get()).exists).toBe(false);
    }
  });
});

describe('incidents Trafikverket importer', () => {
  it('skips when no API key is configured', async () => {
    const result = await runTrafikverketSync(new Date(), undefined);
    expect(result).toEqual({ skipped: true, upserted: 0 });
  });

  it('imports mocked situations to deterministic tv_ docs (idempotent)', async () => {
    const mock: TrafikverketResponse = {
      RESPONSE: {
        RESULT: [
          {
            Situation: [
              {
                Id: 'SIT-1',
                Deviation: [
                  {
                    Id: 'DEV-emu-1',
                    MessageType: 'Vägarbete',
                    Message: 'Vägarbete E6',
                    Geometry: { WGS84: 'POINT (12.0757 57.4874)' },
                  },
                ],
              },
            ],
          },
        ],
      },
    };
    const fetcher = async () => mock;

    const first = await runTrafikverketSync(new Date(), 'fake-key', fetcher);
    expect(first).toEqual({ skipped: false, upserted: 1 });

    const docId = importedIncidentDocId('DEV-emu-1');
    const stored = await adminDb.collection('incidents').doc(docId).get();
    expect(stored.exists).toBe(true);
    expect(stored.data()?.source).toBe('trafikverket');
    expect(stored.data()?.reporterUid).toBeNull();
    expect(stored.data()?.type).toBe('roadwork');

    // Re-run overwrites the same doc (no duplicate).
    const second = await runTrafikverketSync(new Date(), 'fake-key', fetcher);
    expect(second.upserted).toBe(1);
  });
});
