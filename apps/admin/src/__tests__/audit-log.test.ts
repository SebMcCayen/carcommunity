/**
 * Unit tests for the Phase 13n audit-log feature module: adminAuditEvents
 * document mapping, unknown-action label fallback, targetId/cursor query
 * construction, and the client-side action filter — over a mocked Firestore.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';

const getDocsMock = vi.fn();

interface MockConstraint {
  type: 'where' | 'orderBy' | 'limit' | 'startAfter';
  [key: string]: unknown;
}

vi.mock('../lib/firestore', () => ({ getAdminFirestore: () => ({}) }));
vi.mock('firebase/firestore', () => ({
  collection: (_db: unknown, path: string) => ({ path }),
  query: (target: unknown, ...constraints: unknown[]) => ({ target, constraints }),
  where: (field: string, op: string, value: unknown) => ({ type: 'where', field, op, value }),
  orderBy: (field: string, direction: string) => ({ type: 'orderBy', field, direction }),
  limit: (n: number) => ({ type: 'limit', n }),
  startAfter: (cursor: unknown) => ({ type: 'startAfter', cursor }),
  getDocs: (...args: unknown[]) => getDocsMock(...args),
}));

import {
  AUDIT_LOG_PAGE_SIZE,
  auditActionLabelKey,
  filterEventsByAction,
  KNOWN_AUDIT_ACTIONS,
  listAdminAuditEvents,
  type AdminAuditEventRow,
} from '../features/audit-log';

/** Builds a fake QueryDocumentSnapshot. */
function fakeDoc(id: string, data: Record<string, unknown>) {
  return { id, data: () => data };
}

/** Extracts the constraint list from the query object getDocs received. */
function constraintsOfLastQuery(): MockConstraint[] {
  const queryArg = getDocsMock.mock.calls.at(-1)![0] as { constraints: MockConstraint[] };
  return queryArg.constraints;
}

beforeEach(() => {
  getDocsMock.mockReset();
});

describe('audit-log module — document mapping', () => {
  it('maps a full adminAuditEvents document (Timestamp createdAt) to a row', async () => {
    getDocsMock.mockResolvedValue({
      docs: [
        fakeDoc('evt-1', {
          adminId: 'admin-1',
          action: 'user.suspend',
          targetType: 'user',
          targetId: 'user-9',
          reason: 'Upprepade regelbrott',
          details: { durationDays: 7 },
          createdAt: { toDate: () => new Date('2026-07-01T10:00:00.000Z') },
        }),
      ],
    });

    const page = await listAdminAuditEvents();
    expect(page.events).toEqual([
      {
        id: 'evt-1',
        adminId: 'admin-1',
        action: 'user.suspend',
        targetType: 'user',
        targetId: 'user-9',
        reason: 'Upprepade regelbrott',
        details: { durationDays: 7 },
        createdAt: '2026-07-01T10:00:00.000Z',
      } satisfies AdminAuditEventRow,
    ]);
  });

  it('tolerates partial/corrupt documents (permissive mapping)', async () => {
    getDocsMock.mockResolvedValue({
      docs: [
        fakeDoc('evt-2', {
          action: 42, // corrupt — non-string
          createdAt: 'not-a-date',
          details: ['not', 'an', 'object'],
        }),
        fakeDoc('evt-3', {
          action: 'points.adminAdjust',
          adminId: 'admin-2',
          targetType: 'user',
          targetId: 'user-1',
          reason: 'Justering',
          createdAt: '2026-06-30T08:30:00.000Z', // already-serialized string
        }),
      ],
    });

    const page = await listAdminAuditEvents();
    expect(page.events[0]).toMatchObject({
      id: 'evt-2',
      action: '',
      adminId: '',
      targetType: '',
      targetId: '',
      reason: '',
      details: null,
      createdAt: null,
    });
    expect(page.events[1]?.createdAt).toBe('2026-06-30T08:30:00.000Z');
  });

  it('maps malformed Timestamp-like createdAt values to null instead of throwing', async () => {
    getDocsMock.mockResolvedValue({
      docs: [
        // toDate() throws — the row must still map, with createdAt null.
        fakeDoc('evt-throw', {
          action: 'user.warn',
          createdAt: {
            toDate: () => {
              throw new Error('corrupt timestamp');
            },
          },
        }),
        // toDate() returns an invalid Date — toISOString() would RangeError.
        fakeDoc('evt-invalid', {
          action: 'user.warn',
          createdAt: { toDate: () => new Date('nonsense') },
        }),
        // toDate() returns a non-Date value.
        fakeDoc('evt-nondate', {
          action: 'user.warn',
          createdAt: { toDate: () => 'not a date' },
        }),
        // A valid sibling row proves the mapping survives the bad ones.
        fakeDoc('evt-ok', {
          action: 'user.warn',
          createdAt: { toDate: () => new Date('2026-07-02T12:00:00.000Z') },
        }),
      ],
    });

    const page = await listAdminAuditEvents();
    expect(page.events.map((e) => e.createdAt)).toEqual([
      null,
      null,
      null,
      '2026-07-02T12:00:00.000Z',
    ]);
  });
});

