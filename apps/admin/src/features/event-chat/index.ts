/**
 * Event chat moderation feature module for the admin portal.
 *
 * Provides types, API helpers, and display utilities for the event chat
 * moderation views. All operations are validated by the backend.
 *
 * Security notes:
 * - Never render chat message content as raw HTML.
 * - Backend verifies admin role for all moderation endpoints.
 * - Reporter identities are not exposed in admin message lists.
 * - Removal evidence is never hard-deleted.
 */

import {
  EVENT_CHAT_ROUTE_PATHS,
  buildAdminEventChatRemovePath,
  type AdminEventChatMessageSummary,
  type AdminEventChatMessagesResponse,
  type AdminEventChatReportSummary,
  type AdminEventChatReportsResponse,
  type AdminRemoveChatMessageResponse,
  type ChatMessageModerationState,
  type ChatMessageReportReason,
  type ChatMessageReportStatus,
} from '@carcommunity/shared/event-chat';

import { ApiError, apiRequest } from '../../lib/api';

export type {
  AdminEventChatMessageSummary,
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
// API helpers
// ---------------------------------------------------------------------------

export interface LoadAdminChatMessagesParams {
  eventId?: string;
  removed?: boolean;
  page?: number;
  pageSize?: number;
  token?: string;
}

export async function loadAdminChatMessages(
  params: LoadAdminChatMessagesParams = {},
): Promise<AdminEventChatMessagesResponse> {
  const query = new URLSearchParams();
  if (params.eventId) query.set('eventId', params.eventId);
  if (params.removed !== undefined) query.set('removed', String(params.removed));
  if (params.page !== undefined) query.set('page', String(params.page));
  if (params.pageSize !== undefined) query.set('pageSize', String(params.pageSize));
  const qs = query.toString();
  return apiRequest<AdminEventChatMessagesResponse>(
    `${EVENT_CHAT_ROUTE_PATHS.adminMessages}${qs ? `?${qs}` : ''}`,
    { token: params.token },
  );
}

export interface LoadAdminChatReportsParams {
  messageId?: string;
  eventId?: string;
  status?: ChatMessageReportStatus;
  page?: number;
  pageSize?: number;
  token?: string;
}

export async function loadAdminChatReports(
  params: LoadAdminChatReportsParams = {},
): Promise<AdminEventChatReportsResponse> {
  const query = new URLSearchParams();
  if (params.messageId) query.set('messageId', params.messageId);
  if (params.eventId) query.set('eventId', params.eventId);
  if (params.status) query.set('status', params.status);
  if (params.page !== undefined) query.set('page', String(params.page));
  if (params.pageSize !== undefined) query.set('pageSize', String(params.pageSize));
  const qs = query.toString();
  return apiRequest<AdminEventChatReportsResponse>(
    `${EVENT_CHAT_ROUTE_PATHS.adminReports}${qs ? `?${qs}` : ''}`,
    { token: params.token },
  );
}

/**
 * Soft-remove a chat message with a required reason.
 * The message is preserved for audit; only removedAt is set.
 */
export async function removeAdminChatMessage(
  messageId: string,
  reason: string,
  token?: string,
): Promise<AdminRemoveChatMessageResponse> {
  return apiRequest<AdminRemoveChatMessageResponse>(buildAdminEventChatRemovePath(messageId), {
    method: 'POST',
    body: { reason },
    token,
  });
}
