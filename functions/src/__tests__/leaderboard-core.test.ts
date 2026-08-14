/**
 * Social leaderboard PURE logic — unit tests.
 *
 * Everything the leaderboard assembles without Firestore: ranking by value with
 * the uid tiebreak, dropping non-positive values, the top-N cut, the
 * contiguous re-ranking after opt-outs and deleted members are removed, and the
 * candidate-uid set the generator resolves identities for. The emulator test
 * (leaderboard.emulator.test.ts) proves the wiring; this proves the assembly.
 */

import { describe, expect, it } from 'vitest';
import {
  LEADERBOARD_ALL_TIME_SCOPE,
  LEADERBOARD_CATEGORIES,
  LEADERBOARD_TOP_N,
  buildLeaderboardCategory,
  candidateUidsToResolve,
  readCandidateValue,
  topCandidates,
  type LeaderboardCandidate,
  type LeaderboardCategoryKey,
  type LeaderboardIdentity,
} from '../leaderboard/leaderboard-core';

const id = (displayName: string, avatarPath: string | null = null): LeaderboardIdentity => ({
  displayName,
  avatarPath,
});

/** An identity map that names every uid `<uid>-name` with no avatar. */
function identitiesFor(uids: readonly string[]): Map<string, LeaderboardIdentity> {
  return new Map(uids.map((uid) => [uid, id(`${uid}-name`)]));
}

describe('constants', () => {
  it('exposes the all-time scope and a top-10 board of five categories', () => {
    expect(LEADERBOARD_ALL_TIME_SCOPE).toBe('alltime');
    expect(LEADERBOARD_TOP_N).toBe(10);
    expect([...LEADERBOARD_CATEGORIES]).toEqual([
      'crownPoints',
      'distance',
      'events',
      'convoys',
      'streak',
    ]);
  });
});

describe('readCandidateValue', () => {
  it('accepts finite non-negative numbers (fractional preserved)', () => {
    expect(readCandidateValue(0)).toBe(0);
    expect(readCandidateValue(42)).toBe(42);
    expect(readCandidateValue(1234.5)).toBe(1234.5);
  });

  it('rejects missing, negative, NaN, Infinity and non-numbers as 0', () => {
    expect(readCandidateValue(undefined)).toBe(0);
    expect(readCandidateValue(null)).toBe(0);
    expect(readCandidateValue(-5)).toBe(0);
    expect(readCandidateValue(Number.NaN)).toBe(0);
    expect(readCandidateValue(Number.POSITIVE_INFINITY)).toBe(0);
    expect(readCandidateValue('100')).toBe(0);
  });
});

describe('topCandidates ranking', () => {
  it('orders by value DESC then uid ASC, dropping non-positive values', () => {
    const input: LeaderboardCandidate[] = [
      { uid: 'c', value: 50 },
      { uid: 'a', value: 100 },
      { uid: 'b', value: 100 }, // tie with a → uid ascending puts a first
      { uid: 'z', value: 0 }, // dropped (not positive)
      { uid: 'y', value: -3 }, // dropped
    ];
    expect(topCandidates(input, 10).map((c) => c.uid)).toEqual(['a', 'b', 'c']);
  });

  it('honours the retain cap', () => {
    const input: LeaderboardCandidate[] = [
      { uid: 'a', value: 5 },
      { uid: 'b', value: 4 },
      { uid: 'c', value: 3 },
    ];
    expect(topCandidates(input, 2).map((c) => c.uid)).toEqual(['a', 'b']);
    expect(topCandidates(input, 0)).toEqual([]);
  });

  it('does not mutate its input', () => {
    const input: LeaderboardCandidate[] = [
      { uid: 'a', value: 1 },
      { uid: 'b', value: 2 },
    ];
    topCandidates(input, 10);
    expect(input.map((c) => c.uid)).toEqual(['a', 'b']);
  });
});

