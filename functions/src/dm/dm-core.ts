/**
 * Direct-messaging domain core (pure logic): input parsing, canonical
 * conversation-id derivation, document builders, and summary mappers for the
 * 1:1 friend DM callables (dm.sendMessage / dm.listConversations /
 * dm.getMessages / dm.markRead).
 *
 * Data model (all member-readable, backend-only writes — firebase/firestore.rules):
 *  - conversations/{pairId} where pairId = the two participant UIDs sorted and
 *    joined with `__` (order-independent, so both friends resolve the SAME
 *    canonical document). Fields:
 *      members: [uidLow, uidHigh]           (sorted, for array-contains reads)
 *      memberProfiles: { [uid]: { displayName, avatarPath } }  (denormalized)
 *      lastMessage: { text, senderUid, createdAt } | null       (preview)
 *      lastMessageAt: Timestamp             (mirror of lastMessage.createdAt,
 *                                            the ordering key for the inbox)
 *      unread: { [uid]: number }            (per-member unread counter)
 *      lastReadAt: { [uid]: Timestamp|null }
 *      createdAt / updatedAt: Timestamp
 *  - conversations/{pairId}/messages/{messageId}: { senderUid, text, createdAt,
 *    clientId? }. When a send carries a client idempotency key it is stored as
 *    clientId AND used as the messageId, so a retry is exactly-once and the live
 *    listener can reconcile the delivered doc against the optimistic bubble.
 *
 * A per-user TOTAL unread aggregate lives OFF this tree at
 * userPrivate/{uid}.dmUnreadTotal (owner-only readable) so the map-home chat
 * bubble can bind a single-document listener for its badge without summing the
 * conversation list. The callables keep it in lock-step with the per-member
 * unread counters (bump on send, clear-by-delta on markRead).
 *
 * Kept Firebase-free so it stays unit-testable without the emulator (mirrors
 * friends/friends-core.ts + blocking/blocking-core.ts). The callables in
 * manageDirectMessages.ts own all Firestore I/O, friendship + block checks,
 * and transactions.
 */

import { z } from 'zod';

/** Legacy-parity plain-text message cap (matches event chat's 1000? DMs get 2000). */
export const DM_MESSAGE_MAX_LENGTH = 2000;

/** Length of the denormalized lastMessage preview stored on the conversation. */
export const DM_MESSAGE_PREVIEW_LENGTH = 120;

/** Default page size for dm.getMessages (newest-first window). */
export const DM_MESSAGES_PAGE_SIZE = 30;

/** Upper bound on conversations returned by dm.listConversations. */
export const DM_CONVERSATIONS_LIMIT = 100;

/**
 * Minimal safe profile projection denormalized onto conversation documents
 * and returned to clients. Never includes email, provider identity,
 * subscription, moderation, or other sensitive fields (mirrors
 * friends/friends-core.ts ProfileProjection).
 */
export interface ProfileProjection {
  displayName: string | null;
  avatarPath: string | null;
}

/** The other participant, as surfaced to the caller's UI. */
export interface ConversationOtherUser {
  uid: string;
  displayName: string | null;
  avatarPath: string | null;
}

/** Denormalized lastMessage preview, timestamps normalized to ISO for clients. */
export interface MessagePreview {
  text: string;
  senderUid: string;
  createdAt: string;
}

/** One row of dm.listConversations — the caller's view of a conversation. */
export interface ConversationSummary {
  conversationId: string;
  otherUser: ConversationOtherUser;
  lastMessage: MessagePreview | null;
  unreadCount: number;
  lastReadAt: string | null;
}

/** One message as returned by dm.getMessages. */
export interface MessageSummary {
  id: string;
  senderUid: string;
  text: string;
  createdAt: string;
  /**
   * The client-supplied idempotency key, echoed only when the stored message
   * carries one. Lets a paginated older page reconcile against an optimistic
   * bubble by the same key the live listener uses.
   */
  clientId?: string;
}

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

/** Firebase Auth UIDs are opaque, non-empty, bounded strings. */
const uidSchema = z.string().trim().min(1).max(128);

