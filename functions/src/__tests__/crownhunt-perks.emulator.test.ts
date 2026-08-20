/**
 * Kronjakt SHOP emulator integration tests (Crown Hunt Shop PR1, backend core).
 *
 * Exercises crownHunt.buyPerk (the first member-facing Kronpoäng SINK) and the
 * admin crownHunt.seedPerkCatalog display-mirror writer against the emulator:
 *
 *  - a buy debits KP and increments perkInventory ATOMICALLY;
 *  - an insufficient balance is rejected and leaves the inventory untouched;
 *  - the crownHuntPerks flag OFF rejects every buy;
 *  - an unknown perk and a bad quantity are rejected;
 *  - a replayed idempotency key debits once and grants once;
 *  - a suspended member cannot buy;
 *  - seedPerkCatalog writes config/perkCatalog for admins and rejects members.
 *
 * CI ONLY. Requires the Firebase Emulator Suite (auth + functions + firestore).
 * Run via: pnpm --dir functions emulators:test
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
import { getFirestore as getAdminFirestore, Timestamp } from 'firebase-admin/firestore';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import {
  MAX_PERK_HOLD_PER_PERK,
  PERK_CATALOG,
  PERK_PURCHASE_COOLDOWN_SECONDS,
} from '../crownHunt/perks-core';

const PROJECT_ID = 'demo-test';
const EMULATOR_HOST = '127.0.0.1';
const REGION = 'europe-west1';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'perks-emulator-tests');
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
  return (await callableError(promise)).code;
}

/**
 * The `{ code, reason }` a callable rejection carries, captured from a SINGLE
 * call so the error code and the structured `details.reason` are guaranteed to
 * describe the same rejection (and the emulator is only hit once). The JS SDK
 * throws a `FunctionsError` (a `FirebaseError` subclass) that exposes the
 * callable `details` directly.
 */
