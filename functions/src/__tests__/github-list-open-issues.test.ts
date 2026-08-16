/**
 * Unit tests for listOpenIssues pagination + normalization (shared/githubIssues.ts).
 * global fetch is stubbed — no network, no emulator. Guards the correctness
 * property the openTickets sync depends on: the FULL open set is fetched across
 * pages, and a mid-pagination failure or a page-cap truncation is signalled so
 * the sync never deletes tickets it did not actually see.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { listOpenIssues, OPEN_ISSUES_MAX_PAGES } from '../shared/githubIssues';

const LABEL = 'android-issue';
const TOKEN = 'ghp_test';
const UA = 'test-agent';

/** One well-formed GitHub issue row. */
function row(n: number): Record<string, unknown> {
  return {
    number: n,
    title: `Issue ${n}`,
    body: `body ${n}`,
    html_url: `https://github.com/SebMcCayen/carcommunity/issues/${n}`,
    created_at: '2026-08-16T10:00:00.000Z',
    state: 'open',
    comments: 0,
  };
}

/** A fetch Response stub. */
function okJson(data: unknown): Response {
  return { ok: true, status: 200, json: async () => data } as unknown as Response;
}
function failStatus(status: number): Response {
  return { ok: false, status, json: async () => [] } as unknown as Response;
}

let savedEmu: string | undefined;

beforeEach(() => {
  // The helper short-circuits to null under the emulator; ensure it isn't set.
  savedEmu = process.env.FUNCTIONS_EMULATOR;
  delete process.env.FUNCTIONS_EMULATOR;
});

afterEach(() => {
  if (savedEmu !== undefined) process.env.FUNCTIONS_EMULATOR = savedEmu;
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('listOpenIssues pagination', () => {
  it('accumulates all pages until a short page ends the set (150 across 2 pages)', async () => {
    const page1 = Array.from({ length: 100 }, (_, i) => row(1 + i));
    const page2 = Array.from({ length: 50 }, (_, i) => row(101 + i));
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(okJson(page1))
      .mockResolvedValueOnce(okJson(page2));
    vi.stubGlobal('fetch', fetchMock);

    const result = await listOpenIssues(LABEL, TOKEN, UA);
    expect(result).not.toBeNull();
    expect(result!.complete).toBe(true);
    expect(result!.issues.length).toBe(150);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    // page param increments 1 → 2
    expect(String((fetchMock.mock.calls[0] as unknown[])[0])).toContain('page=1');
    expect(String((fetchMock.mock.calls[1] as unknown[])[0])).toContain('page=2');
  });

  it('returns null if ANY page fails, never a partial set', async () => {
    const page1 = Array.from({ length: 100 }, (_, i) => row(1 + i));
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(okJson(page1))
      .mockResolvedValueOnce(failStatus(502));
    vi.stubGlobal('fetch', fetchMock);

    const result = await listOpenIssues(LABEL, TOKEN, UA);
    expect(result).toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('marks complete:false when the page cap is hit with full pages (truncated)', async () => {
    const fullPage = Array.from({ length: 100 }, (_, i) => row(i + 1));
    const fetchMock = vi.fn().mockResolvedValue(okJson(fullPage));
    vi.stubGlobal('fetch', fetchMock);

    const result = await listOpenIssues(LABEL, TOKEN, UA);
    expect(result).not.toBeNull();
    expect(result!.complete).toBe(false);
    expect(fetchMock).toHaveBeenCalledTimes(OPEN_ISSUES_MAX_PAGES);
    expect(result!.issues.length).toBe(OPEN_ISSUES_MAX_PAGES * 100);
  });

  it('clamps per_page into GitHub 1..100', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okJson([]));
    vi.stubGlobal('fetch', fetchMock);

    await listOpenIssues(LABEL, TOKEN, UA, 500);
    expect(String((fetchMock.mock.calls[0] as unknown[])[0])).toContain('per_page=100');

    fetchMock.mockClear();
    await listOpenIssues(LABEL, TOKEN, UA, 0);
    expect(String((fetchMock.mock.calls[0] as unknown[])[0])).toContain('per_page=1');
  });

  it('drops malformed rows but paginates on RAW page length (not normalized count)', async () => {
    // 100 raw rows, 10 of them malformed → normalized 90, but raw length is 100
    // so the loop MUST fetch a second page rather than stopping early.
    const raw: Record<string, unknown>[] = Array.from({ length: 90 }, (_, i) => row(i + 1));
    for (let i = 0; i < 10; i += 1) raw.push({ number: 'not-a-number' });
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(okJson(raw))
      .mockResolvedValueOnce(okJson([row(200)]));
    vi.stubGlobal('fetch', fetchMock);

    const result = await listOpenIssues(LABEL, TOKEN, UA);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(result!.complete).toBe(true);
    expect(result!.issues.length).toBe(91); // 90 valid from page 1 + 1 from page 2
  });

  it('returns null in the emulator and without a token', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    expect(await listOpenIssues(LABEL, '', UA)).toBeNull();

    process.env.FUNCTIONS_EMULATOR = 'true';
    expect(await listOpenIssues(LABEL, TOKEN, UA)).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