/**
 * A client-supplied idempotency key for a send. Used VERBATIM as the message
 * document id (conversations/{pairId}/messages/{clientId}), so it is
 * constrained to a Firestore-doc-id-safe alphabet (no `/`, `.`, or `..`) and a
 * bounded length — a client-generated UUID fits. Two sends with the same
 * clientId resolve to the same message doc, which is what makes an optimistic
 * retry exactly-once (the callable detects the existing doc and skips the
 * unread bump). Optional for backward compatibility: an older client that omits
 * it gets an auto-id doc and the previous, non-idempotent behaviour.
 */
const clientMessageIdSchema = z
  .string()
  .trim()
  .regex(/^[A-Za-z0-9_-]{1,64}$/);

const sendMessageSchema = z
  .object({
    toUid: uidSchema,
    text: z.string().min(1).max(DM_MESSAGE_MAX_LENGTH),
    clientId: clientMessageIdSchema.optional(),
  })
  .strict();

export type SendMessageInput = z.infer<typeof sendMessageSchema>;

const conversationIdSchema = z.string().trim().min(1).max(300);

const getMessagesSchema = z
  .object({
    conversationId: conversationIdSchema,
    // ISO-8601 cursor on createdAt (exclusive upper bound) for older pages.
    before: z.string().datetime().optional(),
  })
  .strict();

export type GetMessagesInput = z.infer<typeof getMessagesSchema>;

const markReadSchema = z
  .object({
    conversationId: conversationIdSchema,
  })
  .strict();

export type MarkReadInput = z.infer<typeof markReadSchema>;

const listConversationsSchema = z.object({}).strict();

function parse<T>(schema: z.ZodType<T>, data: unknown, expected: string): ParseResult<T> {
  const result = schema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: expected };
  }
  return { ok: true, input: result.data };
}

export const SEND_MESSAGE_EXPECTED = `Expected { toUid, text, clientId? } with text 1..${DM_MESSAGE_MAX_LENGTH} characters and clientId matching [A-Za-z0-9_-]{1,64}.`;
export const GET_MESSAGES_EXPECTED =
  'Expected { conversationId, before? } where before is an ISO-8601 timestamp.';
export const MARK_READ_EXPECTED = 'Expected { conversationId }.';

export function parseSendMessageInput(data: unknown): ParseResult<SendMessageInput> {
  return parse(sendMessageSchema, data, SEND_MESSAGE_EXPECTED);
}

export function parseGetMessagesInput(data: unknown): ParseResult<GetMessagesInput> {
  return parse(getMessagesSchema, data, GET_MESSAGES_EXPECTED);
}

export function parseMarkReadInput(data: unknown): ParseResult<MarkReadInput> {
  return parse(markReadSchema, data, MARK_READ_EXPECTED);
}

export function parseListConversationsInput(data: unknown): ParseResult<Record<string, never>> {
  return parse(listConversationsSchema, data, 'Expected an empty object.');
}

/** User-facing messages (clients branch on the HttpsError code, never text). */
export const SELF_MESSAGE_MESSAGE = 'You cannot message yourself.';
export const NOT_FRIENDS_MESSAGE = 'You can only message your friends.';
export const NOT_DELIVERABLE_MESSAGE = 'This message cannot be delivered right now.';
export const CONVERSATION_NOT_FOUND_MESSAGE = 'Conversation not found.';
export const EMPTY_MESSAGE_MESSAGE = 'Message cannot be empty.';
export const MESSAGE_ID_CONFLICT_MESSAGE =
  'A different message already exists with this id. Please retry with a new one.';

/**
 * Canonical, order-independent conversation id for a pair of users:
 * the two UIDs sorted lexicographically and joined with `__`. `dmPairId(a,b)`
 * === `dmPairId(b,a)`, so a 1:1 conversation is a single document both parties
 * map to (no A→B / B→A duplication). UIDs are alphanumeric Firebase ids, so
 * `__` is an unambiguous separator.
 */
export function dmPairId(a: string, b: string): string {
  return [a, b].sort().join('__');
}

