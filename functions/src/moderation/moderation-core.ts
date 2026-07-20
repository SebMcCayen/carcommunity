/**
 * Moderation-report domain core (pure logic): input parsing, deterministic
 * report ids, and document builders for the THREE report callables that sit
 * outside the event-chat tree:
 *
 *  - chatchannels.reportMessage — community + convoy channel messages
 *  - dm.reportMessage           — 1:1 direct messages
 *  - moderation.reportUser      — reporting a PERSON, not a message
 *
 * WHERE REPORTS LAND. events.reportChatMessage stores its reports in a
 * subcollection of the reported message's own parent
 * (events/{eventId}/messageReports) because there IS such a parent. The three
 * surfaces here have no shared parent — a community channel, a convoy channel
 * and a DM conversation are three different trees — so their reports go to the
 * EXISTING top-level `moderationReports` collection, the queue the admin web
 * already renders and resolves (apps/admin/src/features/moderation-reports).
 *
 * That collection was a Phase-9o scaffold: any authenticated client could
 * create a report document directly, with the rules doing field validation.
 * These callables replace that path, and this change CLOSES it
 * (firebase/firestore.rules) — a direct client create has no eligibility check
 * on the surface being reported, no rate limit, no dedup, and no snapshot, and
 * it lets a client file a report against an arbitrary targetId. Nothing in the
 * app used the create path; the admin module only reads and resolves.
 *
 * So these documents keep the legacy field names and status vocabulary
 * (`reportedBy`, `targetType`, `targetId`, `reason`, `details`,
 * `status: 'pending' | 'reviewed' | 'dismissed'`, `createdAt`) — the admin
 * queue renders and resolves them with no change — and ADD the fields the
 * legacy shape had no room for (surface, scopeId, the message snapshot, the
 * per-reporter tally). The reason enum and the details cap match
 * events/chat-core.ts so a report from a DM and a report from an event chat
 * read the same on a moderator's screen; a unit test pins the reason parity.
 *
 * WHY REPORTS SNAPSHOT THE MESSAGE (and the events version does not). The
 * events report can point at a messageId and stop, because the message
 * document sits next to the report, an admin can read it, and the soft-remove
 * path preserves the original text in adminAuditEvents. Neither holds here:
 *
 *  - DM messages are readable ONLY by the two participants — the Firestore
 *    rules give admins no read path at all, by design. A reportId with no body
 *    would be an unactionable row.
 *  - Community and convoy messages carry a TTL (`expireAt`, 120 / 30 days) and
 *    are hard-deleted by Firestore when it expires. A report older than the
 *    retention window would dangle.
 *
 * So the reporter's callable copies the reported message's text + author +
 * createdAt into the report at report time. This is exactly one message — the
 * one the reporter chose to escalate — never a conversation, never a history.
 * It is why `moderationReports` is admin-read-only and client-write-denied.
 *
 * Kept Firebase-free so it stays unit-testable without the emulator (mirrors
 * chatchannels/chat-core.ts + dm/dm-core.ts). The callables own all Firestore
 * I/O, eligibility checks, rate limiting and transactions.
 */

import { createHash } from 'node:crypto';
import { z } from 'zod';

/**
 * Report reasons. MUST stay identical to events/chat-core.ts
 * CHAT_MESSAGE_REPORT_REASONS — moderators triage event-chat reports and these
 * reports in the same queue, and a reason that exists on one surface but not
 * the other makes the queue's reason filter lie. A unit test pins the parity.
 */
export const MODERATION_REPORT_REASONS = [
  'harassment',
  'hate_or_abuse',
  'spam',
  'unsafe_driving',
  'privacy',
  'other',
] as const;
export type ModerationReportReason = (typeof MODERATION_REPORT_REASONS)[number];

/** Free-text cap on the reporter's optional note (events parity). */
export const MODERATION_REPORT_DETAILS_MAX_LENGTH = 500;

/**
 * Cap on the snapshotted message body. Equal to the largest message a client
 * can post (chat-core / dm-core both cap at 2000), so a snapshot is never a
 * truncated version of the evidence a moderator is judging.
 */
export const MODERATION_SNAPSHOT_TEXT_MAX_LENGTH = 2000;

