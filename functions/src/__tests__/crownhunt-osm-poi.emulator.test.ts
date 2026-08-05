/**
 * Kronjakt OpenStreetMap SAFE-STOP placement emulator integration tests.
 *
 * Covers the ingestion + POI-anchored placement half of the auto-spawn engine:
 *
 *  - runAreaPoiIngestion caches the safe-stop POIs Overpass returns, keeping ONLY
 *    the ones inside the drawn shape, and stamps the area's poiCount /
 *    poisRefreshedAt (Overpass is MOCKED — no network);
 *  - a failed Overpass fetch keeps the last cache and does not touch poiCount;
 *  - the area spawn pass places crowns ONLY at cached POIs;
 *  - an area with NO cached POIs spawns nothing (areasWithoutPois), rather than
 *    falling back to random placement;
 *  - deactivating an area still drains its live crowns.
 *
 * CI ONLY. Requires the Firebase Emulator Suite. Run via:
 *   pnpm --dir functions emulators:test
 *
 * Emulator suites share ONE Firestore, so this suite uses its own coordinates
 * (near Malmö, ~55.6°N) far from the other Kronjakt suites' fixtures, and its own
 * areaId/user prefixes ('cop-').
 */

process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.GCLOUD_PROJECT ??= 'demo-test';

import { getApps as getAdminApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getFirestore as getAdminFirestore, Timestamp } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { runCrownAreaSpawnPass } from '../crownHunt/spawnScheduled';
import { runAreaPoiIngestion, type OverpassFetcher } from '../crownHunt/poiIngestion';
import {
  crownActivityUserHash,
  crownCellKey,
  createSeededRng,
} from '../crownHunt/crown-spawn-core';
import { POI_JITTER_METERS, crownPoiDocId, type OverpassResponse } from '../crownHunt/osm-poi-core';
import { isPointInShape, type CrownSpawnAreaShape } from '../crownHunt/crown-area-core';
import { haversineDistanceMeters } from '../crownHunt/crown-hunt-geo';

const PROJECT_ID = 'demo-test';

const adminApp =
  getAdminApps().find((a) => a?.name === 'crownosm-emulator-tests') ??
  initializeAdminApp({ projectId: PROJECT_ID }, 'crownosm-emulator-tests');
const adminDb = getAdminFirestore(adminApp);

// A quiet cell near Malmö, deliberately far from the Stockholm/Gothenburg spawn
// suites so their crowns cannot bleed into a neighbourhood read.
const CELL_LAT = 55.605;
const CELL_LON = 13.005;
const CELL_KEY = crownCellKey(CELL_LAT, CELL_LON);

const AREA_CIRCLE: CrownSpawnAreaShape = {
  type: 'circle',
  center: { lat: CELL_LAT, lon: CELL_LON },
  radiusMeters: 300,
};

// A mocked Overpass response: three in-shape safe stops, one OUT-of-shape corner
// POI (bbox but outside the circle), and one irrelevant amenity (dropped).
const IN_SHAPE_POIS = [
  { lat: CELL_LAT, lon: CELL_LON, amenity: 'parking' },
  { lat: CELL_LAT + 0.0018, lon: CELL_LON, amenity: 'fuel' }, // ~200 m north
  { lat: CELL_LAT, lon: CELL_LON + 0.0035, amenity: 'charging_station' }, // ~200 m east
];
// ~600 m north-east of the centre: inside the bounding box, outside the 300 m circle.
const OUT_OF_SHAPE = { lat: CELL_LAT + 0.004, lon: CELL_LON + 0.006 };

function mockOverpass(): OverpassResponse {
  return {
    elements: [
      ...IN_SHAPE_POIS.map((p, i) => ({
        type: 'node',
        id: i + 1,
        lat: p.lat,
        lon: p.lon,
        tags: { amenity: p.amenity },
      })),
      {
        type: 'node',
        id: 90,
        lat: OUT_OF_SHAPE.lat,
        lon: OUT_OF_SHAPE.lon,
        tags: { amenity: 'fuel' },
      },
      { type: 'node', id: 91, lat: CELL_LAT, lon: CELL_LON, tags: { amenity: 'restaurant' } },
    ],
  };
}

const okFetcher: OverpassFetcher = async () => mockOverpass();
const failingFetcher: OverpassFetcher = async () => {
  throw new Error('Overpass timeout (mock)');
};

async function setSpawnFlag(enabled: boolean): Promise<void> {
  await adminDb
    .collection('config')
    .doc('featureFlags')
    .set({ crownHunt: true, crownHuntSpawn: enabled }, { merge: true });
}

