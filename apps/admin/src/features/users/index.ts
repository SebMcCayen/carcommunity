/**
 * Admin users feature module (Phase 13l — Firebase migration).
 *
 * Replaces the last two placeholders in the admin nav (users list + detail).
 * Backed by Firebase:
 *  - List/detail READS are direct rules-gated SDK reads on `users/{uid}`
 *    (`isAdmin()` in firestore.rules; the 13a precedent). Only the
 *    backend-managed, admin-safe fields are surfaced.
 *  - Moderation MUTATIONS go through the audited admin.* callables
 *    (warnUser, suspendUser, restoreAccess, setAdminRole); the client never
 *    writes the backend-managed access fields (role / suspended / deleted /
 *    activeMember are set only by Cloud Functions).
 *
 * Security notes:
 *  - Backend independently verifies the `admin` claim and re-guards every
 *    mutation (admins cannot moderate owners; no self-moderation/-elevation);
 *    a reason is mandatory and audited server-side.
 *  - `userPrivate/{uid}` is DELIBERATELY never read here — it is owner-only by
 *    design (sensitive PII); admins only ever see the safe `users/{uid}` doc.
 *  - Enum/boolean fields come from possibly old/partial/hand-edited documents,
 *    so each is coerced defensively (booleans via `=== true`, role validated
 *    against USER_ROLES with a safe `'user'` fallback) before use.
 */

import { collection, doc, getDoc, getDocs, limit as fsLimit, query } from 'firebase/firestore';
import { DEFAULT_DISPLAY_NAME, USER_ROLES, type UserRole } from '@carcommunity/shared/users';

import { callAdmin } from '../../lib/callables';
import { getAdminFirestore } from '../../lib/firestore';

import type { ApiError } from '../../lib/errors';

export type { ApiError, UserRole };
export { USER_ROLES };

export {
  filterUsers,
  sortUsers,
  filterAndSortUsers,
  matchesSearch,
  derivedStatus,
  EMPTY_USER_FILTERS,
  DEFAULT_USER_SORT,
  type UserListFilters,
  type UserSortKey,
  type UserDerivedStatus,
} from './filter';

/**
 * Hard safety bound on how many users a single list read will pull. The real
 * user base is small (well under a few hundred), so in practice every user is
 * loaded and the list is filtered/sorted in memory. This cap only exists so a
 * runaway collection can never fetch an unbounded number of documents; when it
 * is hit the UI surfaces a notice rather than silently hiding accounts.
 */
export const LIST_MAX = 1000;

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/** Admin-safe user summary for the list view. */
export interface AdminUserSummary {
  uid: string;
  displayName: string;
  /**
   * Account email when the `users/{uid}` doc carries one (it is written only
   * when provisioning had an email), else null. Surfaced solely to make the
   * list searchable by email — it is never rendered as a column.
   */
  email: string | null;
  role: UserRole;
  activeMember: boolean;
  suspended: boolean;
  deleted: boolean;
  createdAt: string | null;
  /**
   * ISO string of the account's most recent sign-in
   * (`userLifecycle/{uid}.lastLoginAt`), or null when the account has never
   * recorded a login (predates the lifecycle feature, or never signed in).
   * This is the "Last activity" column and the same signal the server-side
   * inactivity sweep uses.
   */
  lastLoginAt: string | null;
  /**
   * ISO string of `users/{uid}.onboardingCompletedAt`, or null when onboarding
   * has not completed. Onboarding requires a member-chosen nickname, so a set
   * marker is the authoritative signal that `displayName` is a real nickname
   * rather than the provisioning placeholder — see [hasMemberSetNickname].
   */
  onboardingCompletedAt: string | null;
}

/** Result of [adminListUsers]: the loaded users plus whether the cap was hit. */
export interface AdminUserListResult {
  users: AdminUserSummary[];
  /**
   * True when the read returned exactly `LIST_MAX` documents, meaning more
   * users may exist beyond the cap. The UI surfaces this so nothing is hidden
   * silently.
   */
  truncated: boolean;
}

/**
 * The neutral placeholder `displayName` un-onboarded accounts still carry.
 *
 * Re-exported from `@carcommunity/shared/users` (the single read-side source of
 * truth) under a name local to this feature. The admin only ever COMPARES
 * against it, never writes it — the Cloud Function in
 * functions/src/auth/provisioning.ts is what writes the value.
 */
export const PROVISIONING_PLACEHOLDER_NAME = DEFAULT_DISPLAY_NAME;

