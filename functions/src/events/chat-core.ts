/**
 * Event chat domain — pure input validation, eligibility predicates, and
 * document builders (Phase 9c).
 *
 * Ports the legacy semantics of packages/shared/src/event-chat.ts and
 * services/api/src/lib/event-chat-service.ts to the Firestore model:
 *
 * - Messages live at `events/{eventId}/messages/{messageId}` and are written
 *   ONLY via the events.postChatMessage callable (backend-domain-mapping.md:
 *   "Callable function validates membership ... and writes").
 * - Chat participation (read and post) requires an active member with a
 *   `going` or `maybe` RSVP on a published event.
 * - Moderation is a soft-remove: the message body is replaced with an empty
 *   string (clients render a neutral placeholder from moderationState) and
 *   the original text is preserved in the adminAuditEvents record — rules
 *   cannot redact fields per-read, so the member-visible document must never
 *   contain removed text.
 * - Reports deduplicate per (message, reporter, reason) via a deterministic
 *   document ID — repeat reports update details silently (legacy upsert).
 *
 * Pure module — no Firebase Admin SDK imports.
 */

import { z } from 'zod';
import { type UserAccessState } from '../shared/access';
import { memberGateAllows } from '../shared/memberGating';
import type { EventStatus, RsvpStatus } from './events-core';

/** Legacy limits (packages/shared/src/event-chat.ts). */
export const CHAT_MESSAGE_MAX_LENGTH = 1000;
export const CHAT_REPORT_DETAILS_MAX_LENGTH = 500;

/** Legacy rate limit: ~5 messages per 30 seconds per user. */
export const CHAT_RATE_LIMIT_MAX_MESSAGES = 5;
export const CHAT_RATE_LIMIT_WINDOW_MS = 30_000;

export const CHAT_MESSAGE_REPORT_REASONS = [
  'harassment',
  'hate_or_abuse',
  'spam',
  'unsafe_driving',
  'privacy',
  'other',
] as const;
export type ChatMessageReportReason = (typeof CHAT_MESSAGE_REPORT_REASONS)[number];

export const CHAT_MESSAGE_MODERATION_STATES = ['visible', 'removed'] as const;
export type ChatMessageModerationState = (typeof CHAT_MESSAGE_MODERATION_STATES)[number];

// ---------------------------------------------------------------------------
// Input schemas
// ---------------------------------------------------------------------------

const postChatMessageInputSchema = z
  .object({
    eventId: z.string().trim().min(1),
    message: z.string().min(1).max(CHAT_MESSAGE_MAX_LENGTH),
  })
  .strict();

const reportChatMessageInputSchema = z
  .object({
    eventId: z.string().trim().min(1),
    messageId: z.string().trim().min(1),
    reason: z.enum(CHAT_MESSAGE_REPORT_REASONS),
    details: z.string().max(CHAT_REPORT_DETAILS_MAX_LENGTH).optional(),
  })
  .strict();

const removeChatMessageInputSchema = z
  .object({
    eventId: z.string().trim().min(1),
    messageId: z.string().trim().min(1),
    reason: z.string().trim().min(1).max(2000),
  })
  .strict();

export type PostChatMessageInput = z.infer<typeof postChatMessageInputSchema>;
export type ReportChatMessageInput = z.infer<typeof reportChatMessageInputSchema>;
export type RemoveChatMessageInput = z.infer<typeof removeChatMessageInputSchema>;

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

function parse<T>(schema: z.ZodType<T>, data: unknown, expected: string): ParseResult<T> {
  const result = schema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: expected };
  }
  return { ok: true, input: result.data };
}

export function parsePostChatMessageInput(data: unknown): ParseResult<PostChatMessageInput> {
  return parse(
    postChatMessageInputSchema,
    data,
    `Expected { eventId, message } with message 1..${CHAT_MESSAGE_MAX_LENGTH} characters.`,
  );
}

export function parseReportChatMessageInput(data: unknown): ParseResult<ReportChatMessageInput> {
  return parse(
    reportChatMessageInputSchema,
    data,
    `Expected { eventId, messageId, reason: ${CHAT_MESSAGE_REPORT_REASONS.join('|')}, details? }.`,
  );
}

export function parseRemoveChatMessageInput(data: unknown): ParseResult<RemoveChatMessageInput> {
  return parse(
    removeChatMessageInputSchema,
    data,
    'Expected { eventId, messageId, reason }.',
  );
}

// ---------------------------------------------------------------------------
// Eligibility (packages/shared canReadEventChat / canPostEventChatMessage)
// ---------------------------------------------------------------------------

const CHAT_ALLOWED_RSVP_STATUSES: ReadonlySet<string> = new Set(['going', 'maybe']);

export type ChatEligibility =
  | { ok: true }
  | { ok: false; code: 'permission-denied' | 'failed-precondition'; message: string };

