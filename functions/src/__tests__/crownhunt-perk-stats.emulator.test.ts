/**
 * Kronjakt PERK-USAGE STATISTICS — emulator integration tests (admin-stats PR-A).
 *
 * Needs the full Emulator Suite (functions + firestore) and therefore a JDK, so
 * it is excluded from the default `vitest run` and runs behind
 * vitest.emulator.config.ts (pnpm test:emulator under emulators:exec).
 *
 * What it proves end to end (the pure maths is in perk-stats-core.test.ts):
 *  - creating a perkDeploys document increments usedByPerk[perkId] on BOTH the
 *    all-time and the correct YYYY-MM season scope;
 *  - creating a perkDrains document increments trapTriggers on both scopes;
 *  - a perk_shop ledger entry increments purchasedByPerk[perkId] on both scopes;
 *  - the season is derived from each event's createdAt (a fixed historical
 *    instant lands in that month, not the current one);
 *  - exactly-once: re-writing the same source document never double-counts.
 */

process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.GCLOUD_PROJECT ??= 'demo-test';

import { getApps as getAdminApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { Timestamp, getFirestore as getAdminFirestore } from 'firebase-admin/firestore';
import { describe, expect, it } from 'vitest';
import { ALL_TIME_SCOPE, seasonIdForInstant } from '../crownHunt/crown-hunt-stats-core';

const PROJECT_ID = 'demo-test';

const adminApp =
  getAdminApps()[0] ??
  initializeAdminApp({ projectId: PROJECT_ID }, 'crownhunt-perk-stats-emulator');
const adminDb = getAdminFirestore(adminApp);

// Unique suffix so this file's source docs never collide with another file
// sharing the one emulator Firestore.
const TAG = `chps${Date.now()}`;

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

async function perkStats(scope: string): Promise<FirebaseFirestore.DocumentData | undefined> {
  const snap = await adminDb.collection('crownHuntPerkStats').doc(scope).get();
  return snap.exists ? snap.data() : undefined;
}

describe('perk deploy stats (perkDeploys trigger)', () => {
  it('increments usedByPerk[perkId] on the all-time and season scopes', async () => {
    const at = new Date('2024-03-15T12:00:00Z');
    const season = seasonIdForInstant(at); // 2024-03
    const deployId = `${TAG}-deploy-1`;

    await adminDb
      .collection('perkDeploys')
      .doc(deployId)
      .set({
        userId: `${TAG}-u1`,
        perkId: 'shield',
        kind: 'shield',
        effectId: `${TAG}-u1`,
        expiresAt: Timestamp.fromDate(new Date(at.getTime() + 3_600_000)),
        createdAt: Timestamp.fromDate(at),
      });

    const allTime = await pollUntil(async () => {
      const s = await perkStats(ALL_TIME_SCOPE);
      return s && (s.usedByPerk?.shield ?? 0) >= 1 ? s : undefined;
    });
    expect(allTime.usedByPerk?.shield).toBeGreaterThanOrEqual(1);
    // Full fixed-key map present.
    expect(allTime.usedByPerk?.spike_strip).toBeGreaterThanOrEqual(0);
    expect(allTime.usedByPerk?.boost).toBeGreaterThanOrEqual(0);
    expect(allTime.purchasedByPerk?.shield).toBeGreaterThanOrEqual(0);

    const seasonDoc = await pollUntil(async () => {
      const s = await perkStats(season);
      return s && (s.usedByPerk?.shield ?? 0) >= 1 ? s : undefined;
    });
    expect(seasonDoc.scope).toBe(season);
  });

  it('never double-counts a redelivered perkDeploys document (fold marker)', async () => {
    const at = new Date('2024-04-10T12:00:00Z');
    const season = seasonIdForInstant(at); // 2024-04
    const deployId = `${TAG}-deploy-dup`;

    const write = () =>
      adminDb
        .collection('perkDeploys')
        .doc(deployId)
        .set({
          userId: `${TAG}-u2`,
          perkId: 'boost',
          kind: 'boost',
          effectId: `${TAG}-u2`,
          createdAt: Timestamp.fromDate(at),
        });

    await write();
    await pollUntil(async () => {
      const s = await perkStats(season);
      return s && s.usedByPerk?.boost === 1 ? s : undefined;
    });
    // Re-write the SAME id (a redelivery/overwrite). The fold marker keeps it at 1.
    await write();
    await new Promise((r) => setTimeout(r, 2_000));
    const s = await perkStats(season);
    expect(s?.usedByPerk?.boost).toBe(1);
  });
});

describe('trap trigger stats (perkDrains trigger)', () => {
  it('increments trapTriggers on both scopes', async () => {
    const at = new Date('2024-05-20T12:00:00Z');
    const season = seasonIdForInstant(at); // 2024-05
    const drainId = `${TAG}-drain-1`;

    await adminDb
      .collection('perkDrains')
      .doc(drainId)
      .set({
        trapId: `${TAG}-trap`,
        placerUid: `${TAG}-placer`,
        victimUid: `${TAG}-victim`,
        amount: 15,
        drainedAt: Timestamp.fromDate(at),
        createdAt: Timestamp.fromDate(at),
      });

    const seasonDoc = await pollUntil(async () => {
      const s = await perkStats(season);
      return s && (s.trapTriggers ?? 0) >= 1 ? s : undefined;
    });
    expect(seasonDoc.trapTriggers).toBeGreaterThanOrEqual(1);
    const allTime = await pollUntil(async () => {
      const s = await perkStats(ALL_TIME_SCOPE);
      return s && (s.trapTriggers ?? 0) >= 1 ? s : undefined;
    });
    expect(allTime.trapTriggers).toBeGreaterThanOrEqual(1);
  });
});

describe('perk purchase stats (perk_shop ledger branch)', () => {
  it('increments purchasedByPerk[perkId] on both scopes', async () => {
    const at = new Date('2024-06-05T12:00:00Z');
    const season = seasonIdForInstant(at); // 2024-06
    const uid = `${TAG}-buyer`;
    const entryId = `${TAG}-buy-e1`;

    await adminDb
      .collection('pointsLedger')
      .doc(uid)
      .collection('entries')
      .doc(entryId)
      .set({
        transactionType: 'spend',
        source: 'perk_shop',
        amount: -150,
        balanceAfter: 0,
        description: 'Kronjaktsbutik: Spikmatta x1',
        relatedEntityType: 'perk',
        relatedEntityId: 'spike_strip',
        createdAt: Timestamp.fromDate(at),
      });

    const seasonDoc = await pollUntil(async () => {
      const s = await perkStats(season);
      return s && (s.purchasedByPerk?.spike_strip ?? 0) >= 1 ? s : undefined;
    });
    expect(seasonDoc.purchasedByPerk?.spike_strip).toBeGreaterThanOrEqual(1);
    const allTime = await pollUntil(async () => {
      const s = await perkStats(ALL_TIME_SCOPE);
      return s && (s.purchasedByPerk?.spike_strip ?? 0) >= 1 ? s : undefined;
    });
    expect(allTime.purchasedByPerk?.spike_strip).toBeGreaterThanOrEqual(1);
  });

  it('ignores a perk_trap ledger entry (only perk_shop purchases count)', async () => {
    const at = new Date('2024-07-01T12:00:00Z');
    const season = seasonIdForInstant(at); // 2024-07
    const uid = `${TAG}-trapvictim`;
    const entryId = `${TAG}-trap-e1`;

    await adminDb
      .collection('pointsLedger')
      .doc(uid)
      .collection('entries')
      .doc(entryId)
      .set({
        transactionType: 'spend',
        source: 'perk_trap',
        amount: -15,
        balanceAfter: 0,
        description: 'Kronjakt: fångad i en fälla',
        relatedEntityType: 'perk_trap',
        relatedEntityId: `${TAG}-trap`,
        createdAt: Timestamp.fromDate(at),
      });

    // Give the trigger time to (not) fire, then assert no purchase was recorded
    // for this isolated season.
    await new Promise((r) => setTimeout(r, 3_000));
    const s = await perkStats(season);
    // Either the doc was never created for this season, or its purchase counts
    // are all zero — never a spurious count from the perk_trap entry.
    if (s) {
      expect(s.purchasedByPerk?.spike_strip ?? 0).toBe(0);
      expect(s.purchasedByPerk?.shield ?? 0).toBe(0);
      expect(s.purchasedByPerk?.boost ?? 0).toBe(0);
    } else {
      expect(s).toBeUndefined();
    }
  });
});