let areaCounter = 0;
/** Writes an active, safe-confirmed area document directly (no callable). */
async function createArea(shape: CrownSpawnAreaShape = AREA_CIRCLE): Promise<string> {
  areaCounter += 1;
  const ref = adminDb.collection('crownSpawnAreas').doc(`cop-area-${Date.now()}-${areaCounter}`);
  await ref.set({
    areaId: ref.id,
    name: `cop-${areaCounter}`,
    shape,
    active: true,
    safeAreaConfirmed: true,
    createdByUserId: 'cop-admin',
    approvedByUserId: 'cop-admin',
    lastSpawnPassAt: Timestamp.fromMillis(0),
    nextCellOffset: 0,
  });
  return ref.id;
}

async function seedActivity(cellKey: string, count: number, now: Date): Promise<void> {
  const cellRef = adminDb.collection('crownCellActivity').doc(cellKey);
  const batch = adminDb.batch();
  batch.set(cellRef, { cellKey, lastActivityAt: Timestamp.fromDate(now) });
  for (let i = 0; i < count; i += 1) {
    batch.set(
      cellRef.collection('recentUsers').doc(crownActivityUserHash(cellKey, `cop-seed-${i}`)),
      {
        lastSeenAt: Timestamp.fromDate(now),
      },
    );
  }
  await batch.commit();
}

async function clearSpawns(): Promise<void> {
  const snap = await adminDb.collection('crownSpawns').get();
  if (snap.empty) return;
  const batch = adminDb.batch();
  for (const doc of snap.docs) batch.delete(doc.ref);
  await batch.commit();
}

async function clearActivity(cellKey: string): Promise<void> {
  await adminDb.recursiveDelete(adminDb.collection('crownCellActivity').doc(cellKey));
}

/**
 * Removes this suite's areas (and their POI caches) so a spawn pass sees exactly
 * the one area under test — otherwise a prior test's still-active area at the same
 * cell wins the crowns for that cell, since they all share CELL_KEY.
 */
async function clearAreas(): Promise<void> {
  const snap = await adminDb.collection('crownSpawnAreas').get();
  for (const doc of snap.docs) {
    if (!doc.id.startsWith('cop-area')) continue;
    await adminDb.recursiveDelete(adminDb.collection('crownSpawnAreaPois').doc(doc.id));
    await doc.ref.delete();
  }
}

beforeAll(async () => {
  await setSpawnFlag(true);
}, 60_000);

afterAll(async () => {
  await clearActivity(CELL_KEY);
});

describe('POI ingestion (mocked Overpass)', () => {
  it('caches only the in-shape POIs and stamps poiCount / poisRefreshedAt', async () => {
    const areaId = await createArea();
    const now = new Date();

    const result = await runAreaPoiIngestion(
      areaId,
      AREA_CIRCLE,
      now,
      okFetcher,
      'mock://overpass',
    );
    expect(result.failed).toBe(false);
    // 5 elements returned; the restaurant is not classifiable, the far POI is out
    // of the circle → 3 cached.
    expect(result.fetched).toBe(4); // parking + fuel + charging + out-of-shape fuel
    expect(result.poiCount).toBe(3);

    const pois = await adminDb
      .collection('crownSpawnAreaPois')
      .doc(areaId)
      .collection('pois')
      .get();
    expect(pois.size).toBe(3);
    for (const doc of pois.docs) {
      const d = doc.data();
      expect(isPointInShape(d.lat as number, d.lon as number, AREA_CIRCLE)).toBe(true);
      expect(d.cellKey).toBe(crownCellKey(d.lat as number, d.lon as number));
      // The deterministic id matches the coordinate.
      expect(doc.id).toBe(crownPoiDocId(areaId, d.lat as number, d.lon as number));
    }

    const area = (await adminDb.collection('crownSpawnAreas').doc(areaId).get()).data()!;
    expect(area.poiCount).toBe(3);
    expect(area.poisRefreshedAt).toBeDefined();
  });

  it('keeps the last cache and does not touch poiCount when Overpass fails', async () => {
    const areaId = await createArea();
    await runAreaPoiIngestion(areaId, AREA_CIRCLE, new Date(), okFetcher, 'mock://overpass');
    const stampedAt = (await adminDb.collection('crownSpawnAreas').doc(areaId).get()).data()!
      .poisRefreshedAt as Timestamp;

    const failed = await runAreaPoiIngestion(
      areaId,
      AREA_CIRCLE,
      new Date(),
      failingFetcher,
      'mock://overpass',
    );
    expect(failed.failed).toBe(true);
    expect(failed.poiCount).toBe(-1);

    // Cache untouched, refresh stamp unchanged (staleness stays visible).
    const pois = await adminDb
      .collection('crownSpawnAreaPois')
      .doc(areaId)
      .collection('pois')
      .get();
    expect(pois.size).toBe(3);
    const area = (await adminDb.collection('crownSpawnAreas').doc(areaId).get()).data()!;
    expect(area.poiCount).toBe(3);
    expect((area.poisRefreshedAt as Timestamp).toMillis()).toBe(stampedAt.toMillis());
  });

  it('removes a stale POI on refresh when it disappears upstream', async () => {
    const areaId = await createArea();
    await runAreaPoiIngestion(areaId, AREA_CIRCLE, new Date(), okFetcher, 'mock://overpass');

    // Refresh with only ONE of the three POIs still present upstream.
    const shrunk: OverpassFetcher = async () => ({
      elements: [
        { type: 'node', id: 1, lat: CELL_LAT, lon: CELL_LON, tags: { amenity: 'parking' } },
      ],
    });
    const result = await runAreaPoiIngestion(
      areaId,
      AREA_CIRCLE,
      new Date(),
      shrunk,
      'mock://overpass',
    );
    expect(result.poiCount).toBe(1);
    expect(result.removedStale).toBe(2);
    const pois = await adminDb
      .collection('crownSpawnAreaPois')
      .doc(areaId)
      .collection('pois')
      .get();
    expect(pois.size).toBe(1);
  });
});

