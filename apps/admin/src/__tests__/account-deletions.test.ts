/**
 * Unit tests for the Phase 13o account-deletions admin feature module:
 * document mapping, purge-date math, status-filtered list queries, and the
 * transactional markProcessed status update (happy path, already-processed
 * no-op, missing-document error, and propagated commit failure).
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';

const getDocMock = vi.fn();
const getDocsMock = vi.fn();
const whereMock = vi.fn();
const orderByMock = vi.fn();
const limitMock = vi.fn();
const queryMock = vi.fn();
const runTransactionMock = vi.fn();
/** The transaction handle handed to the runTransaction callback. */
const txGetMock = vi.fn();
const txUpdateMock = vi.fn();

const SERVER_TIMESTAMP = { __serverTimestamp: true };

// The feature module now also imports callAdmin (for the never-onboarded
// purge). Mock it so the real callables/firebase module — which requires
// VITE_* env vars at import time — is never loaded by this Firestore-focused
// suite.
vi.mock('../lib/callables', () => ({ callAdmin: vi.fn() }));
vi.mock('../lib/firestore', () => ({ getAdminFirestore: () => ({}) }));
vi.mock('firebase/firestore', () => ({
  doc: (_db: unknown, ...segments: unknown[]) => ({ segments }),
  collection: (_db: unknown, ...segments: unknown[]) => ({ segments }),
  query: (...args: unknown[]) => queryMock(...args),
  where: (...args: unknown[]) => whereMock(...args),
  orderBy: (...args: unknown[]) => orderByMock(...args),
  limit: (...args: unknown[]) => limitMock(...args),
  getDoc: (...args: unknown[]) => getDocMock(...args),
  getDocs: (...args: unknown[]) => getDocsMock(...args),
  runTransaction: (...args: unknown[]) => runTransactionMock(...args),
  serverTimestamp: () => SERVER_TIMESTAMP,
}));

import {
  ApiError,
  DELETION_RETENTION_DAYS,
  adminGetAccountDeletionRequest,
  adminListAccountDeletionRequests,
  daysUntilPurge,
  markAccountDeletionProcessed,
  purgeDueAtIso,
  toAdminAccountDeletionRequest,
} from '../features/account-deletions';

/** Minimal Firestore Timestamp stand-in. */
const ts = (iso: string) => ({ toDate: () => new Date(iso) });

beforeEach(() => {
  getDocMock.mockReset();
  getDocsMock.mockReset();
  whereMock.mockReset();
  orderByMock.mockReset();
  limitMock.mockReset();
  queryMock.mockReset();
  queryMock.mockImplementation((target: unknown) => target);
  runTransactionMock.mockReset();
  txGetMock.mockReset();
  txUpdateMock.mockReset();
  // Default: run the callback against the mocked transaction handle, like
  // the real SDK does on a contention-free attempt.
  runTransactionMock.mockImplementation(
    (_db: unknown, fn: (tx: { get: typeof txGetMock; update: typeof txUpdateMock }) => unknown) =>
      fn({ get: txGetMock, update: txUpdateMock }),
  );
});

