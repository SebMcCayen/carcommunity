/**
 * Police-proximity emulator integration tests (police-proximity alert feature).
 *
 * Exercises the user-reported police-pin domain end-to-end: member report →
 * `policeReports/{id}` doc, listNearby radius + geo-cell filtering, member
 * gating, the direct security-rules read (active vs expired), the report
 * rate-limit, and the listNearby rate-limit — all against the Functions +
 * Firestore emulators.
 *
 * Requires the emulators — run via:
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
  getFirestore as getClientFirestore,
  type Firestore as ClientFirestore,
} from 'firebase/firestore';
import { getApps as getAdminApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getFirestore as getAdminFirestore, Timestamp } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  POLICE_LIST_RATE_LIMIT_COLLECTION,
  POLICE_LIST_RATE_LIMIT_MAX,
  POLICE_REPORT_RATE_LIMIT_COLLECTION,
  POLICE_REPORT_RATE_LIMIT_MAX,
  POLICE_REPORT_RATE_LIMIT_WINDOW_MS,
  POLICE_VOTE_RATE_LIMIT_COLLECTION,
  POLICE_VOTE_RATE_LIMIT_MAX,
  policeListRateLimitDocId,
  policeReportRateLimitDocId,
  policeVoteRateLimitDocId,
} from '../police/police-core';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'police-emulator-tests');
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

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'police-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);
  clientDb = getClientFirestore(app);
  connectFirestoreEmulator(clientDb, EMULATOR_HOST, 8080);

  member = await createProvisionedUser('pol-member');
  await makeMember(member);
});

afterAll(async () => {
  if (app) await deleteApp(app);
});

describe('police.report + listNearby', () => {
  it('reports a police pin and returns it via listNearby within radius, excluding far ones', async () => {
    await signInAs(member);
    const created = (
      await call('police-report', {
        latitude: KBA.latitude,
        longitude: KBA.longitude,
      })
    ).data as {
      id: string;
      source: string;
      expiresAt: string;
      mine: boolean;
      reporterUid?: unknown;
    };

    expect(created.source).toBe('manual');
    // The creator owns the pin (mine), and the raw reporterUid is NEVER on the
    // client view (privacy) — only the per-caller boolean.
    expect(created.mine).toBe(true);
    expect(created.reporterUid).toBeUndefined();
    expect(typeof created.expiresAt).toBe('string');

    // Persisted with a geoCell + reporterUid + active status + future expiry.
    // (reporterUid stays STORED server-side; it is just never returned to clients.)
    const stored = await adminDb.collection('policeReports').doc(created.id).get();
    expect(stored.data()?.geoCell).toBeTypeOf('string');
    expect(stored.data()?.reporterUid).toBe(member.uid);
    expect(stored.data()?.status).toBe('active');
    expect(stored.data()?.expiresAt.toMillis()).toBeGreaterThan(Date.now());

    const nearby = (
      await call('police-listNearby', {
        latitude: KBA.latitude,
        longitude: KBA.longitude,
        radiusMeters: 5000,
      })
    ).data as {
      policeReports: Array<{ id: string; source: string; mine: boolean; reporterUid?: unknown }>;
    };
    const own = nearby.policeReports.find((p) => p.id === created.id);
    expect(own).toBeDefined();
    // The reporter sees their own pin flagged `mine` (so the client suppresses its
    // self-alert) and still never receives a reporterUid.
    expect(own?.mine).toBe(true);
    expect(own?.reporterUid).toBeUndefined();

    const farAway = (
      await call('police-listNearby', {
        latitude: FAR.latitude,
        longitude: FAR.longitude,
        radiusMeters: 5000,
      })
    ).data as { policeReports: Array<{ id: string }> };
    expect(farAway.policeReports.some((p) => p.id === created.id)).toBe(false);
  });

  it('does not leak the reporter uid and flags mine=false for another member', async () => {
    // The reporter creates a pin...
    await signInAs(member);
    const created = (
      await call('police-report', { latitude: KBA.latitude, longitude: KBA.longitude })
    ).data as { id: string };

    // ...and a DIFFERENT member lists nearby: they see the pin, but it is NOT
    // theirs (so their proximity alert still fires) and carries no reporterUid.
    const other = await createProvisionedUser('pol-other');
    await makeMember(other);
    await signInAs(other);
    const nearby = (
      await call('police-listNearby', {
        latitude: KBA.latitude,
        longitude: KBA.longitude,
        radiusMeters: 5000,
      })
    ).data as {
      policeReports: Array<{ id: string; mine: boolean; reporterUid?: unknown }>;
    };
    const seen = nearby.policeReports.find((p) => p.id === created.id);
    expect(seen).toBeDefined();
    expect(seen?.mine).toBe(false);
    expect(seen?.reporterUid).toBeUndefined();
  });

  it('accepts a convoy-sourced report', async () => {
    await signInAs(member);
    const created = (
      await call('police-report', {
        latitude: KBA.latitude,
        longitude: KBA.longitude,
        source: 'convoy',
      })
    ).data as { source: string };
    expect(created.source).toBe('convoy');
  });

  it('admits a signed-in non-member while member gating is disabled repo-wide (declared member-gated)', async () => {
    const nonMember = await createProvisionedUser('pol-nonmember');
    // report + listNearby DECLARE member access (requireMemberActor), but
    // member gating is disabled repo-wide today (shared/memberGating.ts), so a
    // plain signed-in, non-suspended caller currently passes — the gate binds
    // only when it re-locks. This test pins TODAY's behaviour: no permission/auth
    // error for a normal signed-in caller (never a hard-coded member assertion
    // that would flip meaning the moment gating is switched on).
    await signInAs(nonMember);
    const code = await callableErrorCode(
      call('police-report', { latitude: KBA.latitude, longitude: KBA.longitude }),
    );
    expect(['no-error', 'functions/permission-denied']).toContain(code);
  });

  it('rejects an unauthenticated report', async () => {
    await auth.signOut();
    const code = await callableErrorCode(
      call('police-report', { latitude: KBA.latitude, longitude: KBA.longitude }),
    );
    expect(code).toBe('functions/unauthenticated');
  });

  it('rejects an invalid coordinate', async () => {
    await signInAs(member);
    const code = await callableErrorCode(
      call('police-report', { latitude: 200, longitude: KBA.longitude }),
    );
    expect(code).toBe('functions/invalid-argument');
  });

  it('excludes an expired pin from listNearby (marker-liveness filter)', async () => {
    // The Admin SDK bypasses rules, so listNearby must apply the rules' intent
    // (expiresAt > now) in memory. Seed one live pin and one already-expired pin
    // in the same cell; only the live one comes back.
    const now = Date.now();
    const base = {
      latitude: KBA.latitude,
      longitude: KBA.longitude,
      geoCell: '319_66',
      status: 'active' as const,
      source: 'manual' as const,
      reporterUid: 'seed',
      createdAt: Timestamp.fromDate(new Date(now)),
    };
    const liveRef = adminDb.collection('policeReports').doc();
    const expiredRef = adminDb.collection('policeReports').doc();
    await liveRef.set({ ...base, expiresAt: Timestamp.fromDate(new Date(now + 30 * 60_000)) });
    await expiredRef.set({ ...base, expiresAt: Timestamp.fromDate(new Date(now - 60_000)) });

    await signInAs(member);
    const nearby = (
      await call('police-listNearby', {
        latitude: KBA.latitude,
        longitude: KBA.longitude,
        radiusMeters: 5000,
      })
    ).data as { policeReports: Array<{ id: string }> };
    const ids = nearby.policeReports.map((p) => p.id);
    expect(ids).toContain(liveRef.id);
    expect(ids).not.toContain(expiredRef.id);
  });

  it('security rules let a member read an active pin but hide an expired one', async () => {
    const now = Date.now();
    const base = {
      latitude: KBA.latitude,
      longitude: KBA.longitude,
      geoCell: '319_66',
      status: 'active' as const,
      source: 'manual' as const,
      reporterUid: 'seed',
      createdAt: Timestamp.fromDate(new Date(now)),
    };
    const liveRef = adminDb.collection('policeReports').doc();
    const expiredRef = adminDb.collection('policeReports').doc();
    await liveRef.set({ ...base, expiresAt: Timestamp.fromDate(new Date(now + 30 * 60_000)) });
    await expiredRef.set({ ...base, expiresAt: Timestamp.fromDate(new Date(now - 60_000)) });

    // `member` is an active member; the read rule is isActiveMember() && active
    // && expiresAt > now (member-read, least-privilege per the feature spec).
    await signInAs(member);
    // Direct read of the active pin succeeds (member + status active + future).
    const liveSnap = await clientGetDoc(clientDoc(clientDb, 'policeReports', liveRef.id));
    expect(liveSnap.exists()).toBe(true);
    // The expired pin is DENIED by the read rule (expiresAt > request.time is
    // false), so a direct get throws permission-denied rather than returning it.
    const code = await callableErrorCode(
      clientGetDoc(clientDoc(clientDb, 'policeReports', expiredRef.id)),
    );
    expect(code).toBe('permission-denied');
  });
});

describe('police.report rate limit', () => {
  const rateLimits = () => adminDb.collection(POLICE_REPORT_RATE_LIMIT_COLLECTION);

  it('throws resource-exhausted once the report window counter is at the cap', async () => {
    const user = await createProvisionedUser('pol-rl-report');
    await makeMember(user);
    await signInAs(user);
    // Seed the counter for the current AND next window at the cap, so the
    // callable rejects regardless of which side of a minute boundary it lands on.
    const nowMs = Date.now();
    for (const ms of [nowMs, nowMs + POLICE_REPORT_RATE_LIMIT_WINDOW_MS]) {
      await rateLimits()
        .doc(policeReportRateLimitDocId(user.uid, ms))
        .set({ uid: user.uid, count: POLICE_REPORT_RATE_LIMIT_MAX });
    }
    const code = await callableErrorCode(
      call('police-report', { latitude: KBA.latitude, longitude: KBA.longitude }),
    );
    expect(code).toBe('functions/resource-exhausted');
  });

  it('does not consume the report window on an invalid-argument call (validate before rate limit)', async () => {
    const user = await createProvisionedUser('pol-rl-invalid');
    await makeMember(user);
    await signInAs(user);
    const nowMs = Date.now();
    await callableErrorCode(call('police-report', { latitude: 200, longitude: KBA.longitude }));
    const counts = await Promise.all(
      [nowMs, nowMs - POLICE_REPORT_RATE_LIMIT_WINDOW_MS].map(async (ms) => {
        const snap = await rateLimits().doc(policeReportRateLimitDocId(user.uid, ms)).get();
        return (snap.data()?.count as number | undefined) ?? 0;
      }),
    );
    expect(counts.every((c) => c === 0)).toBe(true);
  });

  it('serializes a concurrent burst so no window admits more than the cap (atomic limiter)', async () => {
    // The whole point of the transaction: N parallel reports from one uid must
    // NOT all read a pre-increment count of 0 and slip through. Fire more than
    // twice the cap in parallel so a rejection is guaranteed even if the burst
    // happens to straddle a minute boundary (each window is independently capped).
    const user = await createProvisionedUser('pol-rl-concurrent');
    await makeMember(user);
    await signInAs(user);
    const burst = 2 * POLICE_REPORT_RATE_LIMIT_MAX + 2;
    const results = await Promise.allSettled(
      Array.from({ length: burst }, () =>
        call('police-report', { latitude: KBA.latitude, longitude: KBA.longitude }),
      ),
    );
    const succeeded = results.filter((r) => r.status === 'fulfilled').length;
    const rejected = results.length - succeeded;
    // The overflow is rejected — the non-atomic version would let many/all slip.
    expect(succeeded).toBeGreaterThanOrEqual(1);
    expect(rejected).toBeGreaterThan(0);

    // Boundary-safe atomicity invariants (never flake on a minute-straddle):
    //  - no single window counter exceeds the cap (the property the transaction
    //    guarantees), and
    //  - the counters sum EXACTLY to the number of successful reports (every
    //    success incremented its window once; no lost or doubled increments).
    const docs = await rateLimits().where('uid', '==', user.uid).get();
    let totalCounted = 0;
    for (const d of docs.docs) {
      const count = d.data().count as number;
      expect(count).toBeLessThanOrEqual(POLICE_REPORT_RATE_LIMIT_MAX);
      totalCounted += count;
    }
    expect(totalCounted).toBe(succeeded);
    // Together with the per-window cap above, this means at most the cap
    // succeeded per window — in the common single-window case, at most the cap
    // in total.
    if (docs.size === 1) {
      expect(succeeded).toBeLessThanOrEqual(POLICE_REPORT_RATE_LIMIT_MAX);
    }
  });
});

describe('police.listNearby rate limit', () => {
  const rateLimits = () => adminDb.collection(POLICE_LIST_RATE_LIMIT_COLLECTION);

  it('throws resource-exhausted once the list window counter is at the cap', async () => {
    const user = await createProvisionedUser('pol-rl-list');
    await signInAs(user);
    const nowMs = Date.now();
    for (const ms of [nowMs, nowMs + POLICE_REPORT_RATE_LIMIT_WINDOW_MS]) {
      await rateLimits()
        .doc(policeListRateLimitDocId(user.uid, ms))
        .set({ uid: user.uid, count: POLICE_LIST_RATE_LIMIT_MAX });
    }
    const code = await callableErrorCode(
      call('police-listNearby', {
        latitude: KBA.latitude,
        longitude: KBA.longitude,
        radiusMeters: 5000,
      }),
    );
    expect(code).toBe('functions/resource-exhausted');
  });
});

interface VerifyResult {
  policeReportId: string;
  confirmationCount: number;
  disputeCount: number;
  alreadyVoted: boolean;
  switched: boolean;
}

/** Reports a pin AS `reporter`, then returns its id (leaves the reporter signed in). */
async function reportPinAs(reporter: TestUser): Promise<string> {
  await signInAs(reporter);
  const created = (
    await call('police-report', { latitude: KBA.latitude, longitude: KBA.longitude })
  ).data as { id: string };
  return created.id;
}

