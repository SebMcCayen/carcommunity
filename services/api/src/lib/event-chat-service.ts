/**
 * EventChatService — backend service for all event chat operations.
 *
 * Access rules enforced here:
 * - Authenticated member with active member_monthly entitlement.
 * - Non-suspended, non-deleted account.
 * - Event must be published.
 * - RSVP must be `going` or `maybe`.
 * - Blocking is enforced in both directions.
 * - Removed messages return a neutral placeholder for member clients.
 * - Admin endpoints expose full message content for moderation.
 *
 * Security notes:
 * - Plain text only; no HTML rendering.
 * - Message length limited to CHAT_MESSAGE_MAX_LENGTH.
 * - Report details limited to CHAT_REPORT_DETAILS_MAX_LENGTH.
 * - Removal evidence is never hard-deleted.
 */

import type { EventChatMessage as PrismaMessage, EventChatMessageReport as PrismaReport, PrismaClient } from '@prisma/client';
import {
  CHAT_MESSAGE_MAX_LENGTH,
  CHAT_REPORT_DETAILS_MAX_LENGTH,
  CHAT_MESSAGE_REPORT_REASONS,
  DEFAULT_CHAT_PAGE_SIZE,
  MAX_CHAT_PAGE_SIZE,
  type AdminEventChatMessageSummary,
  type AdminEventChatReportSummary,
  type ChatMessageModerationState,
  type ChatMessageReportReason,
  type ChatMessageReportStatus,
  type EventChatMessage,
} from '@carcommunity/shared/event-chat';
import { canModerateEventChat, canPostEventChatMessage, canReadEventChat } from '@carcommunity/shared/event-chat';
import type { EventRsvpStatus, EventStatus } from '@carcommunity/shared/events';
import type { UserRole, UserStatus, SubscriptionEntitlement } from '@carcommunity/shared/users';

import { AppError } from './errors.js';

/** Swedish placeholder shown where a removed message would appear. */
const REMOVED_MESSAGE_PLACEHOLDER = 'Meddelandet har tagits bort.';

// ---------------------------------------------------------------------------
// Input types
// ---------------------------------------------------------------------------

export interface ChatAccessUser {
  userId: string;
  role: UserRole;
  status: UserStatus;
  subscriptionEntitlement: SubscriptionEntitlement;
}

export interface ListMessagesInput {
  eventId: string;
  viewerUser: ChatAccessUser;
  /** Cursor (message id) for loading older messages. */
  before?: string;
  take?: number;
}

export interface CreateMessageInput {
  eventId: string;
  authorUser: ChatAccessUser;
  message: string;
}

export interface ReportMessageInput {
  messageId: string;
  reporterUser: ChatAccessUser;
  eventId: string;
  reason: ChatMessageReportReason;
  details?: string;
}

export interface ListAdminMessagesInput {
  adminUser: ChatAccessUser;
  eventId?: string;
  removed?: boolean;
  page?: number;
  pageSize?: number;
}

export interface ListAdminReportsInput {
  adminUser: ChatAccessUser;
  messageId?: string;
  eventId?: string;
  status?: ChatMessageReportStatus;
  page?: number;
  pageSize?: number;
}

export interface RemoveMessageInput {
  messageId: string;
  adminUser: ChatAccessUser;
  reason: string;
}

export interface ListMessagesResult {
  messages: EventChatMessage[];
  nextCursor: string | null;
}

export interface ListAdminMessagesResult {
  messages: AdminEventChatMessageSummary[];
  total: number;
  hasNext: boolean;
  page: number;
  pageSize: number;
}