/**
 * Chat participation (read and post) requires: passing the member gate, a
 * published event, and a going/maybe RSVP.
 *
 * Member gating is currently DISABLED (shared/memberGating.ts), so the member
 * term currently means only "not suspended, not deleted". The event-status and
 * RSVP requirements are unaffected.
 */
export function guardChatParticipant(input: {
  state: UserAccessState;
  eventStatus: EventStatus | undefined;
  rsvpStatus: RsvpStatus | string | undefined;
}): ChatEligibility {
  if (!memberGateAllows(input.state)) {
    return {
      ok: false,
      code: 'permission-denied',
      message: 'Member subscription required for event chat.',
    };
  }
  if (input.eventStatus !== 'published') {
    return {
      ok: false,
      code: 'failed-precondition',
      message: 'Event chat is only open for published events.',
    };
  }
  if (typeof input.rsvpStatus !== 'string' || !CHAT_ALLOWED_RSVP_STATUSES.has(input.rsvpStatus)) {
    return {
      ok: false,
      code: 'permission-denied',
      message: 'A going or maybe RSVP is required to join the event chat.',
    };
  }
  return { ok: true };
}

// ---------------------------------------------------------------------------
// Document builders
// ---------------------------------------------------------------------------

/** Message document for events/{eventId}/messages/{messageId}. */
export function buildChatMessageDocument(
  input: { authorUserId: string; authorDisplayName: string; message: string },
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    authorUserId: input.authorUserId,
    // Denormalized per backend-domain-mapping.md design principles — avoids
    // an extra users/{uid} read per rendered message.
    authorDisplayName: input.authorDisplayName,
    message: input.message.trim(),
    moderationState: 'visible',
    removedAt: null,
    removedByUserId: null,
    createdAt: serverTimestamp(),
  };
}

/**
 * Soft-removal update. The visible body is blanked (clients render a neutral
 * placeholder from moderationState); the removal reason stays out of the
 * member-readable document and lives in the admin audit record only.
 */
export function buildChatMessageRemoval(
  removedByUserId: string,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    message: '',
    moderationState: 'removed',
    removedAt: serverTimestamp(),
    removedByUserId,
  };
}

/**
 * Deterministic report document ID — one report per (message, reporter,
 * reason), so repeat reports overwrite silently (legacy upsert parity).
 * Reports are never client-readable, so embedding the reporter UID in the
 * ID leaks nothing.
 */
export function chatReportDocId(messageId: string, reporterUserId: string, reason: string): string {
  return `${messageId}_${reporterUserId}_${reason}`;
}

/** Report document for events/{eventId}/messageReports/{reportId}. */
export function buildChatReportDocument(
  input: {
    messageId: string;
    reporterUserId: string;
    reason: ChatMessageReportReason;
    details: string | undefined;
  },
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  const details = input.details?.trim()
    ? input.details.trim().slice(0, CHAT_REPORT_DETAILS_MAX_LENGTH)
    : null;
  return {
    messageId: input.messageId,
    reporterUserId: input.reporterUserId,
    reason: input.reason,
    details,
    status: 'new',
    reviewedAt: null,
    reviewedByUserId: null,
    createdAt: serverTimestamp(),
  };
}

// ---------------------------------------------------------------------------
// Admin chat-report moderation (Phase 18d) — resolve / list inputs
// ---------------------------------------------------------------------------

/**
 * Statuses a moderator can transition a report to. 'new' is the initial state
 * set by events.reportChatMessage and is never a resolve target.
 */
export const CHAT_REPORT_RESOLVABLE_STATUSES = ['under_review', 'resolved', 'dismissed'] as const;
export type ChatReportResolvableStatus = (typeof CHAT_REPORT_RESOLVABLE_STATUSES)[number];

const resolveChatReportSchema = z
  .object({
    eventId: z.string().trim().min(1).max(128),
    reportId: z.string().trim().min(1).max(256),
    status: z.enum(CHAT_REPORT_RESOLVABLE_STATUSES),
  })
  .strict();

export type ResolveChatReportInput = z.infer<typeof resolveChatReportSchema>;

export function parseResolveChatReportInput(data: unknown): ParseResult<ResolveChatReportInput> {
  return parse(
    resolveChatReportSchema,
    data,
    'Expected { eventId, reportId, status: under_review|resolved|dismissed }.',
  );
}

const listChatReportsSchema = z
  .object({
    status: z.enum(['new', 'under_review', 'resolved', 'dismissed']).optional(),
    // No `page`: the queue is a single newest-first window (bounded scan). A
    // real cursor is the documented follow-up if the backlog outgrows it.
    pageSize: z.number().int().min(1).max(100).optional(),
  })
  .strict();

export type ListChatReportsInput = z.infer<typeof listChatReportsSchema>;

export function parseListChatReportsInput(data: unknown): ParseResult<ListChatReportsInput> {
  return parse(listChatReportsSchema, data, 'Expected { status?, pageSize? }.');
}
