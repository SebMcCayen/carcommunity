/**
 * Friends domain core (pure logic): input parsing, id derivation, document
 * builders, and summary mappers for the friend-graph callables
 * (friend.sendRequest / friend.respondRequest / friend.cancelRequest /
 * friend.remove / friend.list).
 *
 * Data model (all owner-readable, backend-only writes — firebase/firestore.rules):
 *  - friendRequests/{requestId} where requestId = friendRequestId(fromUid,
 *    toUid), a collision-resistant length-prefixed SHA-256 over the ordered
 *    (fromUid, toUid) pair (one directional request per ordered pair). Fields:
 *    fromUid, toUid,
 *    status ('pending' | 'accepted' | 'declined'), denormalized display
 *    names + avatar paths for both parties, createdAt, updatedAt.
 *  - users/{uid}/friends/{friendUid}: an established friendship, written for
 *    BOTH sides on accept. Fields: friendUid, displayName, avatarPath,
 *    createdAt. Established friendship is the single source of truth for the
 *    "already friends" check — a stale accepted/declined request never blocks
 *    a fresh request.
 *
 * The denormalized displayName/avatarPath on BOTH document kinds are a snapshot
 * taken at write time and are never rewritten, so friend.list refreshes them
 * from live `users/{uid}` before answering (hydrateFriendSummary below) and
 * treats the stored copy purely as a fallback.
 *
 * Kept Firebase-free so it stays unit-testable without the emulator (mirrors
 * blocking/blocking-core.ts). The callables in manageFriends.ts own all
 * Firestore I/O, block-graph checks, and transactions.
 */

import { createHash } from 'node:crypto';
import { z } from 'zod';

/** Terminal + pending states a friend request can be in. */
export const FRIEND_REQUEST_STATUSES = ['pending', 'accepted', 'declined'] as const;
export type FriendRequestStatus = (typeof FRIEND_REQUEST_STATUSES)[number];

/**
 * Minimal safe profile projection denormalized onto friend/request documents
 * and returned to clients. Never includes email, provider identity,
 * subscription, moderation, or other sensitive fields.
 */
export interface ProfileProjection {
  displayName: string | null;
  avatarPath: string | null;
}

/** An established friend, as returned by friend.list. */
export interface FriendSummary {
  uid: string;
  displayName: string | null;
  avatarPath: string | null;
  friendsSince: string;
}

/** The other party of a pending request, normalized for the caller's UI. */
export interface FriendRequestSummary {
  requestId: string;
  fromUid: string;
  toUid: string;
  /** 'incoming' → addressed to the caller; 'outgoing' → sent by the caller. */
  direction: 'incoming' | 'outgoing';
  otherUser: {
    uid: string;
    displayName: string | null;
    avatarPath: string | null;
  };
  createdAt: string;
}

/** A candidate surfaced in the AMBIGUOUS_NICKNAME error details. */
export interface NicknameCandidate {
  uid: string;
  displayName: string | null;
  avatarPath: string | null;
}

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

/**
 * Firebase Auth UIDs are opaque, non-empty, bounded strings. displayName is
 * the public "nickname" (contracts/schemas/user-profile.schema.json:
 * 1..120 chars). Existence/uniqueness is resolved against users/{uid} at call
 * time, never by the schema.
 */
const uidSchema = z.string().trim().min(1).max(128);
const nicknameSchema = z.string().trim().min(1).max(120);

/**
 * sendRequest accepts EITHER a nickname (the primary path) OR a resolved
 * toUid (used by clients to disambiguate after an AMBIGUOUS_NICKNAME error) —
 * exactly one, never both.
 */
const sendRequestSchema = z
  .object({
    nickname: nicknameSchema.optional(),
    toUid: uidSchema.optional(),
  })
  .strict()
  .refine(
    (value) => (value.nickname === undefined) !== (value.toUid === undefined),
    { message: 'Provide exactly one of { nickname } or { toUid }.' },
  );

export type SendRequestInput = z.infer<typeof sendRequestSchema>;

const respondRequestSchema = z
  .object({
    requestId: z.string().trim().min(1).max(300),
    action: z.enum(['accept', 'decline']),
  })
  .strict();

export type RespondRequestInput = z.infer<typeof respondRequestSchema>;

const removeFriendSchema = z
  .object({
    friendUid: uidSchema,
  })
  .strict();

export type RemoveFriendInput = z.infer<typeof removeFriendSchema>;

