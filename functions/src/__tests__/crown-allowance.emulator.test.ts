/** Runs only in the parent's integrated emulator suite. No production access. */
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { randomUUID } from 'node:crypto';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { dailyTotalDocId, stockholmDayKey } from '../points/points-economy-core';
import { CROWN_DAILY_ALLOWANCES, crownAllowanceWindow } from '../crownHunt/daily-allowance-core';
import { spawnCollectorDocId, spawnDailyCounterDocId } from '../crownHunt/crown-spawn-core';

let db: (typeof import('../firebase'))['db'];
let credit: (typeof import('../crownHunt/daily-allowance'))['creditCrownPoints'];
let submit: (typeof import('../crownHunt/submitClaim'))['submitClaim'];
let spawn: (typeof import('../crownHunt/claimSpawn'))['claimSpawn'];
let originalFlags: Record<string, unknown> = {};
const flagKeys = ['crownHunt', 'crownHuntSpawn', 'crownHuntPerks', 'crownHuntLiveShareScoring'];

beforeAll(async () => {
  if (!process.env.FIRESTORE_EMULATOR_HOST) process.env.FIRESTORE_EMULATOR_HOST = '127.0.0.1:8080';
  process.env.GCLOUD_PROJECT ??= 'demo-test';
  process.env.FIREBASE_DATABASE_EMULATOR_HOST ??= '127.0.0.1:9000';
  process.env.FIREBASE_CONFIG ??= JSON.stringify({
    projectId: 'demo-test',
    databaseURL: 'https://demo-test-default-rtdb.firebaseio.com',
  });
  ({ db } = await import('../firebase'));
  ({ creditCrownPoints: credit } = await import('../crownHunt/daily-allowance'));
  ({ submitClaim: submit } = await import('../crownHunt/submitClaim'));
  ({ claimSpawn: spawn } = await import('../crownHunt/claimSpawn'));
  originalFlags = (await db.doc('config/featureFlags').get()).data() ?? {};
  await db
    .doc('config/featureFlags')
    .set(
      {
        crownHunt: true,
        crownHuntSpawn: true,
        crownHuntPerks: false,
        crownHuntLiveShareScoring: false,
      },
      { merge: true },
    );
});

afterAll(async () => {
  if (db)
    await db
      .doc('config/featureFlags')
      .set(
        Object.fromEntries(flagKeys.map((key) => [key, originalFlags[key] ?? FieldValue.delete()])),
        { merge: true },
      );
});

function counter(uid: string, now = new Date()) {
  return db
    .collection(CROWN_DAILY_ALLOWANCES)
    .doc(uid)
    .collection('days')
    .doc(crownAllowanceWindow(now).day);
}
async function user(earned = 0) {
  const uid = `allowance-${randomUUID()}`;
  await db
    .doc(`users/${uid}`)
    .set({ role: 'user', activeMember: true, suspended: false, deleted: false });
  await counter(uid).set({ earned });
  return uid;
}
async function tier(uid: string, value: 'plus' | 'supporter' | 'community') {
  await db
    .doc(`subscriptions/${uid}`)
    .set({
      userId: uid,
      tier: value,
      status: 'active',
      entitlement: value === 'community' ? 'none' : 'member_monthly',
    });
}
function award(uid: string, key: string, amount = 500, now = new Date()) {
  return credit(
    {
      targetUid: uid,
      amount,
      source: 'crown_hunt',
      transactionType: 'earn',
      description: 'test crown',
      idempotencyKey: key,
    },
    (tx, result) =>
      tx.set(db.doc(`crownHuntClaims/${key}`), { result: 'awarded', pointsAwarded: result.amount }),
    async () => {},
    now,
  );
}

