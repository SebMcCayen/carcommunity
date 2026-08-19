/**
 * Unit tests for the public leaderboard JSON builder
 * (leaderboard/publicLeaderboard-core.ts). Pure — no Firestore, no network.
 *
 * Proves the four invariants the public file must hold: top-3 truncation,
 * PII stripped to public fields (no uid), a missing scope handled gracefully,
 * and `streak` present in all-time but absent from the monthly block.
 */

import { describe, expect, it } from 'vitest';
import {
  PUBLIC_LEADERBOARD_TOP_N,
  buildPublicLeaderboardFile,
  buildPublicMonthBlock,
  homepageLeaderboardEquivalent,
  publicCategoryRows,
  type PublicLeaderboardFile,
  type StoredCategories,
  type StoredLeaderboardRow,
} from '../leaderboard/publicLeaderboard-core';

const GENERATED_AT = new Date('2026-08-16T10:00:00Z');

/** N stored rows ranked 1..N, values descending. */
function storedRows(n: number): StoredLeaderboardRow[] {
  return Array.from({ length: n }, (_, i) => ({
    rank: i + 1,
    uid: `uid-${i + 1}`,
    displayName: `Member ${i + 1}`,
    avatarPath: i % 2 === 0 ? `profileImages/m${i + 1}/a.jpg` : null,
    value: 100 - i,
  }));
}

function fullCategories(): StoredCategories {
  return {
    crownPoints: storedRows(5),
    distance: storedRows(4),
    events: storedRows(2),
    convoys: storedRows(1),
    waves: storedRows(3),
    streak: storedRows(5),
  };
}

function parse(content: string): PublicLeaderboardFile {
  return JSON.parse(content) as PublicLeaderboardFile;
}

describe('publicCategoryRows', () => {
  it('truncates to the public top-3 even when the source has more', () => {
    const rows = publicCategoryRows(storedRows(10));
    expect(rows).toHaveLength(PUBLIC_LEADERBOARD_TOP_N);
    expect(rows.map((r) => r.rank)).toEqual([1, 2, 3]);
    expect(rows.map((r) => r.displayName)).toEqual(['Member 1', 'Member 2', 'Member 3']);
  });

  it('strips every field except rank/displayName/avatarPath/value — NO uid', () => {
    const [row] = publicCategoryRows(storedRows(1));
    expect(Object.keys(row!).sort()).toEqual(['avatarPath', 'displayName', 'rank', 'value']);
    expect((row as unknown as Record<string, unknown>).uid).toBeUndefined();
  });

  it('keeps a null avatarPath and re-derives contiguous ranks', () => {
    const rows = publicCategoryRows([
      { uid: 'a', displayName: 'A', avatarPath: null, value: 9 },
      { uid: 'b', displayName: 'B', avatarPath: 'p/b.jpg', value: 8 },
    ]);
    expect(rows[0]).toEqual({ rank: 1, displayName: 'A', avatarPath: null, value: 9 });
    expect(rows[1]!.avatarPath).toBe('p/b.jpg');
  });

  it('drops rows without a usable displayName and re-numbers survivors', () => {
    const rows = publicCategoryRows([
      { uid: 'a', displayName: '', avatarPath: null, value: 9 },
      { uid: 'b', displayName: 'Real', avatarPath: null, value: 8 },
    ]);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toEqual({ rank: 1, displayName: 'Real', avatarPath: null, value: 8 });
  });

  it('coerces a non-finite/negative value to 0', () => {
    const rows = publicCategoryRows([
      { uid: 'a', displayName: 'A', avatarPath: null, value: Number.POSITIVE_INFINITY },
      { uid: 'b', displayName: 'B', avatarPath: null, value: -5 },
    ]);
    expect(rows.map((r) => r.value)).toEqual([0, 0]);
  });

  it('returns an empty array for a missing category', () => {
    expect(publicCategoryRows(undefined)).toEqual([]);
  });

  it('treats a MALFORMED (non-array) category as empty without throwing', () => {
    // A mangled leaderboards/{scope} doc could store a category as null, an
    // object or a scalar; best-effort publishing must never throw on it.
    for (const bad of [null, {}, 42, 'oops', { rank: 1, displayName: 'X' }]) {
      expect(() => publicCategoryRows(bad as never)).not.toThrow();
      expect(publicCategoryRows(bad as never)).toEqual([]);
    }
  });
});

