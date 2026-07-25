/**
 * User-search domain core (pure logic): query normalization, the minimum-length
 * gate, the result-limit clamp, and the safe public projection returned by
 * `userSearch.members`.
 *
 * WHAT THIS SEARCH IS (and is not)
 * --------------------------------
 * Firestore has no substring/contains/case-insensitive operator and no
 * full-text index. The only capability available without bolting on an external
 * search service is a RANGE scan over a denormalized, case-folded key. So the
 * matching contract here is PREFIX matching over `users/{uid}.displayNameLower`
 * — the same key, derived by the same {@link toSearchKey} rule, that
 * friend nickname resolution already queries (friends/manageFriends.ts
 * resolveTarget):
 *
 *   typed 'gt'   → matches 'gt_86', 'GT86_swe', 'Gtx'      ✅
 *   typed 'gt86' → matches 'gt86', 'gt86_swe'              ✅
 *   typed '86'   → does NOT match 'gt_86'                  ❌ (mid-word)
 *
 * A trailing/mid-word substring would need a precomputed n-gram or token array
 * per user (`array-contains`), which multiplies every profile write and every
 * backfill by the number of generated tokens. That is deliberately NOT built
 * here; the stated requirement ("type 'gt', see 'gt_86'") is a prefix case.
 *
 * WHY toSearchKey / prefixUpperBound ARE IMPORTED, NOT REIMPLEMENTED
 * -----------------------------------------------------------------
 * The stored key and the query key MUST be derived by the identical rule or the
 * range simply misses rows — a second, "equivalent" implementation is a silent
 * desync waiting to happen (locale-sensitive folding, a different trim, a
 * sentinel upper bound that excludes astral characters). friends-core.ts owns
 * that rule and its regression tests, so it is imported verbatim and re-exported
 * for this module's own tests. Nothing else is taken from the friends domain.
 *
 * Kept Firebase-free so every decision below is unit-testable without the
 * emulator; searchMembers.ts owns all Firestore I/O.
 */

import { z } from 'zod';
import { prefixUpperBound, toSearchKey } from '../friends/friends-core';

export { prefixUpperBound, toSearchKey };

/** A single typeahead row. Deliberately the minimal PUBLIC profile only. */
export interface MemberSearchHit {
  uid: string;
  displayName: string | null;
  avatarPath: string | null;
}

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

/**
 * Shortest query we will run, measured in CODE POINTS of the normalized key.
 *
 * A 1-character prefix over a growing member base is close to a
 * collection scan on the server and near-useless on the client (it would return
 * an arbitrary alphabetical slice of everyone whose name starts with that
 * letter), so the client shows a "keep typing" hint instead and the callable
 * refuses it outright as a backstop. Two is the smallest length at which the
 * range is meaningfully selective while still matching Seb's own example
 * ('gt' → 'gt_86').
 */
export const MIN_QUERY_CODE_POINTS = 2;

/**
 * Hard ceiling on rows returned, and the default when the caller asks for none.
 *
 * This is a TYPEAHEAD: a dropdown taller than this is unusable on a phone, and
 * a low cap is also what keeps the callable from becoming a member-directory
 * export — a caller cannot page past it (there is no cursor) so the endpoint
 * can never enumerate the user base.
 */
export const MAX_SEARCH_RESULTS = 20;

/**
 * Raw page size for the `displayNameLower` range scan.
 *
 * The `.limit()` is applied by Firestore BEFORE we drop the caller's own row and
 * restricted (suspended/soft-deleted) accounts, so the fetched page needs
 * headroom or a page filled by rows we then discard would hide real matches
 * behind it. Mirrors the same reasoning as NICKNAME_SCAN_LIMIT in
 * friends/manageFriends.ts. Bounded and fixed — never derived from client input.
 */
export const SEARCH_SCAN_LIMIT = MAX_SEARCH_RESULTS + 10;

