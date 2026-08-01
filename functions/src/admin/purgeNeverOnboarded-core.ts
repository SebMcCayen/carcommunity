/**
 * admin.purgeNeverOnboarded domain — pure selection logic, guards, and
 * constants for the one-off never-onboarded account cleanup.
 *
 * ## Why this exists
 *
 * A display-name leak (fixed forward — new accounts no longer seed the public
 * profile from the identity provider) left a batch of NEVER-ONBOARDED accounts
 * whose Google real name is sitting in the world-readable
 * `users/{uid}.displayName` (and the searchable `displayNameLower`). There is
 * no admin UI or CLI path to delete those accounts safely, so this is a
 * one-off, admin-only, DRY-RUN-FIRST purge that removes them (Auth + Firestore)
 * via the EXISTING account-deletion cascade (account/scheduled.ts
 * `purgeUserData`) — the same cascade the scheduled 30-day hard purge and the
 * inactive-account sweep use, so no orphan is left behind.
 *
 * ## Selection predicate (deliberately conservative)
 *
 * A `users/{uid}` document is a purge candidate when ALL of these hold:
 *  1. It is NEVER ONBOARDED — `onboardingCompletedAt` is null or absent.
 *     A document that carries ANY value there (a Timestamp) is treated as
 *     onboarded and is NEVER selected. `isNeverOnboarded` only ever returns
 *     true for the strictly-unset case, so the failure mode is "skip an
 *     account we were unsure about", never "delete a completed one".
 *  2. Its role is NOT `admin` or `owner`. This is a HARD safety net enforced
 *     regardless of the onboarding flag — Seb's operator account must never be
 *     selected even if its `onboardingCompletedAt` were somehow null.
 *  3. Its uid is NOT in PURGE_SAFELIST_UIDS (empty by default; a place to
 *     protect specific uids without a code change to the predicate).
 *
 * This module is PURE — no Firebase Admin SDK imports — so the predicate, the
 * confirm guard, and the input parsing are unit-testable without emulators.
 * The callable (purgeNeverOnboarded.ts) does the I/O and reuses purgeUserData.
 */

import { z } from 'zod';
import { isAdminRole, isOwnerRole, toUserAccessState } from '../shared/access';

/**
 * The sentinel `confirmToken` a REAL (dryRun:false) purge must carry. A run
 * cannot happen by accident or by default — the token is required and must
 * match this exact string, so the only way to delete is to pass it
 * deliberately.
 */
export const PURGE_CONFIRM_TOKEN = 'PURGE';

/**
 * Explicit per-uid safelist. EMPTY by default and documented so Seb can
 * protect specific uids (beyond the admin/owner role exclusion, which is
 * always applied) by adding them here — no other code change needed.
 */
export const PURGE_SAFELIST_UIDS: ReadonlySet<string> = new Set<string>([]);

/**
 * Upper bound on how many `users` documents the callable reads in one pass.
 * The target collection is tiny (~20 docs), so this is a guardrail, not a
 * working limit: if a scan ever returns this many the callable flags the run
 * as `capped` and logs it rather than silently truncating, so a larger-than-
 * expected collection is surfaced instead of half-processed.
 */
export const PURGE_MAX_SCAN = 500;

/**
 * Upper bound on how many accounts a single REAL invocation purges. Keeps one
 * call from running the full cascade over an unbounded set and blowing the
 * callable timeout; if there are more candidates than this, the call purges
 * this many and reports `capped: true` so Seb can re-run (idempotent) for the
 * remainder. Comfortably above the ~16 expected.
 */
export const PURGE_MAX_BATCH = 50;

const inputSchema = z
  .object({
    dryRun: z.boolean(),
    confirmToken: z.string().optional(),
  })
  .strict();

export type PurgeNeverOnboardedInput = z.infer<typeof inputSchema>;

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

export function parsePurgeInput(data: unknown): ParseResult<PurgeNeverOnboardedInput> {
  const result = inputSchema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: 'Expected { dryRun: boolean, confirmToken?: string }.' };
  }
  return { ok: true, input: result.data };
}

/**
 * True when a `users` document has never completed onboarding: its
 * `onboardingCompletedAt` is null or absent. Any other value (a Timestamp
 * written by completeOnboarding) means the account IS onboarded and must be
 * kept — so this returns true ONLY for the strictly-unset case.
 */
export function isNeverOnboarded(doc: Record<string, unknown> | undefined): boolean {
  const value = doc?.onboardingCompletedAt;
  return value === null || value === undefined;
}

/**
 * True when the document's role is `admin` or `owner`. Read through
 * toUserAccessState so a missing/garbage role defaults to a plain `user`
 * (never accidentally admin) — the same authoritative reader the admin
 * callables use.
 */
export function isAdminOrOwner(doc: Record<string, unknown> | undefined): boolean {
  const { role } = toUserAccessState(doc);
  return isAdminRole(role) || isOwnerRole(role);
}

export type PurgeSelection =
  { selected: true } | { selected: false; reason: 'onboarded' | 'admin_or_owner' | 'safelisted' };

/**
 * The full selection decision for one account, in the order the guards are
 * tested. admin/owner is checked BEFORE onboarding so an operator account is
 * excluded on role grounds no matter what its onboarding flag says (the
 * `admin_or_owner` reason makes that visible in the excluded tally the dry run
 * returns).
 */
export function classifyAccount(
  uid: string,
  doc: Record<string, unknown> | undefined,
): PurgeSelection {
  if (isAdminOrOwner(doc)) return { selected: false, reason: 'admin_or_owner' };
  if (PURGE_SAFELIST_UIDS.has(uid)) return { selected: false, reason: 'safelisted' };
  if (!isNeverOnboarded(doc)) return { selected: false, reason: 'onboarded' };
  return { selected: true };
}

/**
 * Guards a REAL purge: the confirm sentinel must be present and exact. Pure so
 * the "no token → refuse" rule is unit-testable. Dry runs never call this.
 */
export function isConfirmed(confirmToken: string | undefined): boolean {
  return confirmToken === PURGE_CONFIRM_TOKEN;
}