/**
 * Per-reporter rate limit: 10 reports per rolling hour, counted across ALL
 * report types by reporterUserId.
 *
 * Reporting is an abuse vector in both directions. Under-limiting lets one
 * account bury a target (and the moderation queue) under a hundred rows;
 * over-limiting stops someone from reporting a genuine spam flood. Ten an hour
 * is well above what a real incident needs and well below what a brigading
 * script wants. The cap counts REPORT DOCUMENTS created in the window, so the
 * dedup below means re-filing the same report does not burn a fresh slot
 * beyond the first — a user cannot be rate-limited out of correcting their own
 * details. Mirrors feedback.reportIssue's limiter (5/hour), enforced the same
 * way: a count() aggregate read INSIDE the transaction that writes the report,
 * so concurrent submissions serialize and cannot race past the cap.
 */
export const MODERATION_REPORT_RATE_LIMIT_MAX = 10;
export const MODERATION_REPORT_RATE_LIMIT_WINDOW_MS = 60 * 60 * 1000;

export function isModerationRateLimited(countInWindow: number): boolean {
  return countInWindow >= MODERATION_REPORT_RATE_LIMIT_MAX;
}

/** The surface a reported message lives on (part of the report's identity). */
export const MODERATION_MESSAGE_SURFACES = ['community', 'convoy', 'dm'] as const;
export type ModerationMessageSurface = (typeof MODERATION_MESSAGE_SURFACES)[number];

/** Discriminates a message report from a person report in the admin queue. */
export const MODERATION_TARGET_TYPES = ['message', 'user'] as const;
export type ModerationTargetType = (typeof MODERATION_TARGET_TYPES)[number];

/** Collections owned by this domain. */
export const MODERATION_REPORTS_COLLECTION = 'moderationReports';

/**
 * The legacy status vocabulary the admin queue and the rules already use
 * (apps/admin/src/features/moderation-reports). New reports start 'pending' —
 * the analogue of the event-chat report's 'new'.
 */
export const MODERATION_REPORT_INITIAL_STATUS = 'pending';
export const MODERATION_USER_SUMMARIES_COLLECTION = 'moderationUserSummaries';

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

function parse<T>(schema: z.ZodType<T>, data: unknown, expected: string): ParseResult<T> {
  const result = schema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: expected };
  }
  return { ok: true, input: result.data };
}

// ---------------------------------------------------------------------------
// Input schemas
// ---------------------------------------------------------------------------

/**
 * A single Firestore DOCUMENT-ID segment.
 *
 * Every id these schemas accept is handed straight to `collection(...).doc(id)`,
 * and `doc()` does not treat its argument as one opaque segment: it splits on
 * `/`. So an unconstrained id is a path-injection hole, not merely a
 * validation gap — `conversations.doc('a/b/c')` addresses a document in a
 * completely different collection than the caller named, and an even-segment
 * path throws deep inside the SDK, surfacing as an opaque `internal` instead
 * of `invalid-argument`. The three shapes below are therefore constrained to
 * what real ids actually are, not merely stripped of `/`:
 *
 *  - Firebase Auth uids are alphanumeric (`users/{uid}` doc ids);
 *  - conversation ids are `dmPairId` = two such uids sorted and joined with
 *    `__` (dm/dm-core.ts), so `_` must stay legal;
 *  - message/convoy ids are Firestore auto-ids (`[A-Za-z0-9]{20}`).
 *
 * `[A-Za-z0-9._-]` covers all three with room to spare while excluding `/` and
 * every other path metacharacter. `.` / `..` and the reserved `__*__` form are
 * additionally refused: Firestore rejects them outright, so accepting them
 * only converts a bad payload into a 500.
 */
const documentIdSchema = (max: number) =>
  z
    .string()
    .trim()
    .min(1)
    .max(max)
    .regex(/^[A-Za-z0-9._-]+$/)
    .refine((id) => id !== '.' && id !== '..')
    .refine((id) => !/^__.*__$/.test(id));

/** Firestore-safe id (matches chatchannels/chat-core's idSchema). */
const idSchema = documentIdSchema(300);

/** Conversation ids are `uidLow__uidHigh` (dm-core dmPairId) — `_` is allowed. */
const conversationIdSchema = documentIdSchema(300);

/** Firebase uids are <=128 chars and address `users/{uid}` directly. */
const uidSchema = documentIdSchema(128);

const detailsSchema = z.string().max(MODERATION_REPORT_DETAILS_MAX_LENGTH).optional();

/**
 * `convoyId` is required when and only when the channel is 'convoy'. Modelled
 * as a refinement rather than a union so a caller that passes a convoyId with
 * channel 'community' is REJECTED instead of silently ignored — a client
 * sending both has a bug, and swallowing it would file the report against the
 * wrong scope.
 */
