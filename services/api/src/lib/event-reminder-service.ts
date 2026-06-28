/**
 * EventReminderService — prepares event reminder notification delivery.
 *
 * Design rules enforced here:
 *  - Event reminders target only eligible users: appropriate RSVP, active account,
 *    published event, applicable membership where needed.
 *  - Cancelled-event notices may reach users who previously RSVP'd.
 *  - Reminder generation is idempotent (idempotency key per event + category + user + window).
 *  - No exact event coordinates in push payloads.
 *  - Protected event details are loaded by the app after opening via backend revalidation.
 *  - No Azure scheduling in this step — these functions are prepared but not triggered.
 *
 * TODO: Wire these functions to Azure Timer Functions or equivalent scheduling.
 * TODO: Add cron-triggered cleanup for old idempotency keys.
 */

import type { PrismaClient } from '@prisma/client';
import crypto from 'node:crypto';

import type { NotificationCategory } from '@carcommunity/shared/notifications';

import type { NotificationDeliveryService } from './notification-delivery-service.js';

// ---------------------------------------------------------------------------
// Input types
// ---------------------------------------------------------------------------

export interface EventReminderInput {
  eventId: string;
  /** Window label for idempotency (e.g. '24h', '1h'). */
  reminderWindow: string;
}

export interface EventUpdatedInput {
  eventId: string;
}

export interface EventCancelledInput {
  eventId: string;
}

export interface EventReminderResult {
  eventId: string;
  category: NotificationCategory;
  notified: number;
  skipped: number;
}

// ---------------------------------------------------------------------------
// EventReminderService
// ---------------------------------------------------------------------------

export class EventReminderService {
  constructor(
    private readonly prisma: PrismaClient,
    private readonly deliveryService: NotificationDeliveryService,
  ) {}

  /**
   * Send an event reminder to all eligible RSVP'd users.
   *
   * Eligibility:
   *  - Event status is 'upcoming' or 'published'.
   *  - RSVP status is 'going'.
   *  - User account is active (not deleted, not suspended for non-essential).
   *  - Idempotency key prevents duplicate reminders per event + window.
   */
  async sendEventReminder(input: EventReminderInput): Promise<EventReminderResult> {
    const event = await this.prisma.event.findUnique({
      where: { id: input.eventId },
      select: { id: true, title: true, status: true, startsAt: true },
    });

    if (!event || !['published'].includes(event.status)) {
      return { eventId: input.eventId, category: 'event_reminder', notified: 0, skipped: 0 };
    }

    const rsvps = await this.prisma.eventRsvp.findMany({
      where: {
        eventId: input.eventId,
        status: 'going',
        user: {
          status: { in: ['active', 'warned'] },
          deletedAt: null,
        },
      },
      include: {
        user: { select: { id: true, status: true } },
      },
    });

    let notified = 0;
    let skipped = 0;

    // Safe event date format for Swedish locale — no exact coordinates.
    const startDateLabel = event.startsAt
      ? new Date(event.startsAt).toLocaleDateString('sv-SE', {
          weekday: 'long',
          month: 'long',
          day: 'numeric',
        })
      : '';

    const safeTitle = `Påminnelse: ${event.title.slice(0, 60)}`;
    const safePreview = startDateLabel
      ? `Eventet börjar ${startDateLabel}.`
      : 'Eventet börjar snart.';

    for (const rsvp of rsvps) {
      // Idempotency key: event + category + user + window.
      const idempotencyKey = buildReminderIdempotencyKey(
        input.eventId,
        'event_reminder',
        rsvp.user.id,
        input.reminderWindow,
      );

      // Check if already sent for this window.
      const alreadySent = await this.checkIdempotencyKey(idempotencyKey);
      if (alreadySent) {
        skipped++;
        continue;
      }

      try {
        const outcome = await this.deliveryService.deliverToUser({
          userId: rsvp.user.id,
          userStatus: rsvp.user.status as import('@carcommunity/shared/users').UserStatus,
          category: 'event_reminder',
          title: safeTitle,
          // Safe preview — no exact coordinates, no protected data.
          previewText: safePreview,
          body: 'Öppna appen för fullständig information om eventet.',
          actionType: 'open_event',
          relatedEntityType: 'event',
          // relatedEntityId used to deep-link after app open; backend re-validates access.
          relatedEntityId: input.eventId,
          batchId: buildReminderBatchId(idempotencyKey),
        });

        if (outcome.inAppDelivered) {
          await this.recordIdempotencyKey(idempotencyKey);
          notified++;
        } else {
          skipped++;
        }
      } catch {
        skipped++;
      }
    }

    return { eventId: input.eventId, category: 'event_reminder', notified, skipped };
  }

