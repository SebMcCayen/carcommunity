/**
 * Community growth metrics snapshot — emulator integration test.
 *
 * Seeds a small, known community (users, convoys, saved drives, events,
 * vehicles across two makes + `other`, crown claims, friendships) directly via
 * the Admin SDK, then drives `runMetricsSnapshot` at a fixed instant and
 * asserts the written `metrics/{date}` document's counts, sum, derived friend
 * connections and brand distribution.
 *
 * `runMetricsSnapshot` uses the Admin SDK only, so no callable/auth client is
 * needed — the onSchedule handler cannot be invoked over the callable protocol
 * anyway.
 *
 * Requires the Firestore emulator — run via:
 *   pnpm emulators:test
 */

process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.GCLOUD_PROJECT ??= 'demo-test';

import { getApps as getAdminApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getFirestore as getAdminFirestore } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { runMetricsSnapshot } from '../metrics/scheduled';
import { snapshotDateId, type MetricsSnapshot } from '../metrics/metrics-core';

const PROJECT_ID = 'demo-test';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'metrics-emulator-tests');
const adminDb = getAdminFirestore(adminApp);

// Unique per-file suffix so seeded ids never collide with other emulator files
// sharing the same Firestore instance.
const S = 'mx';

async function clearCollection(path: string): Promise<void> {
  const snap = await adminDb.collection(path).get();
  await Promise.all(snap.docs.map((d) => d.ref.delete()));
}

async function clearGroup(id: string): Promise<void> {
  const snap = await adminDb.collectionGroup(id).get();
  await Promise.all(snap.docs.map((d) => d.ref.delete()));
}

beforeAll(async () => {
  // Start from a clean slate for the collections this snapshot aggregates, so a
  // shared emulator's leftovers from other files cannot skew the counts.
  await Promise.all([
    clearCollection('users'),
    clearCollection('convoys'),
    clearCollection('rides'),
    clearCollection('events'),
    clearCollection('vehicles'),
    clearCollection('crownHuntClaims'),
    clearGroup('friends'),
  ]);

  // 3 users, with a friendship between two (stored on both sides = 2 edge docs).
  await Promise.all([
    adminDb.collection('users').doc(`${S}-u1`).set({ displayName: `Metrics One ${S}` }),
    adminDb.collection('users').doc(`${S}-u2`).set({ displayName: `Metrics Two ${S}` }),
    adminDb.collection('users').doc(`${S}-u3`).set({ displayName: `Metrics Three ${S}` }),
    adminDb.collection('users').doc(`${S}-u1`).collection('friends').doc(`${S}-u2`).set({}),
    adminDb.collection('users').doc(`${S}-u2`).collection('friends').doc(`${S}-u1`).set({}),
  ]);

  // 2 convoys — one active, one ended (both count toward "created").
  await Promise.all([
    adminDb.collection('convoys').doc(`${S}-c1`).set({ status: 'active' }),
    adminDb.collection('convoys').doc(`${S}-c2`).set({ status: 'ended' }),
  ]);

  // 3 saved drives — total distance 6000 m; one null distance is ignored by sum().
  await Promise.all([
    adminDb.collection('rides').doc(`${S}-r1`).set({ userId: `${S}-u1`, distanceMeters: 1000 }),
    adminDb.collection('rides').doc(`${S}-r2`).set({ userId: `${S}-u2`, distanceMeters: 5000 }),
    adminDb.collection('rides').doc(`${S}-r3`).set({ userId: `${S}-u3`, distanceMeters: null }),
  ]);

  // 2 events.
  await Promise.all([
    adminDb.collection('events').doc(`${S}-e1`).set({ status: 'published' }),
    adminDb.collection('events').doc(`${S}-e2`).set({ status: 'completed' }),
  ]);

  // 4 vehicles: 2 volvo, 1 saab, 1 other.
  await Promise.all([
    adminDb.collection('vehicles').doc(`${S}-v1`).set({ userId: `${S}-u1`, makeId: 'volvo' }),
    adminDb.collection('vehicles').doc(`${S}-v2`).set({ userId: `${S}-u2`, makeId: 'volvo' }),
    adminDb.collection('vehicles').doc(`${S}-v3`).set({ userId: `${S}-u3`, makeId: 'saab' }),
    adminDb.collection('vehicles').doc(`${S}-v4`).set({ userId: `${S}-u1`, makeId: 'other' }),
  ]);

  // 2 crown claims.
  await Promise.all([
    adminDb.collection('crownHuntClaims').doc(`${S}-cl1`).set({ userId: `${S}-u1` }),
    adminDb.collection('crownHuntClaims').doc(`${S}-cl2`).set({ userId: `${S}-u2` }),
  ]);
});

afterAll(async () => {
  await Promise.all([
    clearCollection('users'),
    clearCollection('convoys'),
    clearCollection('rides'),
    clearCollection('events'),
    clearCollection('vehicles'),
    clearCollection('crownHuntClaims'),
    clearGroup('friends'),
    clearCollection('metrics'),
  ]);
});

describe('runMetricsSnapshot', () => {
  // 2026-07-15 12:00 UTC — inside a single Europe/Stockholm day.
  const now = new Date('2026-07-15T12:00:00.000Z');

  it('writes a PII-free aggregate snapshot with correct counts and brand map', async () => {
    const returned = await runMetricsSnapshot(now);

    const stored = (
      await adminDb.collection('metrics').doc(snapshotDateId(now)).get()
    ).data() as MetricsSnapshot;

    for (const snapshot of [returned, stored]) {
      expect(snapshot.date).toBe('2026-07-15');
      expect(snapshot.totalUsers).toBe(3);
      expect(snapshot.convoysCreated).toBe(2);
      expect(snapshot.activeConvoys).toBe(1);
      expect(snapshot.totalDistanceMeters).toBe(6000);
      expect(snapshot.drivesSaved).toBe(3);
      expect(snapshot.eventsHeld).toBe(2);
      expect(snapshot.crownsCollected).toBe(2);
      // 2 edge docs → 1 undirected connection.
      expect(snapshot.friendConnections).toBe(1);
      expect(snapshot.vehicleProfiles).toBe(4);
      expect(snapshot.brandDistribution).toEqual({ volvo: 2, saab: 1, other: 1 });
      // Zero-count makes must not bloat the map.
      expect(snapshot.brandDistribution.bmw).toBeUndefined();
      expect(typeof snapshot.capturedAtMs).toBe('number');
    }
  });

  it('is idempotent — re-running the same day overwrites, never appends', async () => {
    await runMetricsSnapshot(now);
    // Add a fourth user and re-run: the same doc id is overwritten with the new total.
    await adminDb.collection('users').doc(`${S}-u4`).set({ displayName: `Metrics Four ${S}` });
    await runMetricsSnapshot(now);

    const all = await adminDb.collection('metrics').get();
    const forDay = all.docs.filter((d) => d.id === '2026-07-15');
    expect(forDay).toHaveLength(1);
    expect((forDay[0]?.data() as MetricsSnapshot).totalUsers).toBe(4);
  });
});