export interface ListAdminReportsResult {
  reports: AdminEventChatReportSummary[];
  total: number;
  hasNext: boolean;
  page: number;
  pageSize: number;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function toModerationState(removedAt: Date | null): ChatMessageModerationState {
  return removedAt !== null ? 'removed' : 'visible';
}

function toSafeMessage(
  row: {
    id: string;
    eventId: string;
    authorUserId: string;
    message: string;
    createdAt: Date;
    removedAt: Date | null;
    author: { displayName: string | null };
  },
  viewerUserId: string,
): EventChatMessage {
  const moderationState = toModerationState(row.removedAt);
  return {
    id: row.id,
    eventId: row.eventId,
    author: {
      userId: row.authorUserId,
      displayName: row.author.displayName,
    },
    message: moderationState === 'removed' ? REMOVED_MESSAGE_PLACEHOLDER : row.message,
    createdAt: row.createdAt.toISOString(),
    moderationState,
    isOwnMessage: row.authorUserId === viewerUserId,
  };
}

function toAdminMessage(
  row: {
    id: string;
    eventId: string;
    authorUserId: string;
    message: string;
    createdAt: Date;
    removedAt: Date | null;
    removedByUserId: string | null;
    removalReason: string | null;
    author: { displayName: string | null };
    _count: { reports: number };
    reports: { status: string }[];
  },
): AdminEventChatMessageSummary {
  const reportStatus =
    row.reports.length > 0 ? (row.reports[0]!.status as ChatMessageReportStatus) : null;
  return {
    id: row.id,
    eventId: row.eventId,
    author: { userId: row.authorUserId, displayName: row.author.displayName },
    message: row.message,
    createdAt: row.createdAt.toISOString(),
    moderationState: toModerationState(row.removedAt),
    removedAt: row.removedAt ? row.removedAt.toISOString() : null,
    removedByUserId: row.removedByUserId,
    removalReason: row.removalReason,
    reportCount: row._count.reports,
    reportStatus,
  };
}

function toAdminReport(row: {
  id: string;
  messageId: string;
  reason: string;
  details: string | null;
  status: string;
  createdAt: Date;
  reviewedAt: Date | null;
  reviewedByUserId: string | null;
}): AdminEventChatReportSummary {
  return {
    id: row.id,
    messageId: row.messageId,
    reason: row.reason as ChatMessageReportReason,
    details: row.details,
    status: row.status as ChatMessageReportStatus,
    createdAt: row.createdAt.toISOString(),
    reviewedAt: row.reviewedAt ? row.reviewedAt.toISOString() : null,
    reviewedByUserId: row.reviewedByUserId,
  };
}

// ---------------------------------------------------------------------------
// Service
// ---------------------------------------------------------------------------

export class EventChatService {
  constructor(private readonly prisma: PrismaClient) {}

  /**
   * Loads the user's RSVP status for the given event.
   */
  private async getRsvpStatus(
    userId: string,
    eventId: string,
  ): Promise<EventRsvpStatus | null> {
    const rsvp = await this.prisma.eventRsvp.findUnique({
      where: { eventId_userId: { eventId, userId } },
      select: { status: true },
    });
    return (rsvp?.status ?? null) as EventRsvpStatus | null;
  }

  /**
   * Validates that the event exists and is published.
   */
  private async requirePublishedEvent(eventId: string): Promise<EventStatus> {
    const event = await this.prisma.event.findUnique({
      where: { id: eventId },
      select: { status: true },
    });
    if (!event) {
      throw new AppError(404, 'not_found', 'Event not found.');
    }
    const eventStatus = event.status as EventStatus;
    if (eventStatus !== 'published') {
      throw new AppError(403, 'forbidden', 'Event chat is not available for this event.');
    }
    return eventStatus;
  }

  /**
   * Returns all user IDs invisible to the viewer (both directions of blocking).
   */
  private async getInvisibleUserIds(viewerId: string): Promise<Set<string>> {
    const [blockedByViewer, blockedViewer] = await this.prisma.$transaction([
      this.prisma.userBlock.findMany({
        where: { blockerUserId: viewerId },
        select: { blockedUserId: true },
      }),
      this.prisma.userBlock.findMany({
        where: { blockedUserId: viewerId },
        select: { blockerUserId: true },
      }),
    ]);
    const ids = new Set<string>();
    for (const b of blockedByViewer) ids.add(b.blockedUserId);
    for (const b of blockedViewer) ids.add(b.blockerUserId);
    return ids;
  }

  /**
   * Lists recent event chat messages for an eligible member.
   * Filters messages from blocked users (both directions).
   * Removed messages show a neutral placeholder.
   */
  async listMessages(input: ListMessagesInput): Promise<ListMessagesResult> {
    const take = Math.min(input.take ?? DEFAULT_CHAT_PAGE_SIZE, MAX_CHAT_PAGE_SIZE);
    const eventStatus = await this.requirePublishedEvent(input.eventId);
    const rsvpStatus = await this.getRsvpStatus(input.viewerUser.userId, input.eventId);

    if (
      !canReadEventChat({
        role: input.viewerUser.role,
        status: input.viewerUser.status,
        subscriptionEntitlement: input.viewerUser.subscriptionEntitlement,
        eventStatus,
        rsvpStatus,
      })
    ) {
      throw new AppError(403, 'forbidden', 'Du behöver svara Kommer eller Kanske för att delta i chatten.');
    }

    const invisibleIds = await this.getInvisibleUserIds(input.viewerUser.userId);

    const rows = await this.prisma.eventChatMessage.findMany({
      where: {
        eventId: input.eventId,
        authorUserId: invisibleIds.size > 0 ? { notIn: Array.from(invisibleIds) } : undefined,
        ...(input.before
          ? {
              id: { not: input.before },
              createdAt: {
                lt: (
                  await this.prisma.eventChatMessage.findUnique({
                    where: { id: input.before },
                    select: { createdAt: true },
                  })
                )?.createdAt,
              },
            }
          : {}),
      },
      orderBy: { createdAt: 'desc' },
      take: take + 1,
      select: {
        id: true,
        eventId: true,
        authorUserId: true,
        message: true,
        createdAt: true,
        removedAt: true,
        author: { select: { displayName: true } },
      },
    });

    const hasMore = rows.length > take;
    const page = hasMore ? rows.slice(0, take) : rows;
    // Return in ascending chronological order for the client
    const sorted = [...page].reverse();

    const nextCursor = hasMore && page.length > 0 ? page[page.length - 1]!.id : null;

    return {
      messages: sorted.map((r) => toSafeMessage(r, input.viewerUser.userId)),
      nextCursor,
    };
  }

