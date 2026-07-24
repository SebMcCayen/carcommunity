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
 *      { senderUid, text, createdAt, senderDisplayName, senderAvatarPath,
 *        mentionedUids }.
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
 * @MENTIONS are the deliberate exception: a mention is not a message sitting in
 * a room you chose to open, it is a directed push into a personal inbox — the
 * same reach a DM has. So the mention producer DOES apply the both-ways block
 * check (bounded: at most MAX_MESSAGE_MENTIONS pairs per post), exactly as the
 * DM domain does, and a blocked pair simply resolves to no mention. The message
 * itself still posts and stays readable by everyone, unchanged.
 *
 * MENTIONS (community): resolution is CLIENT-SIDE. `displayName` is NOT unique
 * (the friends nickname lookup already had to grow an AMBIGUOUS_NICKNAME path
 * for it), so the server must never parse "@Seb" out of the message text and
 * guess which Seb was meant — it would silently notify a stranger with the same
 * name. Instead communityChat.post takes an explicit `mentionedUids` array that
 * the client's @-picker resolved from a real profile, and the server's job is to
 * VALIDATE it (bounded count, dedup, no self, deliverable member, not blocked)
 * rather than to guess. The resolved set is stored on the message as
 * `mentionedUids` so the client renders highlights without re-resolving.
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

/** Chars of a message surfaced in a notification preview (mirrors DM's 120). */
export const CHAT_MESSAGE_PREVIEW_LENGTH = 120;

/**
 * Single-line preview of a chat message for a notification body. Mirrors
 * dm-core's messagePreview: the notification builder truncates again to its own
 * limit, this keeps the producer from shipping a 2000-char string to do it.
 */
export function messagePreview(text: string): string {
  return text.trim().slice(0, CHAT_MESSAGE_PREVIEW_LENGTH);
}

/** The single global community channel id (`communityChat/{id}`). */
export const COMMUNITY_CHANNEL_ID = 'global';

/**
 * Hard cap on @mentions per message — the whole reason a mention producer is
 * affordable where a per-message community fan-out is not. It bounds the post's
 * extra I/O (at most this many profile + block lookups, batched) AND the inbox
 * writes it can trigger, so the cost of a message is O(1) in the app's member
 * count no matter how the app grows. A message naming more than ten people isn't
 * a mention, it's a broadcast — that's what the (still unbuilt) digest is for.
 */
export const MAX_MESSAGE_MENTIONS = 10;

/**
 * Message retention windows (days), config-driven so they stay tunable without a
 * schema change: bump the constant and redeploy. Each channel message stores an
 * `expireAt` Timestamp = createdAt + N days; a Firestore TTL policy on that
 * field then auto-deletes expired messages (see communityChat.ts / convoyChat.ts
 * for the one-time `gcloud firestore fields ttls update` setup). DMs deliberately
 * carry NO expireAt — 1:1 messages are kept until account deletion.
 */
export const COMMUNITY_CHAT_RETENTION_DAYS = 120;
export const CONVOY_CHAT_RETENTION_DAYS = 30;

const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * The TTL instant for a message created at `now`: `now + retentionDays`. Computed
 * from a real Date (not the createdAt serverTimestamp sentinel, which has no value
 * at write time); the sub-second skew against the server-stamped createdAt is
 * irrelevant at a 30–120 day horizon.
 */
export function chatMessageExpiry(now: Date, retentionDays: number): Date {
  return new Date(now.getTime() + retentionDays * DAY_MS);
}

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
  /**
   * The uids this message @mentions, as RESOLVED by the server (self-mentions,
   * duplicates, non-members and blocked pairs already removed). The client
   * highlights these and needs no lookup of its own. Always present; `[]` for a
   * message with no mentions and for every convoy message (convoyChat.post
   * accepts no mentions — see convoyChat.ts).
   */
  mentionedUids: string[];
  /**
   * The sender's optimistic idempotency key, present only when the message was
   * posted with one (it equals the doc id). The client uses it to reconcile its
   * own pending optimistic bubble against this delivered message; omitted for
   * legacy key-less sends.
   */
  clientId?: string;
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

/**
 * Client idempotency key for an OPTIMISTIC send (mirrors dm-core's
 * clientMessageIdSchema). When present it is used VERBATIM as the message doc id
 * (and echoed back as messageId), so the caller's optimistic bubble reconciles
 * against the delivered document by matching that id — and a retry of the same
 * send lands on the same doc (exactly-once). Optional for backward compatibility:
 * an older client that omits it gets an auto-id doc and the previous behaviour.
 *
 * Validated verbatim (no `.trim()`): the alphabet already forbids whitespace, so
 * a key with surrounding spaces is rejected (invalid-argument) rather than
 * silently normalized into a different id the client's de-dupe wouldn't match.
 */
