/**
 * Live location emulator integration tests (Phase 10).
 *
 * Exercises the full lifecycle end-to-end: start → update → marker in
 * RTDB → stop/hideMeNow removal, staleness rejection, member gating and
 * the liveLocation feature flag, and the TTL sweep via runLiveCleanup.
 *
 * Requires the Functions + Database emulators — run via:
 *   pnpm emulators:test
 */

process.env.FIREBASE_AUTH_EMULATOR_HOST ??= '127.0.0.1:9099';
process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.FIREBASE_DATABASE_EMULATOR_HOST ??= '127.0.0.1:9000';
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
import { getDatabase as getAdminDatabase } from 'firebase-admin/database';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { runLiveCleanup } from '../live/scheduled';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ??
  initializeAdminApp(
    { projectId: PROJECT_ID, databaseURL: `http://${EMULATOR_HOST}:9000?ns=${PROJECT_ID}-default-rtdb` },
    'live-emulator-tests',
  );
const adminAuth = getAdminAuth(adminApp);
const adminDb = getAdminFirestore(adminApp);
const adminRtdb = getAdminDatabase(adminApp);

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

async function makeMember(user: TestUser): Promise<void> {
  await adminAuth.setCustomUserClaims(user.uid, { activeMember: true });
  await adminDb.collection('users').doc(user.uid).set({ activeMember: true }, { merge: true });
}

async function signInAs(user: TestUser): Promise<void> {
  await signInWithEmailAndPassword(auth, user.email, user.password);
  await auth.currentUser?.getIdToken(true);
}

const call = (name: string, data: unknown) => httpsCallable(functions, name)(data);

const coordinate = (recordedAt: string) => ({
  latitude: 59.334,
  longitude: 18.063,
  accuracyMeters: 12,
  speedMetersPerSecond: 8.3,
  recordedAt,
});

