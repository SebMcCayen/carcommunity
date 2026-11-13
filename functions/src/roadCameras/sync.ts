import { createHash } from 'node:crypto';
import { FieldPath, FieldValue } from 'firebase-admin/firestore';
import { defineSecret } from 'firebase-functions/params';
import { onSchedule } from 'firebase-functions/v2/scheduler';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { readFeatureFlag } from '../shared/featureFlags';
import { CPU_SCHEDULED } from '../shared/instanceLimits';
import { withServerErrorReporting } from '../errors/serverErrors';
import { TRAFIKVERKET_ENDPOINT } from '../incidents/trafikverket-core';
import {
  buildCameraRequest,
  buildSpeedCameraRequest,
  parseCameraCatalog,
  toPublicCamera,
} from './core';
import {
  CAMERA_COLLECTION,
  CATALOG_COLLECTION,
  CATALOG_META_COLLECTION,
  CATALOG_MAX_PAGES,
  CATALOG_PAGE_SIZE,
} from './callables';
import { MAX_CATALOG_RESPONSE_BYTES } from './quota';

const TRAFIKVERKET_API_KEY = defineSecret('TRAFIKVERKET_API_KEY');
const MAX_SOURCE_BYTES = 8 * 1024 * 1024;
export type CameraSourceFetcher = (body: string) => Promise<unknown>;

/** Only two national requests daily; never poll or download images here. */
export const fetchCameraSource: CameraSourceFetcher = async (body) => {
  const result = await fetch(TRAFIKVERKET_ENDPOINT, {
    method: 'POST',
    headers: { 'Content-Type': 'text/xml' },
    body,
    redirect: 'error',
    signal: AbortSignal.timeout(30_000),
  });
  if (!result.ok || !result.body) throw new Error('Camera source request failed.');
  const reader = result.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const chunk = await reader.read();
      if (chunk.done) break;
      length += chunk.value.byteLength;
      if (length > MAX_SOURCE_BYTES) throw new Error('Camera source response exceeds size limit.');
      chunks.push(chunk.value);
    }
  } finally {
    await reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8')) as unknown;
};

