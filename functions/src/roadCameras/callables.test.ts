import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { createHash } from 'node:crypto';
import { Timestamp } from 'firebase-admin/firestore';
import { HttpsError, type CallableRequest } from 'firebase-functions/v2/https';

const mocks = vi.hoisted(() => ({
  records: new Map<string, Record<string, unknown>>(),
  reads: [] as string[],
  commits: [] as Array<Array<{ path: string; value: Record<string, unknown> }>>,
  registrations: [] as Array<Record<string, unknown>>,
  actor: vi.fn(),
  flag: vi.fn(),
  photo: vi.fn(),
  transaction: vi.fn(),
}));

vi.mock('../access/callable', () => ({
  onCall: (options: Record<string, unknown>, handler: unknown) => {
    mocks.registrations.push(options);
    return { run: handler };
  },
}));
vi.mock('../shared/memberActor', () => ({ requireActiveActor: mocks.actor }));
vi.mock('../shared/featureFlags', () => ({ readFeatureFlag: mocks.flag }));
vi.mock('./photo', () => ({ fetchCameraPhoto: mocks.photo }));
vi.mock('../firebase', () => {
  const ref = (path: string) => ({
    path,
    id: path.split('/').at(-1),
    get: async () => snapshot(path),
  });
  const snapshot = (path: string) => {
    mocks.reads.push(path);
    const value = mocks.records.get(path);
    return {
      exists: value !== undefined,
      ref: ref(path),
      get: (key: string) => value?.[key],
      data: () => value,
    };
  };
  return {
    db: {
      collection: (name: string) => ({ doc: (id: string) => ref(`${name}/${id}`) }),
      runTransaction: mocks.transaction.mockImplementation(async (callback) => {
        const writes: Array<{ path: string; value: Record<string, unknown> }> = [];
        const result = await callback({
          get: async (doc: { path: string }) => snapshot(doc.path),
          getAll: async (...docs: Array<{ path: string }>) => docs.map((doc) => snapshot(doc.path)),
          set: (doc: { path: string }, value: Record<string, unknown>) =>
            writes.push({ path: doc.path, value }),
        });
        if (writes.length) {
          mocks.commits.push(writes);
          for (const write of writes) mocks.records.set(write.path, write.value);
        }
        return result;
      }),
    },
  };
});

let api: typeof import('./callables');
const cameraId = `traffic_${'a'.repeat(32)}`;
const image = { base64: '/9j/AA==', contentType: 'image/jpeg', photoTime: null, cacheSeconds: 300 };
const request = (data: unknown = {}, authenticated = true) =>
  ({
    data,
    auth: authenticated ? { uid: 'test-user' } : undefined,
  }) as unknown as CallableRequest;

beforeEach(async () => {
  vi.resetModules();
  vi.spyOn(Date, 'now').mockReturnValue(Date.UTC(2026, 10, 12, 12));
  vi.stubEnv('FUNCTIONS_EMULATOR', 'false');
  mocks.records.clear();
  mocks.reads.length = 0;
  mocks.commits.length = 0;
  mocks.registrations.length = 0;
  mocks.transaction.mockClear();
  mocks.photo.mockReset();
  mocks.flag.mockReset();
  mocks.actor.mockReset();
  mocks.actor.mockImplementation(async (req: CallableRequest) => {
    if (!req.auth) throw new HttpsError('unauthenticated', 'Sign in');
    return { uid: req.auth.uid };
  });
  mocks.flag.mockResolvedValue(true);
  mocks.photo.mockResolvedValue(image);
  api = await import('./callables');
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});

