/**
 * Friends domain core (pure logic): input parsing, id derivation, document
 * builders, and summary mappers for the friend-graph callables
 * (friend.sendRequest / friend.respondRequest / friend.remove / friend.list).
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

export function parseSendRequestInput(data: unknown): ParseResult<SendRequestInput> {
  return parse(sendRequestSchema, data, SEND_REQUEST_EXPECTED);
}

export function parseRespondRequestInput(data: unknown): ParseResult<RespondRequestInput> {
  return parse(respondRequestSchema, data, RESPOND_REQUEST_EXPECTED);
}

export function parseRemoveFriendInput(data: unknown): ParseResult<RemoveFriendInput> {
  return parse(removeFriendSchema, data, REMOVE_FRIEND_EXPECTED);
}

export function parseListInput(data: unknown): ParseResult<Record<string, never>> {
  return parse(listSchema, data, 'Expected an empty object.');
}

/** User-facing messages (clients branch on the HttpsError code, never text). */
export const SELF_REQUEST_MESSAGE = 'You cannot send a friend request to yourself.';
export const NICKNAME_NOT_FOUND_MESSAGE = 'No user found with that nickname.';
export const AMBIGUOUS_NICKNAME_MESSAGE =
  'Several members share that nickname. Pick the intended one.';
export const ALREADY_FRIENDS_MESSAGE = 'You are already friends with this user.';
export const REQUEST_ALREADY_SENT_MESSAGE = 'You already have a pending request to this user.';
export const NOT_ADDABLE_MESSAGE = 'This user cannot be added right now.';
export const REQUEST_NOT_FOUND_MESSAGE = 'Friend request not found.';
export const REQUEST_NOT_PENDING_MESSAGE = 'This friend request has already been handled.';

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
