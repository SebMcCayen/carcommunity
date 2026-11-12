import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { createHash } from 'node:crypto';
import { parseCameraCatalog, toPublicCamera, CAMERA_QUERY_LIMIT } from './core';

const mocks = vi.hoisted(() => ({
  records: new Map<string, Record<string, unknown>>(),
  commits: [] as Array<Array<{ path: string; value?: Record<string, unknown>; remove?: boolean }>>,
  scheduled: [] as Array<{ options: Record<string, unknown>; handler: () => Promise<unknown> }>,
  flag: vi.fn(),
}));

vi.mock('../firebase', () => {
  const ref = (path: string) => ({
    path,
    id: path.split('/').at(-1),
    get: async () => snapshot(path),
  });
  const snapshot = (path: string) => {
    const value = mocks.records.get(path);
    return {
      id: path.split('/').at(-1),
      ref: ref(path),
      exists: value !== undefined,
      get: (key: string) => value?.[key],
      data: () => value,
    };
  };
  const query = (collection: string, limit = 500, cursor = '') => ({
    orderBy: () => query(collection, limit, cursor),
    limit: (value: number) => query(collection, value, cursor),
    startAfter: (doc: { id: string }) => query(collection, limit, doc.id),
    get: async () => {
      const paths = [...mocks.records.keys()]
        .filter(
          (path) => path.startsWith(`${collection}/`) && path.slice(collection.length + 1) > cursor,
        )
        .sort()
        .slice(0, limit);
      return { docs: paths.map(snapshot), size: paths.length };
    },
  });
  return {
    db: {
      collection: (name: string) => ({ ...query(name), doc: (id: string) => ref(`${name}/${id}`) }),
      batch: () => {
        const writes: Array<{ path: string; value?: Record<string, unknown>; remove?: boolean }> =
          [];
        return {
          set: (doc: { path: string }, value: Record<string, unknown>) => {
            writes.push({ path: doc.path, value });
          },
          delete: (doc: { path: string }) => {
            writes.push({ path: doc.path, remove: true });
          },
          commit: async () => {
            mocks.commits.push([...writes]);
            for (const write of writes) {
              if (write.remove) mocks.records.delete(write.path);
              else mocks.records.set(write.path, write.value!);
            }
          },
        };
      },
    },
  };
});
vi.mock('./callables', () => ({
  CAMERA_COLLECTION: 'roadCameraRecords',
  CATALOG_COLLECTION: 'roadCameraCatalogPages',
  CATALOG_META_COLLECTION: 'roadCameraCatalogMetadata',
  CATALOG_MAX_PAGES: 40,
  CATALOG_PAGE_SIZE: 250,
}));
vi.mock('../shared/featureFlags', () => ({ readFeatureFlag: mocks.flag }));
vi.mock('../errors/serverErrors', () => ({
  withServerErrorReporting: (_name: string, handler: unknown) => handler,
}));
vi.mock('firebase-functions/params', () => ({ defineSecret: () => ({ value: () => 'test-key' }) }));
vi.mock('firebase-functions/v2/scheduler', () => ({
  onSchedule: (options: Record<string, unknown>, handler: () => Promise<unknown>) => {
    mocks.scheduled.push({ options, handler });
    return { run: handler };
  },
}));

import { runCameraSync, fetchCameraSource } from './sync';

const liveCamera = {
  Id: 'live/123',
  Name: 'Bridge',
  Active: true,
  Deleted: false,
  Type: 'Trafikflödeskamera',
  Direction: 270,
  Geometry: { WGS84: 'POINT (12.1 57.5)' },
  PhotoUrl: 'https://api.trafikinfo.trafikverket.se/v2/Images/TrafficFlowCamera_123.Jpeg',
};
const speedCamera = {
  Id: 'speed/123',
  Name: 'Road 40',
  Deleted: false,
  Bearing: 90,
  Geometry: { WGS84: 'POINT (12.2 57.6)' },
};
const response = (type: string, rows: unknown[]) => ({ RESPONSE: { RESULT: [{ [type]: rows }] } });
const validCameraResponse = response('Camera', [liveCamera]);
const validSpeedResponse = response('TrafficSafetyCamera', [speedCamera]);
const fetchSources = (camera: unknown = validCameraResponse, speed: unknown = validSpeedResponse) =>
  vi.fn(async (body: string) =>
    body.includes('objecttype="TrafficSafetyCamera"') ? speed : camera,
  );

