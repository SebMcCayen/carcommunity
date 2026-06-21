/**
 * Shared event chat contracts for API, mobile, and admin.
 *
 * Backend is the source of truth for all access decisions.
 * Client-side checks are for user experience only — never security enforcement.
 *
 * Privacy rules:
 * - Chat messages never expose email, provider identity, or subscription details.
 * - Removed messages show a neutral placeholder; removal reason is admin-only.
 * - Blocking is enforced in both directions; direction is never revealed.
 * - Reporter identities are not exposed in normal responses.
 */

import type { SubscriptionEntitlement, UserRole, UserStatus } from './users.js';
import { canAccessAdminFeatures, canAccessMemberFeatures, isSuspendedStatus } from './users.js';
import type { EventRsvpStatus, EventStatus } from './events.js';

// ---------------------------------------------------------------------------
// Enums
// ---------------------------------------------------------------------------

export const CHAT_MESSAGE_REPORT_REASONS = [
  'harassment',
  'hate_or_abuse',
  'spam',
  'unsafe_driving',
  'privacy',
  'other',
] as const;
export type ChatMessageReportReason = (typeof CHAT_MESSAGE_REPORT_REASONS)[number];

export const CHAT_MESSAGE_REPORT_STATUSES = [
  'new',
  'under_review',
  'resolved',
  'dismissed',
] as const;
export type ChatMessageReportStatus = (typeof CHAT_MESSAGE_REPORT_STATUSES)[number];

export const CHAT_MESSAGE_MODERATION_STATES = ['visible', 'removed'] as const;
export type ChatMessageModerationState = (typeof CHAT_MESSAGE_MODERATION_STATES)[number];

// ---------------------------------------------------------------------------
// Pagination constants
// ---------------------------------------------------------------------------

export const DEFAULT_CHAT_PAGE_SIZE = 30;
export const MAX_CHAT_PAGE_SIZE = 50;
export const CHAT_MESSAGE_MAX_LENGTH = 1000;
export const CHAT_REPORT_DETAILS_MAX_LENGTH = 500;
// ---------------------------------------------------------------------------
// Route paths
// ---------------------------------------------------------------------------

export const EVENT_CHAT_ROUTE_PATHS = {
  adminMessages: '/v1/admin/event-chat/messages',
  adminReports: '/v1/admin/event-chat/reports',
} as const;

export function buildEventChatMessagesPath(eventId: string): string {
  return `/v1/events/${eventId}/chat/messages`;
}

export function buildEventChatMessageReportPath(eventId: string, messageId: string): string {
  return `/v1/events/${eventId}/chat/messages/${messageId}/report`;
}

export function buildAdminEventChatRemovePath(messageId: string): string {
  return `/v1/admin/event-chat/messages/${messageId}/remove`;
}

// ---------------------------------------------------------------------------
// Safe chat author summary
// ---------------------------------------------------------------------------

/**
 * Minimal, privacy-safe author information for chat messages.
 * Does not include email, provider identity, subscription, or session data.
 */
export interface ChatMessageAuthorSummary {
  /** Opaque user identifier. */
  userId: string;
  /** Display name if available. May be null. */
  displayName: string | null;
}

// ---------------------------------------------------------------------------
// Chat message contracts
// ---------------------------------------------------------------------------

/**
 * A single chat message safe for delivery to a member client.
 * Removed messages return a neutral placeholder body; the removal reason is not exposed.
 */
export interface EventChatMessage {
  /** Opaque message identifier. */
  id: string;
  /** Event the message belongs to. */
  eventId: string;
  /** Safe author summary. Never includes sensitive identity data. */
  author: ChatMessageAuthorSummary;
  /** Plain text message body, or a neutral removal placeholder. */
  message: string;
  /** ISO 8601 creation timestamp. */
  createdAt: string;
  /** Moderation state — `visible` or `removed`. */
  moderationState: ChatMessageModerationState;
  /** True when the message was authored by the current viewer. */
  isOwnMessage: boolean;
}

export interface PaginatedEventChatResponse {
  ok: true;
  data: {
    messages: EventChatMessage[];
  };
  meta: {
    /** Cursor for requesting older messages, or null if no more pages. */
    nextCursor: string | null;
    /** Total messages returned in this page. */
    count: number;
  };
}

// ---------------------------------------------------------------------------
// Create message
// ---------------------------------------------------------------------------

export interface CreateEventChatMessageRequest {
  /** Plain text only. HTML characters are treated as literal text. Max 1000 chars. */
  message: string;
}

export interface CreateEventChatMessageResponse {
  ok: true;
  data: {
    message: EventChatMessage;
  };
}

// ---------------------------------------------------------------------------
// Report message
// ---------------------------------------------------------------------------