describe('buildLeaderboardCategory', () => {
  it('ranks, resolves identity and applies value/tiebreak deterministically', () => {
    const candidates: LeaderboardCandidate[] = [
      { uid: 'u2', value: 100 },
      { uid: 'u1', value: 250 },
      { uid: 'u3', value: 50 },
    ];
    const identities = new Map<string, LeaderboardIdentity>([
      ['u1', id('Alice', 'profileImages/u1/a.jpg')],
      ['u2', id('Bob')],
      ['u3', id('Cara')],
    ]);
    const rows = buildLeaderboardCategory(candidates, identities, new Set());
    expect(rows).toEqual([
      { rank: 1, uid: 'u1', displayName: 'Alice', avatarPath: 'profileImages/u1/a.jpg', value: 250 },
      { rank: 2, uid: 'u2', displayName: 'Bob', avatarPath: null, value: 100 },
      { rank: 3, uid: 'u3', displayName: 'Cara', avatarPath: null, value: 50 },
    ]);
  });

  it('caps the board at topN', () => {
    const candidates: LeaderboardCandidate[] = Array.from({ length: 15 }, (_, i) => ({
      uid: `u${String(i).padStart(2, '0')}`,
      value: 100 - i,
    }));
    const rows = buildLeaderboardCategory(
      candidates,
      identitiesFor(candidates.map((c) => c.uid)),
      new Set(),
      LEADERBOARD_TOP_N,
    );
    expect(rows).toHaveLength(LEADERBOARD_TOP_N);
    expect(rows.at(-1)?.uid).toBe('u09');
    expect(rows.map((r) => r.rank)).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
  });

  it('excludes opted-out members and re-ranks the survivors contiguously', () => {
    const candidates: LeaderboardCandidate[] = [
      { uid: 'u1', value: 300 },
      { uid: 'u2', value: 200 }, // opted out → removed, no gap left
      { uid: 'u3', value: 100 },
    ];
    const rows = buildLeaderboardCategory(
      candidates,
      identitiesFor(['u1', 'u2', 'u3']),
      new Set(['u2']),
    );
    expect(rows.map((r) => [r.uid, r.rank])).toEqual([
      ['u1', 1],
      ['u3', 2],
    ]);
  });

  it('skips members with no users doc (deleted members drop off)', () => {
    const candidates: LeaderboardCandidate[] = [
      { uid: 'u1', value: 300 },
      { uid: 'gone', value: 250 }, // no identity entry → deleted, dropped
      { uid: 'u3', value: 100 },
    ];
    const identities = new Map<string, LeaderboardIdentity>([
      ['u1', id('One')],
      ['u3', id('Three')],
    ]);
    const rows = buildLeaderboardCategory(candidates, identities, new Set());
    expect(rows.map((r) => [r.uid, r.rank])).toEqual([
      ['u1', 1],
      ['u3', 2],
    ]);
  });

  it('fills the board from below when top candidates are removed', () => {
    // 12 candidates, the top 2 both removed (one opt-out, one deleted): the
    // board must still be a full 10 rows drawn from the survivors.
    const candidates: LeaderboardCandidate[] = Array.from({ length: 12 }, (_, i) => ({
      uid: `u${String(i).padStart(2, '0')}`,
      value: 100 - i,
    }));
    const identities = identitiesFor(candidates.map((c) => c.uid));
    identities.delete('u01'); // deleted member
    const rows = buildLeaderboardCategory(candidates, identities, new Set(['u00']));
    expect(rows).toHaveLength(LEADERBOARD_TOP_N);
    expect(rows[0]?.uid).toBe('u02');
    expect(rows.map((r) => r.rank)).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
  });

  it('returns an empty board when nobody has a positive value', () => {
    const candidates: LeaderboardCandidate[] = [
      { uid: 'a', value: 0 },
      { uid: 'b', value: 0 },
    ];
    expect(buildLeaderboardCategory(candidates, identitiesFor(['a', 'b']), new Set())).toEqual([]);
  });
});

describe('candidateUidsToResolve', () => {
  it('unions the retained top candidates across every category', () => {
    const perCategory = {
      crownPoints: [{ uid: 'a', value: 10 }],
      distance: [{ uid: 'b', value: 10 }],
      events: [{ uid: 'a', value: 5 }], // duplicate a → deduped
      convoys: [{ uid: 'c', value: 1 }],
      streak: [{ uid: 'd', value: 0 }], // non-positive → not resolved
    } as Record<LeaderboardCategoryKey, LeaderboardCandidate[]>;
    expect(candidateUidsToResolve(perCategory).sort()).toEqual(['a', 'b', 'c']);
  });

  it('respects the retention cap per category', () => {
    const many: LeaderboardCandidate[] = Array.from({ length: 5 }, (_, i) => ({
      uid: `u${i}`,
      value: 10 - i,
    }));
    const perCategory = {
      crownPoints: many,
      distance: [],
      events: [],
      convoys: [],
      streak: [],
    } as Record<LeaderboardCategoryKey, LeaderboardCandidate[]>;
    expect(candidateUidsToResolve(perCategory, 2).sort()).toEqual(['u0', 'u1']);
  });
});