describe('camera callable access boundaries', () => {
  it('registers both production callables with App Check and no warm instances', () => {
    expect(mocks.registrations).toHaveLength(2);
    for (const options of mocks.registrations) {
      expect(options).toMatchObject({
        enforceAppCheck: true,
        minInstances: 0,
        region: 'europe-west1',
        timeoutSeconds: 20,
      });
      expect(options.maxInstances).toBeGreaterThan(0);
    }
  });

  it('rejects unauthenticated callers before quota reservation, catalog reads or photo I/O', async () => {
    await expect(api.catalog.run(request({}, false))).rejects.toMatchObject({
      code: 'unauthenticated',
    });
    await expect(api.photo.run(request({ cameraId }, false))).rejects.toMatchObject({
      code: 'unauthenticated',
    });
    expect(mocks.transaction).not.toHaveBeenCalled();
    expect(mocks.flag).not.toHaveBeenCalled();
    expect(mocks.photo).not.toHaveBeenCalled();
    expect(mocks.reads).toEqual([]);
  });

  it('rejects restricted actors before all camera work', async () => {
    mocks.actor.mockRejectedValue(new HttpsError('permission-denied', 'Restricted'));
    await expect(api.photo.run(request({ cameraId }))).rejects.toMatchObject({
      code: 'permission-denied',
    });
    expect(mocks.flag).not.toHaveBeenCalled();
    expect(mocks.transaction).not.toHaveBeenCalled();
  });

  it('honors the backend flag before budget reservations or downloads', async () => {
    mocks.flag.mockResolvedValue(false);
    await expect(api.catalog.run(request())).rejects.toMatchObject({ code: 'failed-precondition' });
    await expect(api.photo.run(request({ cameraId }))).rejects.toMatchObject({
      code: 'failed-precondition',
    });
    expect(mocks.flag).toHaveBeenCalledWith('externalDataSources');
    expect(mocks.transaction).not.toHaveBeenCalled();
    expect(mocks.photo).not.toHaveBeenCalled();
  });

  it('rejects arbitrary image URLs and unexpected catalog input before reserving quota', async () => {
    await expect(
      api.photo.run(request({ cameraId: 'https://evil.example/a.jpg' })),
    ).rejects.toMatchObject({ code: 'invalid-argument' });
    await expect(
      api.photo.run(request({ cameraId, url: 'https://evil.example/' })),
    ).rejects.toMatchObject({ code: 'invalid-argument' });
    await expect(api.catalog.run(request({ limit: 10000 }))).rejects.toMatchObject({
      code: 'invalid-argument',
    });
    expect(mocks.transaction).not.toHaveBeenCalled();
  });
});

describe('transactional quota reservation', () => {
  it('updates one hashed user counter and global counter in the same transaction', async () => {
    await api.reserveCameraQuota('test-user', 'photo');
    const key = createHash('sha256').update('test-user').digest('hex');
    expect(mocks.commits).toHaveLength(1);
    expect(mocks.commits[0]!.map((write) => write.path)).toEqual([
      `roadCameraRateLimits/${key}`,
      'roadCameraRateLimits/global',
    ]);
    expect(mocks.commits[0]![0]!.value.photosDay).toBe(1);
    expect(mocks.commits[0]![0]!.value.expiresAt).toBeInstanceOf(Timestamp);
    expect(mocks.commits[0]![1]!.value.photoBytes).toBeGreaterThan(128 * 1024);
  });

  it('writes neither counter when the user budget is exhausted', async () => {
    for (let i = 0; i < 6; i++) await api.reserveCameraQuota('test-user', 'photo');
    const writesBefore = mocks.commits.length;
    await expect(api.reserveCameraQuota('test-user', 'photo')).rejects.toMatchObject({
      code: 'resource-exhausted',
    });
    expect(mocks.commits).toHaveLength(writesBefore);
  });
});

