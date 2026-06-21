/**
 * Event chat API client for the mobile app.
 *
 * All requests require a valid session token. Token is never logged.
 * Backend is the source of truth for all access decisions.
 *
 * Security notes:
 * - Do not log token values or message contents.
 * - HTML characters in messages are plain text — never render with dangerouslySetInnerHTML.
 */

import {
  EVENT_CHAT_ROUTE_PATHS,
  buildAdminEventChatRemovePath,
  buildEventChatMessageReportPath,
  buildEventChatMessagesPath,
  type CreateEventChatMessageResponse,
  type PaginatedEventChatResponse,
  type ReportChatMessageResponse,
  type ReportChatMessageRequest,
} from '@carcommunity/shared/event-chat';

import { publicEnv } from '../config/env';

const base = publicEnv.apiBaseUrl.replace(/\/$/, '');
const buildUrl = (path: string) => `${base}${path.startsWith('/') ? path : `/${path}`}`;

export class EventChatApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = 'EventChatApiError';
  }
}

function bearerHeaders(token?: string): Record<string, string> {
  if (!token) return {};
  return { Authorization: 'Bearer ' + token };
}

async function requestJson<TResponse>(
  path: string,
  init?: RequestInit,
  token?: string,
): Promise<TResponse> {
  if (!base) {
    throw new EventChatApiError(
      0,
      'API base URL is not configured. Set EXPO_PUBLIC_API_BASE_URL in your .env file.',
    );
  }

  const headers: Record<string, string> = {
    'content-type': 'application/json',
    ...(init?.headers as Record<string, string> | undefined),
    ...bearerHeaders(token),
  };

  const response = await fetch(buildUrl(path), { ...init, headers });

  if (!response.ok) {
    throw new EventChatApiError(
      response.status,
      `Event chat request failed with status ${response.status}`,
    );
  }

  return (await response.json()) as TResponse;
}

/**
 * Fetch recent chat messages for an event.
 * Only eligible members (going/maybe RSVP) can access.
 * Uses cursor pagination for loading older messages.
 */
export async function loadEventChatMessages(params: {
  eventId: string;
  before?: string;
  take?: number;
  token?: string;
}): Promise<PaginatedEventChatResponse> {
  const query = new URLSearchParams();
  if (params.before) query.set('before', params.before);
  if (params.take !== undefined) query.set('take', String(params.take));
  const qs = query.toString();
  const path = buildEventChatMessagesPath(params.eventId);
  return requestJson<PaginatedEventChatResponse>(
    qs ? `${path}?${qs}` : path,
    { method: 'GET' },
    params.token,
  );
}

/**
 * Post a new plain-text message to an event chat.
 * Message content is stored as plain text and must never be rendered as HTML.
 */
export async function postEventChatMessage(params: {
  eventId: string;
  message: string;
  token?: string;
}): Promise<CreateEventChatMessageResponse> {
  return requestJson<CreateEventChatMessageResponse>(
    buildEventChatMessagesPath(params.eventId),
    {
      method: 'POST',
      body: JSON.stringify({ message: params.message }),
    },
    params.token,
  );
}

/**
 * Report a chat message.
 * Returns a neutral acknowledgement regardless of whether a prior report existed.
 */
export async function reportEventChatMessage(params: {
  eventId: string;
  messageId: string;
  request: ReportChatMessageRequest;
  token?: string;
}): Promise<ReportChatMessageResponse> {
  return requestJson<ReportChatMessageResponse>(
    buildEventChatMessageReportPath(params.eventId, params.messageId),
    {
      method: 'POST',
      body: JSON.stringify(params.request),
    },
    params.token,
  );
}
