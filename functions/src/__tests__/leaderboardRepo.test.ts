/**
 * Unit tests for the homepage-repo GitHub Contents sync
 * (leaderboard/leaderboardRepo.ts) against a mocked global fetch. No emulators,
 * no network. Mirrors events/__tests__/homepageRepo.test.ts.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  HOMEPAGE_LEADERBOARD_FILE_URL,
  HOMEPAGE_LEADERBOARD_COMMIT_MESSAGE,
  HOMEPAGE_REPO_BRANCH,
  syncHomepageLeaderboardFile,
} from '../leaderboard/leaderboardRepo';
import {
  buildPublicLeaderboardFile,
  type StoredCategories,
} from '../leaderboard/publicLeaderboard-core';

const TOKEN = 'test-token';

const CATEGORIES: StoredCategories = {
  crownPoints: [{ rank: 1, uid: 'u1', displayName: 'Anna', avatarPath: null, value: 120 }],
  distance: [{ rank: 1, uid: 'u2', displayName: 'Bo', avatarPath: null, value: 5000 }],
  events: [],
  convoys: [],
  streak: [],
};

const NEXT_CONTENT = buildPublicLeaderboardFile(CATEGORIES, null, new Date('2026-08-16T10:00:00Z'));

function githubFileResponse(content: string, sha = 'sha-1'): Response {
  return new Response(
    JSON.stringify({ sha, content: Buffer.from(content, 'utf8').toString('base64') }),
    { status: 200 },
  );
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  delete process.env.FUNCTIONS_EMULATOR;
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function putCalls() {
  return fetchMock.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === 'PUT');
}

describe('syncHomepageLeaderboardFile', () => {
  it('SKIPS the commit when the stored file differs only in generatedAt', async () => {
    const stored = buildPublicLeaderboardFile(CATEGORIES, null, new Date('2026-08-16T04:00:00Z'));
    fetchMock.mockResolvedValueOnce(githubFileResponse(stored));

    expect(await syncHomepageLeaderboardFile(NEXT_CONTENT, TOKEN)).toBe('unchanged');
    expect(putCalls()).toHaveLength(0);
  });

  it('commits with the current sha when the board changed, pinned to the deploy branch', async () => {
    const stored = buildPublicLeaderboardFile(null, null, new Date('2026-08-16T04:00:00Z'));
    fetchMock
      .mockResolvedValueOnce(githubFileResponse(stored, 'old-sha'))
      .mockResolvedValueOnce(new Response('{}', { status: 200 }));

    expect(await syncHomepageLeaderboardFile(NEXT_CONTENT, TOKEN)).toBe('committed');

    const [getUrl] = fetchMock.mock.calls[0] as [string];
    expect(getUrl).toBe(`${HOMEPAGE_LEADERBOARD_FILE_URL}?ref=${HOMEPAGE_REPO_BRANCH}`);

    const [url, init] = putCalls()[0] as [string, RequestInit];
    expect(url).toBe(HOMEPAGE_LEADERBOARD_FILE_URL);
    const body = JSON.parse(String(init.body)) as {
      message: string;
      branch: string;
      content: string;
      sha?: string;
    };
    expect(body.message).toBe(HOMEPAGE_LEADERBOARD_COMMIT_MESSAGE);
    expect(body.branch).toBe(HOMEPAGE_REPO_BRANCH);
    expect(body.sha).toBe('old-sha');
    expect(Buffer.from(body.content, 'base64').toString('utf8')).toBe(NEXT_CONTENT);
  });

  it('creates the file (no sha) when it does not exist yet', async () => {
    fetchMock
      .mockResolvedValueOnce(new Response('{"message":"Not Found"}', { status: 404 }))
      .mockResolvedValueOnce(new Response('{}', { status: 201 }));

    expect(await syncHomepageLeaderboardFile(NEXT_CONTENT, TOKEN)).toBe('committed');
    const [, init] = putCalls()[0] as [string, RequestInit];
    const body = JSON.parse(String(init.body)) as Record<string, unknown>;
    expect(body).not.toHaveProperty('sha');
    expect(body.branch).toBe(HOMEPAGE_REPO_BRANCH);
  });

  it('retries ONCE on a 409 sha conflict with a fresh sha', async () => {
    const stored = buildPublicLeaderboardFile(null, null, new Date('2026-08-16T04:00:00Z'));
    fetchMock
      .mockResolvedValueOnce(githubFileResponse(stored, 'stale-sha'))
      .mockResolvedValueOnce(new Response('{}', { status: 409 }))
      .mockResolvedValueOnce(githubFileResponse(stored, 'fresh-sha'))
      .mockResolvedValueOnce(new Response('{}', { status: 200 }));

    expect(await syncHomepageLeaderboardFile(NEXT_CONTENT, TOKEN)).toBe('committed');
    const retryBody = JSON.parse(String((putCalls()[1] as [string, RequestInit])[1].body)) as {
      sha?: string;
    };
    expect(retryBody.sha).toBe('fresh-sha');
  });

  it('fails (never throws) on an empty token, without calling GitHub', async () => {
    expect(await syncHomepageLeaderboardFile(NEXT_CONTENT, '')).toBe('failed');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('fails (never throws) on GitHub errors and network faults', async () => {
    fetchMock.mockResolvedValueOnce(new Response('{}', { status: 500 }));
    expect(await syncHomepageLeaderboardFile(NEXT_CONTENT, TOKEN)).toBe('failed');

    fetchMock.mockRejectedValueOnce(new Error('network down'));
    expect(await syncHomepageLeaderboardFile(NEXT_CONTENT, TOKEN)).toBe('failed');
  });

  it('never reaches GitHub from the Functions emulator', async () => {
    process.env.FUNCTIONS_EMULATOR = 'true';
    try {
      expect(await syncHomepageLeaderboardFile(NEXT_CONTENT, TOKEN)).toBe('skipped-emulator');
      expect(fetchMock).not.toHaveBeenCalled();
    } finally {
      delete process.env.FUNCTIONS_EMULATOR;
    }
  });
});
