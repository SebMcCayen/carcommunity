/**
 * Unit tests for the pure Users-list filter/sort helpers.
 *
 * These are Firebase-free (the module under test only imports types), so no
 * SDK mock is needed. The headline case is the reported search bug: a search
 * for "SebMcCayen" must find a member whose displayName is exactly
 * "SebMcCayen" — case-insensitively and as a substring — over the FULL loaded
 * list, not just a truncated page.
 */

import { describe, expect, it } from 'vitest';

import type { AdminUserSummary } from '../features/users';
import {
  EMPTY_USER_FILTERS,
  derivedStatus,
  filterUsers,
  filterAndSortUsers,
  matchesSearch,
  sortUsers,
} from '../features/users/filter';

function makeUser(overrides: Partial<AdminUserSummary> = {}): AdminUserSummary {
  return {
    uid: 'uid-default',
    displayName: 'Someone',
    email: null,
    role: 'user',
    activeMember: false,
    suspended: false,
    deleted: false,
    createdAt: '2026-01-01T00:00:00.000Z',
    lastLoginAt: null,
    onboardingCompletedAt: null,
    ...overrides,
  };
}

const seb = makeUser({
  uid: 'abc123',
  displayName: 'SebMcCayen',
  email: 'seb@example.com',
  role: 'owner',
  activeMember: true,
  createdAt: '2025-06-01T10:00:00.000Z',
  lastLoginAt: '2026-08-14T08:00:00.000Z',
});
const other = makeUser({
  uid: 'zzz999',
  displayName: 'Rustbucket',
  email: 'rust@example.com',
  createdAt: '2026-02-01T10:00:00.000Z',
  lastLoginAt: '2026-03-01T10:00:00.000Z',
});
const neverLoggedIn = makeUser({
  uid: 'new000',
  displayName: 'Fresh',
  createdAt: '2026-08-01T10:00:00.000Z',
  lastLoginAt: null,
});

describe('matchesSearch', () => {
  it('matches "SebMcCayen" against displayName "SebMcCayen" exactly', () => {
    expect(matchesSearch(seb, 'SebMcCayen')).toBe(true);
  });

  it('is case-insensitive', () => {
    expect(matchesSearch(seb, 'sebmccayen')).toBe(true);
    expect(matchesSearch(seb, 'SEBMCCAYEN')).toBe(true);
  });

  it('matches as a substring, not just a prefix', () => {
    expect(matchesSearch(seb, 'mccayen')).toBe(true);
    expect(matchesSearch(seb, 'ccay')).toBe(true);
  });

  it('matches on email and uid too', () => {
    expect(matchesSearch(seb, 'seb@example')).toBe(true);
    expect(matchesSearch(seb, 'abc123')).toBe(true);
  });

  it('an empty / whitespace term matches everything', () => {
    expect(matchesSearch(neverLoggedIn, '')).toBe(true);
    expect(matchesSearch(neverLoggedIn, '   ')).toBe(true);
  });

  it('does not match unrelated terms', () => {
    expect(matchesSearch(seb, 'rustbucket')).toBe(false);
  });

  it('tolerates a null email', () => {
    expect(matchesSearch(neverLoggedIn, 'fresh')).toBe(true);
    expect(matchesSearch(neverLoggedIn, 'nope@nowhere')).toBe(false);
  });
});

describe('filterUsers', () => {
  const all = [seb, other, neverLoggedIn];

  it('finds SebMcCayen across the full list via search', () => {
    const result = filterUsers(all, { ...EMPTY_USER_FILTERS, search: 'sebmccayen' });
    expect(result).toEqual([seb]);
  });

  it('filters by role', () => {
    expect(filterUsers(all, { ...EMPTY_USER_FILTERS, role: 'owner' })).toEqual([seb]);
    expect(filterUsers(all, { ...EMPTY_USER_FILTERS, role: 'user' })).toEqual([
      other,
      neverLoggedIn,
    ]);
  });

  it('filters by member status', () => {
    expect(filterUsers(all, { ...EMPTY_USER_FILTERS, member: 'yes' })).toEqual([seb]);
    expect(filterUsers(all, { ...EMPTY_USER_FILTERS, member: 'no' })).toEqual([
      other,
      neverLoggedIn,
    ]);
  });

  it('filters by derived status', () => {
    const suspended = makeUser({ uid: 's1', suspended: true });
    const deleted = makeUser({ uid: 'd1', deleted: true });
    const list = [seb, suspended, deleted];
    expect(filterUsers(list, { ...EMPTY_USER_FILTERS, status: 'suspended' })).toEqual([suspended]);
    expect(filterUsers(list, { ...EMPTY_USER_FILTERS, status: 'deleted' })).toEqual([deleted]);
    expect(filterUsers(list, { ...EMPTY_USER_FILTERS, status: 'active' })).toEqual([seb]);
  });

  it('combines facets with AND', () => {
    const result = filterUsers(all, { ...EMPTY_USER_FILTERS, search: 'e', role: 'owner' });
    expect(result).toEqual([seb]);
  });
});

describe('derivedStatus', () => {
  it('ranks deleted over suspended over active', () => {
    expect(derivedStatus({ deleted: true, suspended: true })).toBe('deleted');
    expect(derivedStatus({ deleted: false, suspended: true })).toBe('suspended');
    expect(derivedStatus({ deleted: false, suspended: false })).toBe('active');
  });
});

describe('sortUsers', () => {
  const all = [other, seb, neverLoggedIn];

  it('sorts by most recent activity first, nulls last', () => {
    expect(sortUsers(all, 'lastActivityDesc').map((u) => u.uid)).toEqual([
      seb.uid, // 2026-08
      other.uid, // 2026-03
      neverLoggedIn.uid, // null → last
    ]);
  });

  it('sorts by least recent activity first, nulls first (inactive view)', () => {
    expect(sortUsers(all, 'lastActivityAsc').map((u) => u.uid)).toEqual([
      neverLoggedIn.uid, // null → first
      other.uid,
      seb.uid,
    ]);
  });

  it('sorts by created date both directions', () => {
    expect(sortUsers(all, 'createdDesc').map((u) => u.uid)).toEqual([
      neverLoggedIn.uid, // 2026-08
      other.uid, // 2026-02
      seb.uid, // 2025-06
    ]);
    expect(sortUsers(all, 'createdAsc').map((u) => u.uid)).toEqual([
      seb.uid,
      other.uid,
      neverLoggedIn.uid,
    ]);
  });

  it('sorts by name', () => {
    expect(sortUsers(all, 'nameAsc').map((u) => u.displayName)).toEqual([
      'Fresh',
      'Rustbucket',
      'SebMcCayen',
    ]);
  });

  it('does not mutate its input', () => {
    const input = [...all];
    sortUsers(input, 'nameAsc');
    expect(input).toEqual(all);
  });
});

describe('filterAndSortUsers', () => {
  it('filters then sorts (the page pipeline)', () => {
    const all = [other, seb, neverLoggedIn];
    const result = filterAndSortUsers(all, { ...EMPTY_USER_FILTERS, role: 'user' }, 'nameAsc');
    expect(result.map((u) => u.displayName)).toEqual(['Fresh', 'Rustbucket']);
  });
});
