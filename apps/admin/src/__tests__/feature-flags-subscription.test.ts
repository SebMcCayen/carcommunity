import { describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({ doc: vi.fn(), subscribe: vi.fn(), unsubscribe: vi.fn() }));
vi.mock('firebase/firestore', () => ({
  doc: mocks.doc,
  getDoc: vi.fn(),
  onSnapshot: mocks.subscribe,
}));
vi.mock('@/lib/firestore', () => ({ getAdminFirestore: () => 'test-db' }));
vi.mock('@/lib/callables', () => ({ callAdmin: vi.fn() }));
import { subscribeFeatureFlagRows } from '@/features/feature-flags';

describe('effective flag subscription', () => {
  it('reads the backend flag document, keeps default semantics, and exposes cache provenance', () => {
    mocks.doc.mockReturnValue('flag-reference');
    mocks.subscribe.mockReturnValue(mocks.unsubscribe);
    const onRows = vi.fn();
    const onError = vi.fn();
    const stop = subscribeFeatureFlagRows(onRows, onError);
    expect(mocks.doc).toHaveBeenCalledWith('test-db', 'config', 'featureFlags');
    expect(mocks.subscribe.mock.calls[0]!.slice(0, 2)).toEqual([
      'flag-reference',
      { includeMetadataChanges: true },
    ]);
    const receive = mocks.subscribe.mock.calls[0]![2];
    receive({ data: () => undefined, metadata: { fromCache: true } });
    expect(onRows.mock.calls[0]![1]).toBe(true);
    expect(onRows.mock.calls[0]![0]).toContainEqual(
      expect.objectContaining({ key: 'crownHuntSpawn', enabled: false }),
    );
    receive({ data: () => ({ crownHuntSpawn: true }), metadata: { fromCache: false } });
    expect(onRows.mock.calls[1]![1]).toBe(false);
    expect(onRows.mock.calls[1]![0]).toContainEqual(
      expect.objectContaining({ key: 'crownHuntSpawn', enabled: true }),
    );
    expect(mocks.subscribe.mock.calls[0]![3]).toBe(onError);
    stop();
    expect(mocks.unsubscribe).toHaveBeenCalledOnce();
  });
});