let member: TestUser;
let freeUser: TestUser;

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'live-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  member = await createProvisionedUser('live-member');
  await makeMember(member);
  freeUser = await createProvisionedUser('live-free');
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('live session lifecycle', () => {
  it('start → update → marker exists → stop removes it', async () => {
    await signInAs(member);
    await adminDb
      .collection('users')
      .doc(member.uid)
      .set({ displayName: 'Sebbe' }, { merge: true });
    // Seed the member's main car so the session denormalizes it onto the marker.
    await adminDb.collection('vehicles').doc('veh-main').set({
      userId: member.uid,
      make: 'Volvo',
      model: '242',
      modelYear: 1980,
      powertrain: 'petrol',
      imagePath: `vehicleImages/${member.uid}/veh-main/photo.jpg`,
      isMainCar: true,
    });

    const started = (await call('live-startSession', { duration: '1h' })).data as {
      sessionId: string;
      expiresAt: string;
    };
    expect(started.sessionId).toBeTruthy();

    await call('live-updatePosition', { coordinate: coordinate(new Date().toISOString()) });

    const latest = (await adminRtdb.ref(`liveLocation/${member.uid}/latest`).get()).val();
    expect(latest).toMatchObject({
      latitude: 59.334,
      longitude: 18.063,
      sessionId: started.sessionId,
      displayName: 'Sebbe',
      expiresAt: started.expiresAt,
      mainCar: {
        make: 'Volvo',
        model: '242',
        modelYear: 1980,
        imagePath: `vehicleImages/${member.uid}/veh-main/photo.jpg`,
      },
    });

    await call('live-stopSession', {});
    expect((await adminRtdb.ref(`liveLocation/${member.uid}/latest`).get()).exists()).toBe(false);
    const session = (await adminRtdb.ref(`liveLocation/${member.uid}/session`).get()).val();
    expect(session.status).toBe('stopped');
    expect(session.stopReason).toBe('user_stop');
  });

  it('rejects stale positions and updates without an active session', async () => {
    await signInAs(member);
    // Previous test stopped the session.
    expect(
      await callableErrorCode(
        call('live-updatePosition', { coordinate: coordinate(new Date().toISOString()) }),
      ),
    ).toBe('functions/failed-precondition');

    await call('live-startSession', { duration: '1h' });
    // Restarting removed any previous marker immediately.
    expect((await adminRtdb.ref(`liveLocation/${member.uid}/latest`).get()).exists()).toBe(false);
    const stale = new Date(Date.now() - 90_000).toISOString();
    expect(
      await callableErrorCode(call('live-updatePosition', { coordinate: coordinate(stale) })),
    ).toBe('functions/invalid-argument');
    await call('live-stopSession', {});
  });

  it('lets a non-member share and honors the liveLocation flag', async () => {
    // Sharing your own location is free: a non-member (no activeMember claim)
    // can start a session and update position. Only viewing OTHERS is paid.
    await signInAs(freeUser);
    const started = (await call('live-startSession', { duration: '1h' })).data as {
      sessionId: string;
    };
    expect(started.sessionId).toBeTruthy();
    await call('live-updatePosition', { coordinate: coordinate(new Date().toISOString()) });
    expect((await adminRtdb.ref(`liveLocation/${freeUser.uid}/latest`).get()).exists()).toBe(true);
    await call('live-stopSession', {});

    await signInAs(member);
    await adminDb
      .collection('config')
      .doc('featureFlags')
      .set({ liveLocation: false }, { merge: true });
    try {
      expect(await callableErrorCode(call('live-startSession', { duration: '1h' }))).toBe(
        'functions/failed-precondition',
      );
    } finally {
      await adminDb
        .collection('config')
        .doc('featureFlags')
        .set({ liveLocation: true }, { merge: true });
    }
  });

  it('hideMeNow removes the marker and works while suspended', async () => {
    const sharer = await createProvisionedUser('live-hide');
    await makeMember(sharer);
    await signInAs(sharer);
    await call('live-startSession', { duration: '1h' });
    await call('live-updatePosition', { coordinate: coordinate(new Date().toISOString()) });

    // Suspend mid-session; hideMeNow must still work.
    await adminDb.collection('users').doc(sharer.uid).set({ suspended: true }, { merge: true });
    const result = (await call('live-hideMeNow', {})).data as { status: string };
    expect(result.status).toBe('stopped');
    expect((await adminRtdb.ref(`liveLocation/${sharer.uid}/latest`).get()).exists()).toBe(false);
    const session = (await adminRtdb.ref(`liveLocation/${sharer.uid}/session`).get()).val();
    expect(session.stopReason).toBe('hide_me_now');
  });

  it('extendSession pushes expiry to a fresh capped window, never past 6h', async () => {
    const sharer = await createProvisionedUser('live-extend');
    await makeMember(sharer);
    await signInAs(sharer);

    const started = (await call('live-startSession', { duration: '1h' })).data as {
      sessionId: string;
      expiresAt: string;
    };
    const startedExpiry = Date.parse(started.expiresAt);

    const before = Date.now();
    const extended = (await call('live-extendSession', {})).data as {
      sessionId: string;
      status: string;
      expiresAt: string;
    };
    const after = Date.now();

    // Same session (id/marker unchanged) — a seamless extend, not a restart.
    expect(extended.sessionId).toBe(started.sessionId);
    expect(extended.status).toBe('active');

    // Fresh window: pushed forward past the original 1h expiry...
    const newExpiry = Date.parse(extended.expiresAt);
    expect(newExpiry).toBeGreaterThan(startedExpiry);
    // ...and exactly a 6h cap from "now" (bounded by the call's wall-clock window).
    // Slack is generous (±60s) so emulator/CI variance in the callable's wall
    // time can't flake this: a 60s window still verifies the 6h cap math.
    const SIX_H = 6 * 60 * 60 * 1000;
    const SLACK = 60_000;
    expect(newExpiry).toBeGreaterThanOrEqual(before + SIX_H - SLACK);
    expect(newExpiry).toBeLessThanOrEqual(after + SIX_H + SLACK);

    // The session node itself carries the new expiry.
    const node = (await adminRtdb.ref(`liveLocation/${sharer.uid}/session`).get()).val();
    expect(node.expiresAt).toBe(extended.expiresAt);
    expect(node.id).toBe(started.sessionId);

    await call('live-stopSession', {});
  });

  it('extendSession refuses a session that has no active session (must restart)', async () => {
    const sharer = await createProvisionedUser('live-extend-none');
    await makeMember(sharer);
    await signInAs(sharer);
    // No session started (or already stopped): extend is failed-precondition.
    expect(await callableErrorCode(call('live-extendSession', {}))).toBe(
      'functions/failed-precondition',
    );
  });

  it('extendSession does NOT resurrect a session that expired before commit', async () => {
    const sharer = await createProvisionedUser('live-extend-expired');
    await makeMember(sharer);
    await signInAs(sharer);

    await call('live-startSession', { duration: '1h' });

    // Simulate the session crossing its expiresAt boundary between the client's
    // read and the extend's commit: push expiresAt into the PAST directly on the
    // RTDB node while status stays 'active'. The atomic check-and-extend must
    // evaluate liveness at commit time and ABORT — a naive unconditional write
    // would resurrect it with a fresh 6h window.
    const sessionNodeRef = adminRtdb.ref(`liveLocation/${sharer.uid}/session`);
    const expiredIso = new Date(Date.now() - 60_000).toISOString();
    await sessionNodeRef.update({ expiresAt: expiredIso });

    expect(await callableErrorCode(call('live-extendSession', {}))).toBe(
      'functions/failed-precondition',
    );

    // The transaction aborted, so nothing was written: expiresAt is unchanged
    // (still in the past), NOT pushed forward to a fresh capped window.
    const after = (await sessionNodeRef.get()).val();
    expect(after.expiresAt).toBe(expiredIso);
  });
});

