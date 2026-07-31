/**
 * Shared event chat moderation contracts for admin.
 *
 * Backend is the source of truth for all access and moderation decisions.
 * Client-side checks are for user experience only — never security enforcement.
 *
 * Privacy rules:
 * - Reporter identities are not exposed in normal responses.
 * - Removal reasons are admin-only.
 */

// ---------------------------------------------------------------------------
// Enums
// ---------------------------------------------------------------------------

const CHAT_MESSAGE_REPORT_REASONS = [
  'harassment',
  'hate_or_abuse',
  'spam',
  'unsafe_driving',
  'privacy',
  'other',
] as const;
export type ChatMessageReportReason = (typeof CHAT_MESSAGE_REPORT_REASONS)[number];

const CHAT_MESSAGE_REPORT_STATUSES = [
  'new',
  'under_review',
  'resolved',
  'dismissed',
] as const;
export type ChatMessageReportStatus = (typeof CHAT_MESSAGE_REPORT_STATUSES)[number];

const CHAT_MESSAGE_MODERATION_STATES = [
  'visible',
  'auto_hidden',
  'removed',
  'allowed',
] as const;
export type ChatMessageModerationState = (typeof CHAT_MESSAGE_MODERATION_STATES)[number];

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