  /**
   * Posts a new plain-text message to an event chat.
   */
  async createMessage(input: CreateMessageInput): Promise<EventChatMessage> {
    const eventStatus = await this.requirePublishedEvent(input.eventId);
    const rsvpStatus = await this.getRsvpStatus(input.authorUser.userId, input.eventId);

    if (
      !canPostEventChatMessage({
        role: input.authorUser.role,
        status: input.authorUser.status,
        subscriptionEntitlement: input.authorUser.subscriptionEntitlement,
        eventStatus,
        rsvpStatus,
      })
    ) {
      throw new AppError(403, 'forbidden', 'Du behöver svara Kommer eller Kanske för att delta i chatten.');
    }

    const trimmed = input.message.trim();
    if (!trimmed) {
      throw new AppError(400, 'validation_error', 'Message cannot be empty.');
    }
    if (trimmed.length > CHAT_MESSAGE_MAX_LENGTH) {
      throw new AppError(400, 'validation_error', `Message exceeds maximum length of ${CHAT_MESSAGE_MAX_LENGTH} characters.`);
    }

    const created = await this.prisma.eventChatMessage.create({
      data: {
        eventId: input.eventId,
        authorUserId: input.authorUser.userId,
        message: trimmed,
      },
      select: {
        id: true,
        eventId: true,
        authorUserId: true,
        message: true,
        createdAt: true,
        removedAt: true,
        author: { select: { displayName: true } },
      },
    });

    return toSafeMessage(created, input.authorUser.userId);
  }

  /**
   * Files a report against a chat message.
   * Safe against duplicate reports (same user + message + reason).
   * Users cannot report their own messages.
   */
  async reportMessage(input: ReportMessageInput): Promise<void> {
    const eventStatus = await this.requirePublishedEvent(input.eventId);
    const rsvpStatus = await this.getRsvpStatus(input.reporterUser.userId, input.eventId);

    if (
      !canReadEventChat({
        role: input.reporterUser.role,
        status: input.reporterUser.status,
        subscriptionEntitlement: input.reporterUser.subscriptionEntitlement,
        eventStatus,
        rsvpStatus,
      })
    ) {
      throw new AppError(403, 'forbidden', 'Access denied.');
    }

    const message = await this.prisma.eventChatMessage.findUnique({
      where: { id: input.messageId },
      select: { id: true, eventId: true, authorUserId: true },
    });

    if (!message || message.eventId !== input.eventId) {
      throw new AppError(404, 'not_found', 'Message not found.');
    }

    if (message.authorUserId === input.reporterUser.userId) {
      throw new AppError(400, 'validation_error', 'You cannot report your own message.');
    }

    if (!CHAT_MESSAGE_REPORT_REASONS.includes(input.reason)) {
      throw new AppError(400, 'validation_error', 'Invalid report reason.');
    }

    const details =
      input.details && input.details.trim()
        ? input.details.trim().slice(0, CHAT_REPORT_DETAILS_MAX_LENGTH)
        : null;

    // Upsert: if the same user already reported this message for this reason, update details silently.
    await this.prisma.eventChatMessageReport.upsert({
      where: {
        messageId_reporterUserId_reason: {
          messageId: input.messageId,
          reporterUserId: input.reporterUser.userId,
          reason: input.reason,
        },
      },
      create: {
        messageId: input.messageId,
        reporterUserId: input.reporterUser.userId,
        reason: input.reason,
        details,
        status: 'new',
      },
      update: {
        details,
      },
    });
  }

