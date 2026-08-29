/**
 * Unit tests for the Phase 13q moderation-reports admin feature module:
 * document mapping (doc-id canonical, permissive timestamp/detail coercion),
 * status-filtered + cursor-paginated list queries, and the transactional
 * resolveModerationReport status update (happy path, already-at-status no-op,
 * invalid status guard, missing-document error, and propagated commit
 * failure).
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';

const getDocMock = vi.fn();
const getDocsMock = vi.fn();
const whereMock = vi.fn();
const orderByMock = vi.fn();
const limitMock = vi.fn();
const startAfterMock = vi.fn();
const queryMock = vi.fn();
const runTransactionMock = vi.fn();
/** The transaction handle handed to the runTransaction callback. */
const txGetMock = vi.fn();
const txUpdateMock = vi.fn();

const callAdminMock = vi.fn();

vi.mock('../lib/firestore', () => ({ getAdminFirestore: () => ({}) }));
vi.mock('../lib/callables', () => ({ callAdmin: (...args: unknown[]) => callAdminMock(...args) }));
vi.mock('firebase/firestore', () => ({
  doc: (_db: unknown, ...segments: unknown[]) => ({ segments }),
  collection: (_db: unknown, ...segments: unknown[]) => ({ segments }),
  query: (...args: unknown[]) => queryMock(...args),
  where: (...args: unknown[]) => whereMock(...args),
  orderBy: (...args: unknown[]) => orderByMock(...args),
  limit: (...args: unknown[]) => limitMock(...args),
  startAfter: (...args: unknown[]) => startAfterMock(...args),
  getDoc: (...args: unknown[]) => getDocMock(...args),
  getDocs: (...args: unknown[]) => getDocsMock(...args),
  runTransaction: (...args: unknown[]) => runTransactionMock(...args),
}));

import {
  ADMIN_DELETE_COMMUNITY_MESSAGE_CALLABLE,
  ApiError,
  MODERATION_REPORTS_PAGE_SIZE,
  adminDeleteCommunityMessage,
  adminGetModerationReport,
  adminListModerationReports,
  isCommunityMessageReport,
  resolveModerationReport,
  toAdminModerationReport,
} from '../features/moderation-reports';

/** Minimal Firestore Timestamp stand-in. */
const ts = (iso: string) => ({ toDate: () => new Date(iso) });

beforeEach(() => {
  getDocMock.mockReset();
  getDocsMock.mockReset();
  whereMock.mockReset();
  orderByMock.mockReset();
  limitMock.mockReset();
  startAfterMock.mockReset();
  queryMock.mockReset();
  queryMock.mockImplementation((target: unknown) => target);
  runTransactionMock.mockReset();
  txGetMock.mockReset();
  txUpdateMock.mockReset();
  callAdminMock.mockReset();
  // Default: run the callback against the mocked transaction handle, like the
  // real SDK does on a contention-free attempt.
  runTransactionMock.mockImplementation(
    (_db: unknown, fn: (tx: { get: typeof txGetMock; update: typeof txUpdateMock }) => unknown) =>
      fn({ get: txGetMock, update: txUpdateMock }),
  );
});