/**
 * cancelRequest addresses the request by its RECIPIENT, not by a requestId.
 *
 * The doc id is a deterministic hash of the ordered (fromUid, toUid) pair, so
 * deriving it server-side from (caller, toUid) means a caller can only ever
 * address their OWN outgoing request: no requestId belonging to anyone else can
 * be named at all, which makes the authorization structural rather than an
 * ownership check on a client-supplied id. It also makes the call idempotent by
 * construction — the same { toUid } always resolves to the same document.
 */
const cancelRequestSchema = z
  .object({
    toUid: uidSchema,
  })
  .strict();

export type CancelRequestInput = z.infer<typeof cancelRequestSchema>;

const listSchema = z.object({}).strict();

function parse<T>(schema: z.ZodType<T>, data: unknown, expected: string): ParseResult<T> {
  const result = schema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: expected };
  }
  return { ok: true, input: result.data };
}

export const SEND_REQUEST_EXPECTED =
  'Expected exactly one of { nickname } (a display name) or { toUid } (a user id).';
export const RESPOND_REQUEST_EXPECTED = 'Expected { requestId, action } where action is accept|decline.';
export const REMOVE_FRIEND_EXPECTED = 'Expected { friendUid } (a non-empty user id).';
export const CANCEL_REQUEST_EXPECTED = 'Expected { toUid } (a non-empty user id).';

export function parseSendRequestInput(data: unknown): ParseResult<SendRequestInput> {
  return parse(sendRequestSchema, data, SEND_REQUEST_EXPECTED);
}

export function parseRespondRequestInput(data: unknown): ParseResult<RespondRequestInput> {
  return parse(respondRequestSchema, data, RESPOND_REQUEST_EXPECTED);
}

export function parseRemoveFriendInput(data: unknown): ParseResult<RemoveFriendInput> {
  return parse(removeFriendSchema, data, REMOVE_FRIEND_EXPECTED);
}

export function parseCancelRequestInput(data: unknown): ParseResult<CancelRequestInput> {
  return parse(cancelRequestSchema, data, CANCEL_REQUEST_EXPECTED);
}

export function parseListInput(data: unknown): ParseResult<Record<string, never>> {
  return parse(listSchema, data, 'Expected an empty object.');
}

/** User-facing messages (clients branch on the HttpsError code, never text). */
export const SELF_REQUEST_MESSAGE = 'You cannot send a friend request to yourself.';
export const NICKNAME_NOT_FOUND_MESSAGE = 'No user found with that nickname.';
export const AMBIGUOUS_NICKNAME_MESSAGE =
  'Several members match that nickname. Pick the intended one.';
export const ALREADY_FRIENDS_MESSAGE = 'You are already friends with this user.';
export const REQUEST_ALREADY_SENT_MESSAGE = 'You already have a pending request to this user.';
export const NOT_ADDABLE_MESSAGE = 'This user cannot be added right now.';
export const REQUEST_NOT_FOUND_MESSAGE = 'Friend request not found.';
export const REQUEST_NOT_PENDING_MESSAGE = 'This friend request has already been handled.';
export const BACKEND_UNAVAILABLE_MESSAGE =
  'The friends service is temporarily unavailable. Try again shortly.';

/**
 * gRPC status code 9 (FAILED_PRECONDITION), the status Firestore returns when a
 * query has no backing composite index. Compared numerically because the
 * Firestore SDK surfaces a plain `Error` carrying a `code` NUMBER — there is no
 * exported error class to instanceof against.
 */
const GRPC_FAILED_PRECONDITION = 9;

/**
 * True when `error` is Firestore's "the query requires an index" failure.
 *
 * WHY THIS EXISTS (regression guard, 2026-07-19): `friend.list` range-scans
 * `friendRequests` with two equality filters plus an `orderBy('createdAt')`,
 * which REQUIRES the composite indexes declared in
 * firebase/firestore.indexes.json. Those indexes are deployed by hand
 * (`firebase deploy --only firestore:indexes`) and had never been deployed to
 * production, so every `friend.list` call failed with FAILED_PRECONDITION. The
 * raw throw escaped the callable as an opaque INTERNAL, which the Android
 * client could only render as "your friends couldn't be loaded" on BOTH the
 * Friends page and the convoy invite picker — a deployment fault that was
 * indistinguishable from an app bug and therefore invisible for days.
 *
 * Classifying it here turns that silence into a specific, retryable signal
 * (`unavailable` + REASON_BACKEND_UNAVAILABLE) plus a server log naming the
 * missing index, so the NEXT missing index is diagnosable from the error alone.
 *
 * NOTE: the message text is matched only to narrow an already-narrow status —
 * FAILED_PRECONDITION is otherwise unused by this callable's own reads — so a
 * wording change upstream degrades to the generic path, never to a wrong one.
 */
