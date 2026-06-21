/**
 * Event chat API routes.
 *
 * All access decisions are enforced by the backend service layer.
 * Client-side checks are for user experience only.
 *
 * Endpoints:
 *   GET  /v1/events/:eventId/chat/messages          — list recent messages
 *   POST /v1/events/:eventId/chat/messages          — post a message
 *   POST /v1/events/:eventId/chat/messages/:messageId/report — report a message
 *   GET  /v1/admin/event-chat/messages              — admin message list
 *   GET  /v1/admin/event-chat/reports               — admin report list
 *   POST /v1/admin/event-chat/messages/:messageId/remove — admin remove message
 */

import {
  CHAT_MESSAGE_MAX_LENGTH,
  CHAT_REPORT_DETAILS_MAX_LENGTH,
  CHAT_MESSAGE_REPORT_REASONS,
  CHAT_MESSAGE_REPORT_STATUSES,
  EVENT_CHAT_ROUTE_PATHS,
  MAX_CHAT_PAGE_SIZE,
  buildAdminEventChatRemovePath,
  buildEventChatMessageReportPath,
  buildEventChatMessagesPath,
  canModerateEventChat,
  type AdminEventChatMessagesResponse,
  type AdminEventChatReportsResponse,
  type AdminRemoveChatMessageResponse,
  type CreateEventChatMessageResponse,
  type PaginatedEventChatResponse,
  type ReportChatMessageResponse,
} from '@carcommunity/shared/event-chat';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';

import { requireAdminHook, requireMemberHook } from '../lib/auth-context.js';
import { AppError } from '../lib/errors.js';
import { EventChatService } from '../lib/event-chat-service.js';

// ---------------------------------------------------------------------------
// Validation schemas
// ---------------------------------------------------------------------------

const eventIdParamsSchema = z
  .object({
    eventId: z.string().uuid(),
  })
  .strict();

const messageIdParamsSchema = z
  .object({
    messageId: z.string().uuid(),
  })
  .strict();

const eventMessageIdParamsSchema = z
  .object({
    eventId: z.string().uuid(),
    messageId: z.string().uuid(),
  })
  .strict();

const listMessagesQuerySchema = z
  .object({
    before: z.string().uuid().optional(),
    take: z.coerce.number().int().min(1).max(MAX_CHAT_PAGE_SIZE).optional(),
  })
  .strict();

/** Plain-text message — HTML characters are treated as literal text. */
const createMessageBodySchema = z
  .object({
    message: z
      .string()
      .min(1, 'Message cannot be empty.')
      .max(CHAT_MESSAGE_MAX_LENGTH, `Message exceeds maximum length of ${CHAT_MESSAGE_MAX_LENGTH} characters.`),
  })
  .strict();

const reportMessageBodySchema = z
  .object({
    reason: z.enum(CHAT_MESSAGE_REPORT_REASONS),
    details: z.string().max(CHAT_REPORT_DETAILS_MAX_LENGTH).optional(),
  })
  .strict();

const adminMessagesQuerySchema = z
  .object({
    eventId: z.string().uuid().optional(),
    removed: z
      .enum(['true', 'false'])
      .transform((v) => v === 'true')
      .optional(),
    page: z.coerce.number().int().min(1).optional(),
    pageSize: z.coerce.number().int().min(1).max(100).optional(),
  })
  .strict();

const adminReportsQuerySchema = z
  .object({
    messageId: z.string().uuid().optional(),
    eventId: z.string().uuid().optional(),
    status: z.enum(CHAT_MESSAGE_REPORT_STATUSES).optional(),
    page: z.coerce.number().int().min(1).optional(),
    pageSize: z.coerce.number().int().min(1).max(100).optional(),
  })
  .strict();

const adminRemoveBodySchema = z
  .object({
    reason: z.string().min(1).max(2000),
  })
  .strict();

// ---------------------------------------------------------------------------
// Route registration
// ---------------------------------------------------------------------------

export interface RegisterEventChatRoutesDependencies {
  eventChatService?: EventChatService;
}

