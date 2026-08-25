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
import {
  IMPORT_PERSISTENT_EXPIRES_AT_MS,
  TRAFIKVERKET_FINGERPRINT_VERSION,
  TRAFIKVERKET_QUERY_LIMIT,
  TRAFIKVERKET_SYNC_METADATA_COLLECTION,
  TRAFIKVERKET_SYNC_METADATA_DOC,
  importedIncidentDocId,
} from '../incidents/trafikverket-core';
import {
  INCIDENT_CLEAR_RATE_LIMIT_MAX,
  INCIDENT_LIST_RATE_LIMIT_COLLECTION,
  INCIDENT_LIST_RATE_LIMIT_MAX,
  INCIDENT_LIST_RATE_LIMIT_WINDOW_MS,
  incidentListRateLimitDocId,
} from '../incidents/incidents-core';

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

describe('incidents.listNearby rate limit', () => {
  const rateLimits = () => adminDb.collection(INCIDENT_LIST_RATE_LIMIT_COLLECTION);

  it('admits a call while the caller is under the window cap', async () => {
    const user = await createProvisionedUser('inc-rl-under');
    await signInAs(user);
    const result = await call('incidents-listNearby', {
      latitude: KBA.latitude,
      longitude: KBA.longitude,
      radiusMeters: 5000,
    });
    expect(Array.isArray((result.data as { incidents: unknown[] }).incidents)).toBe(true);
    // The admitted call bumped the caller's counter for the window it ran in.
    // Check the current AND previous window ids so a minute-boundary crossing
    // between the call and this read cannot flake the assertion.
    const nowMs = Date.now();
    const counts = await Promise.all(
      [nowMs, nowMs - INCIDENT_LIST_RATE_LIMIT_WINDOW_MS].map(async (ms) => {
        const snap = await rateLimits().doc(incidentListRateLimitDocId(user.uid, ms)).get();
        return (snap.data()?.count as number | undefined) ?? 0;
      }),
    );
    expect(counts.some((c) => c >= 1)).toBe(true);
  });

  it('throws resource-exhausted once the window counter is at the cap', async () => {
    const user = await createProvisionedUser('inc-rl-over');
    await signInAs(user);
    // Seed the counter for the current AND next window at the cap, so the
    // callable rejects regardless of which side of a minute boundary it lands
    // on (removes any Date.now() boundary flake).
    const nowMs = Date.now();
    for (const ms of [nowMs, nowMs + INCIDENT_LIST_RATE_LIMIT_WINDOW_MS]) {
      await rateLimits()
        .doc(incidentListRateLimitDocId(user.uid, ms))
        .set({ uid: user.uid, count: INCIDENT_LIST_RATE_LIMIT_MAX });
    }
    const code = await callableErrorCode(
      call('incidents-listNearby', {
        latitude: KBA.latitude,
        longitude: KBA.longitude,
        radiusMeters: 5000,
      }),
    );
    expect(code).toBe('functions/resource-exhausted');
  });

  it('does not consume the window on an invalid-argument call (validate before rate limit)', async () => {
    const user = await createProvisionedUser('inc-rl-badinput');
    await signInAs(user);
    // A malformed call must be rejected with invalid-argument BEFORE the rate
    // limiter runs, so it neither reads nor writes the counter doc.
    const code = await callableErrorCode(
      call('incidents-listNearby', {
        latitude: 200, // out of range → parseListNearbyInput rejects
        longitude: KBA.longitude,
        radiusMeters: 5000,
      }),
    );
    expect(code).toBe('functions/invalid-argument');
    // No counter was created/incremented for either the current or previous
    // window (boundary-safe), i.e. the bad input did not burn the user's window.
    const nowMs = Date.now();
    const snaps = await Promise.all(
      [nowMs, nowMs - INCIDENT_LIST_RATE_LIMIT_WINDOW_MS].map((ms) =>
        rateLimits().doc(incidentListRateLimitDocId(user.uid, ms)).get(),
      ),
    );
    expect(snaps.every((s) => !s.exists)).toBe(true);
  });

  it('self-heals a corrupt (non-numeric) count without throwing — fail-open resets to 1', async () => {
    const user = await createProvisionedUser('inc-rl-corrupt');
    await signInAs(user);
    // Seed a NON-numeric `count` for the caller's current window. The read side
    // treats a non-numeric count as 0 (admits), and FieldValue.increment(1)
    // OVERWRITES a non-numeric field with the operand rather than throwing — so
    // a corrupt counter doc must NOT become a persistent internal error: the
    // call succeeds and the counter self-heals to a numeric 1.
    const nowMs = Date.now();
    await rateLimits()
      .doc(incidentListRateLimitDocId(user.uid, nowMs))
      .set({ uid: user.uid, count: 'corrupt' });
    const result = await call('incidents-listNearby', {
      latitude: KBA.latitude,
      longitude: KBA.longitude,
      radiusMeters: 5000,
    });
    // Did not throw resource-exhausted / internal — returned a normal payload.
    expect(Array.isArray((result.data as { incidents: unknown[] }).incidents)).toBe(true);
    // The window listNearby wrote is now a numeric 1 (increment overwrote the
    // corrupt string). Check current AND next window ids so a minute-boundary
    // crossing between the seed and the call cannot flake the assertion.
    const counts = await Promise.all(
      [nowMs, nowMs + INCIDENT_LIST_RATE_LIMIT_WINDOW_MS].map(async (ms) => {
        const snap = await rateLimits().doc(incidentListRateLimitDocId(user.uid, ms)).get();
        return snap.data()?.count;
      }),
    );
    expect(counts.some((c) => c === 1)).toBe(true);
  });

  it('resets across windows: a prior-window cap does not throttle the current window', async () => {
    const user = await createProvisionedUser('inc-rl-reset');
    await signInAs(user);
    // Cap the PREVIOUS window only; the current window's counter is untouched,
    // so the call must be admitted (the counter is window-scoped, not global).
    const prevMs = Date.now() - INCIDENT_LIST_RATE_LIMIT_WINDOW_MS;
    await rateLimits()
      .doc(incidentListRateLimitDocId(user.uid, prevMs))
      .set({ uid: user.uid, count: INCIDENT_LIST_RATE_LIMIT_MAX });
    const result = await call('incidents-listNearby', {
      latitude: KBA.latitude,
      longitude: KBA.longitude,
      radiusMeters: 5000,
    });
    expect(Array.isArray((result.data as { incidents: unknown[] }).incidents)).toBe(true);
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
  beforeAll(async () => {
    const imported = await adminDb
      .collection('incidents')
      .where('source', '==', 'trafikverket')
      .get();
    await Promise.all(imported.docs.map((doc) => adminDb.recursiveDelete(doc.ref)));
    await adminDb.collection('incidentSyncMetadata').doc('trafikverket').delete();
  });

  it('skips when no API key is configured', async () => {
    const result = await runTrafikverketSync(new Date(), undefined);
    expect(result).toMatchObject({
      skipped: true,
      situationsReceived: 0,
      deviationsParsed: 0,
      created: 0,
      changed: 0,
      unchangedSkipped: 0,
      missingDeleted: 0,
      upserted: 0,
    });
  });

  it('does not advance freshness when the upstream fetch fails or returns an invalid feed', async () => {
    const metadataRef = adminDb.collection('incidentSyncMetadata').doc('trafikverket');
    await metadataRef.set({
      marker: 'before-failure',
      freshUntil: Timestamp.fromMillis(1),
    });
    const before = await metadataRef.get();

    await expect(
      runTrafikverketSync(new Date(), 'fake-key', async () => {
        throw new Error('upstream unavailable');
      }),
    ).rejects.toThrow('upstream unavailable');
    expect((await metadataRef.get()).updateTime!.toMillis()).toBe(before.updateTime!.toMillis());

    await expect(
      runTrafikverketSync(new Date(), 'fake-key', async () => ({ RESPONSE: { RESULT: [] } })),
    ).rejects.toThrow('not safe to import');
    const after = await metadataRef.get();
    expect(after.updateTime!.toMillis()).toBe(before.updateTime!.toMillis());
    expect(after.data()?.marker).toBe('before-failure');
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
    expect(first).toMatchObject({
      skipped: false,
      situationsReceived: 1,
      deviationsParsed: 1,
      created: 1,
      changed: 0,
      unchangedSkipped: 0,
      missingDeleted: 0,
      upserted: 1,
      reconciliationSkipped: null,
    });

    const docId = importedIncidentDocId('DEV-emu-1');
    const stored = await adminDb.collection('incidents').doc(docId).get();
    expect(stored.exists).toBe(true);
    expect(stored.data()?.source).toBe('trafikverket');
    expect(stored.data()?.reporterUid).toBeNull();
    expect(stored.data()?.type).toBe('roadwork');
    // No upstream time in this payload → postedAt is OMITTED (client hides age).
    expect(stored.data()?.postedAt).toBeUndefined();

    const firstUpdateTime = stored.updateTime!.toMillis();
    // Re-run performs ZERO incident writes: the metadata freshness advances,
    // but the imported document's updateTime stays byte-for-byte unchanged.
    const second = await runTrafikverketSync(new Date(), 'fake-key', fetcher);
    expect(second).toMatchObject({
      created: 0,
      changed: 0,
      unchangedSkipped: 1,
      upserted: 0,
    });
    expect((await adminDb.collection('incidents').doc(docId).get()).updateTime!.toMillis()).toBe(
      firstUpdateTime,
    );

    const metadata = await adminDb
      .collection(TRAFIKVERKET_SYNC_METADATA_COLLECTION)
      .doc(TRAFIKVERKET_SYNC_METADATA_DOC)
      .get();
    expect(metadata.exists).toBe(true);
    expect(metadata.data()?.fingerprintVersion).toBe(TRAFIKVERKET_FINGERPRINT_VERSION);
    expect(metadata.data()?.responseComplete).toBe(true);
    expect(metadata.data()?.freshUntil).toBeInstanceOf(Timestamp);
  });

  it('stores the upstream original post time as postedAt (not the sync time)', async () => {
    const mock: TrafikverketResponse = {
      RESPONSE: {
        RESULT: [
          {
            Situation: [
              {
                Id: 'SIT-posted',
                Deviation: [
                  {
                    Id: 'DEV-posted-1',
                    MessageType: 'Vägarbete',
                    Message: 'Vägarbete med känd starttid',
                    Geometry: { WGS84: 'POINT (12.0757 57.4874)' },
                    // 14:23+02:00 == 12:23:00Z — the offset must NOT be dropped.
                    CreationTime: '2026-07-30T14:23:00+02:00',
                  },
                ],
              },
            ],
          },
        ],
      },
    };
    // Sync "now" is deliberately far from the upstream post time, so a doc that
    // stored the sync instant instead would fail the assertion below.
    const syncNow = new Date('2026-07-31T20:00:00Z');
    const result = await runTrafikverketSync(syncNow, 'fake-key', async () => mock);
    expect(result).toMatchObject({ skipped: false, upserted: 1, created: 1 });

    const stored = await adminDb
      .collection('incidents')
      .doc(importedIncidentDocId('DEV-posted-1'))
      .get();
    const postedAt = stored.data()?.postedAt as Timestamp | undefined;
    expect(postedAt).toBeInstanceOf(Timestamp);
    expect(postedAt?.toMillis()).toBe(Date.UTC(2026, 6, 30, 12, 23, 0));
    // New imports use the stable upstream post time as createdAt when available.
    expect((stored.data()?.createdAt as Timestamp).toMillis()).toBe(
      Date.UTC(2026, 6, 30, 12, 23, 0),
    );
  });

  it('does NOT crash and omits postedAt when the upstream time is missing/unparseable', async () => {
    const mock: TrafikverketResponse = {
      RESPONSE: {
        RESULT: [
          {
            Situation: [
              {
                Id: 'SIT-notime',
                Deviation: [
                  {
                    Id: 'DEV-notime-1',
                    MessageType: 'Vägarbete',
                    Geometry: { WGS84: 'POINT (12.0757 57.4874)' },
                    // Zone-less → treated as unusable, so the field is omitted.
                    CreationTime: '2026-07-30T14:23:00',
                  },
                ],
              },
            ],
          },
        ],
      },
    };
    const result = await runTrafikverketSync(new Date(), 'fake-key', async () => mock);
    expect(result).toMatchObject({ skipped: false, upserted: 1, created: 1 });
    const stored = await adminDb
      .collection('incidents')
      .doc(importedIncidentDocId('DEV-notime-1'))
      .get();
    expect(stored.exists).toBe(true);
    expect(stored.data()?.postedAt).toBeUndefined();
  });

  it('rewrites a changed stable field exactly once', async () => {
    const response = (message: string): TrafikverketResponse => ({
      RESPONSE: {
        RESULT: [
          {
            Situation: [
              {
                Id: 'SIT-change',
                Deviation: [
                  {
                    Id: 'DEV-change-1',
                    MessageType: 'Vägarbete',
                    Message: message,
                    Geometry: { WGS84: 'POINT (12.0757 57.4874)' },
                  },
                ],
              },
            ],
          },
        ],
      },
    });

    await runTrafikverketSync(new Date(), 'fake-key', async () => response('Första texten'));
    const changed = await runTrafikverketSync(new Date(), 'fake-key', async () =>
      response('Uppdaterad text'),
    );
    expect(changed).toMatchObject({
      created: 0,
      changed: 1,
      unchangedSkipped: 0,
      upserted: 1,
    });
    expect(
      (
        await adminDb.collection('incidents').doc(importedIncidentDocId('DEV-change-1')).get()
      ).data()?.note,
    ).toBe('Uppdaterad text');
  });

  it('recursively deletes imported incidents missing from a complete response', async () => {
    const deviation = (id: string) => ({
      Id: id,
      MessageType: 'Vägarbete',
      Geometry: { WGS84: 'POINT (12.0757 57.4874)' },
    });
    const response = (ids: string[]): TrafikverketResponse => ({
      RESPONSE: {
        RESULT: [{ Situation: [{ Id: 'SIT-reconcile', Deviation: ids.map(deviation) }] }],
      },
    });
    await runTrafikverketSync(new Date(), 'fake-key', async () =>
      response(['DEV-reconcile-keep', 'DEV-reconcile-gone']),
    );
    const gone = adminDb.collection('incidents').doc(importedIncidentDocId('DEV-reconcile-gone'));
    const descendant = gone.collection('unexpectedLedger').doc('child');
    await descendant.set({ exists: true });

    const result = await runTrafikverketSync(new Date(), 'fake-key', async () =>
      response(['DEV-reconcile-keep']),
    );
    expect(result).toMatchObject({
      created: 0,
      changed: 0,
      unchangedSkipped: 1,
      missingDeleted: 1,
      reconciliationSkipped: null,
    });
    expect((await gone.get()).exists).toBe(false);
    expect((await descendant.get()).exists).toBe(false);
  });

  it('migrates a legacy rolling-TTL doc once without changing its createdAt', async () => {
    const id = 'DEV-legacy-migrate';
    const ref = adminDb.collection('incidents').doc(importedIncidentDocId(id));
    const originalCreatedAt = Timestamp.fromDate(new Date('2026-01-02T03:04:05Z'));
    await ref.set({
      type: 'roadwork',
      latitude: KBA.latitude,
      longitude: KBA.longitude,
      geoCell: '319_67',
      status: 'active',
      source: 'trafikverket',
      reporterUid: null,
      note: null,
      createdAt: originalCreatedAt,
      expiresAt: Timestamp.fromMillis(Date.now() + 6 * 60 * 60 * 1000),
    });
    const mock: TrafikverketResponse = {
      RESPONSE: {
        RESULT: [
          {
            Situation: [
              {
                Id: 'SIT-legacy',
                Deviation: [
                  {
                    Id: id,
                    MessageType: 'Vägarbete',
                    Geometry: { WGS84: `POINT (${KBA.longitude} ${KBA.latitude})` },
                  },
                ],
              },
            ],
          },
        ],
      },
    };

    const migrated = await runTrafikverketSync(new Date(), 'fake-key', async () => mock);
    expect(migrated).toMatchObject({ changed: 1, legacyMigrated: 1, upserted: 1 });
    const stored = await ref.get();
    expect((stored.data()?.createdAt as Timestamp).toMillis()).toBe(originalCreatedAt.toMillis());
    expect((stored.data()?.expiresAt as Timestamp).toMillis()).toBe(
      IMPORT_PERSISTENT_EXPIRES_AT_MS,
    );
    expect(stored.data()?.importFingerprintVersion).toBe(TRAFIKVERKET_FINGERPRINT_VERSION);

    const updateTime = stored.updateTime!.toMillis();
    const rerun = await runTrafikverketSync(new Date(), 'fake-key', async () => mock);
    expect(rerun).toMatchObject({ changed: 0, legacyMigrated: 0, unchangedSkipped: 1 });
    expect((await ref.get()).updateTime!.toMillis()).toBe(updateTime);
  });

  it('withholds deletion on an implausible below-limit drop but still imports incoming changes', async () => {
    const batch = adminDb.batch();
    for (let i = 0; i < 100; i += 1) {
      batch.set(adminDb.collection('incidents').doc(importedIncidentDocId(`DEV-drop-${i}`)), {
        type: 'roadwork',
        status: 'active',
        source: 'trafikverket',
        reporterUid: null,
        sourceId: `DEV-drop-${i}`,
        note: 'gammal text',
        latitude: KBA.latitude,
        longitude: KBA.longitude,
        geoCell: '319_67',
        createdAt: Timestamp.now(),
        expiresAt: Timestamp.fromMillis(IMPORT_PERSISTENT_EXPIRES_AT_MS),
        importFingerprintVersion: TRAFIKVERKET_FINGERPRINT_VERSION,
        importFingerprint: 'old-fingerprint',
      });
    }
    await batch.commit();

    const incomingIds = Array.from({ length: 10 }, (_, i) => `DEV-drop-${i}`);
    const result = await runTrafikverketSync(new Date(), 'fake-key', async () => ({
      RESPONSE: {
        RESULT: [
          {
            Situation: [
              {
                Id: 'SIT-drop',
                Deviation: incomingIds.map((id) => ({
                  Id: id,
                  MessageType: 'Vägarbete',
                  Message: 'ny text',
                  Geometry: { WGS84: `POINT (${KBA.longitude} ${KBA.latitude})` },
                })),
              },
            ],
          },
        ],
      },
    }));
    expect(result).toMatchObject({
      situationsReceived: 1,
      deviationsParsed: 10,
      changed: 10,
      missingDeleted: 0,
      reconciliationSkipped: 'implausible-upstream-drop',
    });
    expect(
      (await adminDb.collection('incidents').doc(importedIncidentDocId('DEV-drop-0')).get()).data()
        ?.note,
    ).toBe('ny text');
    expect(
      (await adminDb.collection('incidents').doc(importedIncidentDocId('DEV-drop-99')).get())
        .exists,
    ).toBe(true);
  });

  it('never deletes on a response capped at TRAFIKVERKET_QUERY_LIMIT', async () => {
    const retained = adminDb.collection('incidents').doc('tv_reconcile-retained-on-cap');
    await retained.set({
      type: 'roadwork',
      status: 'active',
      source: 'trafikverket',
      reporterUid: null,
      note: null,
      latitude: KBA.latitude,
      longitude: KBA.longitude,
      geoCell: '319_67',
      createdAt: Timestamp.now(),
      expiresAt: Timestamp.fromMillis(IMPORT_PERSISTENT_EXPIRES_AT_MS),
      importFingerprintVersion: TRAFIKVERKET_FINGERPRINT_VERSION,
      importFingerprint: 'old',
      sourceId: 'reconcile-retained-on-cap',
    });
    const situations = Array.from({ length: TRAFIKVERKET_QUERY_LIMIT }, (_, i) => ({
      Id: `SIT-cap-${i}`,
      Deviation: [
        {
          Id: `DEV-cap-${i}`,
          MessageType: 'Vägarbete',
          // Only one is renderable; all ids still prove upstream presence.
          Geometry: i === 0 ? { WGS84: 'POINT (12.0757 57.4874)' } : undefined,
        },
      ],
    }));
    const result = await runTrafikverketSync(new Date(), 'fake-key', async () => ({
      RESPONSE: { RESULT: [{ Situation: situations }] },
    }));
    expect(result).toMatchObject({
      situationsReceived: TRAFIKVERKET_QUERY_LIMIT,
      deviationsParsed: 1,
      missingDeleted: 0,
      reconciliationSkipped: 'query-limit-reached',
    });
    expect((await retained.get()).exists).toBe(true);
  });

  it('suppresses stale non-current migrated imports while preserving the legacy bridge', async () => {
    const id = 'DEV-freshness';
    const legacyId = 'tv_DEV-freshness-legacy';
    const now = new Date();
    await runTrafikverketSync(now, 'fake-key', async () => ({
      RESPONSE: {
        RESULT: [
          {
            Situation: [
              {
                Id: 'SIT-freshness',
                Deviation: [
                  {
                    Id: id,
                    MessageType: 'Vägarbete',
                    Geometry: { WGS84: `POINT (${KBA.longitude} ${KBA.latitude})` },
                  },
                ],
              },
            ],
          },
        ],
      },
    }));
    await adminDb.collection('incidents').doc(legacyId).set({
      type: 'roadwork',
      status: 'active',
      source: 'trafikverket',
      reporterUid: null,
      note: null,
      latitude: KBA.latitude,
      longitude: KBA.longitude,
      geoCell: '319_67',
      createdAt: Timestamp.now(),
      expiresAt: Timestamp.fromMillis(Date.now() + 60 * 60 * 1000),
    });
    const reader = await createProvisionedUser('inc-tv-fresh-reader');
    await signInAs(reader);
    const nearby = async () =>
      (
        await call('incidents-listNearby', {
          latitude: KBA.latitude,
          longitude: KBA.longitude,
          radiusMeters: 5000,
        })
      ).data as { incidents: Array<{ id: string }> };
    expect(
      (await nearby()).incidents.some((incident) => incident.id === importedIncidentDocId(id)),
    ).toBe(true);
    expect((await nearby()).incidents.some((incident) => incident.id === legacyId)).toBe(true);

    await adminDb
      .collection('incidents')
      .doc(importedIncidentDocId(id))
      .update({ importFingerprintVersion: TRAFIKVERKET_FINGERPRINT_VERSION + 1 });
    await adminDb
      .collection(TRAFIKVERKET_SYNC_METADATA_COLLECTION)
      .doc(TRAFIKVERKET_SYNC_METADATA_DOC)
      .set({ freshUntil: Timestamp.fromMillis(Date.now() - 1) }, { merge: true });
    expect(
      (await nearby()).incidents.some((incident) => incident.id === importedIncidentDocId(id)),
    ).toBe(false);
    expect((await nearby()).incidents.some((incident) => incident.id === legacyId)).toBe(true);
  });

  it('imports >500 classified incidents without tripping the 500-write WriteBatch limit', async () => {
    // Regression guard for the batch-boundary bug: raising TRAFIKVERKET_QUERY_LIMIT
    // to 3000 lets a single run import thousands of incidents, but Firestore's
    // WriteBatch throws above 500 writes/commit. runTrafikverketSync must chunk
    // its upserts (UPSERT_BATCH_SIZE=400). Seed 1,200 classifiable deviations —
    // spanning three batch boundaries — and assert they ALL persist and the run
    // does not throw. A single un-chunked batch would throw here.
    const COUNT = 1_200;
    const deviations = Array.from({ length: COUNT }, (_, i) => ({
      Id: `DEV-bulk-${i}`,
      MessageType: 'Vägarbete',
      Message: `Vägarbete bulk ${i}`,
      // Vary the point slightly per deviation; all within Sweden's WGS84 range.
      Geometry: { WGS84: `POINT (${(12 + i * 0.0001).toFixed(4)} 57.4874)` },
    }));
    const mock: TrafikverketResponse = {
      RESPONSE: { RESULT: [{ Situation: [{ Id: 'SIT-bulk', Deviation: deviations }] }] },
    };
    const fetcher = async () => mock;

    const result = await runTrafikverketSync(new Date(), 'fake-key', fetcher);
    expect(result).toMatchObject({
      skipped: false,
      situationsReceived: 1,
      deviationsParsed: COUNT,
      created: COUNT,
      changed: 0,
      upserted: COUNT,
    });

    // Spot-check that docs across every batch boundary actually persisted.
    for (const i of [0, 399, 400, 799, 800, COUNT - 1]) {
      const stored = await adminDb
        .collection('incidents')
        .doc(importedIncidentDocId(`DEV-bulk-${i}`))
        .get();
      expect(stored.exists).toBe(true);
      expect(stored.data()?.source).toBe('trafikverket');
    }
  });
});

// ---------------------------------------------------------------------------
// incidents.reportCleared — "no, it's gone"
// ---------------------------------------------------------------------------
//
// The safety-critical half of the feature: these tests pin WHEN a real hazard
// leaves everyone's map. Each `describe` provisions its own members because the
// callable is rate-limited to INCIDENT_CLEAR_RATE_LIMIT_MAX votes per uid per
// minute — sharing the top-level `member` across every case would eventually
// trip the limiter and turn a real assertion into a flake.

/** A fix AT the incident, captured now — the honest happy-path sample. */
function clearVoteAt(
  incidentId: string,
  position: { latitude: number; longitude: number } = KBA,
  overrides: Record<string, unknown> = {},
) {
  return {
    incidentId,
    latitude: position.latitude,
    longitude: position.longitude,
    accuracyMeters: 12,
    capturedAt: new Date().toISOString(),
    ...overrides,
  };
}

interface ClearResponse {
  clearedCount: number;
  confirmationCount: number;
  reportedCleared: boolean;
  removed: boolean;
  alreadyVoted: boolean;
  switchedFromConfirmation: boolean;
}

async function reportIncidentAs(user: TestUser, type = 'hazard'): Promise<string> {
  await signInAs(user);
  const created = (
    await call('incidents-report', {
      type,
      latitude: KBA.latitude,
      longitude: KBA.longitude,
    })
  ).data as { id: string };
  return created.id;
}

describe('incidents.reportCleared', () => {
  let reporter: TestUser;

  beforeAll(async () => {
    reporter = await createProvisionedUser('inc-clear-reporter');
    await makeMember(reporter);
  });

  it('fades an incident on the first clear vote but leaves it on the map', async () => {
    const incidentId = await reportIncidentAs(reporter);

    const voter = await createProvisionedUser('inc-clear-v1');
    await makeMember(voter);
    await signInAs(voter);
    const result = (await call('incidents-reportCleared', clearVoteAt(incidentId)))
      .data as ClearResponse;

    expect(result.clearedCount).toBe(1);
    expect(result.confirmationCount).toBe(0);
    expect(result.reportedCleared).toBe(true);
    expect(result.removed).toBe(false);
    expect(result.alreadyVoted).toBe(false);

    // Persisted on the document, and the vote ledger is keyed by the voting uid.
    const stored = await adminDb.collection('incidents').doc(incidentId).get();
    expect(stored.data()?.clearedCount).toBe(1);
    expect(stored.data()?.reportedCleared).toBe(true);
    const ledger = await adminDb
      .collection('incidents')
      .doc(incidentId)
      .collection('clearVotes')
      .doc(voter.uid)
      .get();
    expect(ledger.exists).toBe(true);
    expect(ledger.data()?.uid).toBe(voter.uid);

    // STILL VISIBLE to everyone, carrying both signals — the whole point of
    // transparent decay rather than deletion.
    const nearby = (
      await call('incidents-listNearby', {
        latitude: KBA.latitude,
        longitude: KBA.longitude,
        radiusMeters: 5000,
      })
    ).data as {
      incidents: Array<{
        id: string;
        clearedCount: number;
        confirmationCount: number;
        reportedCleared: boolean;
      }>;
    };
    const onMap = nearby.incidents.find((i) => i.id === incidentId);
    expect(onMap).toBeDefined();
    expect(onMap?.clearedCount).toBe(1);
    expect(onMap?.confirmationCount).toBe(0);
    expect(onMap?.reportedCleared).toBe(true);
  });

  it('refuses to count a clear vote on an already-expired incident', async () => {
    // An expired incident is invisible to every reader (the read rule and
    // listNearby both gate on `expiresAt`), so a vote must not land on it — the
    // counters and the removal decision would be written onto a document that is
    // already gone. The liveness check runs INSIDE the transaction against that
    // attempt's own clock; see isIncidentLive.
    const now = Date.now();
    const deadRef = adminDb.collection('incidents').doc();
    await deadRef.set({
      type: 'hazard',
      latitude: KBA.latitude,
      longitude: KBA.longitude,
      geoCell: '319_66',
      status: 'active',
      source: 'user',
      reporterUid: reporter.uid,
      note: null,
      createdAt: Timestamp.fromDate(new Date(now - 5 * 60 * 60 * 1000)),
      expiresAt: Timestamp.fromDate(new Date(now - 60 * 1000)),
    });

    const voter = await createProvisionedUser('inc-clear-expired');
    await makeMember(voter);
    await signInAs(voter);
    expect(await callableErrorCode(call('incidents-reportCleared', clearVoteAt(deadRef.id)))).toBe(
      'functions/failed-precondition',
    );

    // Nothing was written: no ledger entry, no counter bump.
    expect((await deadRef.collection('clearVotes').doc(voter.uid).get()).exists).toBe(false);
    expect((await deadRef.get()).data()?.clearedCount).toBeUndefined();
  });

  it('is idempotent: a second vote from the same member does not double-count', async () => {
    const incidentId = await reportIncidentAs(reporter);

    const voter = await createProvisionedUser('inc-clear-idem');
    await makeMember(voter);
    await signInAs(voter);
    const first = (await call('incidents-reportCleared', clearVoteAt(incidentId)))
      .data as ClearResponse;
    expect(first.clearedCount).toBe(1);
    expect(first.alreadyVoted).toBe(false);

    const repeat = (await call('incidents-reportCleared', clearVoteAt(incidentId)))
      .data as ClearResponse;
    expect(repeat.clearedCount).toBe(1);
    expect(repeat.alreadyVoted).toBe(true);
    expect(repeat.removed).toBe(false);

    const stored = await adminDb.collection('incidents').doc(incidentId).get();
    expect(stored.data()?.clearedCount).toBe(1);
  });

  it('removes the incident for everyone at 2 NET clear votes', async () => {
    const incidentId = await reportIncidentAs(reporter);

    const voterA = await createProvisionedUser('inc-clear-a');
    await makeMember(voterA);
    await signInAs(voterA);
    const first = (await call('incidents-reportCleared', clearVoteAt(incidentId)))
      .data as ClearResponse;
    expect(first.removed).toBe(false);

    const voterB = await createProvisionedUser('inc-clear-b');
    await makeMember(voterB);
    await signInAs(voterB);
    const second = (await call('incidents-reportCleared', clearVoteAt(incidentId)))
      .data as ClearResponse;
    expect(second.clearedCount).toBe(2);
    expect(second.removed).toBe(true);
    // Removed, therefore not "faded" — it is gone, not dimmed.
    expect(second.reportedCleared).toBe(false);

    // EXPIRED, not deleted: the audit trail (document + vote ledger) survives
    // for the cleanupExpired sweep, but the marker is off every user's map.
    const stored = await adminDb.collection('incidents').doc(incidentId).get();
    expect(stored.exists).toBe(true);
    expect(stored.data()?.expiresAt.toMillis()).toBeLessThanOrEqual(Date.now());

    const nearby = (
      await call('incidents-listNearby', {
        latitude: KBA.latitude,
        longitude: KBA.longitude,
        radiusMeters: 5000,
      })
    ).data as { incidents: Array<{ id: string }> };
    expect(nearby.incidents.some((i) => i.id === incidentId)).toBe(false);
  });

  it('one confirm plus one clear is a TIE: no fade, no removal', async () => {
    const incidentId = await reportIncidentAs(reporter);

    const confirmer = await createProvisionedUser('inc-tie-confirm');
    await makeMember(confirmer);
    await signInAs(confirmer);
    await call('incidents-confirm', { incidentId });

    const clearer = await createProvisionedUser('inc-tie-clear');
    await makeMember(clearer);
    await signInAs(clearer);
    const result = (await call('incidents-reportCleared', clearVoteAt(incidentId)))
      .data as ClearResponse;

    expect(result.clearedCount).toBe(1);
    expect(result.confirmationCount).toBe(1);
    expect(result.reportedCleared).toBe(false);
    expect(result.removed).toBe(false);

    const stored = await adminDb.collection('incidents').doc(incidentId).get();
    expect(stored.data()?.reportedCleared).toBe(false);
    expect(stored.exists).toBe(true);
  });

  it('lets a member SWITCH from confirming to clearing, adjusting both counters once', async () => {
    const incidentId = await reportIncidentAs(reporter);

    const switcher = await createProvisionedUser('inc-switch-to-clear');
    await makeMember(switcher);
    await signInAs(switcher);
    await call('incidents-confirm', { incidentId });

    const result = (await call('incidents-reportCleared', clearVoteAt(incidentId)))
      .data as ClearResponse;
    expect(result.switchedFromConfirmation).toBe(true);
    expect(result.clearedCount).toBe(1);
    // Never counted on BOTH sides: the confirmation is gone, not merely
    // outweighed.
    expect(result.confirmationCount).toBe(0);
    expect(result.reportedCleared).toBe(true);

    const incidentRef = adminDb.collection('incidents').doc(incidentId);
    expect((await incidentRef.get()).data()?.confirmationCount).toBe(0);
    expect((await incidentRef.collection('confirmations').doc(switcher.uid).get()).exists).toBe(
      false,
    );
    expect((await incidentRef.collection('clearVotes').doc(switcher.uid).get()).exists).toBe(true);
  });

  it('lets a member SWITCH BACK from clearing to confirming, un-fading the marker', async () => {
    const incidentId = await reportIncidentAs(reporter);

    const switcher = await createProvisionedUser('inc-switch-to-confirm');
    await makeMember(switcher);
    await signInAs(switcher);
    const cleared = (await call('incidents-reportCleared', clearVoteAt(incidentId)))
      .data as ClearResponse;
    expect(cleared.reportedCleared).toBe(true);

    const confirmed = (await call('incidents-confirm', { incidentId })).data as {
      confirmationCount: number;
      clearedCount: number;
      reportedCleared: boolean;
      switchedFromClearVote: boolean;
    };
    expect(confirmed.switchedFromClearVote).toBe(true);
    expect(confirmed.confirmationCount).toBe(1);
    expect(confirmed.clearedCount).toBe(0);
    // The fade must LIFT — a re-corroborated hazard drawn faded is the failure
    // that gets someone hurt.
    expect(confirmed.reportedCleared).toBe(false);

    const incidentRef = adminDb.collection('incidents').doc(incidentId);
    expect((await incidentRef.get()).data()?.reportedCleared).toBe(false);
    expect((await incidentRef.collection('clearVotes').doc(switcher.uid).get()).exists).toBe(false);
  });

  it('removes IMMEDIATELY when the original reporter clears their own report', async () => {
    const ownReporter = await createProvisionedUser('inc-clear-own');
    await makeMember(ownReporter);
    const incidentId = await reportIncidentAs(ownReporter);

    const result = (await call('incidents-reportCleared', clearVoteAt(incidentId)))
      .data as ClearResponse;
    // ONE vote, no threshold: the reporter has the best information about their
    // own report.
    expect(result.clearedCount).toBe(1);
    expect(result.removed).toBe(true);
    expect(result.reportedCleared).toBe(false);

    const stored = await adminDb.collection('incidents').doc(incidentId).get();
    expect(stored.data()?.expiresAt.toMillis()).toBeLessThanOrEqual(Date.now());
  });

  it('removes IMMEDIATELY when an admin clears it (moderation)', async () => {
    const incidentId = await reportIncidentAs(reporter);

    await signInAs(adminUser);
    const result = (await call('incidents-reportCleared', clearVoteAt(incidentId)))
      .data as ClearResponse;
    expect(result.removed).toBe(true);

    const stored = await adminDb.collection('incidents').doc(incidentId).get();
    expect(stored.data()?.expiresAt.toMillis()).toBeLessThanOrEqual(Date.now());
  });

  it('rejects a vote from OUT OF RANGE, however the accuracy is reported', async () => {
    const incidentId = await reportIncidentAs(reporter);

    const distant = await createProvisionedUser('inc-clear-far');
    await makeMember(distant);
    await signInAs(distant);

    // ~30 km away. An unbounded accuracy must not buy that distance: this is the
    // exploit shape PR #573 closed inside isWithinGeofence, re-asserted here at
    // the callable so neither bound is load-bearing on its own.
    expect(
      await callableErrorCode(
        call('incidents-reportCleared', clearVoteAt(incidentId, FAR, { accuracyMeters: 5_000 })),
      ),
    ).toBe('functions/failed-precondition');
    expect(
      await callableErrorCode(
        call('incidents-reportCleared', clearVoteAt(incidentId, FAR, { accuracyMeters: 50 })),
      ),
    ).toBe('functions/failed-precondition');

    // Nothing was counted.
    const stored = await adminDb.collection('incidents').doc(incidentId).get();
    expect(stored.data()?.clearedCount ?? 0).toBe(0);
    expect(stored.data()?.reportedCleared ?? false).toBe(false);
  });

  it('rejects an absurd or non-finite accuracy at the input boundary', async () => {
    const incidentId = await reportIncidentAs(reporter);

    const spoofer = await createProvisionedUser('inc-clear-accuracy');
    await makeMember(spoofer);
    await signInAs(spoofer);

    for (const accuracyMeters of [50_000, 1e9, -1]) {
      expect(
        await callableErrorCode(
          call('incidents-reportCleared', clearVoteAt(incidentId, FAR, { accuracyMeters })),
        ),
      ).toBe('functions/invalid-argument');
    }
    // NaN and Infinity are not JSON values, so they never reach the wire as
    // themselves — the schema-level rejection is pinned directly in
    // incidents-core.test.ts (`parseReportClearedInput`), which is where zod's
    // behaviour actually lives.
  });

  it('rejects a stale position', async () => {
    const incidentId = await reportIncidentAs(reporter);

    const staleVoter = await createProvisionedUser('inc-clear-stale');
    await makeMember(staleVoter);
    await signInAs(staleVoter);
    const code = await callableErrorCode(
      call(
        'incidents-reportCleared',
        clearVoteAt(incidentId, KBA, {
          capturedAt: new Date(Date.now() - 60 * 60 * 1000).toISOString(),
        }),
      ),
    );
    expect(code).toBe('functions/failed-precondition');
  });

  it('rejects a clear vote on an IMPORTED (Trafikverket) incident', async () => {
    // The importer full-overwrites every tv_ doc every 30 minutes, so a member
    // vote would simply be erased — and upstream is the authority anyway.
    const docId = importedIncidentDocId('CLEAR-VOTE-IMPORTED');
    await adminDb
      .collection('incidents')
      .doc(docId)
      .set({
        type: 'roadwork',
        latitude: KBA.latitude,
        longitude: KBA.longitude,
        geoCell: '319_67',
        status: 'active',
        source: 'trafikverket',
        reporterUid: null,
        note: null,
        createdAt: Timestamp.fromDate(new Date()),
        expiresAt: Timestamp.fromDate(new Date(Date.now() + 6 * 60 * 60 * 1000)),
      });

    const voter = await createProvisionedUser('inc-clear-tv');
    await makeMember(voter);
    await signInAs(voter);
    expect(await callableErrorCode(call('incidents-reportCleared', clearVoteAt(docId)))).toBe(
      'functions/failed-precondition',
    );

    // Rejected for ADMINS too — the importer would just re-upsert it.
    await signInAs(adminUser);
    expect(await callableErrorCode(call('incidents-reportCleared', clearVoteAt(docId)))).toBe(
      'functions/failed-precondition',
    );

    const stored = await adminDb.collection('incidents').doc(docId).get();
    expect(stored.data()?.clearedCount).toBeUndefined();
  });

  it('rejects a missing incident and a non-member caller', async () => {
    const incidentId = await reportIncidentAs(reporter);

    const voter = await createProvisionedUser('inc-clear-missing');
    await makeMember(voter);
    await signInAs(voter);
    expect(
      await callableErrorCode(call('incidents-reportCleared', clearVoteAt('does-not-exist'))),
    ).toBe('functions/not-found');

    // A suspended member cannot vote (requireMemberActor).
    const suspended = await createProvisionedUser('inc-clear-suspended');
    await makeMember(suspended);
    await adminDb.collection('users').doc(suspended.uid).set({ suspended: true }, { merge: true });
    await signInAs(suspended);
    expect(await callableErrorCode(call('incidents-reportCleared', clearVoteAt(incidentId)))).toBe(
      'functions/permission-denied',
    );
  });

  it('rate-limits a member hammering clear votes', async () => {
    const spammer = await createProvisionedUser('inc-clear-spam');
    await makeMember(spammer);

    // Distinct incidents so nothing is rejected as an idempotent repeat — the
    // ONLY thing that may stop this loop is the limiter.
    const ids: string[] = [];
    for (let i = 0; i < INCIDENT_CLEAR_RATE_LIMIT_MAX + 1; i += 1) {
      ids.push(await reportIncidentAs(reporter));
    }

    await signInAs(spammer);
    let throttled = false;
    for (const id of ids) {
      const code = await callableErrorCode(call('incidents-reportCleared', clearVoteAt(id)));
      if (code === 'functions/resource-exhausted') {
        throttled = true;
        break;
      }
    }
    expect(throttled).toBe(true);
  });
});

describe('incidents.reportCleared idempotency', () => {
  it('reports a repeat vote as an idempotent success even from OUT OF RANGE', () => {
    // A repeat writes nothing, so the proximity gate has nothing to protect —
    // and running it anyway would tell a member who voted at the scene and then
    // drove on to "drive closer" about a vote they had already cast. Pinned here
    // because the ordering that makes this true is easy to "tidy" away.
    return (async () => {
      const reporter = await createProvisionedUser('inc-clear-idem-far-r');
      await makeMember(reporter);
      const incidentId = await reportIncidentAs(reporter);

      const voter = await createProvisionedUser('inc-clear-idem-far-v');
      await makeMember(voter);
      await signInAs(voter);
      const first = (await call('incidents-reportCleared', clearVoteAt(incidentId)))
        .data as ClearResponse;
      expect(first.alreadyVoted).toBe(false);

      // Same member, same incident, now 30 km away.
      const repeat = (await call('incidents-reportCleared', clearVoteAt(incidentId, FAR)))
        .data as ClearResponse;
      expect(repeat.alreadyVoted).toBe(true);
      expect(repeat.clearedCount).toBe(1);
      expect(repeat.removed).toBe(false);

      const stored = await adminDb.collection('incidents').doc(incidentId).get();
      expect(stored.data()?.clearedCount).toBe(1);
    })();
  });
});
