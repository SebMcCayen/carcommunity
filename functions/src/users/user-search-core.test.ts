import { describe, expect, it } from 'vitest';
import {
  MAX_SEARCH_RESULTS,
  MEMBER_SEARCH_RATE_LIMIT_MAX,
  MEMBER_SEARCH_RATE_LIMIT_WINDOW_MS,
  MIN_QUERY_CODE_POINTS,
  SEARCH_SCAN_LIMIT,
  clampSearchLimit,
  isSearchableKey,
  isUnderMemberSearchRateLimit,
  memberSearchRateLimitDocId,
  memberSearchRateLimitExpiry,
  memberSearchRateLimitWindowIndex,
  parseSearchMembersInput,
  searchKeyRange,
  toMemberSearchHit,
  toSearchQueryKey,
} from './user-search-core';

/**
 * Firestore orders strings by their UTF-8 BYTES, not by JS's UTF-16 code-unit
 * ordering, and the two disagree for astral characters. The range assertions
 * below therefore compare encoded bytes — the same convention as
 * friends/friends-core.test.ts — so a bound that only "looks" correct in JS
 * cannot pass here while failing in production.
 */
const utf8 = (value: string) => Buffer.from(value, 'utf8');
const sortsBelow = (a: string, b: string) => Buffer.compare(utf8(a), utf8(b)) < 0;
/** True when `value` falls inside the half-open range the callable queries. */
const matchedByPrefix = (value: string, typed: string) => {
  const { start, end } = searchKeyRange(toSearchQueryKey(typed));
  return !sortsBelow(value, start) && sortsBelow(value, end);
};

describe('toSearchQueryKey', () => {
  it('normalizes typed text into the stored displayNameLower key space', () => {
    expect(toSearchQueryKey('GT')).toBe('gt');
    expect(toSearchQueryKey('  Gt_86 ')).toBe('gt_86');
  });

  it('folds locale-invariantly, so a Turkish-locale device queries the same key', () => {
    // A locale-SENSITIVE fold maps 'I' to 'ı' and would silently desync the
    // query key from the key the backend wrote.
    expect(toSearchQueryKey('ISAK')).toBe('isak');
    expect(toSearchQueryKey('ISAK').charCodeAt(0)).toBe('i'.charCodeAt(0));
  });
});

describe('prefix matching semantics', () => {
  it("matches Seb's example: typing 'gt' finds the nickname 'gt_86'", () => {
    expect(matchedByPrefix('gt_86', 'gt')).toBe(true);
    expect(matchedByPrefix('gt_86', 'GT')).toBe(true);
    expect(matchedByPrefix('gt_86', 'gt_8')).toBe(true);
    expect(matchedByPrefix('gt_86', 'gt_86')).toBe(true);
  });

  it('matches every member sharing the typed prefix, not just one', () => {
    expect(matchedByPrefix('gt86_swe', 'gt')).toBe(true);
    expect(matchedByPrefix('gtr_nismo', 'gt')).toBe(true);
  });

  it('does NOT match a mid-word or trailing substring (the documented limit)', () => {
    // This is the whole prefix-vs-substring trade-off stated in the module
    // KDoc and the PR body. If someone later adds n-gram tokens, these
    // expectations are the ones that should flip — deliberately, not silently.
    expect(matchedByPrefix('gt_86', '86')).toBe(false);
    expect(matchedByPrefix('gt_86', '_86')).toBe(false);
    expect(matchedByPrefix('gt_86', 't_8')).toBe(false);
  });

  it('does not sweep in a neighbouring prefix', () => {
    expect(matchedByPrefix('gu', 'gt')).toBe(false);
    expect(matchedByPrefix('gs', 'gt')).toBe(false);
    expect(matchedByPrefix('g', 'gt')).toBe(false);
  });

  it('includes a name continuing with an ASTRAL character', () => {
    // A '￿' sentinel upper bound encodes to 3 UTF-8 bytes, which sorts
    // BELOW a 4-byte emoji — such a bound would silently hide this member.
    expect(matchedByPrefix('gt86\u{1F600}', 'gt86')).toBe(true);
  });
});