export interface ReportChatMessageRequest {
  reason: ChatMessageReportReason;
  /** Optional details — max 500 chars. */
  details?: string;
}

export interface ReportChatMessageResponse {
  /** Neutral acknowledgement. Does not reveal whether a previous report existed. */
  ok: true;
  data: {
    reported: true;
  };
}

// ---------------------------------------------------------------------------
// Admin contracts
// ---------------------------------------------------------------------------

/**
 * Admin-facing message summary for moderation.
 * Includes moderation context not available in member responses.
 * Does not include session data, provider identities, or subscription details.
 */
export interface AdminEventChatMessageSummary {
  id: string;
  eventId: string;
  author: ChatMessageAuthorSummary;
  /** Full message text, even if removed. Admins see the original content. */
  message: string;
  createdAt: string;
  moderationState: ChatMessageModerationState;
  removedAt: string | null;
  removedByUserId: string | null;
  removalReason: string | null;
  /** Aggregate report count for this message. Does not identify individual reporters. */
  reportCount: number;
  /** Highest-priority report status for this message, or null if no reports. */
  reportStatus: ChatMessageReportStatus | null;
}

export interface AdminEventChatReportSummary {
  id: string;
  messageId: string;
  reason: ChatMessageReportReason;
  /** Optional reporter-supplied details. */
  details: string | null;
  status: ChatMessageReportStatus;
  createdAt: string;
  reviewedAt: string | null;
  reviewedByUserId: string | null;
}

export interface AdminEventChatMessagesResponse {
  ok: true;
  data: {
    messages: AdminEventChatMessageSummary[];
  };
  meta: {
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
  };
}

export interface AdminEventChatReportsResponse {
  ok: true;
  data: {
    reports: AdminEventChatReportSummary[];
  };
  meta: {
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
  };
}

export interface AdminRemoveMessageRequest {
  /** Required removal reason for audit purposes. */
  reason: string;
}

export interface AdminRemoveChatMessageResponse {
  ok: true;
  data: {
    message: AdminEventChatMessageSummary;
  };
}

// ---------------------------------------------------------------------------
// Access helpers
// ---------------------------------------------------------------------------

/**
 * RSVP statuses that allow reading and posting event chat.
 * Users with `not_going` or no RSVP cannot access chat.
 */
const CHAT_ALLOWED_RSVP_STATUSES = new Set<EventRsvpStatus>(['going', 'maybe']);

/**
 * Returns true if the event state allows chat access.
 * Chat is only available for published events.
 */
function isEventChatOpen(eventStatus: EventStatus): boolean {
  return eventStatus === 'published';
}

/**
 * Returns true if the RSVP status allows chat access.
 * Requires `going` or `maybe`; `not_going` and no RSVP are rejected.
 */
function hasEligibleRsvp(rsvpStatus: EventRsvpStatus | null | undefined): boolean {
  if (!rsvpStatus) return false;
  return CHAT_ALLOWED_RSVP_STATUSES.has(rsvpStatus);
}

/**
 * Returns true if the user can read event chat messages.
 *
 * Requires:
 * - Active member_monthly subscription
 * - Non-suspended, non-deleted account
 * - Published event
 * - RSVP status of `going` or `maybe`
 *
 * Blocking must be enforced separately at the query level.
 */
export function canReadEventChat(input: {
  role: UserRole;
  status: UserStatus;
  subscriptionEntitlement: SubscriptionEntitlement;
  eventStatus: EventStatus;
  rsvpStatus: EventRsvpStatus | null | undefined;
}): boolean {
  if (isSuspendedStatus(input.status) || input.status === 'deleted') {
    return false;
  }
  if (!canAccessMemberFeatures(input)) {
    return false;
  }
  if (!isEventChatOpen(input.eventStatus)) {
    return false;
  }
  return hasEligibleRsvp(input.rsvpStatus);
}

/**
 * Returns true if the user can post a new event chat message.
 * Posting requires the same eligibility as reading.
 */
export function canPostEventChatMessage(input: {
  role: UserRole;
  status: UserStatus;
  subscriptionEntitlement: SubscriptionEntitlement;
  eventStatus: EventStatus;
  rsvpStatus: EventRsvpStatus | null | undefined;
}): boolean {
  return canReadEventChat(input);
}

/**
 * Returns true if the user can moderate event chat (admin message removal, report review).
 * Requires admin or owner role and a non-suspended, non-deleted account.
 * Admin moderators use dedicated admin endpoints — they do not bypass member chat visibility.
 */
export function canModerateEventChat(input: { role: UserRole; status: UserStatus }): boolean {
  return canAccessAdminFeatures(input);
}