  /**
   * Lists messages for admin moderation.
   * Includes full message text even if removed.
   */
  async listAdminMessages(input: ListAdminMessagesInput): Promise<ListAdminMessagesResult> {
    if (!canModerateEventChat(input.adminUser)) {
      throw new AppError(403, 'forbidden', 'Admin access required.');
    }

    const page = input.page ?? 1;
    const pageSize = input.pageSize ?? DEFAULT_CHAT_PAGE_SIZE;
    const skip = (page - 1) * pageSize;

    const where = {
      ...(input.eventId ? { eventId: input.eventId } : {}),
      ...(input.removed === true ? { removedAt: { not: null } } : {}),
      ...(input.removed === false ? { removedAt: null } : {}),
    };

    const [total, rows] = await this.prisma.$transaction([
      this.prisma.eventChatMessage.count({ where }),
      this.prisma.eventChatMessage.findMany({
        where,
        orderBy: { createdAt: 'desc' },
        skip,
        take: pageSize,
        select: {
          id: true,
          eventId: true,
          authorUserId: true,
          message: true,
          createdAt: true,
          removedAt: true,
          removedByUserId: true,
          removalReason: true,
          author: { select: { displayName: true } },
          _count: { select: { reports: true } },
          reports: {
            orderBy: { createdAt: 'desc' },
            take: 1,
            select: { status: true },
          },
        },
      }),
    ]);

    return {
      messages: rows.map(toAdminMessage),
      total,
      hasNext: skip + rows.length < total,
      page,
      pageSize,
    };
  }

  /**
   * Lists message reports for admin review.
   */
  async listAdminReports(input: ListAdminReportsInput): Promise<ListAdminReportsResult> {
    if (!canModerateEventChat(input.adminUser)) {
      throw new AppError(403, 'forbidden', 'Admin access required.');
    }

    const page = input.page ?? 1;
    const pageSize = input.pageSize ?? DEFAULT_CHAT_PAGE_SIZE;
    const skip = (page - 1) * pageSize;

    const where = {
      ...(input.messageId ? { messageId: input.messageId } : {}),
      ...(input.status ? { status: input.status } : {}),
      ...(input.eventId ? { message: { eventId: input.eventId } } : {}),
    };

    const [total, rows] = await this.prisma.$transaction([
      this.prisma.eventChatMessageReport.count({ where }),
      this.prisma.eventChatMessageReport.findMany({
        where,
        orderBy: { createdAt: 'desc' },
        skip,
        take: pageSize,
        select: {
          id: true,
          messageId: true,
          reason: true,
          details: true,
          status: true,
          createdAt: true,
          reviewedAt: true,
          reviewedByUserId: true,
        },
      }),
    ]);

    return {
      reports: rows.map(toAdminReport),
      total,
      hasNext: skip + rows.length < total,
      page,
      pageSize,
    };
  }

  /**
   * Soft-removes a chat message and writes an audit log.
   * Updates any open reports on the message to `resolved`.
   * Never hard-deletes — evidence is preserved for audit.
   */
  async removeMessage(input: RemoveMessageInput): Promise<AdminEventChatMessageSummary> {
    if (!canModerateEventChat(input.adminUser)) {
      throw new AppError(403, 'forbidden', 'Admin access required.');
    }

    if (!input.reason.trim()) {
      throw new AppError(400, 'validation_error', 'Removal reason is required.');
    }

    const message = await this.prisma.eventChatMessage.findUnique({
      where: { id: input.messageId },
      select: { id: true, eventId: true, removedAt: true },
    });

    if (!message) {
      throw new AppError(404, 'not_found', 'Message not found.');
    }

    const now = new Date();

    const updated = await this.prisma.$transaction(async (tx) => {
      const msg = await tx.eventChatMessage.update({
        where: { id: input.messageId },
        data: {
          removedAt: message.removedAt ?? now,
          removedByUserId: input.adminUser.userId,
          removalReason: input.reason.trim(),
        },
        select: {
          id: true,
          eventId: true,
          authorUserId: true,
          message: true,
          createdAt: true,
          removedAt: true,
          removedByUserId: true,
          removalReason: true,
          author: { select: { displayName: true } },
          _count: { select: { reports: true } },
          reports: {
            orderBy: { createdAt: 'desc' },
            take: 1,
            select: { status: true },
          },
        },
      });

      // Resolve any open reports on this message
      await tx.eventChatMessageReport.updateMany({
        where: {
          messageId: input.messageId,
          status: { in: ['new', 'under_review'] },
        },
        data: {
          status: 'resolved',
          reviewedAt: now,
          reviewedByUserId: input.adminUser.userId,
        },
      });

      // Audit log
      await tx.auditLog.create({
        data: {
          actorUserId: input.adminUser.userId,
          action: 'event_chat.remove_message',
          entityType: 'event_chat_message',
          entityId: input.messageId,
          reason: input.reason.trim(),
        },
      });

      return msg;
    });

    return toAdminMessage(updated);
  }
}