describe('live TTL sweep', () => {
  it('expires overdue sessions and removes silent-stale markers', async () => {
    const now = new Date();
    const iso = (msAgo: number) => new Date(now.getTime() - msAgo).toISOString();

    // Overdue session with a marker.
    await adminRtdb.ref('liveLocation/ttl-expired').set({
      session: {
        id: 's-exp',
        status: 'active',
        duration: '1h',
        startedAt: iso(2 * 3600_000),
        expiresAt: iso(3600_000),
        stoppedAt: null,
      },
      latest: { latitude: 1, longitude: 1, recordedAt: iso(3600_000), sessionId: 's-exp' },
    });
    // Active session whose client went silent 20 minutes ago.
    await adminRtdb.ref('liveLocation/ttl-silent').set({
      session: {
        id: 's-silent',
        status: 'active',
        duration: '4h',
        startedAt: iso(30 * 60_000),
        expiresAt: new Date(now.getTime() + 3 * 3600_000).toISOString(),
        stoppedAt: null,
      },
      latest: { latitude: 2, longitude: 2, recordedAt: iso(20 * 60_000), sessionId: 's-silent' },
    });
    // Healthy session updated seconds ago.
    await adminRtdb.ref('liveLocation/ttl-healthy').set({
      session: {
        id: 's-ok',
        status: 'active',
        duration: '4h',
        startedAt: iso(60_000),
        expiresAt: new Date(now.getTime() + 3 * 3600_000).toISOString(),
        stoppedAt: null,
      },
      latest: { latitude: 3, longitude: 3, recordedAt: iso(10_000), sessionId: 's-ok' },
    });

    const result = await runLiveCleanup(now);
    expect(result.expiredSessions).toBeGreaterThanOrEqual(1);
    expect(result.removedMarkers).toBeGreaterThanOrEqual(2);

    expect((await adminRtdb.ref('liveLocation/ttl-expired/session/status').get()).val()).toBe(
      'expired',
    );
    expect((await adminRtdb.ref('liveLocation/ttl-expired/latest').get()).exists()).toBe(false);
    expect((await adminRtdb.ref('liveLocation/ttl-silent/latest').get()).exists()).toBe(false);
    expect((await adminRtdb.ref('liveLocation/ttl-silent/session/status').get()).val()).toBe(
      'active',
    );
    expect((await adminRtdb.ref('liveLocation/ttl-healthy/latest').get()).exists()).toBe(true);
  });

  it('does NOT end a stationary convoy session within a realistic window (issue #1)', async () => {
    // Root-cause guard for "the convoy auto-ended after ~20 min while I stood
    // still". The BACKEND never ends a convoy on a short timeout: a convoy-auto
    // session runs a 6h window, and a member standing still (its heartbeat still
    // refreshing the marker within the 15-min stale window) is swept as HEALTHY —
    // status stays active and the marker is kept. The premature end was purely the
    // CLIENT's stationary auto-stop, now suppressed for convoy sessions.
    const now = new Date();
    const iso = (msAgo: number) => new Date(now.getTime() - msAgo).toISOString();
    await adminRtdb.ref('liveLocation/ttl-convoy-parked').set({
      session: {
        id: 's-convoy-parked',
        status: 'active',
        duration: '6h',
        // Started 20 minutes ago and parked ever since — the exact window Seb hit.
        startedAt: iso(20 * 60_000),
        expiresAt: new Date(now.getTime() + 6 * 3600_000 - 20 * 60_000).toISOString(),
        stoppedAt: null,
        convoyAutoStarted: true,
        convoyId: 'convoy-parked',
      },
      // A parked member's 3-min heartbeat keeps the marker inside the 15-min
      // stale window, so it is NOT silent-stale.
      latest: {
        latitude: 4,
        longitude: 4,
        recordedAt: iso(2 * 60_000),
        sessionId: 's-convoy-parked',
      },
    });

    await runLiveCleanup(now);

    // The session is untouched — still active — so the convoy it backs stays live.
    expect(
      (await adminRtdb.ref('liveLocation/ttl-convoy-parked/session/status').get()).val(),
    ).toBe('active');
    // And the marker is kept, so the member stays visible to the convoy.
    expect(
      (await adminRtdb.ref('liveLocation/ttl-convoy-parked/latest').get()).exists(),
    ).toBe(true);
  });
});

