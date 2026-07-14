/**
 * Chat-channels domain core (pure logic): input parsing, message-document
 * builders, and client-summary mappers shared by the COMMUNITY and CONVOY chat
 * callables (communityChat.post/list/markRead + convoyChat.post/list).
 *
 * Product model (THREE chats): (1) a single COMMUNITY channel every active
 * member reads + posts to, (2) a per-CONVOY channel readable/postable only by
 * ACCEPTED convoy members, and (3) FRIENDS chat = the EXISTING 1:1 DMs
 * (functions/src/dm — NOT rebuilt here). The chat UI additionally surfaces the
 * existing in-app notifications inbox (functions/src/notifications) as a section
 * — that is a UI concern, no new notifications system is built here.
 *
 * Data model (member-readable per channel, backend-only writes —
 * firebase/firestore.rules):
 *  - COMMUNITY: a single fixed channel document `communityChat/{COMMUNITY_CHANNEL_ID}`
 *    (COMMUNITY_CHANNEL_ID = 'global') with a `messages` subcollection:
 *    `communityChat/global/messages/{messageId}`
 *      { senderUid, text, createdAt, senderDisplayName, senderAvatarPath }.
 *    Any ACTIVE MEMBER reads; writes go through communityChat.post only.
 *  - CONVOY: per convoy, `convoyChats/{convoyId}/messages/{messageId}` with the
 *    same message shape. Only ACCEPTED members of `convoys/{convoyId}`
 *    (memberUids + members[uid].inviteStatus === 'accepted', owner included)
 *    read/post; writes go through convoyChat.post only.
 *
 * The sender's safe profile (displayName + avatarPath) is DENORMALIZED onto each
 * message so a channel render needs no per-message profile lookup (channels have
 * no bounded member set to keep a profile map for, unlike a 1:1 DM conversation).
 *
 * UNREAD (community): a per-user unread AGGREGATE would require a fan-out write
 * to every member on every post — prohibitively expensive on a global channel.
 * Instead the community channel uses a lightweight per-user LAST-READ marker at
 * `userPrivate/{uid}.communityChatLastReadAt` (owner-only readable, alongside
 * dmUnreadTotal). communityChat.markRead stamps it; communityChat.list returns
 * it. The client's newest-message live listener shows an unread dot when the
 * newest message's createdAt is newer than the caller's lastReadAt — O(1) per
 * user, no fan-out. Convoy channels carry no unread state (small, session-scoped).
 *
 * BLOCKING (community): the community channel is a global town square and does
 * NOT filter messages by blocks server-side — that would cost an unbounded block
 * lookup per page and break the createdAt pagination cursor. Blocking still
 * governs DMs/friend/convoy interactions; hiding a blocked user's community
 * messages is left to the client as a display concern (documented choice).
 *
 * Kept Firebase-free so it stays unit-testable without the emulator (mirrors
 * dm/dm-core.ts + convoy/convoy-core.ts). The callables in communityChat.ts /
 * convoyChat.ts own all Firestore I/O, membership checks, and transactions.
 */

import { z } from 'zod';

/** Plain-text message cap (matches DM's 1..2000). */
export const CHAT_MESSAGE_MAX_LENGTH = 2000;

/** Default page size for the *-list callables (newest-first window). */
export const CHAT_MESSAGES_PAGE_SIZE = 30;

/** The single global community channel id (`communityChat/{id}`). */
export const COMMUNITY_CHANNEL_ID = 'global';

/**
 * Minimal safe profile projection denormalized onto each message. Never includes
 * email, provider identity, subscription, moderation, or other sensitive fields
 * (mirrors dm/convoy ProfileProjection).
 */
export interface ProfileProjection {
  displayName: string | null;
  avatarPath: string | null;
}

/** One chat message as returned by communityChat.list / convoyChat.list. */
export interface ChatMessageSummary {
  id: string;
  senderUid: string;
  text: string;
  senderDisplayName: string | null;
  senderAvatarPath: string | null;
  createdAt: string;
}

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

/** Firestore-safe id (auto-generated; validated on the convoy path). */
const idSchema = z
  .string()
  .trim()
  .min(1)
  .max(300)
  .regex(/^[A-Za-z0-9._-]+$/)
  .refine((id) => id !== '.' && id !== '..');

const postCommunitySchema = z
  .object({
    text: z.string().min(1).max(CHAT_MESSAGE_MAX_LENGTH),
  })
  .strict();

export type PostCommunityInput = z.infer<typeof postCommunitySchema>;

const listCommunitySchema = z
  .object({
    // ISO-8601 cursor on createdAt (exclusive upper bound) for older pages.
    before: z.string().datetime().optional(),
  })
  .strict();

export type ListCommunityInput = z.infer<typeof listCommunitySchema>;

const markReadCommunitySchema = z.object({}).strict();