describe('isSearchableKey', () => {
  it('rejects a query shorter than the minimum', () => {
    expect(MIN_QUERY_CODE_POINTS).toBe(2);
    expect(isSearchableKey('')).toBe(false);
    expect(isSearchableKey('g')).toBe(false);
  });

  it('accepts the minimum and anything longer', () => {
    expect(isSearchableKey('gt')).toBe(true);
    expect(isSearchableKey('gt_86')).toBe(true);
  });

  it('is applied AFTER normalization, so whitespace never counts as length', () => {
    expect(isSearchableKey(toSearchQueryKey('  g  '))).toBe(false);
    expect(isSearchableKey(toSearchQueryKey('  gt  '))).toBe(true);
  });

  it('counts CODE POINTS, not UTF-16 units', () => {
    // One emoji is a single character to the person typing it but two
    // String.length units; a `.length >= 2` gate would wrongly admit it.
    expect('\u{1F600}'.length).toBe(2);
    expect(isSearchableKey('\u{1F600}')).toBe(false);
    // Two emoji are two perceived characters and must be admitted.
    expect(isSearchableKey('\u{1F600}\u{1F697}')).toBe(true);
  });
});

describe('parseSearchMembersInput', () => {
  it('accepts a bare query', () => {
    const parsed = parseSearchMembersInput({ query: 'gt' });
    expect(parsed.ok).toBe(true);
    if (parsed.ok) {
      expect(parsed.input.query).toBe('gt');
      expect(parsed.input.limit).toBeUndefined();
    }
  });

  it('accepts an optional positive integer limit', () => {
    const parsed = parseSearchMembersInput({ query: 'gt', limit: 5 });
    expect(parsed.ok).toBe(true);
    if (parsed.ok) expect(parsed.input.limit).toBe(5);
  });

  it('rejects a missing, non-string, or over-long query', () => {
    expect(parseSearchMembersInput({}).ok).toBe(false);
    expect(parseSearchMembersInput({ query: 42 }).ok).toBe(false);
    expect(parseSearchMembersInput({ query: 'x'.repeat(121) }).ok).toBe(false);
  });

  it('rejects a malformed limit rather than silently coercing it', () => {
    expect(parseSearchMembersInput({ query: 'gt', limit: 0 }).ok).toBe(false);
    expect(parseSearchMembersInput({ query: 'gt', limit: -5 }).ok).toBe(false);
    expect(parseSearchMembersInput({ query: 'gt', limit: 2.5 }).ok).toBe(false);
    expect(parseSearchMembersInput({ query: 'gt', limit: '10' }).ok).toBe(false);
  });

  it('rejects unknown keys (strict), so no field smuggles through', () => {
    expect(parseSearchMembersInput({ query: 'gt', includeEmail: true }).ok).toBe(false);
  });
});

describe('clampSearchLimit', () => {
  it('defaults to the maximum when unspecified', () => {
    expect(clampSearchLimit(undefined)).toBe(MAX_SEARCH_RESULTS);
  });

  it('honours a smaller ask', () => {
    expect(clampSearchLimit(5)).toBe(5);
    expect(clampSearchLimit(1)).toBe(1);
  });

  it('CAPS an over-ask instead of honouring it — the enumeration guard', () => {
    expect(clampSearchLimit(MAX_SEARCH_RESULTS + 1)).toBe(MAX_SEARCH_RESULTS);
    expect(clampSearchLimit(10_000)).toBe(MAX_SEARCH_RESULTS);
    expect(clampSearchLimit(Number.MAX_SAFE_INTEGER)).toBe(MAX_SEARCH_RESULTS);
  });

  it('never returns a non-positive page size', () => {
    expect(clampSearchLimit(0)).toBe(1);
    expect(clampSearchLimit(-1)).toBe(1);
  });

  it('leaves headroom in the raw scan for rows filtered out after the fetch', () => {
    // Firestore applies .limit() BEFORE the caller's own row and restricted
    // accounts are dropped, so the scan must fetch strictly more than it returns
    // or a page of filtered-out rows would hide real matches behind it.
    expect(SEARCH_SCAN_LIMIT).toBeGreaterThan(MAX_SEARCH_RESULTS);
  });
});