const reportChannelMessageSchema = z
  .object({
    channel: z.enum(['community', 'convoy']),
    convoyId: idSchema.optional(),
    messageId: idSchema,
    reason: z.enum(MODERATION_REPORT_REASONS),
    details: detailsSchema,
  })
  .strict()
  .refine(
    (input) => (input.channel === 'convoy' ? input.convoyId !== undefined : input.convoyId === undefined),
    { message: 'convoyId is required for the convoy channel and forbidden otherwise.' },
  );

export type ReportChannelMessageInput = z.infer<typeof reportChannelMessageSchema>;

const reportDirectMessageSchema = z
  .object({
    conversationId: conversationIdSchema,
    messageId: idSchema,
    reason: z.enum(MODERATION_REPORT_REASONS),
    details: detailsSchema,
  })
  .strict();

export type ReportDirectMessageInput = z.infer<typeof reportDirectMessageSchema>;

const reportUserSchema = z
  .object({
    reportedUserId: uidSchema,
    reason: z.enum(MODERATION_REPORT_REASONS),
    details: detailsSchema,
  })
  .strict();

export type ReportUserInput = z.infer<typeof reportUserSchema>;

const REASONS = MODERATION_REPORT_REASONS.join('|');

export const REPORT_CHANNEL_MESSAGE_EXPECTED = `Expected { channel: community|convoy, convoyId (convoy only), messageId, reason: ${REASONS}, details? }.`;
export const REPORT_DIRECT_MESSAGE_EXPECTED = `Expected { conversationId, messageId, reason: ${REASONS}, details? }.`;
export const REPORT_USER_EXPECTED = `Expected { reportedUserId, reason: ${REASONS}, details? }.`;

export function parseReportChannelMessageInput(
  data: unknown,
): ParseResult<ReportChannelMessageInput> {
  return parse(reportChannelMessageSchema, data, REPORT_CHANNEL_MESSAGE_EXPECTED);
}

export function parseReportDirectMessageInput(data: unknown): ParseResult<ReportDirectMessageInput> {
  return parse(reportDirectMessageSchema, data, REPORT_DIRECT_MESSAGE_EXPECTED);
}

export function parseReportUserInput(data: unknown): ParseResult<ReportUserInput> {
  return parse(reportUserSchema, data, REPORT_USER_EXPECTED);
}

/** User-facing messages (clients branch on the HttpsError code, never text). */
export const SELF_REPORT_MESSAGE = 'You cannot report yourself.';
export const SELF_MESSAGE_REPORT_MESSAGE = 'You cannot report your own message.';
export const MESSAGE_NOT_FOUND_MESSAGE = 'Message not found.';
export const CONVERSATION_NOT_FOUND_MESSAGE = 'Conversation not found.';
export const USER_NOT_FOUND_MESSAGE = 'User not found.';
/**
 * A stored message whose `senderUid` is missing or not a non-empty string.
 *
 * UNREACHABLE BY CONSTRUCTION, and the guard is deliberately defensive: the
 * Firestore rules allow NO client writes to communityChat/{c}/messages,
 * convoyChats/{c}/messages or conversations/{p}/messages, and the only writers
 * — communityChat.post / convoyChat.post / dm.sendMessage — build the document
 * through chat-core `buildMessageDocument` / dm-core `buildDirectMessageDocument`,
 * both of which set `senderUid` from the authenticated actor's uid. There is no
 * path that can persist one without an author.
 *
 * It is still worth a hard stop rather than a fallback: filing a report whose
 * `reportedUserId` and snapshot author are `''` names NOBODY, which is worse in
 * the queue than no report at all (it cannot be triaged and it poisons the
 * per-person pivot). `internal` rather than `failed-precondition` because
 * failed-precondition is reserved for normal states the user can act on, and
 * this is a corrupt document only we can fix — paired with a logger.error
 * carrying the ids so the bad document is findable.
 */
export const MALFORMED_MESSAGE_MESSAGE = 'This message could not be reported.';

export const RATE_LIMITED_MESSAGE =
  'Too many reports — please wait a while before submitting another.';

// ---------------------------------------------------------------------------
// Deterministic report ids (dedup)
// ---------------------------------------------------------------------------

/**
 * Length-prefixed SHA-256 over a tuple → a collision-resistant, Firestore-safe
 * (hex) document id. Length-prefixing makes the encoding injective: no input
 * value can forge a field boundary, so distinct tuples never map to the same
 * digest regardless of which characters the parts contain. Mirrors
 * friends/friends-core.ts + crownHunt/crownhunt-core.ts hashDocId.
 *
 * events.reportChatMessage gets away with a plain `${messageId}_${uid}_${reason}`
 * join because its ids live inside one event's subcollection. Ours are flat and
 * mix a conversationId that itself contains `__`, so a naive join would be
 * ambiguous; hashing removes the question.
 */
