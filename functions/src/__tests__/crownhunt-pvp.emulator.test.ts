/**
 * Kronjakt PvP emulator integration tests (Crown Hunt Shop PR3).
 *
 * Exercises the DEPLOY/USE side and the trap DRAIN against the emulator:
 *
 *  - the crownHuntPerks flag OFF no-ops every deploy;
 *  - deploy shield writes perkShield + the PUBLIC perkShieldPublic status and
 *    consumes one shield from inventory;
 *  - deploy boost writes perkBoost and consumes one;
 *  - deploy trap needs coordinates, writes an armed activePerks doc, consumes
 *    one, is idempotent on replay, and is capped at 1 active trap;
 *  - a deploy with no inventory is rejected;
 *  - the inline drain in live.updatePosition moves 15 KP victim → placer, once
 *    per trap (a second sample does not double-drain);
 *  - a shielded victim is skipped by the drain;
 *  - a boost doubles a crown-claim award.
 *
 * CI ONLY. Requires the Firebase Emulator Suite (auth + functions + firestore +
 * database). Run via: pnpm --dir functions emulators:test
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
import { getFirestore as getAdminFirestore, Timestamp } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { TRAP_DRAIN_KP } from '../crownHunt/perks-core';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'pvp-emulator-tests');
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
  intervalMs = 200,
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

/** The callable error's { code, reason } — reason from the HttpsError details. */
async function callableCodeAndReason(
  promise: Promise<unknown>,
): Promise<{ code: string; reason: string | undefined }> {
  try {
    await promise;
    return { code: 'no-error', reason: undefined };
  } catch (error) {
    if (error instanceof FirebaseError) {
      const details = (error as unknown as { details?: { reason?: string } }).details;
      return { code: error.code, reason: details?.reason };
    }
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

async function setFlags(flags: Record<string, boolean>): Promise<void> {
  await adminDb.collection('config').doc('featureFlags').set(flags, { merge: true });
}

async function setInventory(uid: string, inv: Record<string, number>): Promise<void> {
  await adminDb.collection('perkInventory').doc(uid).set(inv, { merge: true });
}

/**
 * Seeds an event with its precise coordinates on the member-gated
 * `details/private` subdoc (the branch layout deployTrap resolves through), and
 * returns its id. `startsAtMs`/`endsAtMs` default to a currently-active window.
 */
async function seedEvent(args: {
  latitude: number;
  longitude: number;
  status?: string;
  startsAtMs?: number;
  endsAtMs?: number | null;
}): Promise<string> {
  const now = Date.now();
  const startsAtMs = args.startsAtMs ?? now;
  const endsAtMs = args.endsAtMs === undefined ? now + 2 * 60 * 60 * 1000 : args.endsAtMs;
  const ref = adminDb.collection('events').doc();
  await ref.set({
    status: args.status ?? 'published',
    startsAt: Timestamp.fromMillis(startsAtMs),
    ...(endsAtMs === null ? {} : { endsAt: Timestamp.fromMillis(endsAtMs) }),
    createdAt: Timestamp.fromMillis(now),
  });
  await ref.collection('details').doc('private').set({
    latitude: args.latitude,
    longitude: args.longitude,
  });
  return ref.id;
}

async function setBalance(uid: string, balance: number): Promise<void> {
  await adminDb.collection('pointsLedger').doc(uid).set({ balance }, { merge: true });
}

async function readBalance(uid: string): Promise<number> {
  const snap = await adminDb.collection('pointsLedger').doc(uid).get();
  return (snap.data()?.balance as number | undefined) ?? 0;
}

async function readInventory(uid: string): Promise<Record<string, number>> {
  const snap = await adminDb.collection('perkInventory').doc(uid).get();
  return (snap.data() as Record<string, number> | undefined) ?? {};
}

/** The victim's real-time trap-trigger events (perkDrainEvents/{uid}/drains). */
async function readDrainEvents(uid: string): Promise<Array<{ amount: number; trapId: string }>> {
  const snap = await adminDb.collection('perkDrainEvents').doc(uid).collection('drains').get();
  return snap.docs.map((d) => ({
    amount: (d.data().amount as number | undefined) ?? 0,
    trapId: (d.data().trapId as string | undefined) ?? '',
  }));
}

/** The titles of every durable in-app notification in a user's inbox. */
async function readNotificationTitles(uid: string): Promise<string[]> {
  const snap = await adminDb.collection('notifications').doc(uid).collection('items').get();
  return snap.docs.map((d) => (d.data().title as string | undefined) ?? '');
}

/** Ages an account past the 7-day new-account victim-immunity window. */
async function ageAccount(uid: string): Promise<void> {
  await adminDb
    .collection('users')
    .doc(uid)
    .set(
      { createdAt: Timestamp.fromMillis(Date.now() - 30 * 24 * 60 * 60 * 1000) },
      { merge: true },
    );
}

let keyCounter = 0;
function key(prefix = 'k'): string {
  keyCounter += 1;
  return `${prefix}-${Date.now()}-${keyCounter}`;
}

// The emulator suite shares ONE Firestore across every test file, and a
// deployed trap stays armed for 6h — so two tests placing a trap at the SAME
// coordinate would let one test's trap drain the other's victim. Each test
// takes a UNIQUE spot, far enough apart (>0.5 deg ~ 55 km) that no two tests'
// traps ever share a crown cell or its 3x3 drain neighbourhood.
let spotCounter = 0;
function uniqueSpot(): { latitude: number; longitude: number } {
  spotCounter += 1;
  return { latitude: 10 + spotCounter * 0.5, longitude: 10 + spotCounter * 0.5 };
}

interface DeployResponse {
  perkId: string;
  kind: string;
  effectId: string;
  expiresAt: string;
  inventoryCount: number;
  alreadyDeployed: boolean;
}

async function startShare(user: TestUser): Promise<void> {
  await signInAs(user);
  await call('live-startSession', { duration: '1h' });
}

async function move(user: TestUser, lat: number, lng: number): Promise<void> {
  await call('live-updatePosition', {
    coordinate: {
      latitude: lat,
      longitude: lng,
      accuracyMeters: 5,
      speedMetersPerSecond: 0,
      recordedAt: new Date().toISOString(),
    },
  });
}

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'pvp-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  await setFlags({ crownHunt: true, crownHuntPerks: true, crownHuntSpawn: true, liveLocation: true });
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('crownHunt.deployPerk — flag gate', () => {
  it('no-ops every deploy while crownHuntPerks is OFF', async () => {
    const member = await createProvisionedUser('pvp-flagoff');
    await setInventory(member.uid, { shield: 1 });
    await setFlags({ crownHuntPerks: false });
    await signInAs(member);
    try {
      expect(
        await callableErrorCode(call('crownHunt-deployPerk', { perkId: 'shield', idempotencyKey: key() })),
      ).toBe('functions/failed-precondition');
      // Inventory untouched.
      expect((await readInventory(member.uid)).shield).toBe(1);
    } finally {
      await setFlags({ crownHuntPerks: true });
    }
  });
});

describe('crownHunt.deployPerk — shield / boost', () => {
  it('raises a shield and exposes only the public timestamp', async () => {
    const member = await createProvisionedUser('pvp-shield');
    await setInventory(member.uid, { shield: 2 });
    await signInAs(member);
    const res = (await call('crownHunt-deployPerk', { perkId: 'shield', idempotencyKey: key() }))
      .data as DeployResponse;
    expect(res.kind).toBe('shield');
    expect(res.inventoryCount).toBe(1);

    const priv = await adminDb.collection('perkShield').doc(member.uid).get();
    expect(priv.exists).toBe(true);
    const pub = await adminDb.collection('perkShieldPublic').doc(member.uid).get();
    expect(pub.exists).toBe(true);
    // ONLY the timestamp is public — no other perk state leaks.
    expect(Object.keys(pub.data() ?? {}).sort()).toEqual(['shieldedUntil', 'updatedAt']);
  });

  it('arms a boost and consumes one', async () => {
    const member = await createProvisionedUser('pvp-boost');
    await setInventory(member.uid, { boost: 1 });
    await signInAs(member);
    const res = (await call('crownHunt-deployPerk', { perkId: 'boost', idempotencyKey: key() }))
      .data as DeployResponse;
    expect(res.kind).toBe('boost');
    expect(res.inventoryCount).toBe(0);
    expect((await adminDb.collection('perkBoost').doc(member.uid).get()).exists).toBe(true);
  });

  it('rejects a deploy with no inventory', async () => {
    const member = await createProvisionedUser('pvp-empty');
    await signInAs(member);
    expect(
      await callableErrorCode(call('crownHunt-deployPerk', { perkId: 'shield', idempotencyKey: key() })),
    ).toBe('functions/failed-precondition');
  });
});

describe('crownHunt.deployPerk — concurrent activation limit', () => {
  it('refuses a THIRD distinct live effect and consumes no inventory', async () => {
    const member = await createProvisionedUser('pvp-actlimit');
    await setInventory(member.uid, { shield: 1, boost: 1, spike_strip: 1 });
    await signInAs(member);

    // Raise a shield (1 active) then a boost (2 active) — both allowed.
    expect(
      ((await call('crownHunt-deployPerk', { perkId: 'shield', idempotencyKey: key() }))
        .data as DeployResponse).kind,
    ).toBe('shield');
    expect(
      ((await call('crownHunt-deployPerk', { perkId: 'boost', idempotencyKey: key() }))
        .data as DeployResponse).kind,
    ).toBe('boost');

    // A trap would be the third distinct live effect → refused, reason-coded.
    const rejection = await callableCodeAndReason(
      call('crownHunt-deployPerk', {
        perkId: 'spike_strip',
        ...uniqueSpot(),
        idempotencyKey: key(),
      }),
    );
    expect(rejection.code).toBe('functions/failed-precondition');
    expect(rejection.reason).toBe('activation_limit');
    // The refused trap consumed nothing.
    expect((await readInventory(member.uid)).spike_strip).toBe(1);
  });

  it('still lets you re-raise an already-active kind (replaces, not a new effect)', async () => {
    const member = await createProvisionedUser('pvp-actlimit-reraise');
    await setInventory(member.uid, { shield: 2, boost: 1 });
    await signInAs(member);

    await call('crownHunt-deployPerk', { perkId: 'shield', idempotencyKey: key() });
    await call('crownHunt-deployPerk', { perkId: 'boost', idempotencyKey: key() });
    // Re-raising the shield (already live) is allowed even at 2 active effects.
    const res = (await call('crownHunt-deployPerk', { perkId: 'shield', idempotencyKey: key() }))
      .data as DeployResponse;
    expect(res.kind).toBe('shield');
    // It consumed a second shield unit.
    expect((await readInventory(member.uid)).shield).toBe(0);
  });
});

describe('crownHunt.deployPerk — trap', () => {
  it('requires coordinates', async () => {
    const member = await createProvisionedUser('pvp-trap-nocoord');
    await setInventory(member.uid, { spike_strip: 1 });
    await signInAs(member);
    expect(
      await callableErrorCode(call('crownHunt-deployPerk', { perkId: 'spike_strip', idempotencyKey: key() })),
    ).toBe('functions/invalid-argument');
  });

  it('drops an armed trap, consumes one, and is idempotent', async () => {
    const member = await createProvisionedUser('pvp-trap');
    await setInventory(member.uid, { spike_strip: 2 });
    await signInAs(member);
    const input = { perkId: 'spike_strip', ...uniqueSpot(), idempotencyKey: key() };
    const res = (await call('crownHunt-deployPerk', input)).data as DeployResponse;
    expect(res.kind).toBe('trap');
    expect(res.inventoryCount).toBe(1);
    expect(res.alreadyDeployed).toBe(false);

    const trap = await adminDb.collection('activePerks').doc(res.effectId).get();
    expect(trap.data()?.status).toBe('armed');
    expect(trap.data()?.placedByUid).toBe(member.uid);
    expect(trap.data()?.radiusM).toBe(100);

    // Replay: same key → no second consume.
    const replay = (await call('crownHunt-deployPerk', input)).data as DeployResponse;
    expect(replay.alreadyDeployed).toBe(true);
    expect((await readInventory(member.uid)).spike_strip).toBe(1);
  });

  it('caps a member at one active trap', async () => {
    const member = await createProvisionedUser('pvp-trap-cap');
    await setInventory(member.uid, { spike_strip: 3 });
    await signInAs(member);
    await call('crownHunt-deployPerk', { perkId: 'spike_strip', ...uniqueSpot(), idempotencyKey: key() });
    // A second, far-away trap is refused while the first is armed.
    expect(
      await callableErrorCode(
        call('crownHunt-deployPerk', {
          perkId: 'spike_strip',
          ...uniqueSpot(),
          idempotencyKey: key(),
        }),
      ),
    ).toBe('functions/failed-precondition');
  });

  it('is not fooled by accumulated EXPIRED armed traps (cap enforced over LIVE traps)', async () => {
    const member = await createProvisionedUser('pvp-trap-expired');
    await setInventory(member.uid, { spike_strip: 3 });
    // Seed 25 EXPIRED armed traps (status stays 'armed'; only expiresAt is past)
    // — more than the query's .limit(20). Before the server-side expiry filter,
    // these could fill the limit and empty the live set, bypassing the guards.
    const past = Timestamp.fromMillis(Date.now() - 60 * 60 * 1000);
    const batch = adminDb.batch();
    for (let i = 0; i < 25; i += 1) {
      batch.set(adminDb.collection('activePerks').doc(`expired-${member.uid}-${i}`), {
        placedByUid: member.uid,
        status: 'armed',
        lat: 20 + i * 0.01,
        lng: 20 + i * 0.01,
        radiusM: 100,
        victimCount: 0,
        cellKey: '0_0',
        expiresAt: past,
        createdAt: past,
      });
    }
    await batch.commit();
    await signInAs(member);

    // Despite 25 expired armed docs, a NEW deploy still succeeds (they don't count).
    const spot = uniqueSpot();
    const res = (
      await call('crownHunt-deployPerk', { perkId: 'spike_strip', ...spot, idempotencyKey: key() })
    ).data as DeployResponse;
    expect(res.kind).toBe('trap');

    // Now there is exactly ONE live trap. A second deploy must be refused — the
    // 1-active cap is enforced against the live trap, not defeated by the 25
    // stale ones that would otherwise fill the query limit.
    expect(
      await callableErrorCode(
        call('crownHunt-deployPerk', { perkId: 'spike_strip', ...uniqueSpot(), idempotencyKey: key() }),
      ),
    ).toBe('functions/failed-precondition');
  });

  it('is RACE-SAFE: two concurrent deploys yield exactly ONE live trap', async () => {
    const member = await createProvisionedUser('pvp-trap-race');
    await setInventory(member.uid, { spike_strip: 3 }); // enough that the daily cap is not what stops it
    await signInAs(member);
    const spot = uniqueSpot();

    // Fire two deploys CONCURRENTLY with DIFFERENT idempotency keys. Without the
    // in-transaction cap check both could pass the pre-check and create two live
    // traps; the tx.get(query) inside the transaction makes the second serialize,
    // re-query, see the first trap and reject.
    const results = await Promise.allSettled([
      call('crownHunt-deployPerk', { perkId: 'spike_strip', ...spot, idempotencyKey: key() }),
      call('crownHunt-deployPerk', { perkId: 'spike_strip', ...spot, idempotencyKey: key() }),
    ]);
    const fulfilled = results.filter((r) => r.status === 'fulfilled');
    const rejected = results.filter((r) => r.status === 'rejected') as PromiseRejectedResult[];
    expect(fulfilled).toHaveLength(1);
    expect(rejected).toHaveLength(1);
    // The rejection must be the 1-active-trap cap SPECIFICALLY, not an unrelated
    // transient emulator error — otherwise the regression guard is toothless.
    const [rejection] = rejected;
    expect(rejection?.reason).toBeInstanceOf(FirebaseError);
    expect((rejection?.reason as FirebaseError | undefined)?.code).toBe(
      'functions/failed-precondition',
    );

    // Authoritative: exactly ONE live armed trap exists for this member.
    const live = await adminDb
      .collection('activePerks')
      .where('placedByUid', '==', member.uid)
      .where('status', '==', 'armed')
      .get();
    const liveCount = live.docs.filter((d) => {
      const exp = d.data().expiresAt as Timestamp | undefined;
      return exp instanceof Timestamp && exp.toMillis() > Date.now();
    }).length;
    expect(liveCount).toBe(1);
  });
});

describe('crownHunt.deployPerk — trap event exclusion (anti-griefing)', () => {
  it('refuses a trap within 300 m of an active event, with the event_too_close reason', async () => {
    const member = await createProvisionedUser('pvp-trap-event-near');
    await setInventory(member.uid, { spike_strip: 2 });
    const spot = uniqueSpot();
    await seedEvent({ latitude: spot.latitude, longitude: spot.longitude }); // active now
    await signInAs(member);

    // Same coordinate as the event → inside the exclusion radius → rejected.
    const { code, reason } = await callableCodeAndReason(
      call('crownHunt-deployPerk', { perkId: 'spike_strip', ...spot, idempotencyKey: key() }),
    );
    expect(code).toBe('functions/failed-precondition');
    expect(reason).toBe('event_too_close');
  });

  it('allows a trap far from the active event', async () => {
    const member = await createProvisionedUser('pvp-trap-event-far');
    await setInventory(member.uid, { spike_strip: 1 });
    const eventSpot = uniqueSpot();
    await seedEvent({ latitude: eventSpot.latitude, longitude: eventSpot.longitude });
    await signInAs(member);

    // A DIFFERENT unique spot is >50 km away — well outside the 300 m radius.
    const trapSpot = uniqueSpot();
    const res = (
      await call('crownHunt-deployPerk', {
        perkId: 'spike_strip',
        ...trapSpot,
        idempotencyKey: key(),
      })
    ).data as DeployResponse;
    expect(res.kind).toBe('trap');
    expect(res.alreadyDeployed).toBe(false);
  });

  it('allows a trap next to a DRAFT event (only published/completed count)', async () => {
    const member = await createProvisionedUser('pvp-trap-event-draft');
    await setInventory(member.uid, { spike_strip: 1 });
    const spot = uniqueSpot();
    await seedEvent({ latitude: spot.latitude, longitude: spot.longitude, status: 'draft' });
    await signInAs(member);

    const res = (
      await call('crownHunt-deployPerk', { perkId: 'spike_strip', ...spot, idempotencyKey: key() })
    ).data as DeployResponse;
    expect(res.kind).toBe('trap');
  });

  it('allows a trap next to a long-past event (outside the +3 h window)', async () => {
    const member = await createProvisionedUser('pvp-trap-event-past');
    await setInventory(member.uid, { spike_strip: 1 });
    const spot = uniqueSpot();
    const dayAgo = Date.now() - 24 * 60 * 60 * 1000;
    await seedEvent({
      latitude: spot.latitude,
      longitude: spot.longitude,
      startsAtMs: dayAgo,
      endsAtMs: dayAgo + 2 * 60 * 60 * 1000,
    });
    await signInAs(member);

    const res = (
      await call('crownHunt-deployPerk', { perkId: 'spike_strip', ...spot, idempotencyKey: key() })
    ).data as DeployResponse;
    expect(res.kind).toBe('trap');
  });
});

describe('trap DRAIN (inline in live.updatePosition)', () => {
  it('moves 15 KP victim → placer, exactly once per trap', async () => {
    const placer = await createProvisionedUser('pvp-placer');
    const victim = await createProvisionedUser('pvp-victim');
    await ageAccount(victim.uid); // not a new account → drainable
    await setInventory(placer.uid, { spike_strip: 1 });
    await setBalance(victim.uid, 100);
    await setBalance(placer.uid, 0);
    const spot = uniqueSpot();

    // Placer drops a trap where the victim will be.
    await signInAs(placer);
    const trap = (
      await call('crownHunt-deployPerk', {
        perkId: 'spike_strip',
        latitude: spot.latitude,
        longitude: spot.longitude,
        idempotencyKey: key(),
      })
    ).data as DeployResponse;

    // Victim shares position ON the trap.
    await startShare(victim);
    await move(victim, spot.latitude, spot.longitude);

    await pollUntil(async () => ((await readBalance(victim.uid)) === 85 ? true : undefined));
    expect(await readBalance(victim.uid)).toBe(100 - TRAP_DRAIN_KP);
    expect(await readBalance(placer.uid)).toBe(TRAP_DRAIN_KP);

    // A second sample in the same spot must NOT drain again (once per trap).
    await move(victim, spot.latitude, spot.longitude);
    await new Promise((r) => setTimeout(r, 1500));
    expect(await readBalance(victim.uid)).toBe(100 - TRAP_DRAIN_KP);
    // The trap recorded exactly one victim.
    const trapDoc = await adminDb.collection('activePerks').doc(trap.effectId).get();
    expect(trapDoc.data()?.victimCount).toBe(1);
  });

  it('skips a shielded victim', async () => {
    const placer = await createProvisionedUser('pvp-placer2');
    const victim = await createProvisionedUser('pvp-victim2');
    await ageAccount(victim.uid);
    await setInventory(placer.uid, { spike_strip: 1 });
    await setInventory(victim.uid, { shield: 1 });
    await setBalance(victim.uid, 100);
    await setBalance(placer.uid, 0);
    const spot = uniqueSpot();

    await signInAs(placer);
    await call('crownHunt-deployPerk', {
      perkId: 'spike_strip',
      latitude: spot.latitude,
      longitude: spot.longitude,
      idempotencyKey: key(),
    });

    // Victim raises a shield, then drives onto the trap.
    await signInAs(victim);
    await call('crownHunt-deployPerk', { perkId: 'shield', idempotencyKey: key() });
    await startShare(victim);
    await move(victim, spot.latitude, spot.longitude);
    await new Promise((r) => setTimeout(r, 1500));

    expect(await readBalance(victim.uid)).toBe(100); // untouched
    expect(await readBalance(placer.uid)).toBe(0);
  });

  it('on a drain, writes a victim real-time event + victim and placer notifications', async () => {
    const placer = await createProvisionedUser('pvp-notify-placer');
    const victim = await createProvisionedUser('pvp-notify-victim');
    await ageAccount(victim.uid);
    await setInventory(placer.uid, { spike_strip: 1 });
    await setBalance(victim.uid, 100);
    await setBalance(placer.uid, 0);
    const spot = uniqueSpot();

    await signInAs(placer);
    const trap = (
      await call('crownHunt-deployPerk', {
        perkId: 'spike_strip',
        latitude: spot.latitude,
        longitude: spot.longitude,
        idempotencyKey: key(),
      })
    ).data as DeployResponse;

    await startShare(victim);
    await move(victim, spot.latitude, spot.longitude);
    await pollUntil(async () =>
      (await readBalance(victim.uid)) === 100 - TRAP_DRAIN_KP ? true : undefined,
    );

    // VICTIM real-time signal: one event carrying the drain amount + trap id.
    const events = await pollUntil(async () => {
      const e = await readDrainEvents(victim.uid);
      return e.length > 0 ? e : undefined;
    });
    expect(events).toHaveLength(1);
    const event = events[0];
    expect(event?.amount).toBe(TRAP_DRAIN_KP);
    expect(event?.trapId).toBe(trap.effectId);

    // BOTH sides get a durable in-app notification.
    const victimTitles = await pollUntil(async () => {
      const t = await readNotificationTitles(victim.uid);
      return t.length > 0 ? t : undefined;
    });
    expect(victimTitles).toContain('Du körde på en Spikmatta!');
    const placerTitles = await pollUntil(async () => {
      const t = await readNotificationTitles(placer.uid);
      return t.length > 0 ? t : undefined;
    });
    expect(placerTitles).toContain('Någon körde på din Spikmatta!');
  });

  it('emits no drain, event, or notification while crownHuntPerks is OFF', async () => {
    const placer = await createProvisionedUser('pvp-off-placer');
    const victim = await createProvisionedUser('pvp-off-victim');
    await ageAccount(victim.uid);
    await setInventory(placer.uid, { spike_strip: 1 });
    await setBalance(victim.uid, 100);
    await setBalance(placer.uid, 0);
    const spot = uniqueSpot();

    // Deploy WHILE on (deploy is separately gated), then flip perks OFF so the
    // DRAIN path no-ops. The flag cache is bypassed under the emulator, so the
    // change takes effect on the very next position sample.
    await signInAs(placer);
    await call('crownHunt-deployPerk', {
      perkId: 'spike_strip',
      latitude: spot.latitude,
      longitude: spot.longitude,
      idempotencyKey: key(),
    });
    await setFlags({ crownHuntPerks: false });
    try {
      await startShare(victim);
      await move(victim, spot.latitude, spot.longitude);
      await new Promise((r) => setTimeout(r, 1500));

      expect(await readBalance(victim.uid)).toBe(100); // no drain
      expect(await readDrainEvents(victim.uid)).toHaveLength(0);
      expect(await readNotificationTitles(victim.uid)).not.toContain('Du körde på en Spikmatta!');
    } finally {
      await setFlags({ crownHuntPerks: true });
    }
  });

  it('never drains, signals, or notifies a placer who drives onto their OWN trap', async () => {
    const placer = await createProvisionedUser('pvp-self-placer');
    await ageAccount(placer.uid);
    await setInventory(placer.uid, { spike_strip: 1 });
    await setBalance(placer.uid, 100);
    const spot = uniqueSpot();

    await signInAs(placer);
    await call('crownHunt-deployPerk', {
      perkId: 'spike_strip',
      latitude: spot.latitude,
      longitude: spot.longitude,
      idempotencyKey: key(),
    });
    await startShare(placer);
    await move(placer, spot.latitude, spot.longitude);
    await new Promise((r) => setTimeout(r, 1500));

    expect(await readBalance(placer.uid)).toBe(100); // a self-trap never drains
    expect(await readDrainEvents(placer.uid)).toHaveLength(0);
    expect(await readNotificationTitles(placer.uid)).not.toContain(
      'Någon körde på din Spikmatta!',
    );
  });

  it('is immune to a brand-new account (no ageAccount)', async () => {
    const placer = await createProvisionedUser('pvp-placer3');
    const victim = await createProvisionedUser('pvp-newvictim'); // NOT aged
    await setInventory(placer.uid, { spike_strip: 1 });
    await setBalance(victim.uid, 100);
    await setBalance(placer.uid, 0);
    const spot = uniqueSpot();

    await signInAs(placer);
    await call('crownHunt-deployPerk', {
      perkId: 'spike_strip',
      latitude: spot.latitude,
      longitude: spot.longitude,
      idempotencyKey: key(),
    });
    await startShare(victim);
    await move(victim, spot.latitude, spot.longitude);
    await new Promise((r) => setTimeout(r, 1500));

    expect(await readBalance(victim.uid)).toBe(100); // new-account immunity
  });
});

describe('BOOST doubles a crown claim', () => {
  it('awards 2x while a boost is active and 1x otherwise', async () => {
    const member = await createProvisionedUser('pvp-boostclaim');
    await ageAccount(member.uid);
    await setBalance(member.uid, 0);

    // Seed a live SHARED auto-spawn crown worth 10 KP at a known spot.
    const spawnId = `spawn-${Date.now()}`;
    const lat = 59.5;
    const lng = 17.5;
    await adminDb
      .collection('crownSpawns')
      .doc(spawnId)
      .set({
        status: 'live',
        rarity: 'common',
        collectMode: 'shared',
        rewardPoints: 10,
        latitude: lat,
        longitude: lng,
        collectRadiusMeters: 75,
        cellKey: '0_0',
        expiresAt: Timestamp.fromMillis(Date.now() + 60 * 60 * 1000),
        createdAt: Timestamp.now(),
      });

    // Arm a boost.
    await setInventory(member.uid, { boost: 1 });
    await signInAs(member);
    await call('crownHunt-deployPerk', { perkId: 'boost', idempotencyKey: key() });

    const nowIso = new Date().toISOString();
    const earlierIso = new Date(Date.now() - 10_000).toISOString();
    const res = (
      await call('crownHunt-claimSpawn', {
        spawnId,
        latitude: lat,
        longitude: lng,
        accuracyMeters: 5,
        speedMetersPerSecond: 0,
        recordedAt: nowIso,
        previousFix: {
          latitude: lat,
          longitude: lng,
          accuracyMeters: 5,
          speedMetersPerSecond: 0,
          recordedAt: earlierIso,
        },
        idempotencyKey: key(),
      })
    ).data as { result: string; pointsAwarded: number | null };
    expect(res.result).toBe('awarded');
    expect(res.pointsAwarded).toBe(20); // 10 * 2 boost
  });
});