const postConvoySchema = z
  .object({
    convoyId: idSchema,
    text: z.string().min(1).max(CHAT_MESSAGE_MAX_LENGTH),
  })
  .strict();

export type PostConvoyInput = z.infer<typeof postConvoySchema>;

const listConvoySchema = z
  .object({
    convoyId: idSchema,
    before: z.string().datetime().optional(),
  })
  .strict();

export type ListConvoyInput = z.infer<typeof listConvoySchema>;

function parse<T>(schema: z.ZodType<T>, data: unknown, expected: string): ParseResult<T> {
  const result = schema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: expected };
  }
  return { ok: true, input: result.data };
}

export const POST_COMMUNITY_EXPECTED = `Expected { text } with text 1..${CHAT_MESSAGE_MAX_LENGTH} characters.`;
export const LIST_COMMUNITY_EXPECTED = 'Expected { before? } where before is an ISO-8601 timestamp.';
export const POST_CONVOY_EXPECTED = `Expected { convoyId, text } with text 1..${CHAT_MESSAGE_MAX_LENGTH} characters.`;
export const LIST_CONVOY_EXPECTED = 'Expected { convoyId, before? } where before is an ISO-8601 timestamp.';

export function parsePostCommunityInput(data: unknown): ParseResult<PostCommunityInput> {
  return parse(postCommunitySchema, data, POST_COMMUNITY_EXPECTED);
}

export function parseListCommunityInput(data: unknown): ParseResult<ListCommunityInput> {
  return parse(listCommunitySchema, data, LIST_COMMUNITY_EXPECTED);
}

export function parseMarkReadCommunityInput(data: unknown): ParseResult<Record<string, never>> {
  return parse(markReadCommunitySchema, data, 'Expected an empty object.');
}

export function parsePostConvoyInput(data: unknown): ParseResult<PostConvoyInput> {
  return parse(postConvoySchema, data, POST_CONVOY_EXPECTED);
}

export function parseListConvoyInput(data: unknown): ParseResult<ListConvoyInput> {
  return parse(listConvoySchema, data, LIST_CONVOY_EXPECTED);
}

/** User-facing messages (clients branch on the HttpsError code, never text). */
export const EMPTY_MESSAGE_MESSAGE = 'Message cannot be empty.';
export const NOT_DELIVERABLE_MESSAGE = 'This message cannot be delivered right now.';
export const CONVOY_NOT_FOUND_MESSAGE = 'Convoy not found.';
export const NOT_CONVOY_MEMBER_MESSAGE = 'Only accepted convoy members can use this chat.';

/** Reads a profile doc into the minimal safe projection (missing → null). */
export function toProfileProjection(doc: Record<string, unknown> | undefined): ProfileProjection {
  const displayName = doc?.displayName;
  const avatarPath = doc?.avatarPath;
  return {
    displayName: typeof displayName === 'string' ? displayName : null,
    avatarPath: typeof avatarPath === 'string' ? avatarPath : null,
  };
}

/**
 * A channel message document body ({community,convoy}Chats/.../messages/{id}).
 * The sender's safe profile is denormalized so the channel renders with no
 * per-message profile lookup.
 */
export function buildChatMessageDocument(
  input: { senderUid: string; text: string; senderProfile: ProfileProjection },
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    senderUid: input.senderUid,
    text: input.text.trim(),
    senderDisplayName: input.senderProfile.displayName,
    senderAvatarPath: input.senderProfile.avatarPath,
    createdAt: serverTimestamp(),
  };
}

/** Maps a stored message doc into the client summary. */
export function toChatMessageSummary(
  id: string,
  data: Record<string, unknown>,
  createdAtIso: string,
): ChatMessageSummary {
  return {
    id,
    senderUid: typeof data.senderUid === 'string' ? data.senderUid : '',
    text: typeof data.text === 'string' ? data.text : '',
    senderDisplayName: typeof data.senderDisplayName === 'string' ? data.senderDisplayName : null,
    senderAvatarPath: typeof data.senderAvatarPath === 'string' ? data.senderAvatarPath : null,
    createdAt: createdAtIso,
  };
}

/**
 * True when `uid` is an ACCEPTED member of a stored convoy doc (owner included).
 * Mirrors the convoy chat rules gate: `uid ∈ memberUids` AND
 * `members[uid].inviteStatus === 'accepted'`. The owner is seeded accepted, so a
 * declined/still-invited member (or a non-member) is rejected.
 */
export function isAcceptedConvoyMember(
  data: Record<string, unknown> | undefined,
  uid: string,
): boolean {
  const memberUids = Array.isArray(data?.memberUids) ? (data!.memberUids as unknown[]) : [];
  if (!memberUids.includes(uid)) {
    return false;
  }
  const members = (data?.members ?? {}) as Record<string, Record<string, unknown> | undefined>;
  return members[uid]?.inviteStatus === 'accepted';
}