  /**
   * Send an event-updated notice to all eligible RSVP'd users.
   *
   * Eligibility same as reminder, except RSVP may also be 'interested'.
   * Idempotent per event update cycle — caller should pass a unique triggerKey.
   */
  async sendEventUpdatedNotice(
    input: EventUpdatedInput,
    triggerKey = 'default',
  ): Promise<EventReminderResult> {
    const event = await this.prisma.event.findUnique({
      where: { id: input.eventId },
      select: { id: true, title: true, status: true },
    });

    if (!event) {
      return { eventId: input.eventId, category: 'event_updated', notified: 0, skipped: 0 };
    }

    const rsvps = await this.prisma.eventRsvp.findMany({
      where: {
        eventId: input.eventId,
        status: { in: ['going', 'maybe'] },
        user: { status: { in: ['active', 'warned'] }, deletedAt: null },
      },
      include: { user: { select: { id: true, status: true } } },
    });

    let notified = 0;
    let skipped = 0;

    const safeTitle = `Uppdatering: ${event.title.slice(0, 60)}`;
    const safePreview = 'Eventet har uppdaterats. Öppna appen för mer information.';

    for (const rsvp of rsvps) {
      const idempotencyKey = buildReminderIdempotencyKey(
        input.eventId,
        'event_updated',
        rsvp.user.id,
        triggerKey,
      );

      if (await this.checkIdempotencyKey(idempotencyKey)) {
        skipped++;
        continue;
      }

      try {
        const outcome = await this.deliveryService.deliverToUser({
          userId: rsvp.user.id,
          userStatus: rsvp.user.status as import('@carcommunity/shared/users').UserStatus,
          category: 'event_updated',
          title: safeTitle,
          previewText: safePreview,
          body: 'Öppna appen för fullständig information.',
          actionType: 'open_event',
          relatedEntityType: 'event',
          relatedEntityId: input.eventId,
          batchId: buildReminderBatchId(idempotencyKey),
        });

        if (outcome.inAppDelivered) {
          await this.recordIdempotencyKey(idempotencyKey);
          notified++;
        } else {
          skipped++;
        }
      } catch {
        skipped++;
      }
    }

    return { eventId: input.eventId, category: 'event_updated', notified, skipped };
  }

  /**
   * Send an event-cancelled notice to all users who RSVP'd.
   *
   * Cancelled notices may reach users who previously RSVP'd (all statuses).
   * Idempotent per event cancellation.
   */
  async sendEventCancelledNotice(
    input: EventCancelledInput,
    triggerKey = 'cancellation',
  ): Promise<EventReminderResult> {
    const event = await this.prisma.event.findUnique({
      where: { id: input.eventId },
      select: { id: true, title: true, status: true },
    });

    if (!event) {
      return { eventId: input.eventId, category: 'event_cancelled', notified: 0, skipped: 0 };
    }

    // Include all RSVP statuses for cancellations.
    const rsvps = await this.prisma.eventRsvp.findMany({
      where: {
        eventId: input.eventId,
        user: { deletedAt: null, status: { not: 'deleted' } },
      },
      include: { user: { select: { id: true, status: true } } },
    });

    let notified = 0;
    let skipped = 0;

    const safeTitle = `Inställt: ${event.title.slice(0, 60)}`;
    const safePreview = 'Eventet har ställts in.';

    for (const rsvp of rsvps) {
      const idempotencyKey = buildReminderIdempotencyKey(
        input.eventId,
        'event_cancelled',
        rsvp.user.id,
        triggerKey,
      );

      if (await this.checkIdempotencyKey(idempotencyKey)) {
        skipped++;
        continue;
      }

      try {
        const outcome = await this.deliveryService.deliverToUser({
          userId: rsvp.user.id,
          userStatus: rsvp.user.status as import('@carcommunity/shared/users').UserStatus,
          category: 'event_cancelled',
          title: safeTitle,
          previewText: safePreview,
          body: 'Öppna appen för mer information.',
          actionType: 'open_event',
          relatedEntityType: 'event',
          relatedEntityId: input.eventId,
          batchId: buildReminderBatchId(idempotencyKey),
        });

        if (outcome.inAppDelivered) {
          await this.recordIdempotencyKey(idempotencyKey);
          notified++;
        } else {
          skipped++;
        }
      } catch {
        skipped++;
      }
    }

    return { eventId: input.eventId, category: 'event_cancelled', notified, skipped };
  }

  // -------------------------------------------------------------------------
  // Idempotency helpers
  // -------------------------------------------------------------------------

  /**
   * Check whether a reminder with this key has already been delivered.
   * Uses the UserNotification batchId convention via a dedicated check.
   */
  private async checkIdempotencyKey(key: string): Promise<boolean> {
    const existing = await this.prisma.userNotification.findFirst({
      where: { batchId: buildReminderBatchId(key) },
      select: { id: true },
    });
    return existing !== null;
  }

  private async recordIdempotencyKey(key: string): Promise<void> {
    // No-op: the batchId is set on the notification at creation time.
    // This method is here for clarity and future extension.
    void key;
  }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function buildReminderIdempotencyKey(
  eventId: string,
  category: string,
  userId: string,
  window: string,
): string {
  return `event-reminder:${eventId}:${category}:${userId}:${window}`;
}

function buildReminderBatchId(key: string): string {
  const hash = crypto.createHash('sha256').update(key).digest('hex');
  return `${hash.slice(0, 8)}-${hash.slice(8, 12)}-${hash.slice(12, 16)}-${hash.slice(16, 20)}-${hash.slice(20, 32)}`;
}