describe('convoy run finalize (server-side member-run backstop)', () => {
  // Ended long enough ago that the client's grace window has elapsed.
  const endedLongAgoMs = 10 * 60_000;

  // A unique suffix (time + random) shared by a test's uid AND sessionId, so two
  // tests running in the same millisecond (or in parallel) cannot collide on the
  // shared emulator Firestore.
  const uniqueSuffix = () => `${Date.now()}-${Math.floor(Math.random() * 1e9)}`;

  // A stopped convoy-auto session node, keyed by a unique uid so it does not
  // collide with the shared-Firestore other tests. sessionId is the ride key.
  async function writeEndedConvoySession(
    uid: string,
    sessionId: string,
    now: Date,
    overrides: Record<string, unknown> = {},
  ): Promise<void> {
    await adminRtdb.ref(`liveLocation/${uid}`).set({
      session: {
        id: sessionId,
        status: 'stopped',
        duration: '6h',
        startedAt: new Date(now.getTime() - endedLongAgoMs - 30 * 60_000).toISOString(),
        expiresAt: new Date(now.getTime() + 5 * 3600_000).toISOString(),
        stoppedAt: new Date(now.getTime() - endedLongAgoMs).toISOString(),
        stopReason: 'user_stop',
        convoyAutoStarted: true,
        convoyId: 'conv-fin',
        displayName: 'ConvoyFinDriver',
        mainCar: null,
        ...overrides,
      },
    });
  }

  it('writes a summary-only ride for a member whose app did not save it, and flags the session', async () => {
    const now = new Date();
    const suffix = uniqueSuffix();
    const uid = `convoyfin-unsaved-${suffix}`;
    const sessionId = `sess-${suffix}`;
    await writeEndedConvoySession(uid, sessionId, now);

    const result = await runLiveCleanup(now);
    expect(result.finalizedConvoyRides).toBeGreaterThanOrEqual(1);

    const ride = await adminDb.collection('rides').doc(`${uid}_${sessionId}`).get();
    expect(ride.exists).toBe(true);
    const data = ride.data()!;
    expect(data.userId).toBe(uid);
    expect(data.sourceSessionId).toBe(sessionId);
    // Summary-only: a real positive duration, distance/speed left null (no route).
    expect(data.durationSeconds).toBe(30 * 60);
    expect(data.distanceMeters).toBeNull();
    // The session is marked so the every-5-minute sweep never re-processes it.
    expect(
      (await adminRtdb.ref(`liveLocation/${uid}/session/convoyRideFinalized`).get()).val(),
    ).toBe(true);
  });

  it('does NOT overwrite a ride the client already saved (dedupe by session id)', async () => {
    const now = new Date();
    const suffix = uniqueSuffix();
    const uid = `convoyfin-client-${suffix}`;
    const sessionId = `sess-${suffix}`;
    // The client's richer save landed first, on the SAME ride id.
    await adminDb.collection('rides').doc(`${uid}_${sessionId}`).set({
      userId: uid,
      sourceSessionId: sessionId,
      distanceMeters: 12_345,
      durationSeconds: 1800,
      title: 'Client saved',
    });
    await writeEndedConvoySession(uid, sessionId, now);

    await runLiveCleanup(now);

    const data = (await adminDb.collection('rides').doc(`${uid}_${sessionId}`).get()).data()!;
    // Untouched — the client's route-derived distance and title survive.
    expect(data.distanceMeters).toBe(12_345);
    expect(data.title).toBe('Client saved');
    // Still flagged, so the sweep stops re-checking Firestore for it every run.
    expect(
      (await adminRtdb.ref(`liveLocation/${uid}/session/convoyRideFinalized`).get()).val(),
    ).toBe(true);
  });

  it('holds off within the grace window (a live client gets to save the rich drive)', async () => {
    const now = new Date();
    const suffix = uniqueSuffix();
    const uid = `convoyfin-grace-${suffix}`;
    const sessionId = `sess-${suffix}`;
    // Ended just now — inside the grace window.
    await writeEndedConvoySession(uid, sessionId, now, {
      stoppedAt: new Date(now.getTime() - 1000).toISOString(),
    });

    await runLiveCleanup(now);

    expect((await adminDb.collection('rides').doc(`${uid}_${sessionId}`).get()).exists).toBe(false);
    expect(
      (await adminRtdb.ref(`liveLocation/${uid}/session/convoyRideFinalized`).get()).exists(),
    ).toBe(false);
  });
});

describe('live-location block mirror (blocking-onBlockWrite)', () => {
  it('mirrors a block into liveLocationBlocks and removes it on unblock', async () => {
    const target = await createProvisionedUser('live-block-target');
    await makeMember(target);
    await signInAs(member);

    // blocking.block writes userBlocks/{member}/blocked/{target}; the trigger
    // mirrors it to RTDB so the liveLocation read rule can enforce it.
    await call('blocking-block', { targetUserId: target.uid });
    const mirrored = await pollUntil(async () => {
      const snap = await adminRtdb
        .ref(`liveLocationBlocks/${member.uid}/${target.uid}`)
        .get();
      return snap.val() === true ? true : undefined;
    });
    expect(mirrored).toBe(true);

    // Unblock removes the mirror node.
    await call('blocking-unblock', { targetUserId: target.uid });
    const removed = await pollUntil(async () => {
      const snap = await adminRtdb
        .ref(`liveLocationBlocks/${member.uid}/${target.uid}`)
        .get();
      return snap.exists() ? undefined : true;
    });
    expect(removed).toBe(true);
  });
});