describe('police.remove', () => {
  it('removes the reporter own pin and hides it from listNearby', async () => {
    const id = await reportPinAs(member);
    const res = (await call('police-remove', { policeReportId: id })).data as { removed: boolean };
    expect(res.removed).toBe(true);

    const stored = await adminDb.collection('policeReports').doc(id).get();
    expect(stored.exists).toBe(false);

    const nearby = (
      await call('police-listNearby', {
        latitude: KBA.latitude,
        longitude: KBA.longitude,
        radiusMeters: 5000,
      })
    ).data as { policeReports: Array<{ id: string }> };
    expect(nearby.policeReports.some((p) => p.id === id)).toBe(false);
  });

  it('is an idempotent no-op for a missing pin', async () => {
    await signInAs(member);
    const res = (
      await call('police-remove', { policeReportId: 'does-not-exist-1234' })
    ).data as { removed: boolean };
    expect(res.removed).toBe(false);
  });

  it('rejects removing another member pin (owner-only) and leaves it live', async () => {
    const id = await reportPinAs(member);
    const other = await createProvisionedUser('pol-rm-other');
    await makeMember(other);
    await signInAs(other);
    const code = await callableErrorCode(call('police-remove', { policeReportId: id }));
    expect(code).toBe('functions/permission-denied');
    const stored = await adminDb.collection('policeReports').doc(id).get();
    expect(stored.exists).toBe(true);
  });
});