describe('atomic crown allowance', () => {
  it('serializes competing awards, clips remaining KP, and retries without double counting', async () => {
    const uid = await user(2240);
    const keys = [randomUUID(), randomUUID()];
    const outcomes = await Promise.allSettled(keys.map((key) => award(uid, key)));
    const winner = outcomes.findIndex((result) => result.status === 'fulfilled');
    expect(outcomes.filter((result) => result.status === 'fulfilled')).toHaveLength(1);
    expect(winner).toBeGreaterThanOrEqual(0);
    expect((await award(uid, keys[winner]!)).amount).toBe(10);
    expect((await counter(uid).get()).data()?.earned).toBe(2250);
    expect((await db.doc(`pointsLedger/${uid}`).get()).data()?.balance).toBe(10);
    expect((await db.doc(`crownHuntClaims/${keys[1 - winner]}`).get()).exists).toBe(false);
  });
  it('reads subscription truth, not activeMember; upgrades immediately and never resets/claws back', async () => {
    const uid = await user(2250); // forged/stale activeMember=true cannot grant paid headroom
    const key = randomUUID();
    await expect(award(uid, key)).rejects.toThrow('crown_points_limit_reached');
    await tier(uid, 'plus');
    expect((await award(uid, key, 1000)).amount).toBe(750);
    await tier(uid, 'community');
    await expect(award(uid, randomUUID())).rejects.toThrow('crown_points_limit_reached');
    expect((await counter(uid).get()).data()?.earned).toBe(3000);
    expect((await db.doc(`pointsLedger/${uid}`).get()).data()?.balance).toBe(750);
    const supporter = await user(2998);
    await tier(supporter, 'supporter');
    expect((await award(supporter, randomUUID())).amount).toBe(2);
  });
  it('preserves other-source budgets and seeds rollout-day crown earnings only', async () => {
    const uid = await user();
    await counter(uid).delete();
    for (const [source, type, amount] of [
      ['crown_hunt', 'earn', 2240],
      ['badge', 'earn', 200],
      ['perk_trap', 'earn', 150],
      ['crown_hunt', 'reversal', -100],
    ] as const) {
      await db
        .doc(`pointsLedger/${uid}/entries/${randomUUID()}`)
        .set({ source, transactionType: type, amount, createdAt: Timestamp.now() });
    }
    expect((await award(uid, randomUUID())).amount).toBe(10);
    expect((await counter(uid).get()).data()?.earned).toBe(2250);
    // No synchronous pointsDailyTotals write was added: the pre-existing fold
    // remains responsible for charging actual awards exactly once.
  });
  it('starts a new bucket at Stockholm midnight and keeps yesterday intact', async () => {
    const uid = await user(2250);
    const tomorrow = crownAllowanceWindow(new Date()).resetsAt;
    expect((await award(uid, randomUUID(), 25, tomorrow)).allowance?.remaining).toBe(2225);
    expect((await counter(uid).get()).data()?.earned).toBe(2250);
    expect((await counter(uid, tomorrow).get()).data()?.earned).toBe(25);
  });
  it('keeps the legacy economy fold exactly once without double charging either counter', async () => {
    const uid = await user();
    const paid = await award(uid, randomUUID(), 25);
    const entry = await db.doc(`pointsLedger/${uid}/entries/${paid.entryId}`).get();
    const { onLedgerEntryCreated } = await import('../points/economyTriggers');
    const event = { data: entry, params: { uid, entryId: paid.entryId } };
    await onLedgerEntryCreated.run(event as never);
    await onLedgerEntryCreated.run(event as never);
    const day = stockholmDayKey(entry.data()!.createdAt.toDate());
    expect(
      (await db.doc(`pointsDailyTotals/${dailyTotalDocId(uid, day)}`).get()).data()?.total,
    ).toBe(25);
    expect((await counter(uid).get()).data()?.earned).toBe(25);
  });
  it.each(['suspended', 'deleted'])('rejects a %s account transactionally', async (field) => {
    const uid = await user();
    await db.doc(`users/${uid}`).update({ [field]: true });
    await expect(award(uid, randomUUID())).rejects.toThrow();
    expect((await counter(uid).get()).data()?.earned).toBe(0);
  });
});

describe('both real claim handlers share the allowance', () => {
  const lat = 59.3326,
    lng = 18.0649;
  async function fixtures(earned: number, mode: 'shared' | 'exclusive') {
    const uid = await user(earned);
    const pointId = randomUUID(),
      spawnId = randomUUID();
    const base = { latitude: lat, longitude: lng, rewardPoints: 25, createdAt: Timestamp.now() };
    await db
      .doc(`crownHuntPoints/${pointId}`)
      .set({
        ...base,
        title: 'test',
        status: 'active',
        geofenceRadiusMeters: 50,
        repeatRule: 'once',
        maxCollectors: 1,
        collectorCount: 0,
      });
    await db
      .doc(`crownSpawns/${spawnId}`)
      .set({
        ...base,
        status: 'live',
        collectMode: mode,
        rarity: 'common',
        expiresAt: Timestamp.fromMillis(Date.now() + 3600_000),
      });
    const common = {
      latitude: lat,
      longitude: lng,
      speedMetersPerSecond: 0,
      accuracyMeters: 5,
      recordedAt: new Date().toISOString(),
    };
    const pointInput = { ...common, pointId, idempotencyKey: randomUUID() };
    const spawnInput = {
      ...common,
      spawnId,
      idempotencyKey: randomUUID(),
      previousFix: { ...common, recordedAt: new Date(Date.now() - 6000).toISOString() },
    };
    return { uid, pointId, spawnId, pointInput, spawnInput };
  }
  it.each(['shared', 'exclusive'] as const)(
    'cross-path race clips once and never consumes the losing %s collection',
    async (mode) => {
      const f = await fixtures(2240, mode);
      const invokePoint = () => submit.run({ auth: { uid: f.uid }, data: f.pointInput } as never);
      const invokeSpawn = () => spawn.run({ auth: { uid: f.uid }, data: f.spawnInput } as never);
      const results = await Promise.all([invokePoint(), invokeSpawn()]);
      expect(results.map((r) => r.result).sort()).toEqual(['awarded', 'daily_limit_reached']);
      expect(results.reduce((sum, r) => sum + (r.pointsAwarded ?? 0), 0)).toBe(10);
      const replay = await (results[0].result === 'awarded' ? invokePoint() : invokeSpawn());
      expect(replay.pointsAwarded).toBe(10);
      expect((await counter(f.uid).get()).data()?.earned).toBe(2250);
      if (results[1].result === 'daily_limit_reached') {
        expect((await db.doc(`crownSpawns/${f.spawnId}`).get()).data()?.status).toBe('live');
        expect(
          (await db.doc(`crownSpawnCollectors/${spawnCollectorDocId(f.spawnId, f.uid)}`).get())
            .exists,
        ).toBe(false);
        expect(
          (await db.doc(`crownSpawnDailyClaims/${spawnDailyCounterDocId(f.uid, new Date())}`).get())
            .exists,
        ).toBe(false);
      } else {
        expect((await db.doc(`crownHuntPoints/${f.pointId}`).get()).data()).toMatchObject({
          status: 'active',
          collectorCount: 0,
        });
      }
      await tier(f.uid, 'plus');
      const retried = await (results[0].result === 'daily_limit_reached'
        ? invokePoint()
        : invokeSpawn());
      expect(retried.result).toBe('awarded');
      expect(retried.pointsAwarded).toBe(25);
      expect(retried.allowance?.cap).toBe(3000);
    },
  );
});