export async function runCameraSync(key: string, fetcher = fetchCameraSource) {
  const [cameraResponse, speedResponse] = await Promise.all([
    fetcher(buildCameraRequest(key)),
    fetcher(buildSpeedCameraRequest(key)),
  ]);
  // Both sources must be complete BEFORE any write or removal is considered.
  const parsed = parseCameraCatalog(cameraResponse, speedResponse);
  const publicCameras = parsed.cameras.map(toPublicCamera);
  const pageCount = Math.ceil(publicCameras.length / CATALOG_PAGE_SIZE);
  if (
    pageCount > CATALOG_MAX_PAGES ||
    Buffer.byteLength(
      JSON.stringify({ cameras: publicCameras, updatedAt: new Date().toISOString() }),
    ) > MAX_CATALOG_RESPONSE_BYTES
  ) {
    throw new Error('Camera catalog exceeds snapshot budget.');
  }
  const existing = new Map<string, FirebaseFirestore.DocumentSnapshot>();
  let cursor: FirebaseFirestore.QueryDocumentSnapshot | undefined;
  for (;;) {
    let query = db.collection(CAMERA_COLLECTION).orderBy(FieldPath.documentId()).limit(500);
    if (cursor) query = query.startAfter(cursor);
    const page = await query.get();
    for (const record of page.docs) existing.set(record.id, record);
    if (existing.size > CATALOG_MAX_PAGES * CATALOG_PAGE_SIZE) {
      throw new Error('Existing camera catalog exceeds scan budget.');
    }
    if (page.size < 500) break;
    cursor = page.docs.at(-1);
  }
  for (const type of ['speed', 'traffic', 'road_condition'] as const) {
    const oldCount = [...existing.values()].filter((doc) => doc.get('type') === type).length;
    const newCount = parsed.cameras.filter((camera) => camera.type === type).length;
    if (oldCount >= 20 && newCount < oldCount / 2) {
      throw new Error('Camera catalog dropped implausibly; preserving previous snapshot.');
    }
  }
  const metaRef = db.collection(CATALOG_META_COLLECTION).doc('current');
  const oldMeta = await metaRef.get();
  const generation = createHash('sha256').update(JSON.stringify(publicCameras)).digest('hex');
  const changedGeneration = oldMeta.get('generation') !== generation;
  let pending = db.batch();
  let operations = 0;
  let changed = 0;
  for (const camera of parsed.cameras) {
    const fingerprint = createHash('sha256').update(JSON.stringify(camera)).digest('hex');
    if (existing.get(camera.id)?.get('fingerprint') === fingerprint) continue;
    pending.set(db.collection(CAMERA_COLLECTION).doc(camera.id), { ...camera, fingerprint });
    changed++;
    if (++operations === 400) {
      await pending.commit();
      pending = db.batch();
      operations = 0;
    }
  }
  if (operations) await pending.commit();
  // At most 40 pages and one pointer, published in a single atomic commit.
  // The preceding generation remains readable until the next daily replacement.
  const publish = db.batch();
  if (changedGeneration) {
    for (let page = 0; page < pageCount; page++) {
      publish.set(db.collection(CATALOG_COLLECTION).doc(`${generation}_${page}`), {
        cameras: publicCameras.slice(page * CATALOG_PAGE_SIZE, (page + 1) * CATALOG_PAGE_SIZE),
      });
    }
  }
  publish.set(metaRef, {
    generation,
    pageCount,
    updatedAt: FieldValue.serverTimestamp(),
    previousGeneration: changedGeneration
      ? (oldMeta.get('generation') ?? null)
      : (oldMeta.get('previousGeneration') ?? null),
    previousPageCount: changedGeneration
      ? (oldMeta.get('pageCount') ?? 0)
      : (oldMeta.get('previousPageCount') ?? 0),
  });
  await publish.commit();
  // Reconcile only a validated national snapshot. Never remove records on an
  // upstream error, capped response, or implausible drop.
  const ids = new Set(parsed.cameras.map((camera) => camera.id));
  pending = db.batch();
  operations = 0;
  let removed = 0;
  for (const [id, doc] of existing) {
    if (ids.has(id)) continue;
    pending.delete(doc.ref);
    removed++;
    if (++operations === 400) {
      await pending.commit();
      pending = db.batch();
      operations = 0;
    }
  }
  if (changedGeneration) {
    const obsolete = oldMeta.get('previousGeneration');
    const obsoletePages = oldMeta.get('previousPageCount');
    if (
      typeof obsolete === 'string' &&
      /^[a-f0-9]{64}$/.test(obsolete) &&
      obsolete !== generation &&
      obsolete !== oldMeta.get('generation') &&
      Number.isInteger(obsoletePages) &&
      obsoletePages <= CATALOG_MAX_PAGES
    ) {
      for (let i = 0; i < obsoletePages; i++) {
        pending.delete(db.collection(CATALOG_COLLECTION).doc(`${obsolete}_${i}`));
        if (++operations === 400) {
          await pending.commit();
          pending = db.batch();
          operations = 0;
        }
      }
    }
  }
  if (operations) await pending.commit();
  return { cameras: parsed.cameras.length, changed, removed, changedGeneration };
}

export const sync = onSchedule(
  {
    region: 'europe-west1',
    minInstances: 0,
    maxInstances: 1,
    cpu: CPU_SCHEDULED,
    concurrency: 1,
    memory: '256MiB',
    timeoutSeconds: 300,
    timeZone: 'Europe/Stockholm',
    schedule: '15 4 * * *',
    secrets: [TRAFIKVERKET_API_KEY],
  },
  withServerErrorReporting('roadCameras.sync', async () => {
    if (!(await readFeatureFlag('externalDataSources'))) return;
    const key = TRAFIKVERKET_API_KEY.value();
    if (!key) {
      logger.info('Camera catalog sync skipped: no API key.');
      return;
    }
    logger.info('Camera catalog synchronized', await runCameraSync(key));
  }),
);