function hashDocId(parts: readonly string[]): string {
  const hash = createHash('sha256');
  for (const part of parts) {
    hash.update(`${part.length}:${part}`);
  }
  return hash.digest('hex');
}

/**
 * Dedup key for a MESSAGE report: (surface, scope, message, reporter, reason).
 * Exactly the events.reportChatMessage grain — one message is one artifact, and
 * the same person re-reporting it adds no information a moderator can use, so
 * the repeat silently refreshes the details instead of adding a queue row.
 * `scopeId` is the convoyId / conversationId / the fixed community channel id.
 */
export function moderationMessageReportId(input: {
  surface: ModerationMessageSurface;
  scopeId: string;
  messageId: string;
  reporterUserId: string;
  reason: ModerationReportReason;
}): string {
  return hashDocId([
    'message',
    input.surface,
    input.scopeId,
    input.messageId,
    input.reporterUserId,
    input.reason,
  ]);
}

/**
 * Dedup key for a USER report: (reporter, reportedUser) — deliberately WITHOUT
 * the reason.
 *
 * A message report keys on the reason because a message can genuinely be two
 * things at once (spam AND harassment) and each is a separate judgement about a
 * separate artifact. A person report is one complaint about one person; the
 * reason is a facet of it. Keying on reason too would let a single reporter file
 * six rows against the same person just by cycling the enum — which is precisely
 * the mass-report harassment the rate limit exists to stop, only cheaper.
 *
 * So a repeat report from the same reporter UPDATES this one document (newest
 * reason + details win) and TALLIES: `occurrences` increments and
 * `lastReportedAt` moves. Nothing is lost — a moderator can still see this
 * person has been flagged by the same reporter five times — but the queue grows
 * one row per (reporter, target) pair, and the count of rows for a target is
 * therefore the count of DISTINCT people who reported them, which is the number
 * that actually means something.
 */
export function moderationUserReportId(reporterUserId: string, reportedUserId: string): string {
  return hashDocId(['user', reporterUserId, reportedUserId]);
}

// ---------------------------------------------------------------------------
// Document builders
// ---------------------------------------------------------------------------

/** Trims + caps the reporter's optional note; empty/blank → null (events parity). */
export function normalizeDetails(details: string | undefined): string | null {
  const trimmed = details?.trim();
  return trimmed ? trimmed.slice(0, MODERATION_REPORT_DETAILS_MAX_LENGTH) : null;
}

/** The evidence copied off the reported message at report time. */
export interface ReportedMessageSnapshot {
  /** Message body as it read when reported. Capped, never truncated in practice. */
  text: string;
  /** Author of the reported message (the person the report is really about). */
  authorUserId: string;
  /** Author's denormalized display name, or null when the surface has none. */
  authorDisplayName: string | null;
  /** ISO-8601 creation time of the message, or null when unreadable. */
  createdAt: string | null;
}

export function toReportedMessageSnapshot(input: {
  text: unknown;
  authorUserId: string;
  authorDisplayName: unknown;
  createdAtIso: string | null;
}): ReportedMessageSnapshot {
  return {
    text:
      typeof input.text === 'string' ? input.text.slice(0, MODERATION_SNAPSHOT_TEXT_MAX_LENGTH) : '',
    authorUserId: input.authorUserId,
    authorDisplayName:
      typeof input.authorDisplayName === 'string' ? input.authorDisplayName : null,
    createdAt: input.createdAtIso,
  };
}

/**
 * moderationReports/{reportId} for a MESSAGE report.
 *
 * `reportedBy` / `targetType` / `targetId` / `status` / `createdAt` are the
 * legacy field names the admin queue already renders and the rules already
 * gate, so an admin sees these rows in the existing list with no admin-web
 * change. `targetId` is the reported messageId (targetType 'message').
 *
 * The added fields are the ones the scaffold had nowhere to put:
 * `reportedUserId` is denormalized off the snapshot so a moderator can pivot
 * "every report against this person" with one equality query rather than a
 * read per row; `surface` + `scopeId` say WHICH chat and which convoy /
 * conversation, so the report is locatable; `snapshot` is the evidence.
 */