describe('toAdminModerationReport mapping', () => {
  it('maps a canonical pending document, sourcing fields from data and id from the doc id', () => {
    const row = toAdminModerationReport('report-1', {
      reportedBy: 'reporter-uid',
      targetType: 'message',
      targetId: 'msg-42',
      reason: 'harassment',
      details: 'Repeated abusive replies.',
      status: 'pending',
      createdAt: ts('2026-07-01T10:00:00.000Z'),
    });
    expect(row).toEqual({
      id: 'report-1',
      reportedBy: 'reporter-uid',
      targetType: 'message',
      targetId: 'msg-42',
      reason: 'harassment',
      details: 'Repeated abusive replies.',
      status: 'pending',
      createdAt: '2026-07-01T10:00:00.000Z',
      // New callable-report fields are always present (null when absent).
      surface: null,
      snapshotText: null,
      snapshotAuthorUserId: null,
      snapshotAuthorDisplayName: null,
    });
  });

  it('maps a community-message report with its snapshot + surface', () => {
    const row = toAdminModerationReport('report-cm', {
      reportedBy: 'reporter-uid',
      targetType: 'message',
      targetId: 'msg-community-1',
      reason: 'hate_or_abuse',
      details: null,
      status: 'pending',
      createdAt: ts('2026-08-01T10:00:00.000Z'),
      surface: 'community',
      scopeId: 'global',
      reportedUserId: 'author-uid',
      snapshot: {
        text: 'the reported message body',
        authorUserId: 'author-uid',
        authorDisplayName: 'Author Name',
        createdAt: '2026-08-01T09:00:00.000Z',
      },
    });
    expect(row.surface).toBe('community');
    expect(row.snapshotText).toBe('the reported message body');
    expect(row.snapshotAuthorUserId).toBe('author-uid');
    expect(row.snapshotAuthorDisplayName).toBe('Author Name');
    expect(isCommunityMessageReport(row)).toBe(true);
  });

  it('tolerates a corrupt/absent snapshot on a message report', () => {
    const row = toAdminModerationReport('report-bad-snap', {
      reportedBy: 'r',
      targetType: 'message',
      targetId: 'm',
      reason: 'spam',
      status: 'pending',
      createdAt: ts('2026-08-01T00:00:00.000Z'),
      surface: 'community',
      // snapshot is a non-object — must degrade to nulls, never throw.
      snapshot: 'not-an-object',
    });
    expect(row.snapshotText).toBeNull();
    expect(row.snapshotAuthorUserId).toBeNull();
    // Still a community message report (surface is set) even with no snapshot.
    expect(isCommunityMessageReport(row)).toBe(true);
  });

  it('treats the document ID as canonical (never a stored id field)', () => {
    const row = toAdminModerationReport('doc-id', {
      // A hand-edited doc might carry a stray/foreign id field — it must not win.
      id: 'attacker-controlled-id',
      reportedBy: 'reporter-uid',
      targetType: 'user',
      targetId: 'user-9',
      reason: 'spam',
      status: 'pending',
      createdAt: ts('2026-07-01T00:00:00.000Z'),
    });
    expect(row.id).toBe('doc-id');
  });

  it('coerces missing details/strings and unknown status defensively', () => {
    const row = toAdminModerationReport('report-2', {
      // details omitted, reason non-string, status unrecognized
      reportedBy: 42,
      targetType: 'event',
      targetId: 'evt-1',
      reason: 99,
      status: 'bogus',
      createdAt: ts('2026-07-01T00:00:00.000Z'),
    });
    expect(row.reportedBy).toBe('');
    expect(row.reason).toBe('');
    expect(row.details).toBeNull();
    // Unknown status surfaces for attention rather than disappearing.
    expect(row.status).toBe('pending');
  });

  it('treats blank/empty details as null', () => {
    const row = toAdminModerationReport('report-3', {
      reportedBy: 'r',
      targetType: 'user',
      targetId: 'u',
      reason: 'x',
      details: '',
      status: 'reviewed',
      createdAt: ts('2026-07-01T00:00:00.000Z'),
    });
    expect(row.details).toBeNull();
    expect(row.status).toBe('reviewed');
  });

  it('returns null createdAt instead of throwing when toDate() yields an invalid Date', () => {
    const row = toAdminModerationReport('report-4', {
      reportedBy: 'r',
      targetType: 'user',
      targetId: 'u',
      reason: 'x',
      status: 'pending',
      // A corrupt Timestamp-like value: toISOString() on this would throw.
      createdAt: { toDate: () => new Date('invalid') },
    });
    expect(row.createdAt).toBeNull();
  });

  it('returns null createdAt when toDate() itself throws', () => {
    const row = toAdminModerationReport('report-5', {
      reportedBy: 'r',
      targetType: 'user',
      targetId: 'u',
      reason: 'x',
      status: 'pending',
      createdAt: {
        toDate: () => {
          throw new Error('corrupt timestamp');
        },
      },
    });
    expect(row.createdAt).toBeNull();
  });
});