describe('toAdminAccountDeletionRequest mapping', () => {
  it('maps a canonical pending document written by account.deleteAccount', () => {
    const row = toAdminAccountDeletionRequest('uid-1', {
      userId: 'uid-1',
      reason: 'Leaving the platform',
      status: 'pending',
      createdAt: ts('2026-07-01T10:00:00.000Z'),
    });
    expect(row).toEqual({
      userId: 'uid-1',
      reason: 'Leaving the platform',
      status: 'pending',
      createdAt: '2026-07-01T10:00:00.000Z',
      processedAt: null,
      purgeDueAt: '2026-07-31T10:00:00.000Z',
    });
  });

  it('maps a processed document (purge-stamped) and null reason', () => {
    const row = toAdminAccountDeletionRequest('uid-2', {
      userId: 'uid-2',
      reason: null,
      status: 'processed',
      createdAt: ts('2026-05-01T00:00:00.000Z'),
      processedAt: ts('2026-06-01T03:30:00.000Z'),
    });
    expect(row.reason).toBeNull();
    expect(row.status).toBe('processed');
    expect(row.processedAt).toBe('2026-06-01T03:30:00.000Z');
    expect(row.purgeDueAt).toBe('2026-05-31T00:00:00.000Z');
  });

  it('is defensive about malformed documents: uses the doc id, a pending status, and null timestamps', () => {
    const row = toAdminAccountDeletionRequest('uid-3', {
      status: 'bogus',
      reason: 42,
      createdAt: 'not-a-date',
    });
    expect(row.userId).toBe('uid-3');
    expect(row.reason).toBeNull();
    // Unknown status surfaces for attention rather than disappearing.
    expect(row.status).toBe('pending');
    expect(row.createdAt).toBeNull();
    expect(row.purgeDueAt).toBeNull();
  });

  it('treats the document ID as the authoritative userId even when the stored userId field disagrees', () => {
    // A hand-edited/malformed doc whose stored userId points elsewhere must
    // never win: mark-processed acts on the doc id, so the mapping surfaces
    // the doc id — not the field — to keep display and action on the same uid.
    const row = toAdminAccountDeletionRequest('doc-uid', {
      userId: 'attacker-controlled-uid',
      status: 'pending',
      createdAt: ts('2026-07-01T00:00:00.000Z'),
    });
    expect(row.userId).toBe('doc-uid');
  });

  it('returns null timestamps instead of throwing when toDate() yields an invalid Date', () => {
    const row = toAdminAccountDeletionRequest('uid-4', {
      userId: 'uid-4',
      status: 'pending',
      // A corrupt Timestamp-like value: toISOString() on this would throw.
      createdAt: { toDate: () => new Date('invalid') },
      processedAt: { toDate: () => new Date(Number.NaN) },
    });
    expect(row.createdAt).toBeNull();
    expect(row.processedAt).toBeNull();
    expect(row.purgeDueAt).toBeNull();
  });
});

describe('purge-date math', () => {
  it('adds the 30-day retention window to createdAt', () => {
    expect(DELETION_RETENTION_DAYS).toBe(30);
    expect(purgeDueAtIso('2026-07-01T10:00:00.000Z')).toBe('2026-07-31T10:00:00.000Z');
    expect(purgeDueAtIso(null)).toBeNull();
    expect(purgeDueAtIso('garbage')).toBeNull();
  });

  it('counts whole days until the purge window opens', () => {
    const now = new Date('2026-07-08T12:00:00.000Z');
    // Requested 2026-07-01 10:00 → due 2026-07-31 10:00 → 22.9 days → ceil 23.
    expect(daysUntilPurge('2026-07-01T10:00:00.000Z', now)).toBe(23);
    // Exactly at the cutoff → 0 (due now).
    expect(daysUntilPurge('2026-06-08T12:00:00.000Z', now)).toBe(0);
    // Past the window → negative (overdue, next sweep purges it).
    expect(daysUntilPurge('2026-06-01T00:00:00.000Z', now)).toBeLessThan(0);
    expect(daysUntilPurge(null, now)).toBeNull();
  });
});

describe('adminListAccountDeletionRequests', () => {
  const docsResponse = (docs: Array<{ id: string; data: Record<string, unknown> }>) => ({
    docs: docs.map(({ id, data }) => ({ id, data: () => data })),
  });

  it('filters pending requests oldest-first (queue semantics)', async () => {
    getDocsMock.mockResolvedValue(
      docsResponse([
        {
          id: 'old-uid',
          data: { userId: 'old-uid', status: 'pending', createdAt: ts('2026-06-01T00:00:00.000Z') },
        },
        {
          id: 'new-uid',
          data: { userId: 'new-uid', status: 'pending', createdAt: ts('2026-07-01T00:00:00.000Z') },
        },
      ]),
    );

    const rows = await adminListAccountDeletionRequests('pending');

    expect(rows.map((r) => r.userId)).toEqual(['old-uid', 'new-uid']);
    expect(whereMock).toHaveBeenCalledWith('status', '==', 'pending');
    expect(orderByMock).toHaveBeenCalledWith('createdAt', 'asc');
    expect(limitMock).toHaveBeenCalledWith(50);
  });

  it('filters processed requests through the same composite-index shape', async () => {
    getDocsMock.mockResolvedValue(docsResponse([]));
    await adminListAccountDeletionRequests('processed', 10);
    expect(whereMock).toHaveBeenCalledWith('status', '==', 'processed');
    expect(orderByMock).toHaveBeenCalledWith('createdAt', 'asc');
    expect(limitMock).toHaveBeenCalledWith(10);
  });

  it('omits the status filter for the "all" view', async () => {
    getDocsMock.mockResolvedValue(docsResponse([]));
    await adminListAccountDeletionRequests('all');
    expect(whereMock).not.toHaveBeenCalled();
    expect(orderByMock).toHaveBeenCalledWith('createdAt', 'asc');
  });
});

