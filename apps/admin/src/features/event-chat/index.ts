/**
 * Event chat moderation feature module for the admin portal
 * (Phase 13h — Firebase migration).
 *
 * Migrated from the legacy `apiRequest` REST client to the Firebase callable
 * client (`callAdmin`), targeting the merged backend:
 *  - events-listChatReports   → the cross-event report moderation queue
 *  - events-resolveChatReport → transition a report (under_review/resolved/dismissed)
 *  - events-removeChatMessage → soft-remove the offending message (auto-resolves reports)
 *
 * Design decision (documented): the legacy admin "all messages" browser
 * (loadAdminChatMessages) is intentionally NOT carried over. Event chat messages
 * are participant-only by design (member + going/maybe RSVP), and the migrated
 * backend deliberately exposes no admin cross-event message read — only the
 * reports queue. Moderation is therefore reports-driven: every report carries
 * its eventId + messageId, which is all an admin needs to resolve the report or
 * remove the message. This keeps messages private and matches the backend's
 * privacy model.
 *
 * Security notes:
 * - The admin never receives message bodies here — moderation acts on the
 *   report, not the text (nothing is rendered as HTML).
 * - Backend verifies the admin role for all moderation operations.
 * - Reporter identities are surfaced to admins only (admin-gated callable).
 * - Removal is a soft-delete; the original text is preserved in the audit log.
 */

import {
  type AdminEventChatReportSummary,
  type ChatMessageModerationState,
  type ChatMessageReportReason,
  type ChatMessageReportStatus,
} from '@carcommunity/shared/event-chat';

import type { ApiError } from '../../lib/errors';
import { callAdmin } from '../../lib/callables';

export type {
  AdminEventChatReportSummary,
  ChatMessageModerationState,
  ChatMessageReportReason,
  ChatMessageReportStatus,
  ApiError,
};

// ---------------------------------------------------------------------------
// Swedish display labels
// ---------------------------------------------------------------------------

export function formatReportStatus(status: ChatMessageReportStatus): string {
  switch (status) {
    case 'new':
      return 'Ny';
    case 'under_review':
      return 'Under granskning';
    case 'resolved':
      return 'Löst';
    case 'dismissed':
      return 'Avvisad';
  }
}

export function formatReportReason(reason: ChatMessageReportReason): string {
  switch (reason) {
    case 'harassment':
      return 'Trakasserier';
    case 'hate_or_abuse':
      return 'Hat eller kränkningar';
    case 'spam':
      return 'Spam';
    case 'unsafe_driving':
      return 'Osäker körning';
    case 'privacy':
      return 'Integritetsintrång';
    case 'other':
      return 'Övrigt';
  }
}

export function formatModerationState(state: ChatMessageModerationState): string {
  return state === 'removed' ? 'Borttaget' : 'Synligt';
}

// ---------------------------------------------------------------------------
// Report row — the backend report enriched with the eventId + reporter uid the
// moderation actions need (both returned by events-listChatReports).
// ---------------------------------------------------------------------------

export interface AdminEventChatReportRow extends AdminEventChatReportSummary {
  /** Event the reported message belongs to — required to resolve/remove. */
  eventId: string;
  /** Reporter uid — surfaced to admins only. */
  reporterUserId: string | null;
}

/** Report statuses that can still be acted on (open reports). */
export const OPEN_REPORT_STATUSES: readonly ChatMessageReportStatus[] = ['new', 'under_review'];

/**
 * Statuses a report can be transitioned to via the resolve callable. Excludes
 * `'new'` (the initial state) — resolution only moves a report forward.
 */
export type ResolvableReportStatus = 'under_review' | 'resolved' | 'dismissed';

// ---------------------------------------------------------------------------
// Callable-backed data layer
// ---------------------------------------------------------------------------

export interface LoadAdminChatReportsParams {
  status?: ChatMessageReportStatus;
  pageSize?: number;
}

export interface AdminEventChatReportRowsResponse {
  ok: true;
  data: { reports: AdminEventChatReportRow[] };
  meta: { page: number; pageSize: number; total: number; hasNext: boolean };
}

/**
 * Maps a raw backend report into a typed row. The merged backend always
 * populates the identifying fields (id/eventId/messageId/createdAt); the
 * `?? ''` here is only a defensive last resort against a malformed payload.
 * Rows with an empty `id` are dropped in `loadAdminChatReports` so a broken
 * report can never render.
 */
function toReportRow(report: Record<string, unknown>): AdminEventChatReportRow {
  return {
    id: String(report.id ?? ''),
    eventId: String(report.eventId ?? ''),
    messageId: String(report.messageId ?? ''),
    reporterUserId: (report.reporterUserId as string | null | undefined) ?? null,
    reason: (report.reason as ChatMessageReportReason | undefined) ?? 'other',
    details: (report.details as string | null | undefined) ?? null,
    status: (report.status as ChatMessageReportStatus | undefined) ?? 'new',
    createdAt: String(report.createdAt ?? ''),
    reviewedAt: (report.reviewedAt as string | null | undefined) ?? null,
    reviewedByUserId: (report.reviewedByUserId as string | null | undefined) ?? null,
  };
}

/**
 * Loads the chat-report moderation queue (newest-first) via the
 * `events-listChatReports` callable. Optional status filter; bounded pageSize.
 */
export async function loadAdminChatReports(
  params: LoadAdminChatReportsParams = {},
): Promise<AdminEventChatReportRowsResponse> {
  const payload: Record<string, unknown> = {};
  if (params.status) payload.status = params.status;
  if (params.pageSize !== undefined) payload.pageSize = params.pageSize;

  const result = await callAdmin<{
    reports: Record<string, unknown>[];
    meta: { page: number; pageSize: number; total: number; hasNext: boolean };
  }>('events-listChatReports', payload);

  return {
    ok: true,
    // Drop any malformed row missing its identifying `id` so it never renders.
    data: { reports: result.reports.map(toReportRow).filter((row) => row.id !== '') },
    meta: result.meta,
  };
}

/**
 * Transitions a chat report to under_review / resolved / dismissed via the
 * `events-resolveChatReport` callable. Stamps reviewer + audit server-side.
 */
export async function resolveAdminChatReport(
  eventId: string,
  reportId: string,
  status: ResolvableReportStatus,
): Promise<{ reportId: string; status: ResolvableReportStatus }> {
  return callAdmin<{ reportId: string; status: ResolvableReportStatus }>('events-resolveChatReport', {
    eventId,
    reportId,
    status,
  });
}

/**
 * Soft-removes the offending message (blanks the body, flips moderationState to
 * removed, auto-resolves its open reports) via `events-removeChatMessage`.
 * Acts on a report's eventId + messageId — the admin never reads the body.
 */
export async function removeAdminChatMessageFromReport(
  eventId: string,
  messageId: string,
  reason: string,
): Promise<{ eventId: string; messageId: string; moderationState: 'removed' }> {
  return callAdmin<{ eventId: string; messageId: string; moderationState: 'removed' }>(
    'events-removeChatMessage',
    { eventId, messageId, reason },
  );
}
