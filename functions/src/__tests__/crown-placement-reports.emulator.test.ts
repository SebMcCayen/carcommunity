import { deleteApp, initializeApp, type FirebaseApp } from 'firebase/app';
import { connectAuthEmulator, getAuth, signInWithEmailAndPassword } from 'firebase/auth';
import {
  connectFunctionsEmulator,
  getFunctions,
  httpsCallable,
  type Functions,
} from 'firebase/functions';
import { Timestamp } from 'firebase-admin/firestore';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { adminAuth, db } from '../firebase';
import { crownCellKey } from '../crownHunt/crown-spawn-core';
import { runCrownAreaSpawnPass } from '../crownHunt/spawnScheduled';

interface Case {
  caseId: string;
  crownId: string;
  version: number;
  reportCount: number;
  decision: string | null;
}

const run = Date.now().toString();
const old = { latitude: 58.405, longitude: 15.605 };
const alternate = { latitude: 58.407, longitude: 15.605 };
const areaId = `placement-${run}`;
const caseData = (entry: Case) => ({ caseId: entry.caseId, version: entry.version });
const clients: FirebaseApp[] = [];
let member: Functions;
let second: Functions;
let admin: Functions;

async function actor(role: 'member' | 'admin', name: string) {
  const email = `placement-${name}-${run}@example.com`;
  const password = 'placement-test-password';
  const { uid } = await adminAuth.createUser({ email, password });
  if (role === 'admin') await adminAuth.setCustomUserClaims(uid, { admin: true });
  await db.collection('users').doc(uid).set({ role, status: 'active' }, { merge: true });
  const app = initializeApp({ projectId: 'demo-test', apiKey: 'fake' }, email);
  clients.push(app);
  const auth = getAuth(app);
  connectAuthEmulator(auth, 'http://127.0.0.1:9099', { disableWarnings: true });
  await signInWithEmailAndPassword(auth, email, password);
  const functions = getFunctions(app, 'europe-west1');
  connectFunctionsEmulator(functions, '127.0.0.1', 5001);
  return functions;
}

async function call<T = unknown>(name: string, functions: Functions, data: unknown): Promise<T> {
  return (await httpsCallable<unknown, T>(functions, name)(data)).data;
}

async function findCase(crownId: string, closed = false) {
  const page = await call<{ cases: Case[]; openCount: number }>(
    'crownHunt-listPlacementCases',
    admin,
    {
      closed,
    },
  );
  return page.cases.find((entry) => entry.crownId === crownId);
}

async function report(
  client: Functions,
  crownId: string,
  source: 'automatic' | 'hand_placed',
  point: { latitude: number; longitude: number },
) {
  return call('crownHunt-reportPlacement', client, {
    crownId,
    source,
    ...point,
    reason: 'unsafe',
    comment: 'Går inte att stanna säkert.',
  });
}

beforeAll(async () => {
  [member, second, admin] = await Promise.all([
    actor('member', 'one'),
    actor('member', 'two'),
    actor('admin', 'admin'),
  ]);
});

afterAll(async () => {
  await Promise.all(clients.map((app) => deleteApp(app)));
});