describe('buildPublicLeaderboardFile', () => {
  it('publishes all six categories in the all-time block, waves + streak included', () => {
    const file = parse(buildPublicLeaderboardFile(fullCategories(), null, GENERATED_AT));
    expect(Object.keys(file.alltime).sort()).toEqual([
      'convoys',
      'crownPoints',
      'distance',
      'events',
      'streak',
      'waves',
    ]);
    expect(file.alltime.streak).toHaveLength(3);
    expect(file.alltime.waves).toHaveLength(3);
    expect(file.alltime.crownPoints).toHaveLength(3);
  });

  it('stamps generatedAt and the human notice', () => {
    const file = parse(buildPublicLeaderboardFile(fullCategories(), null, GENERATED_AT));
    expect(file.generatedAt).toBe(GENERATED_AT.toISOString());
    expect(file._generated).toContain('Redigera INTE');
  });

  it('handles a MISSING all-time scope — every category is an empty array, not an error', () => {
    const file = parse(buildPublicLeaderboardFile(null, null, GENERATED_AT));
    expect(file.alltime).toEqual({
      crownPoints: [],
      distance: [],
      events: [],
      convoys: [],
      waves: [],
      streak: [],
    });
    expect(file.month).toBeNull();
  });

  it('omits the month block (null) when no monthly board exists yet', () => {
    const file = parse(buildPublicLeaderboardFile(fullCategories(), null, GENERATED_AT));
    expect(file.month).toBeNull();
  });

  it('the MONTHLY block carries the five monthly categories and NO streak', () => {
    const monthBlock = buildPublicMonthBlock('2026-08', fullCategories());
    const file = parse(buildPublicLeaderboardFile(fullCategories(), monthBlock, GENERATED_AT));
    expect(file.month).not.toBeNull();
    expect(file.month!.yyyymm).toBe('2026-08');
    expect(Object.keys(file.month!).sort()).toEqual([
      'convoys',
      'crownPoints',
      'distance',
      'events',
      'waves',
      'yyyymm',
    ]);
    // streak is an all-time-only category — it must never leak into the month block.
    expect((file.month as Record<string, unknown>).streak).toBeUndefined();
  });

  it('never emits a uid anywhere in the serialized file', () => {
    const monthBlock = buildPublicMonthBlock('2026-08', fullCategories());
    const content = buildPublicLeaderboardFile(fullCategories(), monthBlock, GENERATED_AT);
    expect(content).not.toContain('"uid"');
    expect(content.endsWith('\n')).toBe(true);
  });
});

describe('homepageLeaderboardEquivalent', () => {
  it('is true when only generatedAt differs (skip the commit)', () => {
    const a = buildPublicLeaderboardFile(fullCategories(), null, new Date('2026-08-16T10:00:00Z'));
    const b = buildPublicLeaderboardFile(fullCategories(), null, new Date('2026-08-16T11:00:00Z'));
    expect(homepageLeaderboardEquivalent(a, b)).toBe(true);
  });

  it('is false when a rank changed', () => {
    const a = buildPublicLeaderboardFile(fullCategories(), null, GENERATED_AT);
    const changed = fullCategories();
    changed.crownPoints = storedRows(3).reverse();
    const b = buildPublicLeaderboardFile(changed, null, GENERATED_AT);
    expect(homepageLeaderboardEquivalent(a, b)).toBe(false);
  });

  it('is false for a missing or unparseable existing file', () => {
    const next = buildPublicLeaderboardFile(fullCategories(), null, GENERATED_AT);
    expect(homepageLeaderboardEquivalent(null, next)).toBe(false);
    expect(homepageLeaderboardEquivalent('{not json', next)).toBe(false);
  });
});