/**
 * Whether the account has a MEMBER-SET nickname worth displaying, as opposed to
 * an account still sitting on the provisioning placeholder.
 *
 * The check is deliberately ordered:
 *  1. An empty / whitespace-only `displayName` always yields the label — there
 *     is literally nothing to render, so this wins even if `onboardingCompletedAt`
 *     is somehow set (a hand-edited/partial doc). Showing the label beats
 *     rendering an empty cell.
 *  2. Among NON-empty names, `onboardingCompletedAt` is authoritative: onboarding
 *     stamps it and cannot complete without a valid member-typed nickname
 *     (auth.completeOnboarding), so a set marker ⟺ the name is a real nickname —
 *     it is shown even if it happens to equal the placeholder string.
 *  3. Belt-and-suspenders for old/partial/hand-edited docs missing the marker: a
 *     non-empty name that still exactly equals the provisioning placeholder
 *     counts as "not set"; any other non-empty name is shown.
 */
export function hasMemberSetNickname(
  summary: Pick<AdminUserSummary, 'displayName' | 'onboardingCompletedAt'>,
): boolean {
  const name = summary.displayName.trim();
  if (name === '') return false;
  if (summary.onboardingCompletedAt != null) return true;
  return name !== PROVISIONING_PLACEHOLDER_NAME;
}