const clientMessageIdSchema = z.string().regex(/^[A-Za-z0-9_-]{1,64}$/);

const postCommunitySchema = z
  .object({
    text: z.string().min(1).max(CHAT_MESSAGE_MAX_LENGTH),
    // Client-resolved @mentions (the @-picker hands back uids, never names —
    // displayName is not unique, so the server must not resolve names itself).
    // Over the cap is a hard reject rather than a silent truncation: the picker
    // enforces the same limit, so exceeding it is a client bug worth surfacing.
    mentionedUids: z.array(idSchema).max(MAX_MESSAGE_MENTIONS).optional(),
    clientId: clientMessageIdSchema.optional(),
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
    clientId: clientMessageIdSchema.optional(),
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

export const POST_COMMUNITY_EXPECTED = `Expected { text, mentionedUids?, clientId? } with text 1..${CHAT_MESSAGE_MAX_LENGTH} characters, at most ${MAX_MESSAGE_MENTIONS} mentioned uids, and clientId matching [A-Za-z0-9_-]{1,64}.`;
export const LIST_COMMUNITY_EXPECTED = 'Expected { before? } where before is an ISO-8601 timestamp.';
export const POST_CONVOY_EXPECTED = `Expected { convoyId, text, clientId? } with text 1..${CHAT_MESSAGE_MAX_LENGTH} characters and clientId matching [A-Za-z0-9_-]{1,64}.`;
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

/**
 * The mention candidates worth spending a lookup on: input order preserved,
 * duplicates collapsed, and the sender removed.
 *
 * A self-mention is dropped rather than rejected — @-ing yourself in a sentence
 * ("...same problem @me had") is a normal thing to type, it just isn't a notice
 * anyone needs to receive, and failing the whole post over it would be absurd.
 * Dedup matters for the same reason it matters in the picker: the caller pays
 * one lookup per unique uid, and the recipient gets one notice regardless.
 */
export function normalizeMentionCandidates(
  mentionedUids: readonly string[] | undefined,
  senderUid: string,
): string[] {
  if (!mentionedUids || mentionedUids.length === 0) {
    return [];
  }
  return [...new Set(mentionedUids)].filter((uid) => uid !== senderUid);
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

/**
 * A channel message document body ({community,convoy}Chats/.../messages/{id}).
 * The sender's safe profile is denormalized so the channel renders with no
 * per-message profile lookup. `expireAt` is the retention TTL instant (createdAt
 * + the channel's retention window) that a Firestore TTL policy uses to
 * auto-delete the message; the caller passes the pre-built Timestamp value.
 *
 * `mentionedUids` is the SERVER-RESOLVED mention set (see resolveMentions in
 * communityChat.ts) — the caller has already dropped self/duplicate/non-member/
 * blocked entries, so what lands here is exactly the set that was notified and
 * exactly the set the client should highlight. Always written (`[]` when there
 * are none, and for every convoy message) so the stored shape is uniform across
 * both channels and a reader never has to branch on a missing field.
 */
export function buildChatMessageDocument(
  input: {
    senderUid: string;
    text: string;
    senderProfile: ProfileProjection;
    expireAt: unknown;
    mentionedUids?: readonly string[];
    clientId?: string;
  },
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  const doc: Record<string, unknown> = {
    senderUid: input.senderUid,
    text: input.text.trim(),
    senderDisplayName: input.senderProfile.displayName,
    senderAvatarPath: input.senderProfile.avatarPath,
    mentionedUids: [...(input.mentionedUids ?? [])],
    createdAt: serverTimestamp(),
    expireAt: input.expireAt,
  };
  // Stored only when the send carried an idempotency key (also the doc id), so
  // the client can reconcile its optimistic bubble against the delivered doc.
  // A key-less (legacy) send stores no `clientId` field at all.
  if (input.clientId !== undefined) {
    doc.clientId = input.clientId;
  }
  return doc;
}

/** Maps a stored message doc into the client summary. */
export function toChatMessageSummary(
  id: string,
  data: Record<string, unknown>,
  createdAtIso: string,
): ChatMessageSummary {
  const summary: ChatMessageSummary = {
    id,
    senderUid: typeof data.senderUid === 'string' ? data.senderUid : '',
    text: typeof data.text === 'string' ? data.text : '',
    senderDisplayName: typeof data.senderDisplayName === 'string' ? data.senderDisplayName : null,
    senderAvatarPath: typeof data.senderAvatarPath === 'string' ? data.senderAvatarPath : null,
    // Defaulted, not assumed: messages written before mentions existed carry no
    // mentionedUids field, and they must still list as ordinary messages.
    mentionedUids: Array.isArray(data.mentionedUids)
      ? (data.mentionedUids as unknown[]).filter((uid): uid is string => typeof uid === 'string')
      : [],
    createdAt: createdAtIso,
  };
  // Echoed only when the stored doc carries one (a keyed optimistic send). A
  // received message the caller didn't send still carries the sender's key, but
  // it simply won't match any of the caller's pending bubbles.
  if (typeof data.clientId === 'string') {
    summary.clientId = data.clientId;
  }
  return summary;
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

/**
 * The ACCEPTED members of a stored convoy doc, minus `excludeUid` (the poster).
 * The notification fan-out set for convoyChat.post — derived from the convoy doc
 * the membership check already loaded, so the producer costs no extra read.
 *
 * Bounded by construction: a convoy holds at most MAX_CONVOY_INVITEES (50)
 * invitees + the owner, and only accepted ones are returned.
 */
export function acceptedConvoyMemberUids(
  data: Record<string, unknown> | undefined,
  excludeUid: string,
): string[] {
  const memberUids = Array.isArray(data?.memberUids) ? (data!.memberUids as unknown[]) : [];
  return memberUids
    .filter((uid): uid is string => typeof uid === 'string')
    .filter((uid) => uid !== excludeUid && isAcceptedConvoyMember(data, uid));
}

/**
 * Throttle window for convoy-chat notifications. A convoy chat is a live,
 * back-and-forth session: one inbox item per message per member would bury the
 * inbox and multiply the fan-out cost by the message count. Instead the producer
 * derives a DETERMINISTIC notification id per (convoy, time bucket) and lets
 * writeInAppNotification's idempotent create-if-absent collapse everything in the
 * same window into the FIRST item — so a member gets at most one convoy-chat
 * notice per convoy per window, however busy the chat is.
 */
export const CONVOY_CHAT_NOTIFY_WINDOW_MS = 15 * 60 * 1000;

/**
 * Deterministic notification id for a convoy-chat notice: stable within a
 * CONVOY_CHAT_NOTIFY_WINDOW_MS bucket, so replays and every later message in the
 * same window resolve to the same document and are skipped as duplicates.
 *
 * Buckets are fixed epoch-aligned windows rather than a per-recipient sliding
 * window (which would need a stored last-notified marker plus an extra read and
 * write per member): two messages either side of a boundary can both notify.
 * That's an accepted, bounded imprecision — the ceiling stays ~1 notice per
 * member per window. The id needs no recipient component because the inbox is
 * already per-recipient (`notifications/{uid}/items/{id}`), and it stays within
 * the notificationId charset the markRead callable accepts.
 */
export function convoyChatNotificationId(convoyId: string, now: Date): string {
  return `convoychat-${convoyId}-${windowBucket(now, CONVOY_CHAT_NOTIFY_WINDOW_MS)}`;
}

/** The fixed epoch-aligned window `now` falls in — the id-collapsing primitive. */
function windowBucket(now: Date, windowMs: number): number {
  return Math.floor(now.getTime() / windowMs);
}

/**
 * Throttle window for community @mention notifications, per SENDER. Mirrors the
 * convoy-chat window above and exists for a sharper reason: a mention is the one
 * way one member can put something in another member's inbox on the town square,
 * so without a guard, mentioning someone in every message is a ready-made
 * harassment tool. Bucketing the id per (sender, window) caps that at one notice
 * per sender per window however many messages they post — the same idempotent
 * create-if-absent that collapses a busy convoy chat.
 */
export const COMMUNITY_MENTION_NOTIFY_WINDOW_MS = 15 * 60 * 1000;

/**
 * Deterministic notification id for an @mention notice: stable within a
 * COMMUNITY_MENTION_NOTIFY_WINDOW_MS bucket FOR ONE SENDER, so a replay of the
 * same post, and every further mention by that sender in the same window, resolve
 * to the same document and are skipped as duplicates.
 *
 * Keyed by sender (not by message) on purpose: a per-message id would be
 * perfectly idempotent yet cap nothing, since every message has a fresh id. Being
 * per-sender also means two DIFFERENT members mentioning you in the same window
 * still produce two notices — the collapse only ever silences a repeat from the
 * SAME person, which is exactly the case where one notice is enough. No recipient
 * component is needed: the inbox is already per-recipient
 * (`notifications/{uid}/items/{id}`). Stays within the notificationId charset the
 * markRead callable accepts (Firebase uids are alphanumeric).
 */
export function communityMentionNotificationId(senderUid: string, now: Date): string {
  return `commention-${senderUid}-${windowBucket(now, COMMUNITY_MENTION_NOTIFY_WINDOW_MS)}`;
}
