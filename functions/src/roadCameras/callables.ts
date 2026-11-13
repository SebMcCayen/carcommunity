import { createHash } from 'node:crypto';
import { Timestamp } from 'firebase-admin/firestore';
import { HttpsError } from 'firebase-functions/v2/https';
import { z } from 'zod';
import { onCall } from '../access/callable';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import { readFeatureFlag } from '../shared/featureFlags';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';
import { toPublicCamera, type PublicCamera, type StoredCamera } from './core';
import { fetchCameraPhoto, type CameraPhoto } from './photo';
import {
  consumeCameraQuota,
  MAX_CATALOG_RESPONSE_BYTES,
  type CameraOperation,
  type QuotaState,
} from './quota';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  memory: '256MiB' as const,
  minInstances: 0,
  maxInstances: MAX_INSTANCES_MEMBER,
  timeoutSeconds: 20,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export const CAMERA_COLLECTION = 'roadCameraRecords';
export const CATALOG_COLLECTION = 'roadCameraCatalogPages';
export const CATALOG_META_COLLECTION = 'roadCameraCatalogMetadata';
export const CATALOG_MAX_PAGES = 40;
export const CATALOG_PAGE_SIZE = 250;

const photoInput = z
  .object({
    cameraId: z.string().regex(/^(speed|traffic|road_condition)_[a-f0-9]{32}$/),
  })
  .strict();
const catalogInput = z.object({}).strict();

/** One transaction reserves both budgets; parallel devices cannot overspend. */
export async function reserveCameraQuota(uid: string, operation: CameraOperation): Promise<void> {
  const key = createHash('sha256').update(uid).digest('hex');
  const userRef = db.collection('roadCameraRateLimits').doc(key);
  const globalRef = db.collection('roadCameraRateLimits').doc('global');
  await db.runTransaction(async (tx) => {
    const [user, global] = await tx.getAll(userRef, globalRef);
    const now = Date.now();
    const result = consumeCameraQuota(
      operation,
      (user?.data() ?? {}) as QuotaState,
      (global?.data() ?? {}) as QuotaState,
      now,
    );
    if (!result) throw new HttpsError('resource-exhausted', 'Camera request limit reached.');
    const expiresAt = Timestamp.fromMillis(now + 2 * 86_400_000);
    tx.set(userRef, { ...result.user, expiresAt });
    tx.set(globalRef, { ...result.global, expiresAt });
  });
}

async function requireCamerasEnabled(): Promise<void> {
  if (!(await readFeatureFlag('externalDataSources'))) {
    throw new HttpsError('failed-precondition', 'External data sources are disabled.');
  }
}

export interface CameraCatalog {
  cameras: PublicCamera[];
  updatedAt: string | null;
}

/** A complete bounded snapshot: at most 40 page reads, cached on the device 24h. */
export async function readCameraCatalog(): Promise<CameraCatalog> {
  return db.runTransaction(async (tx) => {
    const meta = await tx.get(db.collection(CATALOG_META_COLLECTION).doc('current'));
    const generation = meta.get('generation');
    const pageCount = meta.get('pageCount');
    if (
      typeof generation !== 'string' ||
      !/^[a-f0-9]{64}$/.test(generation) ||
      !Number.isInteger(pageCount) ||
      pageCount < 1 ||
      pageCount > CATALOG_MAX_PAGES
    )
      throw new HttpsError('unavailable', 'Camera catalog is not ready.');
    const pages = await tx.getAll(
      ...Array.from({ length: pageCount }, (_, i) =>
        db.collection(CATALOG_COLLECTION).doc(`${generation}_${i}`),
      ),
    );
    const cameras: PublicCamera[] = [];
    for (const page of pages) {
      const entries = page.get('cameras');
      if (!Array.isArray(entries) || entries.length > CATALOG_PAGE_SIZE) {
        throw new HttpsError('unavailable', 'Camera catalog is incomplete.');
      }
      cameras.push(...(entries as StoredCamera[]).map(toPublicCamera));
    }
    const updated = meta.get('updatedAt');
    const result = {
      cameras,
      updatedAt: updated instanceof Timestamp ? updated.toDate().toISOString() : null,
    };
    if (Buffer.byteLength(JSON.stringify(result)) > MAX_CATALOG_RESPONSE_BYTES) {
      throw new HttpsError('unavailable', 'Camera catalog exceeds response budget.');
    }
    return result;
  });
}

export const catalog = onCall(CALLABLE_OPTS, async (request): Promise<CameraCatalog> => {
  const actor = await requireActiveActor(request);
  if (!catalogInput.safeParse(request.data ?? {}).success) {
    throw new HttpsError('invalid-argument', 'Expected an empty catalog request.');
  }
  await requireCamerasEnabled();
  await reserveCameraQuota(actor.uid, 'catalog');
  return readCameraCatalog();
});

// Small per-instance optimization only. The distributed budget still charges
// EVERY response, including cache hits, and remains authoritative across instances.
const photoCache = new Map<string, { photo: CameraPhoto; expiresAt: number }>();
const inFlight = new Map<string, Promise<CameraPhoto>>();

export async function loadCameraPhoto(cameraId: string): Promise<CameraPhoto> {
  const cached = photoCache.get(cameraId);
  if (cached && cached.expiresAt > Date.now()) {
    photoCache.delete(cameraId);
    photoCache.set(cameraId, cached);
    return cached.photo;
  }
  const pending = inFlight.get(cameraId);
  if (pending) return pending;
  const load = (async () => {
    const snap = await db.collection(CAMERA_COLLECTION).doc(cameraId).get();
    if (!snap.exists || snap.get('type') === 'speed' || typeof snap.get('photoUrl') !== 'string') {
      throw new HttpsError('not-found', 'Camera photo is unavailable.');
    }
    try {
      const image = await fetchCameraPhoto(snap.get('photoUrl') as string);
      photoCache.delete(cameraId);
      photoCache.set(cameraId, { photo: image, expiresAt: Date.now() + 300_000 });
      while (photoCache.size > 20) photoCache.delete(photoCache.keys().next().value!);
      return image;
    } catch {
      // Never include upstream URLs or request/response bodies in errors or logs.
      throw new HttpsError('unavailable', 'Camera photo is temporarily unavailable.');
    }
  })();
  inFlight.set(cameraId, load);
  try {
    return await load;
  } finally {
    inFlight.delete(cameraId);
  }
}

export const photo = onCall(CALLABLE_OPTS, async (request): Promise<CameraPhoto> => {
  const actor = await requireActiveActor(request);
  const input = photoInput.safeParse(request.data);
  if (!input.success) throw new HttpsError('invalid-argument', 'Expected a valid cameraId.');
  await requireCamerasEnabled();
  // Charge BEFORE cache lookup or upstream I/O. Failed requests also consume a
  // reservation, so retries and cross-camera abuse cannot bypass the budget.
  await reserveCameraQuota(actor.uid, 'photo');
  return loadCameraPhoto(input.data.cameraId);
});