/** Admin-safe user detail. Never includes anything from `userPrivate/{uid}`. */
export interface AdminUserDetail {
  uid: string;
  displayName: string;
  role: UserRole;
  activeMember: boolean;
  suspended: boolean;
  deleted: boolean;
  bio: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

/** Response of admin-warnUser. */
export interface WarnUserResult {
  targetUid: string;
  actionId: string;
}

/** Response of admin-suspendUser / admin-restoreAccess. */
export interface ModerationStatusResult {
  targetUid: string;
  suspended: boolean;
}

/** Response of admin-setAdminRole. */
export interface SetAdminRoleResult {
  targetUid: string;
  role: 'admin' | 'user';
  admin: boolean;
}

/** Response of admin-deleteUser. */
export interface DeleteUserResult {
  targetUid: string;
  deleted: true;
}

// ---------------------------------------------------------------------------
// Coercion helpers
// ---------------------------------------------------------------------------

/**
 * Normalizes a stored timestamp field to an ISO string. Permissive by design —
 * `users/{uid}` documents may be old, partial, or hand-edited, so this accepts
 * a Firestore Timestamp (toDate()), a native Date, or an already-serialized
 * date string, and returns null only when the value is absent or unparseable.
 */
function toIso(value: unknown): string | null {
  if (value == null) return null;
  if (typeof (value as { toDate?: unknown }).toDate === 'function') {
    const date = (value as { toDate: () => Date }).toDate();
    return date instanceof Date && !Number.isNaN(date.getTime()) ? date.toISOString() : null;
  }
  if (value instanceof Date) {
    return Number.isNaN(value.getTime()) ? null : value.toISOString();
  }
  if (typeof value === 'string') {
    const parsed = new Date(value);
    return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString();
  }
  return null;
}

/**
 * Validates a stored role against the known set, falling back to the least
 * privileged `'user'` when the value is missing or unexpected — an unknown
 * stored value must never read as admin or owner.
 */
function coerceRole(raw: unknown): UserRole {
  return (USER_ROLES as readonly string[]).includes(raw as string) ? (raw as UserRole) : 'user';
}

function coerceString(raw: unknown): string {
  return typeof raw === 'string' ? raw : '';
}

function coerceOptionalString(raw: unknown): string | null {
  return typeof raw === 'string' ? raw : null;
}

function toSummary(
  uid: string,
  data: Record<string, unknown>,
  lastLoginAt: string | null,
): AdminUserSummary {
  return {
    uid,
    displayName: coerceString(data.displayName),
    email: coerceOptionalString(data.email),
    role: coerceRole(data.role),
    activeMember: data.activeMember === true,
    suspended: data.suspended === true,
    deleted: data.deleted === true,
    createdAt: toIso(data.createdAt),
    lastLoginAt,
    onboardingCompletedAt: toIso(data.onboardingCompletedAt),
  };
}

// ---------------------------------------------------------------------------
// Reads
// ---------------------------------------------------------------------------

/**
 * Loads the full user list for the admin table. Direct rules-gated reads on
 * `users` and `userLifecycle` (both `isAdmin()`-gated).
 *
 * The list is intentionally NOT ordered in the query: an `orderBy('createdAt')`
 * would silently drop any document missing that field, so a plain collection
 * scan is used to guarantee every account is reachable, and the caller
 * sorts/filters in memory (see `filterAndSortUsers`). A single `LIST_MAX` cap
 * bounds the read; if it is reached the result is flagged `truncated` so the UI
 * can say so rather than hide accounts.
 *
 * Last activity comes from `userLifecycle/{uid}.lastLoginAt`, fetched as one
 * collection read and joined by uid — the same field the server-side inactivity
 * sweep treats as last activity. `userLifecycle` is admin-readable; the
 * owner-only `userPrivate/{uid}` is still never touched.
 */
export async function adminListUsers(): Promise<AdminUserListResult> {
  const db = getAdminFirestore();
  const [usersSnap, lifecycleSnap] = await Promise.all([
    getDocs(query(collection(db, 'users'), fsLimit(LIST_MAX))),
    getDocs(query(collection(db, 'userLifecycle'), fsLimit(LIST_MAX))),
  ]);

  const lastLoginByUid = new Map<string, string | null>();
  for (const d of lifecycleSnap.docs) {
    lastLoginByUid.set(d.id, toIso((d.data() as Record<string, unknown>).lastLoginAt));
  }

  const users = usersSnap.docs.map((d) =>
    toSummary(d.id, d.data() as Record<string, unknown>, lastLoginByUid.get(d.id) ?? null),
  );
  return { users, truncated: usersSnap.size >= LIST_MAX };
}

/**
 * Returns the admin-safe detail for a single user. Reads only `users/{uid}` —
 * `userPrivate/{uid}` is owner-only and deliberately never touched here. A
 * missing document resolves to null (the page renders a not-found state).
 */
export async function adminGetUser(uid: string): Promise<AdminUserDetail | null> {
  const snap = await getDoc(doc(getAdminFirestore(), 'users', uid));
  const data = snap.data() as Record<string, unknown> | undefined;
  if (!data) return null;
  return {
    uid,
    displayName: coerceString(data.displayName),
    role: coerceRole(data.role),
    activeMember: data.activeMember === true,
    suspended: data.suspended === true,
    deleted: data.deleted === true,
    bio: coerceOptionalString(data.bio),
    createdAt: toIso(data.createdAt),
    updatedAt: toIso(data.updatedAt),
  };
}

// ---------------------------------------------------------------------------
// Mutations (audited admin.* callables)
// ---------------------------------------------------------------------------

/**
 * Issues a formal warning to a user via admin-warnUser. A warning never
 * restricts access; it is recorded and the user is notified. Reason is
 * mandatory and audited server-side.
 */
export async function adminWarnUser(targetUid: string, reason: string): Promise<WarnUserResult> {
  return callAdmin<WarnUserResult>('admin-warnUser', { targetUid, reason });
}

/**
 * Suspends a user via admin-suspendUser (sets the enforcement claim and
 * revokes refresh tokens server-side). Reason is mandatory and audited.
 */
export async function adminSuspendUser(
  targetUid: string,
  reason: string,
): Promise<ModerationStatusResult> {
  return callAdmin<ModerationStatusResult>('admin-suspendUser', { targetUid, reason });
}

/**
 * Restores a suspended user's access via admin-restoreAccess. Reason is
 * mandatory and audited server-side.
 */
export async function adminRestoreAccess(
  targetUid: string,
  reason: string,
): Promise<ModerationStatusResult> {
  return callAdmin<ModerationStatusResult>('admin-restoreAccess', { targetUid, reason });
}

/**
 * Grants or revokes the `admin` role via admin-setAdminRole. The owner role is
 * never touched, and callers can never change their own role (backend-guarded).
 * Reason is mandatory and audited server-side.
 */
export async function adminSetAdminRole(
  targetUid: string,
  admin: boolean,
  reason: string,
): Promise<SetAdminRoleResult> {
  return callAdmin<SetAdminRoleResult>('admin-setAdminRole', { targetUid, admin, reason });
}

/**
 * IRREVERSIBLY deletes a user and ALL their data via admin-deleteUser. The
 * backend reuses the same comprehensive erasure as self-service account
 * deletion (every collection/subcollection, social-graph mirrors, live-location
 * state, storage, and the Firebase Auth user) and writes an audited
 * adminAuditEvents record. The backend refuses self-deletion, deleting owner
 * accounts (unless the actor is an owner), and deleting the last remaining
 * admin. Reason is mandatory and audited server-side. There is no undo.
 */
export async function adminDeleteUser(
  targetUid: string,
  reason: string,
): Promise<DeleteUserResult> {
  return callAdmin<DeleteUserResult>('admin-deleteUser', { targetUid, reason });
}