describe('adminListModerationReports', () => {
  const docsResponse = (docs: Array<{ id: string; data: Record<string, unknown> }>) => ({
    docs: docs.map(({ id, data }) => ({ id, data: () => data })),
  });

  it('filters pending reports newest-first via the status+createdAt composite index', async () => {
    getDocsMock.mockResolvedValue(
      docsResponse([
        {
          id: 'new',
          data: {
            reportedBy: 'r1',
            targetType: 'user',
            targetId: 'u1',
            reason: 'spam',
            status: 'pending',
            createdAt: ts('2026-07-02T00:00:00.000Z'),
          },
        },
        {
          id: 'old',
          data: {
            reportedBy: 'r2',
            targetType: 'user',
            targetId: 'u2',
            reason: 'spam',
            status: 'pending',
            createdAt: ts('2026-07-01T00:00:00.000Z'),
          },
        },
      ]),
    );

    const page = await adminListModerationReports({ filter: 'pending' });

    expect(page.reports.map((r) => r.id)).toEqual(['new', 'old']);
    expect(whereMock).toHaveBeenCalledWith('status', '==', 'pending');
    expect(orderByMock).toHaveBeenCalledWith('createdAt', 'desc');
    expect(limitMock).toHaveBeenCalledWith(MODERATION_REPORTS_PAGE_SIZE);
    // A short page (fewer than pageSize rows) yields no next-page cursor.
    expect(page.cursor).toBeNull();
    // No cursor passed in → startAfter is not applied.
    expect(startAfterMock).not.toHaveBeenCalled();
  });

  it('applies the status filter for reviewed and dismissed alike', async () => {
    getDocsMock.mockResolvedValue(docsResponse([]));
    await adminListModerationReports({ filter: 'reviewed' });
    expect(whereMock).toHaveBeenCalledWith('status', '==', 'reviewed');
    whereMock.mockClear();
    await adminListModerationReports({ filter: 'dismissed' });
    expect(whereMock).toHaveBeenCalledWith('status', '==', 'dismissed');
  });

  it('omits the status filter for the "all" view but still orders newest-first', async () => {
    getDocsMock.mockResolvedValue(docsResponse([]));
    await adminListModerationReports({ filter: 'all' });
    expect(whereMock).not.toHaveBeenCalled();
    expect(orderByMock).toHaveBeenCalledWith('createdAt', 'desc');
  });

  it('defaults to the pending filter when no options are given', async () => {
    getDocsMock.mockResolvedValue(docsResponse([]));
    await adminListModerationReports();
    expect(whereMock).toHaveBeenCalledWith('status', '==', 'pending');
  });

  it('returns the last doc as the cursor when the page is full, and applies startAfter on the next page', async () => {
    const full = Array.from({ length: MODERATION_REPORTS_PAGE_SIZE }, (_, i) => ({
      id: `r${i}`,
      data: {
        reportedBy: 'r',
        targetType: 'user',
        targetId: 'u',
        reason: 'x',
        status: 'pending',
        createdAt: ts('2026-07-01T00:00:00.000Z'),
      },
    }));
    const response = docsResponse(full);
    getDocsMock.mockResolvedValue(response);

    const page = await adminListModerationReports({ filter: 'pending' });
    // Cursor is the raw last snapshot (opaque to callers).
    expect(page.cursor).toBe(response.docs[response.docs.length - 1]);

    // Feeding it back applies startAfter with that exact snapshot.
    await adminListModerationReports({ filter: 'pending', cursor: page.cursor });
    expect(startAfterMock).toHaveBeenCalledWith(response.docs[response.docs.length - 1]);
  });

  it('honours a custom pageSize for the limit', async () => {
    getDocsMock.mockResolvedValue(docsResponse([]));
    await adminListModerationReports({ filter: 'all', pageSize: 10 });
    expect(limitMock).toHaveBeenCalledWith(10);
  });
});