export function isMissingIndexError(error: unknown): boolean {
  if (typeof error !== 'object' || error === null) {
    return false;
  }
  const code = (error as { code?: unknown }).code;
  if (code !== GRPC_FAILED_PRECONDITION) {
    return false;
  }
  const message = (error as { message?: unknown }).message;
  return typeof message === 'string' && message.toLowerCase().includes('requires an index');
}

/**
 * `details.reason` discriminators carried on the HttpsError of every
 * sendRequest failure mode. The HttpsError CODE alone is ambiguous — both
 * "already friends" and "request already pending" are `already-exists`, and
 * both "ambiguous nickname" and "not addable" are `failed-precondition` — so
 * a client cannot render a specific, actionable message from the code alone.
 * Every reason below is attached by manageFriends.ts and asserted in
 * friends.emulator.test.ts.
 *
 * PRIVACY: NOT_ADDABLE is deliberately opaque. It is emitted both when the
 * caller blocked the target and when the target blocked the caller, and it
 * carries no further detail, so the blocked party can never learn that (or by
 * whom) they were blocked. Callers must not add a reason that distinguishes
 * the two directions.
 */
export const REASON_AMBIGUOUS_NICKNAME = 'AMBIGUOUS_NICKNAME';
export const REASON_NOT_ADDABLE = 'NOT_ADDABLE';
export const REASON_ALREADY_FRIENDS = 'ALREADY_FRIENDS';
export const REASON_REQUEST_ALREADY_SENT = 'REQUEST_ALREADY_SENT';
export const REASON_NICKNAME_NOT_FOUND = 'NICKNAME_NOT_FOUND';
export const REASON_SELF_REQUEST = 'SELF_REQUEST';

/**
 * Attached by `friend.list` when the read failed for a reason that is the
 * BACKEND's fault and not the caller's — today, a Firestore query with no
 * deployed composite index (see {@link isMissingIndexError}).
 *
 * Distinct from every reason above: those describe a business-rule "no" that
 * the user can act on, whereas this one means the service is broken and the
 * only useful advice is "try again". The client both renders a specific
 * retryable message for it AND auto-reports it, because — unlike an ambiguous
 * nickname or an already-sent request — it is a genuine fault we must hear
 * about.
 */
export const REASON_BACKEND_UNAVAILABLE = 'BACKEND_UNAVAILABLE';

/**
 * The denormalized, case-folded search key stored on `users/{uid}` as
 * `displayNameLower` and used by nickname resolution. Firestore has no
 * case-insensitive or substring operator, so the lowercase form is persisted
 * alongside `displayName` and queried as a prefix range.
 *
 * LOCALE: uses `String.prototype.toLowerCase()`, which is locale-INVARIANT by
 * spec (unlike `toLocaleLowerCase()`). This matters because the key is written
 * by the backend but the query may be derived from text typed on a device in
 * any locale: a locale-sensitive fold would map 'I' to 'ı' under a Turkish
 * locale and silently desync the stored key from the query key. Never swap
 * this for `toLocaleLowerCase()`.
 *
 * Trimming mirrors `nicknameSchema` (which trims before validating), so the
 * stored key always matches the key derived from a trimmed user query.
 */
export function toSearchKey(displayName: string): string {
  return displayName.trim().toLowerCase();
}

/** Largest Unicode code point; the ceiling for {@link prefixUpperBound}. */
const MAX_CODE_POINT = 0x10ffff;

/**
 * EXCLUSIVE upper bound for a `>= key` / `< bound` prefix range query, i.e. the
 * smallest string that sorts strictly above every string starting with `key`.
 *
 * Built by incrementing the final CODE POINT of `key` (not by appending a
 * sentinel such as ''). Firestore orders strings by their UTF-8 BYTES, so
 * an astral character — an emoji like U+1F600, encoded as a 4-byte sequence —
 * sorts ABOVE U+FFFF's 3-byte encoding. A sentinel bound would therefore
 * silently exclude a display name such as "gt86😀" from the prefix "gt86";
 * incrementing the code point cannot. Pinned by an emoji-suffix test in
 * friends-core.test.ts.
 *
 * Lone surrogates (U+D800..U+DFFF) are not valid scalar values, so an increment
 * landing in that block is lifted to U+E000. When `key` consists solely of
 * U+10FFFF there is no representable bound above it and `key` itself is
 * returned, yielding an empty range; callers never hit this because
 * `nicknameSchema` rejects an empty nickname and such a name is unreachable.
 */
