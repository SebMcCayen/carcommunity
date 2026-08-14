/**
 * Crown Hunt COLLECT-LAG detector emulator integration test.
 *
 * Drives the exported `runClaimLagDetection` runner directly against the
 * Firestore emulator (mirroring how the spawn tests drive `runCrownSpawnCleanup`
 * against an injected `now`), seeding the per-attempt `crownSpawnClaims` docs the
 * collect path writes and asserting the runner reads them back, matches the
 * retry-lag burst, and files through the shared pipeline WITHOUT THROWING.
 *
 * The exhaustive pattern/bucket/fingerprint/PII assertions live in the db-free
 * unit test (crown-claim-lag-core.test.ts). This test covers only what the unit
 * test cannot: the real Firestore range read, the admin-Timestamp → record
 * mapping, and the fileAutoIssue link transaction end to end. GitHub is never
 * reached from the emulator (createGitHubIssue short-circuits to null under
 * FUNCTIONS_EMULATOR), so a filed cluster resolves to a `failed` (retriable)
 * outcome rather than a real issue — the point here is that the whole pass runs
 * green, not that an issue is created.
 *
 * CI ONLY. Requires the Firestore emulator (a JVM). Run via:
 *   pnpm --dir functions emulators:test
 * Excluded from the default `vitest run` unit suite by vitest.config.ts.
 */

process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.GCLOUD_PROJECT ??= 'demo-test';

import { getApps as getAdminApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getFirestore as getAdminFirestore, Timestamp } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { runClaimLagDetection } from '../crownHunt/claimLagDetector';

const PROJECT_ID = 'demo-test';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'crown-claim-lag-emulator');
const adminDb = getAdminFirestore(adminApp);

// Unique per-file ids so the shared (no-isolation) crownSpawnClaims collection
// cannot cross-contaminate this test, and cleanup targets only our own seeds.
const BURST_UID = 'claimlag-test-uid-burst';
const CONTROL_UID = 'claimlag-test-uid-control';
const BURST_SPAWN = 'claimlag-test-spawn-burst';
const CONTROL_SPAWN = 'claimlag-test-spawn-control';

const now = new Date('2026-08-14T12:30:00.000Z');
const seededDocIds: string[] = [];

async function seedClaim(fields: {
  userId: string;
  spawnId: string;
  result: string;
  distanceMeters: number | null;
  accuracyMeters: number | null;
  createdAtMs: number;
}): Promise<void> {
  const ref = adminDb.collection('crownSpawnClaims').doc();
  await ref.set({
    userId: fields.userId,
    spawnId: fields.spawnId,
    result: fields.result,
    distanceMeters: fields.distanceMeters,
    accuracyMeters: fields.accuracyMeters,
    claimedAt: Timestamp.fromMillis(fields.createdAtMs),
    createdAt: Timestamp.fromMillis(fields.createdAtMs),
  });
  seededDocIds.push(ref.id);
}

beforeAll(async () => {
  const base = now.getTime() - 10 * 60 * 1000; // 10 min ago, inside the scan window
  // A qualifying burst: 3 outside_radius rejections within ~40s that then succeeds.
  await seedClaim({ userId: BURST_UID, spawnId: BURST_SPAWN, result: 'outside_radius', distanceMeters: 82, accuracyMeters: 14, createdAtMs: base });
  await seedClaim({ userId: BURST_UID, spawnId: BURST_SPAWN, result: 'outside_radius', distanceMeters: 82, accuracyMeters: 14, createdAtMs: base + 20_000 });
  await seedClaim({ userId: BURST_UID, spawnId: BURST_SPAWN, result: 'outside_radius', distanceMeters: 82, accuracyMeters: 14, createdAtMs: base + 40_000 });
  await seedClaim({ userId: BURST_UID, spawnId: BURST_SPAWN, result: 'awarded', distanceMeters: 40, accuracyMeters: 12, createdAtMs: base + 60_000 });
  // A control: a single reject for a different member — must NOT match.
  await seedClaim({ userId: CONTROL_UID, spawnId: CONTROL_SPAWN, result: 'outside_radius', distanceMeters: 90, accuracyMeters: 20, createdAtMs: base + 5_000 });
});

afterAll(async () => {
  const batch = adminDb.batch();
  for (const id of seededDocIds) batch.delete(adminDb.collection('crownSpawnClaims').doc(id));
  await batch.commit();
});

describe('runClaimLagDetection (emulator)', () => {
  it('reads the seeded attempt docs and detects the retry-lag burst', async () => {
    // scanHunt:false so the assertion depends only on the crownSpawnClaims we seed.
    const result = await runClaimLagDetection(now, { scanHunt: false, token: 'emulator-test-token' });

    // At least our burst is matched (other test files' single claims do not form
    // a ≥3-in-2-min burst; the collection has no cross-file isolation, so assert
    // a lower bound rather than an exact count).
    expect(result.episodesMatched).toBeGreaterThanOrEqual(1);
    expect(result.clusters).toBeGreaterThanOrEqual(1);
    expect(result.attemptsScanned).toBeGreaterThanOrEqual(4);

    // Every detected cluster received a filing outcome, and the pass never threw
    // (GitHub is unreachable from the emulator, so these resolve to failed —
    // retriable — rather than created).
    expect(result.filed + result.deduped + result.budgetSkipped + result.failed).toBe(
      result.clusters,
    );
  });

  it('stored each attempt with the scalars the detector reads (no coordinates)', async () => {
    const snap = await adminDb
      .collection('crownSpawnClaims')
      .where('userId', '==', BURST_UID)
      .get();
    expect(snap.size).toBe(4);
    for (const doc of snap.docs) {
      const data = doc.data();
      expect(typeof data.result).toBe('string');
      expect(data.createdAt).toBeInstanceOf(Timestamp);
      // The detector never needs (and these seeds never carry) coordinates.
      expect(data.latitude).toBeUndefined();
      expect(data.longitude).toBeUndefined();
    }
  });
});