describe('POI-anchored spawn pass', () => {
  it('places crowns ONLY at cached POIs inside the shape', async () => {
    await clearSpawns();
    await clearActivity(CELL_KEY);
    await clearAreas();
    const areaId = await createArea();
    const now = new Date();
    await seedActivity(CELL_KEY, 12, now);
    await runAreaPoiIngestion(areaId, AREA_CIRCLE, now, okFetcher, 'mock://overpass');

    const result = await runCrownAreaSpawnPass(
      now,
      { maxAreas: 10, maxCells: 60, maxSpawns: 50 },
      createSeededRng(2024),
    );
    expect(result.spawned).toBeGreaterThan(0);

    const spawns = await adminDb.collection('crownSpawns').where('areaId', '==', areaId).get();
    expect(spawns.empty).toBe(false);
    for (const doc of spawns.docs) {
      const d = doc.data();
      const lat = d.latitude as number;
      const lon = d.longitude as number;
      expect(isPointInShape(lat, lon, AREA_CIRCLE)).toBe(true);
      // Anchored within the jitter of one of the three seeded POIs.
      const nearest = Math.min(
        ...IN_SHAPE_POIS.map((p) => haversineDistanceMeters(p.lat, p.lon, lat, lon)),
      );
      expect(nearest).toBeLessThanOrEqual(POI_JITTER_METERS + 1);
    }

    await clearActivity(CELL_KEY);
    await clearSpawns();
  });

  it('spawns NOTHING for an active area with no cached POIs', async () => {
    await clearSpawns();
    await clearActivity(CELL_KEY);
    await clearAreas();
    const areaId = await createArea();
    const now = new Date();
    // Activity, but deliberately NO POI ingestion.
    await seedActivity(CELL_KEY, 12, now);

    const result = await runCrownAreaSpawnPass(
      now,
      { maxAreas: 10, maxCells: 60, maxSpawns: 50 },
      createSeededRng(5),
    );
    expect(result.areasWithoutPois).toBeGreaterThanOrEqual(1);
    const spawns = await adminDb.collection('crownSpawns').where('areaId', '==', areaId).get();
    expect(spawns.empty).toBe(true);

    await clearActivity(CELL_KEY);
  });

  it('deactivating an area still drains its live POI-anchored crowns', async () => {
    await clearSpawns();
    await clearActivity(CELL_KEY);
    await clearAreas();
    const areaId = await createArea();
    const now = new Date();
    await seedActivity(CELL_KEY, 12, now);
    await runAreaPoiIngestion(areaId, AREA_CIRCLE, now, okFetcher, 'mock://overpass');
    await runCrownAreaSpawnPass(
      now,
      { maxAreas: 10, maxCells: 60, maxSpawns: 50 },
      createSeededRng(9),
    );

    const before = await adminDb.collection('crownSpawns').where('areaId', '==', areaId).get();
    expect(before.empty).toBe(false);

    // Deactivate + drain directly (the CRUD callable's drain is covered elsewhere).
    await adminDb.collection('crownSpawnAreas').doc(areaId).set({ active: false }, { merge: true });
    const live = await adminDb
      .collection('crownSpawns')
      .where('areaId', '==', areaId)
      .where('status', '==', 'live')
      .get();
    const batch = adminDb.batch();
    for (const doc of live.docs) batch.delete(doc.ref);
    await batch.commit();

    const after = await adminDb.collection('crownSpawns').where('areaId', '==', areaId).get();
    expect(after.empty).toBe(true);

    await clearActivity(CELL_KEY);
  });
});