/**
 * `query` is the raw text the user typed. It is bounded at the same 120 chars as
 * `displayName` itself (contracts/schemas/user-profile.schema.json) — a longer
 * query cannot match any stored name, so accepting it would only widen the
 * input surface. `limit` is OPTIONAL and CLAMPED rather than rejected (see
 * {@link clampSearchLimit}); a non-integer or non-positive limit is a
 * malformed call, not an over-ask, and is rejected here.
 */
const searchMembersSchema = z
  .object({
    query: z.string().max(120),
    limit: z.number().int().positive().optional(),
  })
  .strict();

export type SearchMembersInput = z.infer<typeof searchMembersSchema>;

export const SEARCH_MEMBERS_EXPECTED =
  'Expected { query: string (<=120 chars), limit?: positive integer }.';

export function parseSearchMembersInput(data: unknown): ParseResult<SearchMembersInput> {
  const result = searchMembersSchema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: SEARCH_MEMBERS_EXPECTED };
  }
  return { ok: true, input: result.data };
}

/** User-facing message for a query that is too short (clients branch on code). */
export const QUERY_TOO_SHORT_MESSAGE = `Type at least ${MIN_QUERY_CODE_POINTS} characters to search for a member.`;

/**
 * `details.reason` discriminator for the too-short refusal. It shares the
 * `invalid-argument` code with a genuinely malformed payload, and the two want
 * opposite treatment on the client: "keep typing" is a normal, silent state of a
 * typeahead, whereas a malformed payload is a bug worth surfacing.
 */
export const REASON_QUERY_TOO_SHORT = 'QUERY_TOO_SHORT';

/**
 * True when the normalized key is long enough to run.
 *
 * Counted in CODE POINTS (`Array.from`), not UTF-16 code units: a two-emoji
 * nickname prefix is two characters to the person typing it but four
 * `String.length` units, and a one-emoji prefix is two units — so a
 * `.length >= 2` test would both accept a 1-character query and reject nothing
 * it should. The bound is on what the USER perceives as characters.
 */
export function isSearchableKey(key: string): boolean {
  return Array.from(key).length >= MIN_QUERY_CODE_POINTS;
}

/**
 * Normalizes raw typed text into the stored-key space. Identical rule to the
 * one that WROTE the key (trim + locale-invariant lowercase), which is the
 * whole reason a case-insensitive range query works at all.
 */
export function toSearchQueryKey(rawQuery: string): string {
  return toSearchKey(rawQuery);
}

/**
 * Clamps a requested page size into [1, {@link MAX_SEARCH_RESULTS}], defaulting
 * to the maximum when unspecified.
 *
 * CLAMPED, not rejected: the limit is a UI convenience (a narrow surface may
 * want 5 rows), and an over-ask is not an error the user could act on — it is
 * just a client asking for more than the endpoint will ever serve. Rejecting it
 * would turn a harmless request into a visible failure. What must NOT happen is
 * the ask being honoured, because that is exactly how a bounded lookup turns
 * into a member-directory dump.
 */
export function clampSearchLimit(requested: number | undefined): number {
  if (requested === undefined) {
    return MAX_SEARCH_RESULTS;
  }
  return Math.min(Math.max(1, Math.floor(requested)), MAX_SEARCH_RESULTS);
}

/**
 * Half-open `[start, end)` bounds for the `displayNameLower` prefix range.
 *
 * Exposed as one function so a caller cannot accidentally pair a key with a
 * bound derived from a DIFFERENT key.
 */
export function searchKeyRange(key: string): { start: string; end: string } {
  return { start: key, end: prefixUpperBound(key) };
}

/**
 * Projects a `users/{uid}` document into the row the client renders.
 *
 * PRIVACY: this is an allowlist of exactly three public fields, built field by
 * field — never a spread of the stored document. `users/{uid}` also carries
 * `email`, `role`, `activeMember`, `suspended`, `deleted`, `lastLoginAt`,
 * provider identity and other backend-managed state, none of which a stranger
 * typing two letters has any business receiving. A future field added to the
 * user document is therefore invisible here by default rather than leaked by
 * default, which is the only version of this that stays correct over time.
 */