export function buildMessageReportDocument(
  input: {
    surface: ModerationMessageSurface;
    /** convoyId / conversationId / community channel id. */
    scopeId: string;
    messageId: string;
    reporterUserId: string;
    reason: ModerationReportReason;
    details: string | undefined;
    snapshot: ReportedMessageSnapshot;
  },
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  const ts = serverTimestamp();
  return {
    reportedBy: input.reporterUserId,
    targetType: 'message' as ModerationTargetType,
    targetId: input.messageId,
    reason: input.reason,
    details: normalizeDetails(input.details),
    status: MODERATION_REPORT_INITIAL_STATUS,
    createdAt: ts,
    // --- added by the callable-backed report path ---
    surface: input.surface,
    scopeId: input.scopeId,
    reportedUserId: input.snapshot.authorUserId,
    snapshot: { ...input.snapshot },
    occurrences: 1,
    lastReportedAt: ts,
  };
}

/**
 * Repeat MESSAGE report (same reporter, message and reason). Refreshes only the
 * reporter's own note — it must never reset a report the moderation team has
 * already moved to reviewed/dismissed (legacy upsert parity with
 * events.reportChatMessage).
 */
export function buildMessageReportRepeatUpdate(details: string | undefined): Record<string, unknown> {
  return { details: normalizeDetails(details) };
}

/** The bounded, already-public profile projection stored on a USER report. */
export interface ReportedUserSnapshot {
  displayName: string | null;
  avatarPath: string | null;
}

/**
 * moderationReports/{reportId} for a USER report.
 *
 * A person report has no message to anchor to, so the temptation is to attach
 * "context" — their recent messages, their drives, their profile history. We
 * deliberately do NOT. Pulling a user's activity into an admin-readable
 * collection because someone typed their name into a report form builds a
 * dossier out of an accusation, and an unbounded one at that. What is stored is
 * the minimum a moderator needs to identify who this is and how seriously to
 * take it:
 *
 *  - the reported uid and a snapshot of their PUBLIC profile projection
 *    (displayName + avatarPath — already visible to every member, and snapshotted
 *    only so a rename between report and triage doesn't leave the queue pointing
 *    at a name nobody recognises),
 *  - the reporter's reason + free-text note (the accusation itself),
 *  - `occurrences` — how many times THIS reporter has filed,
 *  - and, on the separate moderationUserSummaries/{uid} aggregate, how many
 *    DISTINCT members have reported this person and when they were last
 *    reported. That is the "prior reports" signal, as two integers rather than
 *    a history dump.
 *
 * A moderator who needs more than that has the admin tooling to go look; the
 * report itself does not pre-fetch it for them.
 */
export function buildUserReportDocument(
  input: {
    reportedUserId: string;
    reporterUserId: string;
    reason: ModerationReportReason;
    details: string | undefined;
    snapshot: ReportedUserSnapshot;
  },
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  const ts = serverTimestamp();
  return {
    reportedBy: input.reporterUserId,
    targetType: 'user' as ModerationTargetType,
    targetId: input.reportedUserId,
    reason: input.reason,
    details: normalizeDetails(input.details),
    status: MODERATION_REPORT_INITIAL_STATUS,
    createdAt: ts,
    // --- added by the callable-backed report path ---
    surface: null,
    scopeId: null,
    reportedUserId: input.reportedUserId,
    snapshot: {
      displayName: input.snapshot.displayName,
      avatarPath: input.snapshot.avatarPath,
    },
    occurrences: 1,
    lastReportedAt: ts,
  };
}

/**
 * Repeat USER report from the same reporter. Newest reason + details win and
 * the tally advances; `status` is untouched, so a report a moderator already
 * dismissed is not silently re-opened by the same person filing again (the tally is still visible to whoever looks next).
 * `incrementOccurrences` is the caller's FieldValue.increment(1) sentinel —
 * this module stays Firebase-free.
 */
export function buildUserReportRepeatUpdate(
  input: {
    reason: ModerationReportReason;
    details: string | undefined;
  },
  incrementOccurrences: unknown,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    reason: input.reason,
    details: normalizeDetails(input.details),
    occurrences: incrementOccurrences,
    lastReportedAt: serverTimestamp(),
  };
}

/**
 * moderationUserSummaries/{reportedUserId} — the O(1) "how bad is this" read
 * for the admin queue. `reporterCount` counts DISTINCT reporters (it advances
 * only when a brand-new (reporter, target) report document is created), which
 * is why the user-report dedup key excludes the reason: one person cannot
 * inflate this number. `totalSubmissions` counts every filing including
 * repeats, so a single obsessive reporter is visible as the gap between the
 * two rather than as ten queue rows.
 */
export function buildUserSummaryUpdate(
  input: { reportedUserId: string; newReporter: boolean },
  increment: (by: number) => unknown,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  const ts = serverTimestamp();
  return {
    reportedUserId: input.reportedUserId,
    reporterCount: increment(input.newReporter ? 1 : 0),
    totalSubmissions: increment(1),
    lastReportAt: ts,
  };
}