async function callableError(
  promise: Promise<unknown>,
): Promise<{ code: string; reason: unknown }> {
  try {
    await promise;
    return { code: 'no-error', reason: undefined };
  } catch (error) {
    if (error instanceof FirebaseError) {
      const details = (error as { details?: unknown }).details;
      return { code: error.code, reason: (details as { reason?: unknown } | undefined)?.reason };
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

async function setPerksFlag(enabled: boolean): Promise<void> {
  await adminDb
    .collection('config')
    .doc('featureFlags')
    .set({ crownHunt: true, crownHuntPerks: enabled }, { merge: true });
}

/** Seeds a member's KP balance directly on the ledger document. */
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

/** Seeds a member's held perk inventory directly. */
async function setInventory(uid: string, counts: Record<string, number>): Promise<void> {
  await adminDb.collection('perkInventory').doc(uid).set(counts, { merge: true });
}

/**
 * Clears the purchase-cooldown stamp so a test's back-to-back buys don't trip the
 * PERK_PURCHASE_COOLDOWN_SECONDS anti-burst window (the emulator runs buys ms
 * apart). The cooldown itself is covered end-to-end by its dedicated test below.
 */
async function clearPurchaseCooldown(uid: string): Promise<void> {
  await adminDb.collection('perkPurchaseCooldowns').doc(uid).delete();
}

interface BuyPerkResponse {
  perkId: string;
  qty: number;
  costKp: number;
  newBalance: number;
  inventoryCount: number;
  alreadyPurchased: boolean;
}

let keyCounter = 0;
function buyInput(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  keyCounter += 1;
  return {
    perkId: 'shield',
    qty: 1,
    idempotencyKey: `buy-${Date.now()}-${keyCounter}`,
    ...overrides,
  };
}

let adminUser: TestUser;
let member: TestUser;

beforeAll(async () => {
  app = initializeApp(
    { projectId: PROJECT_ID, apiKey: 'demo-api-key', appId: 'demo-app-id' },
    'perks-emulator-client',
  );
  auth = getAuth(app);
  connectAuthEmulator(auth, `http://${EMULATOR_HOST}:9099`, { disableWarnings: true });
  functions = getFunctions(app, REGION);
  connectFunctionsEmulator(functions, EMULATOR_HOST, 5001);

  adminUser = await createProvisionedUser('perk-admin');
  await adminAuth.setCustomUserClaims(adminUser.uid, { admin: true });
  await adminDb.collection('users').doc(adminUser.uid).set({ role: 'admin' }, { merge: true });
  member = await createProvisionedUser('perk-member');
  await adminDb.collection('users').doc(member.uid).set({ activeMember: true }, { merge: true });

  await setPerksFlag(true);
}, 120_000);

afterAll(async () => {
  await deleteApp(app);
});

describe('crownHunt.seedPerkCatalog', () => {
  it('requires an admin', async () => {
    await signInAs(member);
    expect(await callableErrorCode(call('crownHunt-seedPerkCatalog', {}))).toBe(
      'functions/permission-denied',
    );
  });

  it('writes the member-readable display mirror from the constants', async () => {
    await signInAs(adminUser);
    const res = (await call('crownHunt-seedPerkCatalog', {})).data as {
      version: number;
      perkCount: number;
    };
    expect(res.perkCount).toBe(3);

    const snap = await adminDb.collection('config').doc('perkCatalog').get();
    const doc = snap.data() as { version: number; perks: Array<{ perkId: string; costKp: number }> };
    expect(doc.perks).toHaveLength(3);
    const shield = doc.perks.find((p) => p.perkId === 'shield');
    expect(shield?.costKp).toBe(PERK_CATALOG.shield.costKp);
    // Effect params never reach the mirror.
    expect(JSON.stringify(doc)).not.toContain('drainKp');
  });
});

describe('crownHunt.buyPerk', () => {
  // Existing cases fire several buys for `member` milliseconds apart; reset both
  // per-member limit surfaces before each so only the dedicated limit tests
  // exercise them: clear the anti-burst purchase cooldown, and clear the accrued
  // inventory so an accumulating hold across cases never trips the per-perk /
  // total-value ceilings under the wrong assertion.
  beforeEach(async () => {
    await clearPurchaseCooldown(member.uid);
    await adminDb.collection('perkInventory').doc(member.uid).delete();
  });

  it('debits KP and increments inventory atomically', async () => {
    await setBalance(member.uid, 500);
    await signInAs(member);
    const res = (await call('crownHunt-buyPerk', buyInput({ perkId: 'boost', qty: 2 }))).data as
      BuyPerkResponse;
    // boost = 120 KP each; 2 => 240 spent; 500 - 240 = 260.
    expect(res.costKp).toBe(240);
    expect(res.newBalance).toBe(260);
    expect(res.inventoryCount).toBe(2);
    expect(res.alreadyPurchased).toBe(false);
    expect(await readBalance(member.uid)).toBe(260);
    expect((await readInventory(member.uid)).boost).toBe(2);
  });

  it('rejects an insufficient balance and leaves the inventory untouched', async () => {
    await setBalance(member.uid, 50); // spike_strip costs 150
    await signInAs(member);
    const before = await readInventory(member.uid);
    // Capture the rejection once so code + structured discriminator describe the
    // same call: the client tells "not enough KP" apart from a shop-unavailable
    // rejection via details.reason, without substring-matching the message.
    const err = await callableError(call('crownHunt-buyPerk', buyInput({ perkId: 'spike_strip' })));
    expect(err.code).toBe('functions/failed-precondition');
    expect(err.reason).toBe('insufficient_funds');
    const after = await readInventory(member.uid);
    expect(after.spike_strip ?? 0).toBe(before.spike_strip ?? 0);
    expect(await readBalance(member.uid)).toBe(50);
  });

  it('is idempotent: a replayed key debits once and grants once', async () => {
    await setBalance(member.uid, 1000);
    await signInAs(member);
    const input = buyInput({ perkId: 'shield' });
    const first = (await call('crownHunt-buyPerk', input)).data as BuyPerkResponse;
    const balanceAfterFirst = await readBalance(member.uid);
    const shieldAfterFirst = (await readInventory(member.uid)).shield;

    const second = (await call('crownHunt-buyPerk', input)).data as BuyPerkResponse;
    expect(second.alreadyPurchased).toBe(true);
    expect(first.alreadyPurchased).toBe(false);
    // No second debit, no second grant.
    expect(await readBalance(member.uid)).toBe(balanceAfterFirst);
    expect((await readInventory(member.uid)).shield).toBe(shieldAfterFirst);
  });

  it('rejects an unknown perk and a bad quantity', async () => {
    await setBalance(member.uid, 1000);
    await signInAs(member);
    const unknownPerk = await callableError(call('crownHunt-buyPerk', buyInput({ perkId: 'nope' })));
    expect(unknownPerk.code).toBe('functions/failed-precondition');
    expect(unknownPerk.reason).toBe('shop_unavailable');
    expect(await callableErrorCode(call('crownHunt-buyPerk', buyInput({ qty: 0 })))).toBe(
      'functions/invalid-argument',
    );
    expect(await callableErrorCode(call('crownHunt-buyPerk', buyInput({ qty: 999 })))).toBe(
      'functions/invalid-argument',
    );
  });

  it('rejects every buy when the flag is off', async () => {
    await setPerksFlag(false);
    await setBalance(member.uid, 1000);
    await signInAs(member);
    try {
      const err = await callableError(call('crownHunt-buyPerk', buyInput()));
      expect(err.code).toBe('functions/failed-precondition');
      expect(err.reason).toBe('shop_unavailable');
    } finally {
      await setPerksFlag(true);
    }
  });

  it('rejects a suspended member', async () => {
    const suspended = await createProvisionedUser('perk-suspended');
    await adminDb
      .collection('users')
      .doc(suspended.uid)
      .set({ activeMember: true, suspended: true }, { merge: true });
    await setBalance(suspended.uid, 1000);
    await signInAs(suspended);
    expect(await callableErrorCode(call('crownHunt-buyPerk', buyInput()))).toBe(
      'functions/failed-precondition',
    );
    expect(await readInventory(suspended.uid)).toEqual({});
  });
});

describe('crownHunt.buyPerk — economy limits (hold cap + purchase cooldown)', () => {
  it('fails closed on the per-perk hold cap and grants nothing', async () => {
    const capped = await createProvisionedUser('perk-holdcap');
    await adminDb.collection('users').doc(capped.uid).set({ activeMember: true }, { merge: true });
    await setBalance(capped.uid, 5000);
    // Already holding the maximum of this perk.
    await setInventory(capped.uid, { shield: MAX_PERK_HOLD_PER_PERK });
    await signInAs(capped);

    const err = await callableError(
      call('crownHunt-buyPerk', buyInput({ perkId: 'shield' })),
    );
    expect(err.code).toBe('functions/failed-precondition');
    expect(err.reason).toBe('hold_cap_reached');
    // No debit, no grant beyond the seeded cap.
    expect((await readInventory(capped.uid)).shield).toBe(MAX_PERK_HOLD_PER_PERK);
    expect(await readBalance(capped.uid)).toBe(5000);
  });

  it('meters purchases: a second buy inside the cooldown window fails closed', async () => {
    const buyer = await createProvisionedUser('perk-cooldown');
    await adminDb.collection('users').doc(buyer.uid).set({ activeMember: true }, { merge: true });
    await setBalance(buyer.uid, 5000);
    await signInAs(buyer);

    const first = (await call('crownHunt-buyPerk', buyInput({ perkId: 'shield' }))).data as
      BuyPerkResponse;
    expect(first.alreadyPurchased).toBe(false);
    expect(PERK_PURCHASE_COOLDOWN_SECONDS).toBeGreaterThan(0);

    // Immediately buy again (a DIFFERENT key, so not an idempotent replay).
    const err = await callableError(call('crownHunt-buyPerk', buyInput({ perkId: 'boost' })));
    expect(err.code).toBe('functions/failed-precondition');
    expect(err.reason).toBe('purchase_cooldown');
    // The second buy granted nothing.
    expect((await readInventory(buyer.uid)).boost ?? 0).toBe(0);
  });

  it('fails CLOSED when the cooldown doc exists but its timestamp is corrupt', async () => {
    const buyer = await createProvisionedUser('perk-cooldown-corrupt');
    await adminDb.collection('users').doc(buyer.uid).set({ activeMember: true }, { merge: true });
    await setBalance(buyer.uid, 5000);
    // A cooldown doc that EXISTS but carries NO lastPurchaseAt — corrupt data must
    // not silently disable the anti-burst control.
    await adminDb
      .collection('perkPurchaseCooldowns')
      .doc(buyer.uid)
      .set({ uid: buyer.uid });
    await signInAs(buyer);

    const err = await callableError(call('crownHunt-buyPerk', buyInput({ perkId: 'shield' })));
    expect(err.code).toBe('functions/failed-precondition');
    expect(err.reason).toBe('purchase_cooldown');
    expect((await readInventory(buyer.uid)).shield ?? 0).toBe(0);
  });

  it('insufficient funds WINS over an active purchase cooldown (right reason)', async () => {
    const broke = await createProvisionedUser('perk-broke-cooldown');
    await adminDb.collection('users').doc(broke.uid).set({ activeMember: true }, { merge: true });
    await setBalance(broke.uid, 0); // cannot afford anything
    // …AND is inside an active cooldown.
    await adminDb
      .collection('perkPurchaseCooldowns')
      .doc(broke.uid)
      .set({ uid: broke.uid, lastPurchaseAt: Timestamp.now() });
    await signInAs(broke);

    const err = await callableError(call('crownHunt-buyPerk', buyInput({ perkId: 'shield' })));
    // The blocking problem is the empty balance, so that reason must win.
    expect(err.code).toBe('functions/failed-precondition');
    expect(err.reason).toBe('insufficient_funds');
  });

  it('insufficient funds WINS over the hold cap (right reason)', async () => {
    const broke = await createProvisionedUser('perk-broke-holdcap');
    await adminDb.collection('users').doc(broke.uid).set({ activeMember: true }, { merge: true });
    await setBalance(broke.uid, 0); // cannot afford anything
    await setInventory(broke.uid, { shield: MAX_PERK_HOLD_PER_PERK }); // …AND at the cap
    await signInAs(broke);

    const err = await callableError(call('crownHunt-buyPerk', buyInput({ perkId: 'shield' })));
    expect(err.code).toBe('functions/failed-precondition');
    expect(err.reason).toBe('insufficient_funds');
  });
});