export function prefixUpperBound(key: string): string {
  const codePoints = Array.from(key);
  for (let i = codePoints.length - 1; i >= 0; i -= 1) {
    const codePoint = codePoints[i]!.codePointAt(0)!;
    if (codePoint < MAX_CODE_POINT) {
      let next = codePoint + 1;
      if (next >= 0xd800 && next <= 0xdfff) {
        next = 0xe000;
      }
      return codePoints.slice(0, i).join('') + String.fromCodePoint(next);
    }
  }
  return key;
}

/**
 * Length-prefixed SHA-256 over a tuple → a collision-resistant, Firestore-safe
 * (hex) document ID. Length-prefixing makes the encoding injective: no input
 * value can forge a field boundary, so distinct tuples never map to the same
 * digest regardless of which characters the parts contain. Mirrors
 * crownHunt/crownhunt-core.ts hashDocId.
 */
function hashDocId(parts: readonly string[]): string {
  const hash = createHash('sha256');
  for (const part of parts) {
    hash.update(`${part.length}:${part}`);
  }
  return hash.digest('hex');
}

/**
 * Deterministic, directional request id. friendRequestId(A, B) (A sent to B)
 * is distinct from friendRequestId(B, A), so each direction has at most one
 * request document and a re-sent request upserts the same doc (no duplicate
 * pending records). Derived via length-prefixed SHA-256 over the ordered pair
 * so it can never collide even when a uid contains separator substrings (a
 * naive `${fromUid}__${toUid}` join collides, e.g. ('a','b__c') vs
 * ('a__b','c')).
 */
export function friendRequestId(fromUid: string, toUid: string): string {
  return hashDocId([fromUid, toUid]);
}

/** Reads a profile doc into the minimal safe projection (missing → null). */
export function toProfileProjection(doc: Record<string, unknown> | undefined): ProfileProjection {
  const displayName = doc?.displayName;
  const avatarPath = doc?.avatarPath;
  return {
    displayName: typeof displayName === 'string' ? displayName : null,
    avatarPath: typeof avatarPath === 'string' ? avatarPath : null,
  };
}

/** friendRequests/{requestId} document body (pending). */
export function buildFriendRequestDocument(
  fromUid: string,
  toUid: string,
  fromProfile: ProfileProjection,
  toProfile: ProfileProjection,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  const ts = serverTimestamp();
  return {
    fromUid,
    toUid,
    status: 'pending' satisfies FriendRequestStatus,
    fromDisplayName: fromProfile.displayName,
    fromAvatarPath: fromProfile.avatarPath,
    toDisplayName: toProfile.displayName,
    toAvatarPath: toProfile.avatarPath,
    createdAt: ts,
    updatedAt: ts,
  };
}

/** users/{uid}/friends/{friendUid} document body. */
export function buildFriendshipDocument(
  friendUid: string,
  profile: ProfileProjection,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    friendUid,
    displayName: profile.displayName,
    avatarPath: profile.avatarPath,
    createdAt: serverTimestamp(),
  };
}

/** Maps a stored friendship doc into the client summary. */
export function toFriendSummary(
  friendUid: string,
  data: Record<string, unknown> | undefined,
  friendsSinceIso: string,
): FriendSummary {
  return {
    uid: friendUid,
    displayName: typeof data?.displayName === 'string' ? data.displayName : null,
    avatarPath: typeof data?.avatarPath === 'string' ? data.avatarPath : null,
    friendsSince: friendsSinceIso,
  };
}

/**
 * Live `users/{uid}` projections keyed by uid, used to refresh the denormalized
 * copies carried by friendship / friendRequest documents.
 *
 * A uid is present ONLY when its user document EXISTS. An absent entry
 * therefore means "no live profile to speak for this member" (deleted account,
 * or a read that failed) and NOT "this member has no avatar" — the two must not
 * be conflated, because the first has to fall back to the stored copy while the
 * second is an authoritative null. That distinction is the whole reason this is
 * a Map of projections rather than a Map of avatar paths.
 */