describe('adminGetModerationReport', () => {
  it('returns the mapped report', async () => {
    getDocMock.mockResolvedValue({
      exists: () => true,
      id: 'report-1',
      data: () => ({
        reportedBy: 'r',
        targetType: 'user',
        targetId: 'u',
        reason: 'spam',
        status: 'pending',
        createdAt: ts('2026-07-01T00:00:00.000Z'),
      }),
    });
    const row = await adminGetModerationReport('report-1');
    expect(row?.id).toBe('report-1');
    expect(row?.status).toBe('pending');
  });

  it('returns null when the report does not exist', async () => {
    getDocMock.mockResolvedValue({ exists: () => false });
    expect(await adminGetModerationReport('missing')).toBeNull();
  });
});

describe('resolveModerationReport', () => {
  const pendingSnapshot = (id: string) => ({
    exists: () => true,
    id,
    data: () => ({
      reportedBy: 'r',
      targetType: 'user',
      targetId: 'u',
      reason: 'spam',
      status: 'pending',
      createdAt: ts('2026-07-01T00:00:00.000Z'),
    }),
  });

  it('writes exactly { status } inside a transaction on the happy path', async () => {
    txGetMock.mockResolvedValue(pendingSnapshot('report-1'));

    const result = await resolveModerationReport('report-1', 'reviewed');

    expect(result).toEqual({ id: 'report-1', status: 'reviewed', alreadyResolved: false });
    expect(runTransactionMock).toHaveBeenCalledTimes(1);
    expect(txGetMock).toHaveBeenCalledTimes(1);
    expect(txUpdateMock).toHaveBeenCalledTimes(1);
    const [ref, payload] = txUpdateMock.mock.calls[0] as [
      { segments: unknown[] },
      Record<string, unknown>,
    ];
    expect(ref.segments).toEqual(['moderationReports', 'report-1']);
    // The one field the rules permit — nothing else.
    expect(payload).toEqual({ status: 'reviewed' });
  });

  it('dismisses a pending report', async () => {
    txGetMock.mockResolvedValue(pendingSnapshot('report-2'));
    const result = await resolveModerationReport('report-2', 'dismissed');
    expect(result.alreadyResolved).toBe(false);
    expect(txUpdateMock).toHaveBeenCalledWith(expect.anything(), { status: 'dismissed' });
  });

  it('reopens a reviewed report back to pending', async () => {
    txGetMock.mockResolvedValue({
      exists: () => true,
      id: 'report-3',
      data: () => ({
        reportedBy: 'r',
        targetType: 'user',
        targetId: 'u',
        reason: 'spam',
        status: 'reviewed',
        createdAt: ts('2026-07-01T00:00:00.000Z'),
      }),
    });
    const result = await resolveModerationReport('report-3', 'pending');
    expect(result.alreadyResolved).toBe(false);
    expect(txUpdateMock).toHaveBeenCalledWith(expect.anything(), { status: 'pending' });
  });

  it('is a graceful no-op when the report is already at the requested status', async () => {
    txGetMock.mockResolvedValue({
      exists: () => true,
      id: 'report-4',
      data: () => ({
        reportedBy: 'r',
        targetType: 'user',
        targetId: 'u',
        reason: 'spam',
        status: 'reviewed',
        createdAt: ts('2026-07-01T00:00:00.000Z'),
      }),
    });

    const result = await resolveModerationReport('report-4', 'reviewed');

    expect(result).toEqual({ id: 'report-4', status: 'reviewed', alreadyResolved: true });
    expect(txUpdateMock).not.toHaveBeenCalled();
  });

  it('lets an admin reset a report with an unknown/corrupt stored status back into the vocabulary', async () => {
    // Regression guard: the no-op check must compare the RAW stored status, not
    // the coerced one. If it coerced first, 'bogus' → 'pending' would make a
    // reset to 'pending' a false already-resolved no-op, stranding the bad
    // value. The write must go through and fix the record.
    txGetMock.mockResolvedValue({
      exists: () => true,
      id: 'report-corrupt',
      data: () => ({
        reportedBy: 'r',
        targetType: 'user',
        targetId: 'u',
        reason: 'spam',
        status: 'bogus',
        createdAt: ts('2026-07-01T00:00:00.000Z'),
      }),
    });

    const result = await resolveModerationReport('report-corrupt', 'pending');

    expect(result).toEqual({ id: 'report-corrupt', status: 'pending', alreadyResolved: false });
    expect(txUpdateMock).toHaveBeenCalledWith(expect.anything(), { status: 'pending' });
  });

  it('rejects a status outside the review vocabulary before touching Firestore', async () => {
    await expect(
      // @ts-expect-error — deliberately passing an out-of-vocabulary status.
      resolveModerationReport('report-5', 'deleted'),
    ).rejects.toMatchObject({ name: 'ApiError', statusCode: 400 });
    expect(runTransactionMock).not.toHaveBeenCalled();
  });

  it('throws a 404 ApiError when the report does not exist', async () => {
    txGetMock.mockResolvedValue({ exists: () => false });
    await expect(resolveModerationReport('missing', 'reviewed')).rejects.toMatchObject({
      name: 'ApiError',
      statusCode: 404,
      code: 'not-found',
    });
    expect(txUpdateMock).not.toHaveBeenCalled();
  });

  it('propagates transaction failures (e.g. rules rejection at commit) to the caller', async () => {
    runTransactionMock.mockRejectedValue(new Error('permission-denied'));
    await expect(resolveModerationReport('report-6', 'reviewed')).rejects.toThrow(
      'permission-denied',
    );
  });

  it('re-exports ApiError for page-level error narrowing', () => {
    expect(new ApiError(404, 'not-found', 'x')).toBeInstanceOf(Error);
  });
});