beforeEach(() => {
  mocks.records.clear();
  mocks.commits.length = 0;
  mocks.flag.mockResolvedValue(true);
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Unexpected network request')));
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('daily national camera sync', () => {
  it('makes only two metadata requests and publishes pages plus pointer in one atomic batch', async () => {
    const fetcher = fetchSources();
    const result = await runCameraSync('key', fetcher);
    expect(result).toMatchObject({ cameras: 2, changed: 2, removed: 0, changedGeneration: true });
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(
      fetcher.mock.calls.every(([body]) => body.includes('<INCLUDE>Geometry.WGS84</INCLUDE>')),
    ).toBe(true);
    expect(fetch).not.toHaveBeenCalled();
    const publication = mocks.commits.find((batch) =>
      batch.some((write) => write.path === 'roadCameraCatalogMetadata/current'),
    )!;
    expect(publication).toHaveLength(2);
    expect(publication.some((write) => write.path.startsWith('roadCameraCatalogPages/'))).toBe(
      true,
    );
    const page = publication.find((write) => write.path.startsWith('roadCameraCatalogPages/'))!;
    expect(JSON.stringify(page.value)).not.toContain('photoUrl');
    expect(JSON.stringify(page.value)).not.toContain('PhotoUrl');
  });

  it('only refreshes metadata on unchanged imports, avoiding individual record or page rewrites', async () => {
    const fetcher = fetchSources();
    await runCameraSync('key', fetcher);
    mocks.commits.length = 0;
    const result = await runCameraSync('key', fetcher);
    expect(result).toMatchObject({ changed: 0, removed: 0, changedGeneration: false });
    expect(mocks.commits).toHaveLength(1);
    expect(mocks.commits[0]!.map((write) => write.path)).toEqual([
      'roadCameraCatalogMetadata/current',
    ]);
  });

  it.each([
    {},
    { RESPONSE: { RESULT: [] } },
    response('Camera', []),
    response('Camera', Array(CAMERA_QUERY_LIMIT).fill(liveCamera)),
    response('Camera', [{ ...liveCamera, Geometry: { WGS84: 'garbage' } }]),
  ])(
    'preserves the entire previous catalog without writes on unusable camera response',
    async (bad) => {
      mocks.records.set('roadCameraRecords/previous', { type: 'traffic', name: 'Keep me' });
      await expect(runCameraSync('key', fetchSources(bad))).rejects.toThrow();
      expect(mocks.commits).toEqual([]);
      expect(mocks.records.get('roadCameraRecords/previous')).toEqual({
        type: 'traffic',
        name: 'Keep me',
      });
    },
  );

  it('requires both complete sources before any mutation', async () => {
    await expect(
      runCameraSync('key', fetchSources(validCameraResponse, response('TrafficSafetyCamera', []))),
    ).rejects.toThrow();
    expect(mocks.commits).toEqual([]);
  });

  it('preserves an established type when its parsed count drops by more than half', async () => {
    for (let i = 0; i < 20; i++)
      mocks.records.set(`roadCameraRecords/old-${i}`, { type: 'traffic' });
    await expect(runCameraSync('key', fetchSources())).rejects.toThrow('dropped implausibly');
    expect(mocks.commits).toEqual([]);
    expect(mocks.records.size).toBe(20);
  });

  it('reconciles missing records only after the new pointer has been published', async () => {
    mocks.records.set('roadCameraRecords/obsolete', { type: 'traffic' });
    await runCameraSync('key', fetchSources());
    const publishIndex = mocks.commits.findIndex((batch) =>
      batch.some((write) => write.path === 'roadCameraCatalogMetadata/current'),
    );
    const deleteIndex = mocks.commits.findIndex((batch) =>
      batch.some((write) => write.path === 'roadCameraRecords/obsolete' && write.remove),
    );
    expect(publishIndex).toBeGreaterThanOrEqual(0);
    expect(deleteIndex).toBeGreaterThan(publishIndex);
    expect(mocks.records.has('roadCameraRecords/obsolete')).toBe(false);
  });

  it('keeps the preceding generation while deleting only the older generation pages', async () => {
    const oldGeneration = 'a'.repeat(64);
    const olderGeneration = 'b'.repeat(64);
    mocks.records.set('roadCameraCatalogMetadata/current', {
      generation: oldGeneration,
      pageCount: 1,
      previousGeneration: olderGeneration,
      previousPageCount: 1,
    });
    mocks.records.set(`roadCameraCatalogPages/${oldGeneration}_0`, { cameras: [] });
    mocks.records.set(`roadCameraCatalogPages/${olderGeneration}_0`, { cameras: [] });
    await runCameraSync('key', fetchSources());
    expect(mocks.records.has(`roadCameraCatalogPages/${oldGeneration}_0`)).toBe(true);
    expect(mocks.records.has(`roadCameraCatalogPages/${olderGeneration}_0`)).toBe(false);
    expect(mocks.records.get('roadCameraCatalogMetadata/current')!.previousGeneration).toBe(
      oldGeneration,
    );
  });

  it('writes at most 400 record mutations per batch while publishing a multi-page generation together', async () => {
    const speedRows = Array.from({ length: 800 }, (_, i) => ({ ...speedCamera, Id: `speed-${i}` }));
    const result = await runCameraSync(
      'key',
      fetchSources(validCameraResponse, response('TrafficSafetyCamera', speedRows)),
    );
    expect(result.changed).toBe(801);
    expect(mocks.commits.every((batch) => batch.length <= 400)).toBe(true);
    const publication = mocks.commits.find((batch) =>
      batch.some((write) => write.path === 'roadCameraCatalogMetadata/current'),
    )!;
    expect(publication).toHaveLength(5);
  });

  it('fingerprints private photo endpoints separately from the public generation', async () => {
    await runCameraSync('key', fetchSources());
    mocks.commits.length = 0;
    const changed = response('Camera', [
      { ...liveCamera, PhotoUrl: liveCamera.PhotoUrl.replace('_123', '_456') },
    ]);
    const result = await runCameraSync('key', fetchSources(changed));
    expect(result).toMatchObject({ changed: 1, changedGeneration: false });
    expect(
      mocks.commits.flat().filter((write) => write.path.startsWith('roadCameraRecords/')),
    ).toHaveLength(1);
    const publicCameras = parseCameraCatalog(changed, validSpeedResponse).cameras.map(
      toPublicCamera,
    );
    const expected = createHash('sha256').update(JSON.stringify(publicCameras)).digest('hex');
    expect(mocks.records.get('roadCameraCatalogMetadata/current')!.generation).toBe(expected);
  });
});

describe('metadata fetch and scheduled guard', () => {
  it('uses a bounded POST against the data API and never an image endpoint', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(JSON.stringify(validCameraResponse))),
    );
    expect(await fetchCameraSource('<REQUEST/>')).toEqual(validCameraResponse);
    expect(fetch).toHaveBeenCalledWith('https://api.trafikinfo.trafikverket.se/v2/data.json', {
      method: 'POST',
      headers: { 'Content-Type': 'text/xml' },
      body: '<REQUEST/>',
      redirect: 'error',
      signal: expect.any(AbortSignal),
    });
  });

  it('does no work when the backend external source flag is disabled', async () => {
    mocks.flag.mockResolvedValue(false);
    expect(mocks.scheduled).toHaveLength(1);
    await mocks.scheduled[0]!.handler();
    expect(fetch).not.toHaveBeenCalled();
    expect(mocks.commits).toEqual([]);
    expect(mocks.scheduled[0]!.options).toMatchObject({
      schedule: '15 4 * * *',
      minInstances: 0,
      maxInstances: 1,
      timeZone: 'Europe/Stockholm',
    });
  });
});
