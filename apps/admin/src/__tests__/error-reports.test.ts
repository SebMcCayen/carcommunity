/**
 * Unit tests for the Phase 13m admin error-reports feature module:
 * mapping/filter/sort behavior over mocked Firestore snapshots of
 * `diagnosticsReports` (the document shape written by
 * functions/src/diagnostics/diagnostics-core.ts).
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';

const getDocMock = vi.fn();
const getDocsMock = vi.fn();

vi.mock('../lib/firestore', () => ({ getAdminFirestore: () => ({}) }));
vi.mock('firebase/firestore', () => ({
  doc: (...segments: unknown[]) => ({ segments }),
  collection: (...segments: unknown[]) => ({ segments }),
  query: (target: unknown, ...clauses: unknown[]) => ({ target, clauses }),
  orderBy: (field: unknown, direction: unknown) => ({ orderBy: field, direction }),
  limit: (value: unknown) => ({ limit: value }),
  getDoc: (...args: unknown[]) => getDocMock(...args),
  getDocs: (...args: unknown[]) => getDocsMock(...args),
}));

import {
  adminGetErrorReport,
  adminListErrorReports,
  ERROR_REPORTS_PAGE_SIZE,
} from '../features/error-reports';

const ts = (iso: string) => ({ toDate: () => new Date(iso) });

const reportData = (overrides: Record<string, unknown> = {}) => ({
  userId: 'u1',
  severity: 'error',
  platform: 'android',
  featureArea: 'events',
  safeMessage: 'Kunde inte ladda eventlistan',
  appVersion: '1.2.0',
  buildNumber: '42',
  osVersion: 'Android 15',
  errorCode: 'network_timeout',
  metadata: { retryCount: 3, endpoint: 'events.list' },
  fingerprint: 'abc123',
  createdAt: ts('2026-07-05T10:00:00Z'),
  ...overrides,
});

beforeEach(() => {
  getDocMock.mockReset();
  getDocsMock.mockReset();
});

describe('error-reports module — list', () => {
  it('maps report docs into summaries (metadata excluded from the list view)', async () => {
    getDocsMock.mockResolvedValue({ docs: [{ id: 'r1', data: () => reportData() }] });

    const page = await adminListErrorReports();
    expect(page.reports).toHaveLength(1);
    expect(page.reports[0]).toEqual({
      id: 'r1',
      userId: 'u1',
      severity: 'error',
      platform: 'android',
      featureArea: 'events',
      safeMessage: 'Kunde inte ladda eventlistan',
      errorCode: 'network_timeout',
      appVersion: '1.2.0',
      buildNumber: '42',
      osVersion: 'Android 15',
      fingerprint: 'abc123',
      createdAt: '2026-07-05T10:00:00.000Z',
    });
    expect('metadata' in page.reports[0]!).toBe(false);
    expect(page.hasNext).toBe(false);
  });

  it('queries newest-first with the page limit (never the whole collection)', async () => {
    getDocsMock.mockResolvedValue({ docs: [] });
    await adminListErrorReports();
    const queried = getDocsMock.mock.calls[0]![0] as { clauses: unknown[] };
    expect(queried.clauses).toContainEqual({ orderBy: 'createdAt', direction: 'desc' });
    expect(queried.clauses).toContainEqual({ limit: ERROR_REPORTS_PAGE_SIZE });
  });

  it('applies the severity filter client-side over the fetched page', async () => {
    getDocsMock.mockResolvedValue({
      docs: [
        { id: 'r1', data: () => reportData({ severity: 'critical' }) },
        { id: 'r2', data: () => reportData({ severity: 'info' }) },
      ],
    });
    const page = await adminListErrorReports({ severity: 'critical' });
    expect(page.reports).toHaveLength(1);
    expect(page.reports[0]).toMatchObject({ id: 'r1', severity: 'critical' });
  });

  it('applies the platform filter and combines it with severity', async () => {
    getDocsMock.mockResolvedValue({
      docs: [
        { id: 'r1', data: () => reportData({ severity: 'error', platform: 'android' }) },
        { id: 'r2', data: () => reportData({ severity: 'error', platform: 'web' }) },
        { id: 'r3', data: () => reportData({ severity: 'info', platform: 'web' }) },
      ],
    });
    const page = await adminListErrorReports({ severity: 'error', platform: 'web' });
    expect(page.reports.map((r) => r.id)).toEqual(['r2']);
  });

  it('bases hasNext on the unfiltered fetch, not the filtered count', async () => {
    getDocsMock.mockResolvedValue({
      docs: Array.from({ length: ERROR_REPORTS_PAGE_SIZE }, (_, i) => ({
        id: `r${i}`,
        data: () => reportData({ severity: i === 0 ? 'critical' : 'info' }),
      })),
    });
    const page = await adminListErrorReports({ severity: 'critical' });
    expect(page.reports).toHaveLength(1);
    // A full unfiltered page means older reports exist in Firestore.
    expect(page.hasNext).toBe(true);
  });

  it('coerces malformed enum fields to safe defaults (never critical)', async () => {
    getDocsMock.mockResolvedValue({
      docs: [
        {
          id: 'r1',
          data: () => ({
            // Unknown severity must never read as error/critical.
            severity: 'catastrophic',
            platform: 'windows_phone',
            featureArea: 'time_travel',
            // Non-string scalars must not leak through as strings.
            safeMessage: 12345,
            errorCode: 7,
            userId: false,
            createdAt: 'not-a-date',
          }),
        },
      ],
    });
    const page = await adminListErrorReports();
    expect(page.reports[0]).toEqual({
      id: 'r1',
      userId: null,
      severity: 'info',
      platform: 'unknown',
      featureArea: 'unknown',
      safeMessage: '',
      errorCode: null,
      appVersion: null,
      buildNumber: null,
      osVersion: null,
      fingerprint: null,
      createdAt: null,
    });
  });

  it('accepts already-serialized createdAt strings (permissive toIso)', async () => {
    getDocsMock.mockResolvedValue({
      docs: [{ id: 'r1', data: () => reportData({ createdAt: '2026-07-01T08:30:00.000Z' }) }],
    });
    const page = await adminListErrorReports();
    expect(page.reports[0]!.createdAt).toBe('2026-07-01T08:30:00.000Z');
  });

  it('preserves anonymous reports (userId null)', async () => {
    getDocsMock.mockResolvedValue({
      docs: [{ id: 'r1', data: () => reportData({ userId: null }) }],
    });
    const page = await adminListErrorReports();
    expect(page.reports[0]!.userId).toBeNull();
  });
});

describe('error-reports module — detail', () => {
  it('maps the full report including the server-sanitized metadata', async () => {
    getDocMock.mockResolvedValue({ data: () => reportData() });
    const detail = await adminGetErrorReport('r1');
    expect(detail).toMatchObject({
      id: 'r1',
      severity: 'error',
      platform: 'android',
      safeMessage: 'Kunde inte ladda eventlistan',
      fingerprint: 'abc123',
      metadata: { retryCount: 3, endpoint: 'events.list' },
      createdAt: '2026-07-05T10:00:00.000Z',
    });
  });

  it('resolves a missing report document to null', async () => {
    getDocMock.mockResolvedValue({ data: () => undefined });
    expect(await adminGetErrorReport('missing')).toBeNull();
  });

  it('coerces non-object or empty metadata to null', async () => {
    getDocMock.mockResolvedValueOnce({ data: () => reportData({ metadata: 'oops' }) });
    expect((await adminGetErrorReport('r1'))!.metadata).toBeNull();

    getDocMock.mockResolvedValueOnce({ data: () => reportData({ metadata: [1, 2] }) });
    expect((await adminGetErrorReport('r2'))!.metadata).toBeNull();

    getDocMock.mockResolvedValueOnce({ data: () => reportData({ metadata: {} }) });
    expect((await adminGetErrorReport('r3'))!.metadata).toBeNull();

    getDocMock.mockResolvedValueOnce({ data: () => reportData({ metadata: null }) });
    expect((await adminGetErrorReport('r4'))!.metadata).toBeNull();
  });
});
