/**
 * Unit tests for the Phase 13r admin support (feedback-inbox) feature module:
 * mapping/filter/sort behavior over mocked Firestore snapshots of
 * `feedbackReports` (the document shape written by
 * functions/src/feedback/feedback-core.ts, buildFeedbackReportDocument).
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
  adminGetFeedbackReport,
  adminListFeedbackReports,
  SUPPORT_PAGE_SIZE,
} from '../features/support';

const ts = (iso: string) => ({ toDate: () => new Date(iso) });

const reportData = (overrides: Record<string, unknown> = {}) => ({
  uid: 'user-abc-1234567890',
  platform: 'android',
  summary: 'Kartan laddar inte',
  description: 'Kartan blir svart när jag öppnar en grupp-körning på min telefon.',
  appVersion: '1.4.0',
  osVersion: 'Android 15',
  deviceModel: 'Pixel 8',
  githubIssueStatus: 'created',
  githubIssueNumber: 321,
  githubIssueUrl: 'https://github.com/SebMcCayen/carcommunity/issues/321',
  createdAt: ts('2026-07-08T09:30:00Z'),
  ...overrides,
});

beforeEach(() => {
  getDocMock.mockReset();
  getDocsMock.mockReset();
});

describe('support module — list', () => {
  it('maps report docs into summaries (doc id is canonical)', async () => {
    getDocsMock.mockResolvedValue({ docs: [{ id: 'r1', data: () => reportData() }] });

    const page = await adminListFeedbackReports();
    expect(page.reports).toHaveLength(1);
    expect(page.reports[0]).toEqual({
      id: 'r1',
      uid: 'user-abc-1234567890',
      platform: 'android',
      summary: 'Kartan laddar inte',
      description: 'Kartan blir svart när jag öppnar en grupp-körning på min telefon.',
      appVersion: '1.4.0',
      osVersion: 'Android 15',
      deviceModel: 'Pixel 8',
      githubIssueStatus: 'created',
      githubIssueNumber: 321,
      githubIssueUrl: 'https://github.com/SebMcCayen/carcommunity/issues/321',
      createdAt: '2026-07-08T09:30:00.000Z',
    });
  });

  it('takes the id from the doc id, ignoring any stored id field', async () => {
    getDocsMock.mockResolvedValue({
      docs: [{ id: 'canonical', data: () => reportData({ id: 'spoofed' }) }],
    });
    const page = await adminListFeedbackReports();
    expect(page.reports[0]!.id).toBe('canonical');
  });

  it('queries newest-first with the page limit (never the whole collection)', async () => {
    getDocsMock.mockResolvedValue({ docs: [] });
    await adminListFeedbackReports();
    const queried = getDocsMock.mock.calls[0]![0] as { clauses: unknown[] };
    expect(queried.clauses).toContainEqual({ orderBy: 'createdAt', direction: 'desc' });
    expect(queried.clauses).toContainEqual({ limit: SUPPORT_PAGE_SIZE });
  });

  it('queries feedbackReports with NO where-clause (single-field index only)', async () => {
    getDocsMock.mockResolvedValue({ docs: [] });
    await adminListFeedbackReports({ githubIssueStatus: 'created', platform: 'android' });
    const queried = getDocsMock.mock.calls[0]![0] as { clauses: unknown[] };
    // No `where` clause is ever added — filtering is client-side.
    for (const clause of queried.clauses) {
      expect(clause).not.toHaveProperty('where');
    }
  });

  it('applies the status filter client-side over the fetched page', async () => {
    getDocsMock.mockResolvedValue({
      docs: [
        { id: 'r1', data: () => reportData({ githubIssueStatus: 'created' }) },
        {
          id: 'r2',
          data: () =>
            reportData({
              githubIssueStatus: 'failed',
              githubIssueNumber: null,
              githubIssueUrl: null,
            }),
        },
      ],
    });
    const page = await adminListFeedbackReports({ githubIssueStatus: 'failed' });
    expect(page.reports).toHaveLength(1);
    expect(page.reports[0]).toMatchObject({ id: 'r2', githubIssueStatus: 'failed' });
  });

  it('applies the platform filter and combines it with status', async () => {
    getDocsMock.mockResolvedValue({
      docs: [
        {
          id: 'r1',
          data: () => reportData({ githubIssueStatus: 'created', platform: 'android' }),
        },
        {
          id: 'r2',
          data: () => reportData({ githubIssueStatus: 'failed', platform: 'android' }),
        },
      ],
    });
    const page = await adminListFeedbackReports({
      githubIssueStatus: 'created',
      platform: 'android',
    });
    expect(page.reports.map((r) => r.id)).toEqual(['r1']);
  });

  it('bases hasNext on the unfiltered fetch, not the filtered count', async () => {
    getDocsMock.mockResolvedValue({
      docs: Array.from({ length: SUPPORT_PAGE_SIZE }, (_, i) => ({
        id: `r${i}`,
        data: () => reportData({ githubIssueStatus: i === 0 ? 'failed' : 'created' }),
      })),
    });
    const page = await adminListFeedbackReports({ githubIssueStatus: 'failed' });
    expect(page.reports).toHaveLength(1);
    // A full unfiltered page means older reports exist in Firestore.
    expect(page.hasNext).toBe(true);
  });

  it('reports hasNext=false on a short page', async () => {
    getDocsMock.mockResolvedValue({
      docs: [{ id: 'r1', data: () => reportData() }],
    });
    const page = await adminListFeedbackReports();
    expect(page.hasNext).toBe(false);
  });

  it('accepts already-serialized createdAt strings (permissive toIso)', async () => {
    getDocsMock.mockResolvedValue({
      docs: [{ id: 'r1', data: () => reportData({ createdAt: '2026-07-01T08:30:00.000Z' }) }],
    });
    const page = await adminListFeedbackReports();
    expect(page.reports[0]!.createdAt).toBe('2026-07-01T08:30:00.000Z');
  });

  it('coerces a malformed createdAt to null (toDate throws / bad string)', async () => {
    getDocsMock.mockResolvedValue({
      docs: [
        { id: 'r1', data: () => reportData({ createdAt: 'not-a-date' }) },
        { id: 'r2', data: () => reportData({ createdAt: { toDate: () => new Date('nope') } }) },
        { id: 'r3', data: () => reportData({ createdAt: 12345 }) },
      ],
    });
    const page = await adminListFeedbackReports();
    expect(page.reports.map((r) => r.createdAt)).toEqual([null, null, null]);
  });

  it('renders missing/unknown githubIssue fields as failed with null number/url', async () => {
    getDocsMock.mockResolvedValue({
      docs: [
        {
          id: 'r1',
          data: () => {
            const d = reportData();
            // Simulate a doc written before the GitHub patch, or a partial doc.
            delete (d as Record<string, unknown>).githubIssueStatus;
            delete (d as Record<string, unknown>).githubIssueNumber;
            delete (d as Record<string, unknown>).githubIssueUrl;
            return d;
          },
        },
        { id: 'r2', data: () => reportData({ githubIssueStatus: 'bogus' }) },
      ],
    });
    const page = await adminListFeedbackReports();
    expect(page.reports[0]).toMatchObject({
      githubIssueStatus: 'failed',
      githubIssueNumber: null,
      githubIssueUrl: null,
    });
    // An unknown stored status never masquerades as `created`.
    expect(page.reports[1]!.githubIssueStatus).toBe('failed');
  });

  it('preserves the pending (transient) status when stored', async () => {
    getDocsMock.mockResolvedValue({
      docs: [
        {
          id: 'r1',
          data: () =>
            reportData({
              githubIssueStatus: 'pending',
              githubIssueNumber: null,
              githubIssueUrl: null,
            }),
        },
      ],
    });
    const page = await adminListFeedbackReports();
    expect(page.reports[0]!.githubIssueStatus).toBe('pending');
  });

  it('coerces non-scalar / missing text fields to safe defaults', async () => {
    getDocsMock.mockResolvedValue({
      docs: [
        {
          id: 'r1',
          data: () => ({
            uid: 42, // non-string uid must not leak through
            summary: null, // optional summary absent
            // description missing entirely
            githubIssueNumber: 'not-a-number',
            githubIssueUrl: 12,
            createdAt: ts('2026-07-08T09:30:00Z'),
          }),
        },
      ],
    });
    const page = await adminListFeedbackReports();
    expect(page.reports[0]).toMatchObject({
      uid: null,
      platform: 'android',
      summary: null,
      description: '',
      appVersion: null,
      osVersion: null,
      deviceModel: null,
      githubIssueStatus: 'failed',
      githubIssueNumber: null,
      githubIssueUrl: null,
    });
  });
});

describe('support module — detail', () => {
  it('maps the full report by id', async () => {
    getDocMock.mockResolvedValue({ data: () => reportData() });
    const detail = await adminGetFeedbackReport('r1');
    expect(detail).toMatchObject({
      id: 'r1',
      uid: 'user-abc-1234567890',
      platform: 'android',
      summary: 'Kartan laddar inte',
      githubIssueStatus: 'created',
      githubIssueNumber: 321,
      githubIssueUrl: 'https://github.com/SebMcCayen/carcommunity/issues/321',
      createdAt: '2026-07-08T09:30:00.000Z',
    });
  });

  it('resolves a missing report document to null', async () => {
    getDocMock.mockResolvedValue({ data: () => undefined });
    expect(await adminGetFeedbackReport('missing')).toBeNull();
  });

  it('maps a failed report (no GitHub cross-link) with null number/url', async () => {
    getDocMock.mockResolvedValue({
      data: () =>
        reportData({ githubIssueStatus: 'failed', githubIssueNumber: null, githubIssueUrl: null }),
    });
    const detail = await adminGetFeedbackReport('r1');
    expect(detail).toMatchObject({
      githubIssueStatus: 'failed',
      githubIssueNumber: null,
      githubIssueUrl: null,
    });
  });
});