export async function registerEventChatRoutes(
  app: FastifyInstance,
  dependencies: RegisterEventChatRoutesDependencies = {},
): Promise<void> {
  const eventChatService =
    dependencies.eventChatService ?? new EventChatService(app.prisma);

  // -------------------------------------------------------------------------
  // GET /v1/events/:eventId/chat/messages
  // Returns the most recent chat messages for the event.
  // Only eligible members (going/maybe RSVP) may access.
  // Blocked users are filtered in both directions.
  // -------------------------------------------------------------------------
  app.get(
    buildEventChatMessagesPath(':eventId'),
    { preHandler: requireMemberHook },
    async (request): Promise<PaginatedEventChatResponse> => {
      const auth = request.auth!;
      const params = eventIdParamsSchema.parse(request.params);
      const query = listMessagesQuerySchema.parse(request.query);

      const result = await eventChatService.listMessages({
        eventId: params.eventId,
        viewerUser: {
          userId: auth.userId,
          role: auth.role,
          status: auth.status,
          subscriptionEntitlement: auth.subscriptionEntitlement,
        },
        before: query.before,
        take: query.take,
      });

      return {
        ok: true,
        data: { messages: result.messages },
        meta: { nextCursor: result.nextCursor, count: result.messages.length },
      };
    },
  );

  // -------------------------------------------------------------------------
  // POST /v1/events/:eventId/chat/messages
  // Posts a new plain-text message to the event chat.
  // Rate limited to ~5 messages per 30 seconds per user.
  // -------------------------------------------------------------------------
  app.post(
    buildEventChatMessagesPath(':eventId'),
    {
      preHandler: requireMemberHook,
      config: {
        rateLimit: {
          max: 5,
          timeWindow: 30_000,
          keyGenerator: (request) => {
            const auth = (request as typeof request & { auth: { userId: string } | null }).auth;
            const params = request.params as { eventId?: string };
            return `event-chat:${auth?.userId ?? 'anon'}:${params.eventId ?? 'unknown'}`;
          },
        },
      },
    },
    async (request): Promise<CreateEventChatMessageResponse> => {
      const auth = request.auth!;
      const params = eventIdParamsSchema.parse(request.params);
      const body = createMessageBodySchema.parse(request.body);

      const message = await eventChatService.createMessage({
        eventId: params.eventId,
        authorUser: {
          userId: auth.userId,
          role: auth.role,
          status: auth.status,
          subscriptionEntitlement: auth.subscriptionEntitlement,
        },
        message: body.message,
      });

      return { ok: true, data: { message } };
    },
  );

  // -------------------------------------------------------------------------
  // POST /v1/events/:eventId/chat/messages/:messageId/report
  // Reports a chat message. Safe against duplicates.
  // Users cannot report their own messages.
  // -------------------------------------------------------------------------
  app.post(
    buildEventChatMessageReportPath(':eventId', ':messageId'),
    { preHandler: requireMemberHook },
    async (request): Promise<ReportChatMessageResponse> => {
      const auth = request.auth!;
      const params = eventMessageIdParamsSchema.parse(request.params);
      const body = reportMessageBodySchema.parse(request.body);

      await eventChatService.reportMessage({
        messageId: params.messageId,
        reporterUser: {
          userId: auth.userId,
          role: auth.role,
          status: auth.status,
          subscriptionEntitlement: auth.subscriptionEntitlement,
        },
        eventId: params.eventId,
        reason: body.reason,
        details: body.details,
      });

      return { ok: true, data: { reported: true } };
    },
  );

  // -------------------------------------------------------------------------
  // GET /v1/admin/event-chat/messages
  // Admin: list messages with moderation context.
  // -------------------------------------------------------------------------
  app.get(
    EVENT_CHAT_ROUTE_PATHS.adminMessages,
    { preHandler: requireAdminHook },
    async (request): Promise<AdminEventChatMessagesResponse> => {
      const auth = request.auth!;

      if (!canModerateEventChat({ role: auth.role, status: auth.status })) {
        throw new AppError(403, 'forbidden', 'Admin access required.');
      }

      const query = adminMessagesQuerySchema.parse(request.query);
      const result = await eventChatService.listAdminMessages({
        adminUser: {
          userId: auth.userId,
          role: auth.role,
          status: auth.status,
          subscriptionEntitlement: auth.subscriptionEntitlement,
        },
        eventId: query.eventId,
        removed: query.removed,
        page: query.page,
        pageSize: query.pageSize,
      });

      return {
        ok: true,
        data: { messages: result.messages },
        meta: {
          page: result.page,
          pageSize: result.pageSize,
          total: result.total,
          hasNext: result.hasNext,
        },
      };
    },
  );

  // -------------------------------------------------------------------------
  // GET /v1/admin/event-chat/reports
  // Admin: list message reports with review context.
  // -------------------------------------------------------------------------
  app.get(
    EVENT_CHAT_ROUTE_PATHS.adminReports,
    { preHandler: requireAdminHook },
    async (request): Promise<AdminEventChatReportsResponse> => {
      const auth = request.auth!;

      if (!canModerateEventChat({ role: auth.role, status: auth.status })) {
        throw new AppError(403, 'forbidden', 'Admin access required.');
      }

      const query = adminReportsQuerySchema.parse(request.query);
      const result = await eventChatService.listAdminReports({
        adminUser: {
          userId: auth.userId,
          role: auth.role,
          status: auth.status,
          subscriptionEntitlement: auth.subscriptionEntitlement,
        },
        messageId: query.messageId,
        eventId: query.eventId,
        status: query.status,
        page: query.page,
        pageSize: query.pageSize,
      });

      return {
        ok: true,
        data: { reports: result.reports },
        meta: {
          page: result.page,
          pageSize: result.pageSize,
          total: result.total,
          hasNext: result.hasNext,
        },
      };
    },
  );

  // -------------------------------------------------------------------------
  // POST /v1/admin/event-chat/messages/:messageId/remove
  // Admin: soft-remove a message with a required reason. Writes audit log.
  // -------------------------------------------------------------------------
  app.post(
    buildAdminEventChatRemovePath(':messageId'),
    { preHandler: requireAdminHook },
    async (request): Promise<AdminRemoveChatMessageResponse> => {
      const auth = request.auth!;

      if (!canModerateEventChat({ role: auth.role, status: auth.status })) {
        throw new AppError(403, 'forbidden', 'Admin access required.');
      }

      const params = messageIdParamsSchema.parse(request.params);
      const body = adminRemoveBodySchema.parse(request.body);

      const message = await eventChatService.removeMessage({
        messageId: params.messageId,
        adminUser: {
          userId: auth.userId,
          role: auth.role,
          status: auth.status,
          subscriptionEntitlement: auth.subscriptionEntitlement,
        },
        reason: body.reason,
      });

      return { ok: true, data: { message } };
    },
  );
}