describe('crown placement moderation', () => {
  it('deduplicates reports, reopens ignored cases and blocks a removed location after the spawn disappears', async () => {
    const crown = db.collection('crownSpawns').doc(`remove-${run}`);
    await crown.set({
      ...old,
      status: 'live',
      areaId,
      rarity: 'rare',
      expiresAt: Timestamp.fromMillis(Date.now() + 600_000),
    });
    const baseline = (await call<{ openCount: number }>('crownHunt-listPlacementCases', admin, {}))
      .openCount;
    await Promise.all([
      report(member, crown.id, 'automatic', old),
      report(member, crown.id, 'automatic', old),
    ]);
    await expect(call('crownHunt-listPlacementCases', member, {})).rejects.toMatchObject({
      code: 'functions/permission-denied',
    });
    const first = (await findCase(crown.id))!;
    expect(first.reportCount).toBe(1);
    expect(
      (await call<{ openCount: number }>('crownHunt-listPlacementCases', admin, {})).openCount,
    ).toBe(baseline + 1);
    const detail = await call('crownHunt-getPlacementCase', admin, { caseId: first.caseId });
    expect(detail).toMatchObject({
      active: true,
      reports: [{ reason: 'unsafe', comment: 'Går inte att stanna säkert.' }],
    });

    await call('crownHunt-resolvePlacementCase', admin, { ...caseData(first), action: 'ignore' });
    expect(await findCase(crown.id)).toBeUndefined();
    expect((await findCase(crown.id, true))?.decision).toBe('ignore');
    expect(
      (await call<{ openCount: number }>('crownHunt-listPlacementCases', admin, {})).openCount,
    ).toBe(baseline);
    expect((await crown.get()).exists).toBe(true);

    await report(second, crown.id, 'automatic', old);
    const reopened = (await findCase(crown.id))!;
    expect(reopened.reportCount).toBe(1);
    expect(await findCase(crown.id, true)).toBeUndefined();
    await expect(
      call('crownHunt-resolvePlacementCase', admin, { ...caseData(first), action: 'remove' }),
    ).rejects.toMatchObject({ code: 'functions/failed-precondition' });

    await call('crownHunt-resolvePlacementCase', admin, {
      ...caseData(reopened),
      action: 'remove',
    });
    await call('crownHunt-resolvePlacementCase', admin, {
      ...caseData(reopened),
      action: 'remove',
    });
    expect((await crown.get()).exists).toBe(false);
    expect((await findCase(crown.id, true))?.decision).toBe('remove');
    expect(
      (await db.collection('crownPlacementBlocks').doc(reopened.caseId).get()).data(),
    ).toMatchObject(old);

    const point = db.collection('crownHuntPoints').doc(`blocked-${run}`);
    await point.set({ ...old, longitude: old.longitude + 0.0001, status: 'paused' });
    await expect(
      call('crownHunt-activatePoint', admin, {
        pointId: point.id,
        safeLocationConfirmed: true,
        approvalNote: 'Review',
      }),
    ).rejects.toMatchObject({ code: 'functions/failed-precondition' });
  }, 120_000);

  it('stages a hand-placed move, changes coordinates when paused, and preserves the old location block', async () => {
    const original = { latitude: 58.445, longitude: 15.645 };
    const destination = { latitude: 58.447, longitude: 15.645 };
    const point = db.collection('crownHuntPoints').doc(`move-manual-${run}`);
    await point.set({ ...original, status: 'active' });
    await report(member, point.id, 'hand_placed', original);
    const entry = (await findCase(point.id))!;
    await call('crownHunt-resolvePlacementCase', admin, {
      ...caseData(entry),
      action: 'move',
      destination: { ...destination, safeLocationConfirmed: true },
    });
    expect((await point.get()).data()).toMatchObject({ ...original, status: 'active' });
    await call('crownHunt-pausePoint', admin, { pointId: point.id });
    expect((await point.get()).data()).toMatchObject({ ...destination, status: 'paused' });
    await call('crownHunt-activatePoint', admin, {
      pointId: point.id,
      safeLocationConfirmed: true,
      approvalNote: 'Review',
    });
    await expect(report(second, point.id, 'hand_placed', original)).rejects.toMatchObject({
      code: 'functions/failed-precondition',
    });
    await report(member, point.id, 'hand_placed', destination);
    const next = (await findCase(point.id))!;
    expect(next.caseId).not.toBe(entry.caseId);
    await call('crownHunt-resolvePlacementCase', admin, { ...caseData(next), action: 'remove' });
    expect((await point.get()).get('status')).toBe('ended');
    expect(
      (await db.collection('crownPlacementBlocks').doc(next.caseId).get()).data(),
    ).toMatchObject(destination);
  });

  it('uses the selected stop on the next eligible automatic spawn and skips the old stop after POI refresh', async () => {
    const source = { ...old, latitude: old.latitude + 0.01 };
    const destination = { ...alternate, latitude: alternate.latitude + 0.01 };
    const cellKey = crownCellKey(source.latitude, source.longitude);
    const area = db.collection('crownSpawnAreas').doc(areaId);
    const pois = db.collection('crownSpawnAreaPois').doc(areaId).collection('pois');
    const crown = db.collection('crownSpawns').doc(`move-auto-${run}`);
    const now = new Date();
    const expiresAt = Timestamp.fromMillis(now.getTime() + 60_000);
    await Promise.all([
      db.collection('config').doc('featureFlags').set({ crownHuntSpawn: true }, { merge: true }),
      area.set({
        active: true,
        safeAreaConfirmed: true,
        lastSpawnPassAt: Timestamp.fromMillis(0),
        poiCount: 2,
        shape: {
          type: 'circle',
          center: { lat: source.latitude, lon: source.longitude },
          radiusMeters: 260,
        },
      }),
      pois
        .doc('old')
        .set({ lat: source.latitude, lon: source.longitude, cellKey, category: 'parking' }),
      pois.doc('new').set({
        lat: destination.latitude,
        lon: destination.longitude,
        cellKey,
        category: 'parking',
      }),
      crown.set({ ...source, cellKey, status: 'live', areaId, expiresAt }),
    ]);
    try {
      await report(member, crown.id, 'automatic', source);
      const entry = (await findCase(crown.id))!;
      const caseId = entry.caseId;
      const choices = await call('crownHunt-listPlacementDestinations', admin, { caseId });
      expect(choices).toMatchObject({ destinations: [{ poiId: 'new' }] });
      await call('crownHunt-resolvePlacementCase', admin, {
        ...caseData(entry),
        action: 'move',
        destination: { poiId: 'new' },
      });
      expect((await crown.get()).exists).toBe(true);
      await runCrownAreaSpawnPass(now, { maxAreas: 30, maxCells: 200, maxSpawns: 100 });
      const areaCrowns = db.collection('crownSpawns').where('areaId', '==', areaId);
      expect((await areaCrowns.get()).docs.map((doc) => doc.id)).toEqual([crown.id]);

      await crown.delete();
      await pois.doc('old').delete();
      await pois.doc('refreshed-old').set({
        lat: source.latitude + 0.00003,
        lon: source.longitude,
        cellKey,
        category: 'parking',
      });
      await runCrownAreaSpawnPass(new Date(now.getTime() + 61_000), {
        maxAreas: 30,
        maxCells: 200,
        maxSpawns: 100,
      });
      const after = await areaCrowns.get();
      expect(after.size).toBe(1);
      expect(after.docs[0]?.data()).toMatchObject(destination);
    } finally {
      await area.set({ active: false }, { merge: true });
    }
  });
});