describe('isCommunityMessageReport', () => {
  const base = {
    id: 'r',
    reportedBy: 'u',
    targetId: 't',
    reason: 'spam',
    details: null,
    status: 'pending' as const,
    createdAt: null,
    snapshotText: null,
    snapshotAuthorUserId: null,
    snapshotAuthorDisplayName: null,
  };

  it('is true only for a community-surface message report', () => {
    expect(isCommunityMessageReport({ ...base, targetType: 'message', surface: 'community' })).toBe(
      true,
    );
  });

  it('is false for a convoy/DM message report (not admin-reachable from here)', () => {
    expect(isCommunityMessageReport({ ...base, targetType: 'message', surface: 'convoy' })).toBe(
      false,
    );
    expect(isCommunityMessageReport({ ...base, targetType: 'message', surface: 'dm' })).toBe(false);
  });

  it('is false for a person report or a message report with no surface', () => {
    expect(isCommunityMessageReport({ ...base, targetType: 'user', surface: null })).toBe(false);
    expect(isCommunityMessageReport({ ...base, targetType: 'message', surface: null })).toBe(false);
  });
});

describe('adminDeleteCommunityMessage', () => {
  it('calls the delete callable with the messageId + reason and returns its result', async () => {
    callAdminMock.mockResolvedValue({ messageId: 'msg-1', deleted: true, resolvedReports: 2 });

    const result = await adminDeleteCommunityMessage('msg-1', 'spam');

    expect(result).toEqual({ messageId: 'msg-1', deleted: true, resolvedReports: 2 });
    expect(callAdminMock).toHaveBeenCalledWith(ADMIN_DELETE_COMMUNITY_MESSAGE_CALLABLE, {
      messageId: 'msg-1',
      reason: 'spam',
    });
  });

  it('omits an absent reason from the payload', async () => {
    callAdminMock.mockResolvedValue({ messageId: 'msg-2', deleted: false, resolvedReports: 0 });

    await adminDeleteCommunityMessage('msg-2');

    expect(callAdminMock).toHaveBeenCalledWith(ADMIN_DELETE_COMMUNITY_MESSAGE_CALLABLE, {
      messageId: 'msg-2',
    });
  });

  it('propagates a callable error (e.g. permission-denied) to the caller', async () => {
    callAdminMock.mockRejectedValue(new ApiError(403, 'permission-denied', 'nope'));
    await expect(adminDeleteCommunityMessage('msg-3')).rejects.toMatchObject({
      statusCode: 403,
      code: 'permission-denied',
    });
  });
});