describe('bounded snapshot retrieval', () => {
  const generation = 'b'.repeat(64);
  const camera = {
    id: cameraId,
    type: 'traffic',
    name: 'Bridge',
    latitude: 57.5,
    longitude: 12.1,
    direction: 270,
    photoUrl: 'https://private-upstream.example/',
    photoTime: '2026-11-12',
    fingerprint: 'private',
  };

  it('returns the published generation without private photo URLs or server metadata', async () => {
    mocks.records.set('roadCameraCatalogMetadata/current', {
      generation,
      pageCount: 1,
      updatedAt: Timestamp.fromMillis(1_000),
    });
    mocks.records.set(`roadCameraCatalogPages/${generation}_0`, { cameras: [camera] });
    const result = await api.readCameraCatalog();
    expect(result.updatedAt).toBe('1970-01-01T00:00:01.000Z');
    expect(result.cameras[0]).toEqual({
      id: cameraId,
      type: 'traffic',
      name: 'Bridge',
      latitude: 57.5,
      longitude: 12.1,
      direction: 270,
    });
    expect(JSON.stringify(result)).not.toContain('private');
  });

  it('rejects a missing page instead of returning a partial map', async () => {
    mocks.records.set('roadCameraCatalogMetadata/current', { generation, pageCount: 2 });
    mocks.records.set(`roadCameraCatalogPages/${generation}_0`, { cameras: [camera] });
    await expect(api.readCameraCatalog()).rejects.toMatchObject({ code: 'unavailable' });
  });

  it('rejects an oversized page count before fetching any pages', async () => {
    mocks.records.set('roadCameraCatalogMetadata/current', { generation, pageCount: 41 });
    await expect(api.readCameraCatalog()).rejects.toMatchObject({ code: 'unavailable' });
    expect(mocks.reads).toEqual(['roadCameraCatalogMetadata/current']);
  });

  it('bounds both per-page entries and total encoded response bytes', async () => {
    mocks.records.set('roadCameraCatalogMetadata/current', { generation, pageCount: 1 });
    mocks.records.set(`roadCameraCatalogPages/${generation}_0`, {
      cameras: Array(251).fill(camera),
    });
    await expect(api.readCameraCatalog()).rejects.toMatchObject({ code: 'unavailable' });
    mocks.records.set(`roadCameraCatalogPages/${generation}_0`, {
      cameras: Array(250).fill({ ...camera, name: 'x'.repeat(10_000) }),
    });
    await expect(api.readCameraCatalog()).rejects.toMatchObject({ code: 'unavailable' });
  });
});

describe('photos are on-demand and cache hits still consume server quota', () => {
  it('downloads once for repeated opens but charges both returned images', async () => {
    mocks.records.set(`roadCameraRecords/${cameraId}`, {
      type: 'traffic',
      photoUrl: 'https://upstream.example/image.jpg',
    });
    expect(mocks.photo).not.toHaveBeenCalled();
    await expect(api.photo.run(request({ cameraId }))).resolves.toEqual(image);
    await expect(api.photo.run(request({ cameraId }))).resolves.toEqual(image);
    expect(mocks.photo).toHaveBeenCalledOnce();
    expect(mocks.commits).toHaveLength(2);
    expect(mocks.records.get('roadCameraRateLimits/global')!.photoBytes).toBeGreaterThan(
      256 * 1024,
    );
  });

  it('blocks a seventh cached image without another download or budget write', async () => {
    mocks.records.set(`roadCameraRecords/${cameraId}`, {
      type: 'traffic',
      photoUrl: 'https://upstream.example/image.jpg',
    });
    for (let i = 0; i < 6; i++) await api.photo.run(request({ cameraId }));
    await expect(api.photo.run(request({ cameraId }))).rejects.toMatchObject({
      code: 'resource-exhausted',
    });
    expect(mocks.photo).toHaveBeenCalledOnce();
    expect(mocks.commits).toHaveLength(6);
  });

  it('never downloads a fixed speed camera and still reserves failed attempts', async () => {
    const id = `speed_${'c'.repeat(32)}`;
    mocks.records.set(`roadCameraRecords/${id}`, {
      type: 'speed',
      photoUrl: 'https://upstream.example/image.jpg',
    });
    await expect(api.photo.run(request({ cameraId: id }))).rejects.toMatchObject({
      code: 'not-found',
    });
    expect(mocks.photo).not.toHaveBeenCalled();
    expect(mocks.commits).toHaveLength(1);
  });

  it('does not leak upstream errors or URLs to clients', async () => {
    mocks.records.set(`roadCameraRecords/${cameraId}`, {
      type: 'traffic',
      photoUrl: 'https://upstream.example/secret',
    });
    mocks.photo.mockRejectedValue(new Error('https://upstream.example/secret token=secret'));
    await expect(api.photo.run(request({ cameraId }))).rejects.toMatchObject({
      code: 'unavailable',
      message: 'Camera photo is temporarily unavailable.',
    });
  });
});