export type LiveProfiles = ReadonlyMap<string, ProfileProjection>;

/**
 * The distinct member uids named by a friend.list response — every friend plus
 * the other party of every pending request — for a single batched profile read.
 *
 * De-duplicated: the same member can appear both as a friend and (in a
 * different pair state) as a request counterparty, and paying twice for the
 * same user document would be pure waste.
 */
export function profileUidsToHydrate(
  friends: readonly FriendSummary[],
  requests: readonly FriendRequestSummary[],
): string[] {
  const uids = new Set<string>();
  for (const friend of friends) {
    if (friend.uid) uids.add(friend.uid);
  }
  for (const request of requests) {
    if (request.otherUser.uid) uids.add(request.otherUser.uid);
  }
  return [...uids];
}

/**
 * Overlays the LIVE profile onto a friendship summary.
 *
 * WHY THIS EXISTS (the bug it fixes, 2026-07-27)
 * ---------------------------------------------
 * `users/{uid}/friends/{friendUid}` carries a COPY of the friend's displayName
 * and avatarPath, captured once by buildFriendshipDocument at the moment the
 * request was accepted. Nothing ever rewrites it. A member who sets their first
 * avatar — or changes it, or renames themselves — AFTER a friendship was
 * established therefore stays, in that friend's list, exactly as they were on
 * the day they became friends: forever avatar-less, or wearing an old picture.
 * The member-profile screen reads live `users/{uid}` and shows the real one, so
 * the same person renders with a picture on their profile and a grey silhouette
 * one screen up. This is the same shape as the `displayNameLower` bug (see
 * users/onUserProfileWrite.ts): a derived copy with no writer keeping it true.
 *
 * The fix reads the live document rather than maintaining the copy, so there is
 * no trigger to miss a write path and no backfill for edges written before it.
 * A member who REMOVES their avatar must also stop showing the old one, so a
 * live `null` deliberately WINS over a stored non-null path — hydration is a
 * replacement, not a fallback-fill.
 *
 * The stored copy is kept and is still the fallback: when the live profile is
 * absent (deleted account, or the batched read failed) the list renders the
 * last known name/avatar instead of an anonymous row.
 */
export function hydrateFriendSummary(summary: FriendSummary, live: LiveProfiles): FriendSummary {
  const profile = live.get(summary.uid);
  if (!profile) return summary;
  return { ...summary, displayName: profile.displayName, avatarPath: profile.avatarPath };
}

/**
 * Overlays the LIVE profile onto the other party of a pending request. Same
 * rot, same rule as [hydrateFriendSummary]: friendRequests documents
 * denormalize both parties' names and avatars at send time
 * (buildFriendRequestDocument) and are never rewritten, so a request that has
 * been sitting in someone's inbox since before they uploaded an avatar shows
 * none.
 */
export function hydrateFriendRequestSummary(
  summary: FriendRequestSummary,
  live: LiveProfiles,
): FriendRequestSummary {
  const profile = live.get(summary.otherUser.uid);
  if (!profile) return summary;
  return {
    ...summary,
    otherUser: {
      uid: summary.otherUser.uid,
      displayName: profile.displayName,
      avatarPath: profile.avatarPath,
    },
  };
}

/**
 * Maps a stored friendRequests doc into the caller-oriented summary,
 * projecting the OTHER party's denormalized profile.
 */
export function toFriendRequestSummary(
  requestId: string,
  data: Record<string, unknown>,
  callerUid: string,
  createdAtIso: string,
): FriendRequestSummary {
  const fromUid = String(data.fromUid);
  const toUid = String(data.toUid);
  const direction: 'incoming' | 'outgoing' = toUid === callerUid ? 'incoming' : 'outgoing';
  const otherUid = direction === 'incoming' ? fromUid : toUid;
  const otherDisplayName = direction === 'incoming' ? data.fromDisplayName : data.toDisplayName;
  const otherAvatarPath = direction === 'incoming' ? data.fromAvatarPath : data.toAvatarPath;
  return {
    requestId,
    fromUid,
    toUid,
    direction,
    otherUser: {
      uid: otherUid,
      displayName: typeof otherDisplayName === 'string' ? otherDisplayName : null,
      avatarPath: typeof otherAvatarPath === 'string' ? otherAvatarPath : null,
    },
    createdAt: createdAtIso,
  };
}