export function toMemberSearchHit(
  uid: string,
  data: Record<string, unknown> | undefined,
): MemberSearchHit {
  const displayName = data?.displayName;
  const avatarPath = data?.avatarPath;
  return {
    uid,
    displayName: typeof displayName === 'string' ? displayName : null,
    avatarPath: typeof avatarPath === 'string' ? avatarPath : null,
  };
}

// ---------------------------------------------------------------------------
// Per-user rate limit (fixed window)
// ---------------------------------------------------------------------------
//
// A typeahead is a HOT read path by construction: it fires as fast as the
// client's debounce lets it. The guard therefore has the same cheap shape as
// incidents.listNearby's — a deterministic counter doc read BY ID and bumped
// with FieldValue.increment (commutative, no transaction, no composite index) —
// rather than the transactional windowed count() aggregation used by the
// low-frequency write limiters (feedback.reportIssue, errors.reportClientError).
// A rejected call costs exactly one get and no write, so the guard is always
// cheaper than the scan + block reads it protects.
//
// The counter is written ONLY by the callable via the Admin SDK and is denied to
// all clients in firebase/firestore.rules. `expireAt` carries a Firestore TTL
// policy so spent windows self-delete.

/** Backend-only fixed-window rate-limit counter collection (client-denied). */
export const MEMBER_SEARCH_RATE_LIMIT_COLLECTION = 'memberSearchRateLimits';

/**
 * Max admitted `userSearch.members` calls per uid per fixed 60 s window.
 *
 * Sized so real typing NEVER trips it. The client debounces at ~275 ms and only
 * queries once the key reaches {@link MIN_QUERY_CODE_POINTS}, so even continuous
 * typing for a full minute yields on the order of 20-40 calls; a realistic
 * session (a few searches, a few seconds each) is well under 10. 90/min leaves
 * multiple times that headroom while still being two orders of magnitude below a
 * client stuck in a hot loop — it catches the RUNAWAY, it does not throttle a
 * fast typist.
 */
export const MEMBER_SEARCH_RATE_LIMIT_MAX = 90;

/** Fixed window length: one minute. */
export const MEMBER_SEARCH_RATE_LIMIT_WINDOW_MS = 60_000;

/**
 * Grace added to a window's end before its counter doc becomes eligible for TTL
 * deletion. Affects cleanup timing only (Firestore TTL is best-effort anyway),
 * never the limit decision.
 */
export const MEMBER_SEARCH_RATE_LIMIT_TTL_GRACE_MS = 5 * 60_000;

/** Epoch-minute index of the fixed window containing `nowMs`. */
export function memberSearchRateLimitWindowIndex(nowMs: number): number {
  return Math.floor(nowMs / MEMBER_SEARCH_RATE_LIMIT_WINDOW_MS);
}

/** Deterministic counter document id for (uid, window) — read by id, no query. */
export function memberSearchRateLimitDocId(uid: string, nowMs: number): string {
  return `${uid}_${memberSearchRateLimitWindowIndex(nowMs)}`;
}

/** Instant at which the window's counter doc may be TTL-reaped. */
export function memberSearchRateLimitExpiry(nowMs: number): Date {
  const windowEnd =
    (memberSearchRateLimitWindowIndex(nowMs) + 1) * MEMBER_SEARCH_RATE_LIMIT_WINDOW_MS;
  return new Date(windowEnd + MEMBER_SEARCH_RATE_LIMIT_TTL_GRACE_MS);
}

/** Pure admit/reject decision for the current window's observed count. */
export function isUnderMemberSearchRateLimit(
  currentCount: number,
  max: number = MEMBER_SEARCH_RATE_LIMIT_MAX,
): boolean {
  return currentCount < max;
}

export const RATE_LIMITED_MESSAGE = 'Too many searches — please slow down and try again shortly.';