describe('audit-log module — action labels', () => {
  it('returns an i18n key for every known action', () => {
    for (const action of KNOWN_AUDIT_ACTIONS) {
      const key = auditActionLabelKey(action);
      expect(key, `label key for ${action}`).toMatch(/^auditLog\.action\.[A-Za-z]+$/);
    }
  });

  it('returns null for unknown actions (raw-value fallback)', () => {
    expect(auditActionLabelKey('some.future.action')).toBeNull();
    expect(auditActionLabelKey('')).toBeNull();
    // Prototype-pollution-shaped keys must not resolve to anything.
    expect(auditActionLabelKey('constructor')).toBeNull();
  });
});

describe('audit-log module — query construction', () => {
  it('lists newest-first with the default page size and no targetId filter', async () => {
    getDocsMock.mockResolvedValue({ docs: [] });
    await listAdminAuditEvents();

    const constraints = constraintsOfLastQuery();
    expect(constraints).toEqual([
      { type: 'orderBy', field: 'createdAt', direction: 'desc' },
      { type: 'limit', n: AUDIT_LOG_PAGE_SIZE },
    ]);
    const queryArg = getDocsMock.mock.calls.at(-1)![0] as { target: { path: string } };
    expect(queryArg.target.path).toBe('adminAuditEvents');
  });

  it('adds an equality where clause for the targetId filter (composite index)', async () => {
    getDocsMock.mockResolvedValue({ docs: [] });
    await listAdminAuditEvents({ targetId: '  user-9  ' });

    const constraints = constraintsOfLastQuery();
    expect(constraints).toEqual([
      { type: 'where', field: 'targetId', op: '==', value: 'user-9' },
      { type: 'orderBy', field: 'createdAt', direction: 'desc' },
      { type: 'limit', n: AUDIT_LOG_PAGE_SIZE },
    ]);
  });

  it('ignores a blank targetId filter', async () => {
    getDocsMock.mockResolvedValue({ docs: [] });
    await listAdminAuditEvents({ targetId: '   ' });
    expect(constraintsOfLastQuery().some((c) => c.type === 'where')).toBe(false);
  });
});

describe('audit-log module — cursor logic', () => {
  const fullPage = (offset: number) =>
    Array.from({ length: AUDIT_LOG_PAGE_SIZE }, (_, i) =>
      fakeDoc(`evt-${offset + i}`, {
        action: 'user.warn',
        adminId: 'admin-1',
        targetType: 'user',
        targetId: 'user-9',
        reason: 'Test',
        createdAt: { toDate: () => new Date('2026-07-01T10:00:00.000Z') },
      }),
    );

  it('returns the last snapshot as cursor on a full page and null on a short one', async () => {
    getDocsMock.mockResolvedValueOnce({ docs: fullPage(0) });
    const first = await listAdminAuditEvents();
    expect(first.events).toHaveLength(AUDIT_LOG_PAGE_SIZE);
    expect(first.cursor).not.toBeNull();
    expect((first.cursor as unknown as { id: string }).id).toBe(
      `evt-${AUDIT_LOG_PAGE_SIZE - 1}`,
    );

    getDocsMock.mockResolvedValueOnce({ docs: [fakeDoc('evt-x', { action: 'user.warn' })] });
    const second = await listAdminAuditEvents({ cursor: first.cursor });
    expect(second.cursor).toBeNull();
  });

  it('passes the cursor through startAfter, after orderBy and before limit', async () => {
    getDocsMock.mockResolvedValueOnce({ docs: fullPage(0) });
    const first = await listAdminAuditEvents({ targetId: 'user-9' });

    getDocsMock.mockResolvedValueOnce({ docs: [] });
    await listAdminAuditEvents({ targetId: 'user-9', cursor: first.cursor });

    const constraints = constraintsOfLastQuery();
    expect(constraints.map((c) => c.type)).toEqual(['where', 'orderBy', 'startAfter', 'limit']);
    const startAfterConstraint = constraints.find((c) => c.type === 'startAfter')!;
    expect((startAfterConstraint.cursor as { id: string }).id).toBe(
      `evt-${AUDIT_LOG_PAGE_SIZE - 1}`,
    );
  });

  it('an empty result page yields no cursor', async () => {
    getDocsMock.mockResolvedValue({ docs: [] });
    const page = await listAdminAuditEvents();
    expect(page.events).toEqual([]);
    expect(page.cursor).toBeNull();
  });
});

describe('audit-log module — client-side action filter', () => {
  const rows: AdminAuditEventRow[] = [
    { id: '1', action: 'user.warn', adminId: 'a', targetType: 'user', targetId: 't1', reason: '', details: null, createdAt: null },
    { id: '2', action: 'user.suspend', adminId: 'a', targetType: 'user', targetId: 't2', reason: '', details: null, createdAt: null },
    { id: '3', action: 'user.warn', adminId: 'b', targetType: 'user', targetId: 't3', reason: '', details: null, createdAt: null },
  ];

  it('returns all rows for a blank filter', () => {
    expect(filterEventsByAction(rows, '')).toEqual(rows);
    expect(filterEventsByAction(rows, '   ')).toEqual(rows);
  });

  it('keeps only exact action matches', () => {
    expect(filterEventsByAction(rows, 'user.warn').map((r) => r.id)).toEqual(['1', '3']);
    expect(filterEventsByAction(rows, ' user.suspend ').map((r) => r.id)).toEqual(['2']);
    expect(filterEventsByAction(rows, 'no.such.action')).toEqual([]);
  });
});