/** The sorted [low, high] member pair backing a conversation. */
export function dmMembers(a: string, b: string): [string, string] {
  return [a, b].sort() as [string, string];
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

/** Trims + truncates a message body to the stored preview length. */
export function messagePreview(text: string): string {
  return text.trim().slice(0, DM_MESSAGE_PREVIEW_LENGTH);
}

/**
 * conversations/{pairId}/messages/{messageId} document body. When the send
 * carried an idempotency key it is stored as `clientId` (also the doc id), so
 * the live listener can reconcile the delivered doc against the sender's
 * optimistic bubble. A key-less (legacy) send stores no `clientId` field.
 */
export function buildMessageDocument(
  input: { senderUid: string; text: string; clientId?: string },
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  const doc: Record<string, unknown> = {
    senderUid: input.senderUid,
    text: input.text.trim(),
    createdAt: serverTimestamp(),
  };
  if (input.clientId !== undefined) {
    doc.clientId = input.clientId;
  }
  return doc;
}

/**
 * Full conversations/{pairId} body for a brand-new conversation. The sender's
 * unread stays 0 (they authored it); the recipient's is seeded to 1.
 */
export function buildNewConversationDocument(
  input: {
    senderUid: string;
    recipientUid: string;
    senderProfile: ProfileProjection;
    recipientProfile: ProfileProjection;
    text: string;
  },
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  const ts = serverTimestamp();
  const [low, high] = dmMembers(input.senderUid, input.recipientUid);
  return {
    members: [low, high],
    memberProfiles: {
      [input.senderUid]: {
        displayName: input.senderProfile.displayName,
        avatarPath: input.senderProfile.avatarPath,
      },
      [input.recipientUid]: {
        displayName: input.recipientProfile.displayName,
        avatarPath: input.recipientProfile.avatarPath,
      },
    },
    lastMessage: {
      text: messagePreview(input.text),
      senderUid: input.senderUid,
      createdAt: ts,
    },
    lastMessageAt: ts,
    unread: {
      [input.senderUid]: 0,
      [input.recipientUid]: 1,
    },
    lastReadAt: {
      [input.senderUid]: null,
      [input.recipientUid]: null,
    },
    createdAt: ts,
    updatedAt: ts,
  };
}

/**
 * Maps a stored conversation doc into the caller-oriented summary, projecting
 * the OTHER member's denormalized profile and the caller's own unread/read
 * state. `toIso` converts a stored timestamp value to an ISO string (or null).
 */
export function toConversationSummary(
  conversationId: string,
  data: Record<string, unknown>,
  callerUid: string,
  toIso: (value: unknown) => string | null,
): ConversationSummary {
  const members = Array.isArray(data.members) ? (data.members as string[]) : [];
  const otherUid = members.find((uid) => uid !== callerUid) ?? '';
  const profiles = (data.memberProfiles ?? {}) as Record<
    string,
    Record<string, unknown> | undefined
  >;
  const otherProfile = toProfileProjection(profiles[otherUid]);

  const unreadMap = (data.unread ?? {}) as Record<string, unknown>;
  const rawUnread = unreadMap[callerUid];
  const unreadCount = typeof rawUnread === 'number' && rawUnread > 0 ? rawUnread : 0;

  const lastReadMap = (data.lastReadAt ?? {}) as Record<string, unknown>;
  const lastReadAt = toIso(lastReadMap[callerUid]);

  const rawLast = data.lastMessage as Record<string, unknown> | null | undefined;
  const lastMessage: MessagePreview | null =
    rawLast && typeof rawLast.senderUid === 'string'
      ? {
          text: typeof rawLast.text === 'string' ? rawLast.text : '',
          senderUid: rawLast.senderUid,
          createdAt: toIso(rawLast.createdAt) ?? '',
        }
      : null;

  return {
    conversationId,
    otherUser: {
      uid: otherUid,
      displayName: otherProfile.displayName,
      avatarPath: otherProfile.avatarPath,
    },
    lastMessage,
    unreadCount,
    lastReadAt,
  };
}

/** Maps a stored message doc into the client summary. */
export function toMessageSummary(
  id: string,
  data: Record<string, unknown>,
  createdAtIso: string,
): MessageSummary {
  const summary: MessageSummary = {
    id,
    senderUid: typeof data.senderUid === 'string' ? data.senderUid : '',
    text: typeof data.text === 'string' ? data.text : '',
    createdAt: createdAtIso,
  };
  if (typeof data.clientId === 'string') {
    summary.clientId = data.clientId;
  }
  return summary;
}

/** True when `callerUid` is one of the conversation's stored members. */
export function isConversationMember(
  data: Record<string, unknown> | undefined,
  callerUid: string,
): boolean {
  const members = Array.isArray(data?.members) ? (data!.members as unknown[]) : [];
  return members.includes(callerUid);
}