describe('toMemberSearchHit', () => {
  it('projects only uid, displayName and avatarPath', () => {
    const hit = toMemberSearchHit('uid-1', { displayName: 'Gt_86', avatarPath: 'avatars/a.jpg' });
    expect(hit).toEqual({ uid: 'uid-1', displayName: 'Gt_86', avatarPath: 'avatars/a.jpg' });
  });

  it('NEVER leaks a private or backend-managed field, however the doc grows', () => {
    // The allowlist is the privacy guarantee: a field added to users/{uid}
    // tomorrow must be invisible here by default, not exposed by default.
    const hit = toMemberSearchHit('uid-1', {
      displayName: 'Gt_86',
      avatarPath: null,
      email: 'someone@example.com',
      role: 'admin',
      activeMember: true,
      suspended: false,
      deleted: false,
      displayNameLower: 'gt_86',
      lastLoginAt: 'yesterday',
      aFutureField: 'whatever',
    });
    expect(Object.keys(hit).sort()).toEqual(['avatarPath', 'displayName', 'uid']);
    expect(JSON.stringify(hit)).not.toContain('example.com');
  });

  it('degrades a missing or non-string field to null rather than throwing', () => {
    expect(toMemberSearchHit('uid-1', undefined)).toEqual({
      uid: 'uid-1',
      displayName: null,
      avatarPath: null,
    });
    expect(toMemberSearchHit('uid-1', { displayName: 7, avatarPath: {} })).toEqual({
      uid: 'uid-1',
      displayName: null,
      avatarPath: null,
    });
  });
});

describe('member search rate limit', () => {
  it('admits below the cap and rejects at it', () => {
    expect(isUnderMemberSearchRateLimit(0)).toBe(true);
    expect(isUnderMemberSearchRateLimit(MEMBER_SEARCH_RATE_LIMIT_MAX - 1)).toBe(true);
    expect(isUnderMemberSearchRateLimit(MEMBER_SEARCH_RATE_LIMIT_MAX)).toBe(false);
    expect(isUnderMemberSearchRateLimit(MEMBER_SEARCH_RATE_LIMIT_MAX + 1)).toBe(false);
  });

  it('buckets by fixed epoch-minute windows', () => {
    const base = 1_700_000_000_000;
    const aligned = base - (base % MEMBER_SEARCH_RATE_LIMIT_WINDOW_MS);
    expect(memberSearchRateLimitWindowIndex(aligned)).toBe(
      memberSearchRateLimitWindowIndex(aligned + MEMBER_SEARCH_RATE_LIMIT_WINDOW_MS - 1),
    );
    expect(memberSearchRateLimitWindowIndex(aligned + MEMBER_SEARCH_RATE_LIMIT_WINDOW_MS)).toBe(
      memberSearchRateLimitWindowIndex(aligned) + 1,
    );
  });

  it('scopes the counter to one uid and one window', () => {
    const now = 1_700_000_000_000;
    expect(memberSearchRateLimitDocId('uid-a', now)).not.toBe(
      memberSearchRateLimitDocId('uid-b', now),
    );
    expect(memberSearchRateLimitDocId('uid-a', now)).not.toBe(
      memberSearchRateLimitDocId('uid-a', now + MEMBER_SEARCH_RATE_LIMIT_WINDOW_MS),
    );
    expect(memberSearchRateLimitDocId('uid-a', now)).toBe(
      memberSearchRateLimitDocId('uid-a', now + 1),
    );
  });

  it('expires a spent window strictly after it closes', () => {
    const now = 1_700_000_000_000;
    const windowEnd =
      (memberSearchRateLimitWindowIndex(now) + 1) * MEMBER_SEARCH_RATE_LIMIT_WINDOW_MS;
    expect(memberSearchRateLimitExpiry(now).getTime()).toBeGreaterThan(windowEnd);
  });
});
