/**
 * Pure, Firebase-free filter/sort helpers for the admin Users list.
 *
 * Factored out of the page and the data module so the search/filter/sort logic
 * is unit-testable in isolation (no Firebase SDK, no DOM). The list is loaded
 * in full (small user base — see `adminListUsers`), so filtering and sorting
 * are done entirely in memory here: a case-insensitive SUBSTRING search across
 * name / uid / email, plus role / status / member facets and several sort
 * orders (including least-recent activity, useful for spotting dormant
 * accounts).
 */

import type { UserRole } from '@carcommunity/shared/users';

import type { AdminUserSummary } from './index';

/** Derived lifecycle status shown in the Status column and status facet. */
export type UserDerivedStatus = 'active' | 'suspended' | 'deleted';

/** Empty string means "no filter" for each facet. */
export interface UserListFilters {
  /** Free-text search term (matched case-insensitively as a substring). */
  search: string;
  role: '' | UserRole;
  status: '' | UserDerivedStatus;
  member: '' | 'yes' | 'no';
}

export const EMPTY_USER_FILTERS: UserListFilters = {
  search: '',
  role: '',
  status: '',
  member: '',
};

export type UserSortKey =
  'lastActivityDesc' | 'lastActivityAsc' | 'createdDesc' | 'createdAsc' | 'nameAsc';

export const DEFAULT_USER_SORT: UserSortKey = 'lastActivityDesc';

/**
 * The account's lifecycle status. Ordered so the most consequential state wins:
 * a deleted account reads as deleted even if it was also suspended.
 */
export function derivedStatus(
  user: Pick<AdminUserSummary, 'deleted' | 'suspended'>,
): UserDerivedStatus {
  if (user.deleted) return 'deleted';
  if (user.suspended) return 'suspended';
  return 'active';
}

/** Milliseconds for an ISO timestamp, or null when absent/unparseable. */
function toMillis(iso: string | null): number | null {
  if (!iso) return null;
  const ms = new Date(iso).getTime();
  return Number.isNaN(ms) ? null : ms;
}

/**
 * Whether a single user matches the free-text term. Case-insensitive substring
 * match across the display name, uid and (when present) email. An empty term
 * matches everything. This is what makes "SebMcCayen" find a member whose
 * `displayName` is exactly "SebMcCayen" — as a substring, case-folded.
 */
export function matchesSearch(user: AdminUserSummary, rawTerm: string): boolean {
  const term = rawTerm.trim().toLowerCase();
  if (term === '') return true;
  const haystacks = [user.displayName, user.uid, user.email ?? ''];
  return haystacks.some((value) => value.toLowerCase().includes(term));
}

/**
 * Applies every active facet (search + role + status + member) to the full
 * user list. Facets combine with AND; empty facets are ignored.
 */
export function filterUsers(
  users: AdminUserSummary[],
  filters: UserListFilters,
): AdminUserSummary[] {
  return users.filter((user) => {
    if (!matchesSearch(user, filters.search)) return false;
    if (filters.role && user.role !== filters.role) return false;
    if (filters.status && derivedStatus(user) !== filters.status) return false;
    if (filters.member === 'yes' && !user.activeMember) return false;
    if (filters.member === 'no' && user.activeMember) return false;
    return true;
  });
}

/**
 * Returns a new array sorted by the requested key. Missing activity / created
 * timestamps sort as the oldest possible value, so accounts that never logged
 * in surface first under "least recent activity" (the inactive-account view)
 * and last under "most recent".
 */
export function sortUsers(users: AdminUserSummary[], sort: UserSortKey): AdminUserSummary[] {
  const copy = [...users];
  const byActivity = (a: AdminUserSummary, b: AdminUserSummary) =>
    (toMillis(a.lastLoginAt) ?? -Infinity) - (toMillis(b.lastLoginAt) ?? -Infinity);
  const byCreated = (a: AdminUserSummary, b: AdminUserSummary) =>
    (toMillis(a.createdAt) ?? -Infinity) - (toMillis(b.createdAt) ?? -Infinity);
  switch (sort) {
    case 'lastActivityAsc':
      copy.sort(byActivity);
      break;
    case 'lastActivityDesc':
      copy.sort((a, b) => byActivity(b, a));
      break;
    case 'createdAsc':
      copy.sort(byCreated);
      break;
    case 'createdDesc':
      copy.sort((a, b) => byCreated(b, a));
      break;
    case 'nameAsc':
      copy.sort((a, b) => a.displayName.localeCompare(b.displayName, 'sv'));
      break;
  }
  return copy;
}

/** Filter then sort, the exact pipeline the page renders. */
export function filterAndSortUsers(
  users: AdminUserSummary[],
  filters: UserListFilters,
  sort: UserSortKey,
): AdminUserSummary[] {
  return sortUsers(filterUsers(users, filters), sort);
}