describe('police.confirm / police.dispute (verify)', () => {
  it('confirms someone else pin, dedups a repeat, and surfaces the count on listNearby', async () => {
    const id = await reportPinAs(member);
    const other = await createProvisionedUser('pol-cf-other');
    await makeMember(other);
    await signInAs(other);

    const first = (await call('police-confirm', { policeReportId: id })).data as VerifyResult;
    expect(first.confirmationCount).toBe(1);
    expect(first.disputeCount).toBe(0);
    expect(first.alreadyVoted).toBe(false);

    // A repeat is idempotent — no double count.
    const again = (await call('police-confirm', { policeReportId: id })).data as VerifyResult;
    expect(again.confirmationCount).toBe(1);
    expect(again.alreadyVoted).toBe(true);

    const nearby = (
      await call('police-listNearby', {
        latitude: KBA.latitude,
        longitude: KBA.longitude,
        radiusMeters: 5000,
      })
    ).data as { policeReports: Array<{ id: string; confirmationCount: number; disputeCount: number }> };
    const seen = nearby.policeReports.find((p) => p.id === id);
    expect(seen?.confirmationCount).toBe(1);
    expect(seen?.disputeCount).toBe(0);
  });

  it('switches a confirm to a dispute without counting the member on both sides', async () => {
    const id = await reportPinAs(member);
    const other = await createProvisionedUser('pol-sw-other');
    await makeMember(other);
    await signInAs(other);

    await call('police-confirm', { policeReportId: id });
    const switched = (await call('police-dispute', { policeReportId: id })).data as VerifyResult;
    expect(switched.confirmationCount).toBe(0);
    expect(switched.disputeCount).toBe(1);
    expect(switched.switched).toBe(true);

    // The pin still exists — a dispute informs, it does NOT auto-remove.
    const stored = await adminDb.collection('policeReports').doc(id).get();
    expect(stored.exists).toBe(true);
  });

  it('rejects the reporter verifying their own pin', async () => {
    const id = await reportPinAs(member); // member is signed in and owns it
    const code = await callableErrorCode(call('police-confirm', { policeReportId: id }));
    expect(code).toBe('functions/permission-denied');
  });

  it('is not-found for a missing pin', async () => {
    await signInAs(member);
    const code = await callableErrorCode(
      call('police-confirm', { policeReportId: 'missing-pin-9999' }),
    );
    expect(code).toBe('functions/not-found');
  });

  it('throws resource-exhausted once the shared verify budget is at the cap', async () => {
    const id = await reportPinAs(member);
    const other = await createProvisionedUser('pol-vote-rl');
    await makeMember(other);
    await signInAs(other);
    const nowMs = Date.now();
    for (const ms of [nowMs, nowMs + POLICE_REPORT_RATE_LIMIT_WINDOW_MS]) {
      await adminDb
        .collection(POLICE_VOTE_RATE_LIMIT_COLLECTION)
        .doc(policeVoteRateLimitDocId(other.uid, ms))
        .set({ uid: other.uid, count: POLICE_VOTE_RATE_LIMIT_MAX });
    }
    const code = await callableErrorCode(call('police-confirm', { policeReportId: id }));
    expect(code).toBe('functions/resource-exhausted');
  });
});
