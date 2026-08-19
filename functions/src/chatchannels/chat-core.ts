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
 * user, no fan-out.
 *
 * UNREAD (convoy): the SAME shape, one level deeper. A convoy channel needs a
 * marker PER convoy, so the marker is a map keyed by convoy id at
 * `userPrivate/{uid}.convoyChatLastReadAt` (owner-only readable, same document as
 * the community marker), stamped by convoyChat.markRead. A fan-out counter was
 * rejected for the same reason as on the community channel: it would be a write
 * per accepted member on every post. The client counts the unread messages itself
 * from the bounded newest-message window it is already listening to.
 *
 * The map is CAPPED at CONVOY_LAST_READ_MAX_ENTRIES (see pruneConvoyLastRead):
 * unlike the community channel there is one key per convoy the member has ever
 * opened, so without a cap the document — and the automatic single-field indexes
 * Firestore builds for each map subfield — would grow without bound over the
 * member's whole lifetime. Dropping the OLDEST markers is safe: a convoy old
 * enough to fall off the end has had its messages TTL-deleted
 * (CONVOY_CHAT_RETENTION_DAYS), so a missing marker resolves to "nothing unread"
 * against an empty channel.
 *
 * BLOCKING (community + convoy): messages authored by a uid the caller is in a
 * block relationship with — in EITHER direction — are filtered out of the
 * `*-list` read paths. This used to be left to the client as a display concern
 * because resolving "did they block me" cost an unbounded per-page block lookup;
 * that objection is gone now that `blocking-onBlockWrite` maintains a symmetric
 * `blockVisibility/{uid}.hiddenUids` mirror, so the whole page costs ONE document
 * read (functions/src/blocking/block-visibility.ts). The pagination cursor is
 * taken from the RAW page BEFORE filtering, so the createdAt cursor still walks
 * the channel exactly once and a wholly-hidden page tail cannot stall it.
 *
 * The channels' LIVE windows are direct Firestore snapshot listeners on the
 * client, and a Firestore rule cannot filter a list query per document (a
 * per-document condition fails the WHOLE query), so those windows are filtered
 * CLIENT-side against the same mirror. That is a behaviour guarantee, not a
 * confidentiality one — the message document is still delivered to the device.
 * These channels are readable by every active member by design, so that is the
 * honest boundary; the DM domain, where confidentiality does matter, is gated by
 * firestore.rules instead.
 *
 * @MENTIONS applied the both-ways block check even before the read filter
 * existed, and still do: a mention is not a message sitting in a room you chose
 * to open, it is a directed push into a personal inbox — the same reach a DM
 * has. Bounded at MAX_MESSAGE_MENTIONS pairs per post; a blocked pair resolves
 * to no mention. The message itself still posts, and stays readable by everyone
 * who is not in a block relationship with its author.
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
 * How many per-convoy last-read markers a member's `userPrivate` document keeps.
 *
 * The community channel has exactly ONE marker; a convoy marker exists per convoy
 * the member has ever opened the chat of, which grows for the member's whole
 * lifetime. Each map subfield also gets an automatic single-field index, so the
 * uncapped version costs index entries as well as document size.
 *
 * The cap is far above any plausible number of convoys a member is in AT ONCE
 * (the bar shows one, and the hub's convoy list holds their live ones), so a
 * dropped marker is always for a convoy whose chat is long finished.
 *
 * Enforced PER CALL, not as a transactional invariant — see
 * [pruneConvoyLastRead] for why, and for why the map still cannot grow.
 */
export const CONVOY_LAST_READ_MAX_ENTRIES = 50;

/**
 * The keys to DROP from a member's per-convoy last-read map once `convoyId` has
 * been stamped at `stampedAtMs`, so at most [CONVOY_LAST_READ_MAX_ENTRIES]
 * survive: the OLDEST markers go first, and the one just stamped never does.
 *
 * CONCURRENCY: this is read-compute-write, NOT a transaction, so two markRead
 * calls for DIFFERENT convoys that overlap can each read the same map, each
 * evict the same oldest key and each add a different one — leaving the map one
 * over the cap per racing writer. That is deliberate, and it does not weaken what
 * the cap is FOR. The function evicts down to the cap from whatever it is
 * handed, over-cap input included, so the very next markRead pulls an overshoot
 * straight back: the map self-heals and cannot ratchet upwards, which is the
 * property that keeps the document and its automatic map-subfield indexes
 * bounded. A transaction would make "never more than N, even momentarily" true
 * as well, and it was rejected: markRead runs on the hot path (once per incoming
 * message per watching member), an aborted transaction is a SILENTLY FAILED
 * markRead, and a failed markRead is a badge left lit on a chat the member has
 * read. That trades an invisible, self-correcting imprecision for a visible one.
 *
 * Pure so the eviction rule is unit-testable without Firestore. `existing` is
 * whatever is stored today — any shape, since a client can never write this field
 * but a legacy/partial document can still hold junk: entries that are not
 * millisecond-valued are treated as the oldest possible (they carry no usable
 * ordering and re-stamping restores them), and a non-map value drops out entirely
 * because there is then nothing to prune.
 */
export function pruneConvoyLastRead(
  existing: unknown,
  convoyId: string,
  stampedAtMs: number,
  maxEntries: number = CONVOY_LAST_READ_MAX_ENTRIES,
): string[] {
  const entries = new Map<string, number>();
  if (existing !== null && typeof existing === 'object' && !Array.isArray(existing)) {
    for (const [key, value] of Object.entries(existing as Record<string, unknown>)) {
      entries.set(key, toLastReadMillis(value));
    }
  }
  entries.set(convoyId, stampedAtMs);
  if (entries.size <= maxEntries) {
    return [];
  }
  // Newest first, so everything past the cap is the oldest tail. A stable
  // tie-break on the key keeps the choice deterministic when two markers share a
  // millisecond (and when several are the "oldest possible" junk value).
  const ordered = [...entries.entries()].sort(
    ([keyA, msA], [keyB, msB]) => msB - msA || keyA.localeCompare(keyB),
  );
  return ordered.slice(maxEntries).map(([key]) => key);
}

/**
 * A stored last-read value as epoch millis, or `Number.NEGATIVE_INFINITY` when it
 * carries no usable ordering (missing, malformed, or a Timestamp-shaped object
 * from a raw read rather than an admin-SDK `Timestamp`).
 */
function toLastReadMillis(value: unknown): number {
  if (value !== null && typeof value === 'object' && 'toMillis' in value) {
    const millis = (value as { toMillis: unknown }).toMillis;
    if (typeof millis === 'function') {
      const result: unknown = millis.call(value);
      if (typeof result === 'number' && Number.isFinite(result)) {
        return result;
      }
    }
  }
  return Number.NEGATIVE_INFINITY;
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

/**
 * Denormalized snapshot of the message an inline reply is quoting (WhatsApp-style
 * quote, NOT a thread). Persisted on the replying message so a channel renders the
 * quote with no N+1 fetch and the quote survives the parent TTL-expiring or being
 * deleted — it shows what was replied to AT THE TIME. Built SERVER-SIDE from the
 * parent read in the SAME channel (see buildReplyToSnapshot): the client never
 * supplies the author or text, so a quote can't be forged.
 *
 * `messageId` is the parent's own, STABLE id — kept stable on purpose so a future
 * message-reactions feature (a separate `messageReactions/{messageId}__{uid}`
 * collection keyed by this same id) can attach with no migration. This shape is
 * identical across all chat surfaces (community, convoy, and DM's own
 * dm-core.MessageSummary), so the client renders one quote component everywhere.
 */
export interface ChatReplyTo {
  /** The parent message's stable id (what tap-to-scroll targets, reactions key). */
  messageId: string;
  senderUid: string;
  senderDisplayName: string | null;
  /** messagePreview(parent.text) — bounded quote text captured at reply time. */
  textPreview: string;
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
   * The message this one is an inline reply to, snapshotted server-side at send
   * time. Present only on a reply whose parent was found in the same channel when
   * chatReplies was on; omitted otherwise (an ordinary message, a reply whose
   * parent had already expired, or a send made while the flag was off). The read
   * model stays additive so future per-message features (e.g. reactions) can add
   * their own optional field without disturbing this one.
   */
  replyTo?: ChatReplyTo;
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
    // Inline reply target: the id of the message being replied to (WhatsApp-style
    // quote, not a thread). The client sends ONLY this id — never the quoted
    // author or text — and the server snapshots the parent itself (see
    // buildReplyToSnapshot + the chatReplies flag). Reuses the message-id alphabet;
    // a parent in a DIFFERENT channel is never matched because the callable only
    // ever looks it up in this channel's own messages subcollection.
    replyToMessageId: idSchema.optional(),
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
    // Inline reply target — same contract as the community channel: the client
    // sends only the parent message id, the server snapshots the parent from
    // THIS convoy's messages subcollection (see buildReplyToSnapshot).
    replyToMessageId: idSchema.optional(),
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

const markReadConvoySchema = z.object({ convoyId: idSchema }).strict();

export type MarkReadConvoyInput = z.infer<typeof markReadConvoySchema>;

function parse<T>(schema: z.ZodType<T>, data: unknown, expected: string): ParseResult<T> {
  const result = schema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: expected };
  }
  return { ok: true, input: result.data };
}

export const POST_COMMUNITY_EXPECTED = `Expected { text, mentionedUids?, clientId?, replyToMessageId? } with text 1..${CHAT_MESSAGE_MAX_LENGTH} characters, at most ${MAX_MESSAGE_MENTIONS} mentioned uids, and clientId matching [A-Za-z0-9_-]{1,64}.`;
export const LIST_COMMUNITY_EXPECTED = 'Expected { before? } where before is an ISO-8601 timestamp.';
export const POST_CONVOY_EXPECTED = `Expected { convoyId, text, clientId?, replyToMessageId? } with text 1..${CHAT_MESSAGE_MAX_LENGTH} characters and clientId matching [A-Za-z0-9_-]{1,64}.`;
export const LIST_CONVOY_EXPECTED = 'Expected { convoyId, before? } where before is an ISO-8601 timestamp.';
export const MARK_READ_CONVOY_EXPECTED = 'Expected { convoyId }.';

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

export function parseMarkReadConvoyInput(data: unknown): ParseResult<MarkReadConvoyInput> {
  return parse(markReadConvoySchema, data, MARK_READ_CONVOY_EXPECTED);
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
    replyTo?: ChatReplyTo;
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
  // Stored only for a reply whose parent was resolved (server-side, same channel)
  // — an ordinary message stores no `replyTo` field, exactly like `clientId`. The
  // snapshot is spread into a plain object so nothing but the four snapshot fields
  // is ever persisted (the caller's ChatReplyTo carries no extras today, but this
  // keeps the stored shape pinned to the snapshot).
  if (input.replyTo) {
    doc.replyTo = {
      messageId: input.replyTo.messageId,
      senderUid: input.replyTo.senderUid,
      senderDisplayName: input.replyTo.senderDisplayName,
      textPreview: input.replyTo.textPreview,
    };
  }
  return doc;
}

/**
 * Builds the denormalized reply snapshot from the PARENT message the caller
 * already read IN THE SAME channel. Returns null when there is no usable parent
 * (not found, TTL-expired, or a malformed doc with no sender/text) so the send
 * proceeds WITHOUT a snapshot rather than failing — a reply to a vanished message
 * is still a message. The caller must only ever pass a parent looked up in this
 * channel's own messages subcollection, which is what makes a cross-channel quote
 * impossible: another channel's id simply isn't found here and resolves to null.
 *
 * `textPreview` reuses messagePreview so the stored quote is bounded regardless of
 * the parent's length. `messageId` is the parent's stable id, carried verbatim so
 * tap-to-scroll and a future reactions feature key off the same value.
 */
export function buildReplyToSnapshot(
  parent:
    | { messageId: string; senderUid: unknown; senderDisplayName: unknown; text: unknown }
    | null
    | undefined,
): ChatReplyTo | null {
  if (!parent) {
    return null;
  }
  const senderUid = typeof parent.senderUid === 'string' ? parent.senderUid : '';
  const text = typeof parent.text === 'string' ? parent.text : '';
  if (!parent.messageId || !senderUid || !text.trim()) {
    return null;
  }
  return {
    messageId: parent.messageId,
    senderUid,
    senderDisplayName:
      typeof parent.senderDisplayName === 'string' ? parent.senderDisplayName : null,
    textPreview: messagePreview(text),
  };
}

/**
 * Reads a stored `replyTo` map back into the client snapshot, coalescing missing
 * or non-string fields defensively (a message written before replies existed, or
 * an ordinary message, carries no field at all → undefined). Mirrors the tolerant
 * mapping toChatMessageSummary applies to every other stored field.
 */
function toChatReplyTo(value: unknown): ChatReplyTo | undefined {
  if (value === null || typeof value !== 'object') {
    return undefined;
  }
  const v = value as Record<string, unknown>;
  const messageId = typeof v.messageId === 'string' ? v.messageId : '';
  const senderUid = typeof v.senderUid === 'string' ? v.senderUid : '';
  if (!messageId || !senderUid) {
    return undefined;
  }
  return {
    messageId,
    senderUid,
    senderDisplayName: typeof v.senderDisplayName === 'string' ? v.senderDisplayName : null,
    textPreview: typeof v.textPreview === 'string' ? v.textPreview : '',
  };
}

/**
 * True when a Firestore write failed because the document already existed —
 * i.e. a `create()` lost the race to a concurrent writer.
 *
 * This is what makes the keyed-send idempotency guard ATOMIC. A plain
 * read-then-write (`get()` then `set()`) is not: two concurrent posts carrying
 * the SAME clientId can both observe "missing" before either writes, so both go
 * on to write the message and run its side effects (the notification fan-out) —
 * exactly the double-send the exactly-once contract promises not to do. Issuing
 * the write as `create()` instead makes Firestore itself arbitrate: precisely
 * one invocation commits, and every other one fails here, replays the winner's
 * stored result, and performs no side effects of its own.
 *
 * All three shapes are matched because the admin SDK surfaces ALREADY_EXISTS
 * differently across versions/transports: the gRPC status code 6, the
 * 'already-exists' string code, or only in the message text. Mirrors the same
 * tolerance in crownHunt/submitClaim.ts.
 */
export function isAlreadyExistsError(error: unknown): boolean {
  const code = (error as { code?: unknown })?.code;
  if (code === 6 || code === 'already-exists') {
    return true;
  }
  return String((error as { message?: unknown })?.message ?? '').includes('ALREADY_EXISTS');
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
  // Present only on a reply whose snapshot was stored; omitted for an ordinary
  // message so the read shape stays additive.
  const replyTo = toChatReplyTo(data.replyTo);
  if (replyTo) {
    summary.replyTo = replyTo;
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

/**
 * Throttle window for community REPLY notifications, per SENDER. A reply on the
 * town square is directed reach into the replied-to author's inbox — the same
 * shape as an @mention — so it gets the same anti-harassment collapse: replying
 * to someone's messages over and over in one window produces at most one notice.
 * Reuses the mention window; the two are kept as separate notification-id
 * NAMESPACES (below) so a reply and a mention by the same sender in the same
 * window don't collapse into one another.
 */
export const COMMUNITY_REPLY_NOTIFY_WINDOW_MS = COMMUNITY_MENTION_NOTIFY_WINDOW_MS;

/**
 * Deterministic notification id for a community REPLY notice: stable within a
 * COMMUNITY_REPLY_NOTIFY_WINDOW_MS bucket FOR ONE SENDER, so a replay of the same
 * post and every further reply by that sender in the same window collapse to the
 * one notice. A DIFFERENT id namespace (`commreply-`) from the mention id, so a
 * reply and a mention are never mistaken for one another's duplicate — a member
 * both replied-to AND @mentioned in the same window still gets the two distinct
 * notices (the callable additionally dedups the reply notice when it is already
 * covered by a mention — see communityChat.post). Stays within the notificationId
 * charset the markRead callable accepts.
 */
export function communityReplyNotificationId(senderUid: string, now: Date): string {
  return `commreply-${senderUid}-${windowBucket(now, COMMUNITY_REPLY_NOTIFY_WINDOW_MS)}`;
}