describe('adminGetAccountDeletionRequest', () => {
  it('returns the mapped request', async () => {
    getDocMock.mockResolvedValue({
      exists: () => true,
      id: 'uid-1',
      data: () => ({
        userId: 'uid-1',
        status: 'pending',
        createdAt: ts('2026-07-01T00:00:00.000Z'),
      }),
    });
    const row = await adminGetAccountDeletionRequest('uid-1');
    expect(row?.userId).toBe('uid-1');
    expect(row?.status).toBe('pending');
  });

  it('returns null when the request does not exist', async () => {
    getDocMock.mockResolvedValue({ exists: () => false });
    expect(await adminGetAccountDeletionRequest('missing')).toBeNull();
  });
});

describe('markAccountDeletionProcessed', () => {
  it('writes the shape-minimal processed update (status + processedAt server stamp) inside a transaction', async () => {
    txGetMock.mockResolvedValue({
      exists: () => true,
      id: 'uid-1',
      data: () => ({
        userId: 'uid-1',
        status: 'pending',
        createdAt: ts('2026-07-01T00:00:00.000Z'),
      }),
    });

    const result = await markAccountDeletionProcessed('uid-1');

    expect(result).toEqual({ userId: 'uid-1', status: 'processed', alreadyProcessed: false });
    // Read and write share one transaction — atomic under contention.
    expect(runTransactionMock).toHaveBeenCalledTimes(1);
    expect(txGetMock).toHaveBeenCalledTimes(1);
    expect(txUpdateMock).toHaveBeenCalledTimes(1);
    const [ref, payload] = txUpdateMock.mock.calls[0] as [
      { segments: unknown[] },
      Record<string, unknown>,
    ];
    expect(ref.segments).toEqual(['accountDeletionRequests', 'uid-1']);
    // Exactly the fields the scheduled purge writes — nothing else.
    expect(payload).toEqual({ status: 'processed', processedAt: SERVER_TIMESTAMP });
  });

  it('is a graceful no-op when the transactional read sees an already-processed request', async () => {
    // The state a concurrent purge (or another admin) leaves behind: on
    // contention the SDK re-runs the callback against this fresh snapshot.
    txGetMock.mockResolvedValue({
      exists: () => true,
      id: 'uid-2',
      data: () => ({
        userId: 'uid-2',
        status: 'processed',
        createdAt: ts('2026-05-01T00:00:00.000Z'),
        processedAt: ts('2026-06-01T03:30:00.000Z'),
      }),
    });

    const result = await markAccountDeletionProcessed('uid-2');

    expect(result.alreadyProcessed).toBe(true);
    // The purge's processedAt stamp is never overwritten.
    expect(txUpdateMock).not.toHaveBeenCalled();
  });

  it('throws a 404 ApiError when the request does not exist', async () => {
    txGetMock.mockResolvedValue({ exists: () => false });
    await expect(markAccountDeletionProcessed('missing')).rejects.toMatchObject({
      name: 'ApiError',
      statusCode: 404,
      code: 'not-found',
    });
    expect(txUpdateMock).not.toHaveBeenCalled();
  });

  it('propagates transaction failures (e.g. rules rejection at commit) to the caller', async () => {
    runTransactionMock.mockRejectedValue(new Error('permission-denied'));

    await expect(markAccountDeletionProcessed('uid-3')).rejects.toThrow('permission-denied');
  });

  it('re-exports ApiError for page-level error narrowing', () => {
    expect(new ApiError(404, 'not-found', 'x')).toBeInstanceOf(Error);
  });
});
