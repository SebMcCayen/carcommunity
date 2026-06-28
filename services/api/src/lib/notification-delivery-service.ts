/**
 * NotificationDeliveryService — orchestrates in-app and push delivery.
 *
 * Design rules enforced here:
 *  - In-app delivery may succeed even if push fails.
 *  - Push failure must not roll back the in-app notification.
 *  - Backend validates preference, account status, and feature flag before delivery.
 *  - Client-side preferences are not the security boundary.
 *  - Deleted users must not receive normal notifications.
 *  - Suspended users may receive essential account notices.
 *  - Feature flag `pushNotifications` gates all push delivery.
 *  - In-app notifications may continue when push feature flag is disabled.
 *  - Push tokens must never be logged.
 *  - Retry is bounded (MAX_PUSH_RETRIES).
 *  - Do not block request threads with large fan-out — keep MVP audience sizes bounded.
 *
 * TODO: Replace synchronous fan-out with a background queue / worker.
 * TODO: Add Azure scheduling for event reminder triggers.
 */

import type { PrismaClient } from '@prisma/client';

import { DEFAULT_FEATURE_FLAGS } from '@carcommunity/shared/feature-flags';
import type {
  NotificationCategory,
  AdminNotificationAudience,
  NotificationActionType,
} from '@carcommunity/shared/notifications';
import { ESSENTIAL_NOTIFICATION_CATEGORIES } from '@carcommunity/shared/notifications';
import type { UserStatus } from '@carcommunity/shared/users';
import { isSuspendedStatus } from '@carcommunity/shared/users';

import { AppError } from './errors.js';
import { NotificationService, decryptPushToken } from './notification-service.js';
import type { PushNotificationProvider } from './push-provider.js';
import { DevPushNotificationProvider } from './push-provider.js';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Maximum push retry attempts per notification per device. */
const MAX_PUSH_RETRIES = 2;

/**
 * Maximum synchronous fan-out size for an admin notification batch.
 * Larger audiences should be processed via a background queue.
 * TODO: Remove this cap once a queue is in place.
 */
const MAX_SYNC_AUDIENCE_SIZE = 500;

// ---------------------------------------------------------------------------
// Input types
// ---------------------------------------------------------------------------

export interface DeliverNotificationInput {
  userId: string;
  userStatus: UserStatus;
  category: NotificationCategory;
  title: string;
  previewText: string;
  body?: string;
  actionType?: NotificationActionType;
  relatedEntityType?: string;
  relatedEntityId?: string;
  batchId?: string;
  /** Idempotency key per event + category + user + window. Optional. */
  idempotencyKey?: string;
}

export interface DeliverToAudienceInput {
  audience: AdminNotificationAudience;
  category: NotificationCategory;
  title: string;
  previewText: string;
  body: string;
  actionType?: NotificationActionType;
  relatedEntityId?: string;
  reason: string;
  idempotencyKey: string;
  createdByUserId: string;
  /** Required when audience is 'event_participants'. */
  eventId?: string;
  /** Required when audience is 'specific_user'. */
  targetUserId?: string;
  /** Required for all_users / free_users audiences. */
  confirmed?: boolean;
}

export interface DeliveryOutcome {
  notificationId: string;
  inAppDelivered: boolean;
  pushAttempted: boolean;
  pushSucceeded: boolean;
}

// ---------------------------------------------------------------------------
// NotificationDeliveryService
// ---------------------------------------------------------------------------

export class NotificationDeliveryService {
  private readonly notificationService: NotificationService;
  private readonly pushProvider: PushNotificationProvider;
  private readonly pushNotificationsEnabled: boolean;

  constructor(
    private readonly prisma: PrismaClient,
    options: {
      notificationService?: NotificationService;
      pushProvider?: PushNotificationProvider;
      pushNotificationsFeatureEnabled?: boolean;
    } = {},
  ) {
    this.notificationService =
      options.notificationService ?? new NotificationService(prisma);
    this.pushProvider = options.pushProvider ?? new DevPushNotificationProvider();
    this.pushNotificationsEnabled =
      options.pushNotificationsFeatureEnabled ?? DEFAULT_FEATURE_FLAGS.pushNotifications;
  }

  /**
   * Deliver a notification to a single user.
   *
   * Steps:
   *  1. Check account status (deleted = skip unless essential; suspended = essential only).
   *  2. Check in-app preference.
   *  3. Create in-app notification.
   *  4. Check push feature flag and push preference.
   *  5. Attempt push delivery to active devices.
   *  6. Record delivery attempt (safe result only, no token values).
   *
   * In-app delivery success is independent of push delivery.
   */
  async deliverToUser(input: DeliverNotificationInput): Promise<DeliveryOutcome> {
    const isEssential = ESSENTIAL_NOTIFICATION_CATEGORIES.includes(input.category);

    // Deleted users must not receive any notifications.
    if (input.userStatus === 'deleted') {
      return { notificationId: '', inAppDelivered: false, pushAttempted: false, pushSucceeded: false };
    }

    // Suspended users may only receive essential notices.
    if (isSuspendedStatus(input.userStatus) && !isEssential) {
      return { notificationId: '', inAppDelivered: false, pushAttempted: false, pushSucceeded: false };
    }

    // Check in-app preference.
    const preferences = await this.notificationService.getPreferences({ userId: input.userId });
    const pref = preferences.find((p) => p.category === input.category);
    const inAppEnabled = isEssential ? true : (pref?.inAppEnabled ?? true);

    if (!inAppEnabled) {
      return { notificationId: '', inAppDelivered: false, pushAttempted: false, pushSucceeded: false };
    }

    // Create in-app notification (always attempted if inAppEnabled).
    let notificationId = '';
    let inAppDelivered = false;
    try {
      const notification = await this.notificationService.createNotification({
        userId: input.userId,
        category: input.category,
        title: input.title,
        previewText: input.previewText,
        body: input.body,
        actionType: input.actionType,
        relatedEntityType: input.relatedEntityType,
        relatedEntityId: input.relatedEntityId,
        batchId: input.batchId,
      });
      notificationId = notification.notificationId;
      inAppDelivered = true;
    } catch (err) {
      // If in-app creation fails, we can't send push because the payload requires a notificationId.
      console.error('[DeliveryService] Failed to create in-app notification:', err instanceof Error ? err.message : 'unknown');
    }

    // Push delivery — gated by feature flag and preference.
    let pushAttempted = false;
    let pushSucceeded = false;

    const pushEnabled = this.pushNotificationsEnabled && (pref?.pushEnabled ?? false);
    if (pushEnabled && notificationId) {
      const devices = await this.notificationService.getActiveDevices({ userId: input.userId });

      for (const device of devices) {
        pushAttempted = true;

        let attempt: { success: boolean; safeErrorCode?: string } = { success: false, safeErrorCode: 'not_attempted' };

        for (let retry = 0; retry <= MAX_PUSH_RETRIES; retry++) {
          const decrypted = decryptPushToken(device.encryptedPushToken);
          if (!decrypted) {
            attempt = { success: false, safeErrorCode: 'token_decrypt_failed' };
            break;
          }

          let result: import('./push-provider.js').PushSendResult;
          try {
            result = await this.pushProvider.sendPushNotification(device.encryptedPushToken, {
              notificationId,
              category: input.category,
              title: input.title,
              previewText: input.previewText,
              actionType: input.actionType ?? 'open_notifications',
            });
          } catch {
            result = { success: false, safeErrorCode: 'push_provider_error' };
          }
          if (result.success) {
            attempt = { success: true };
            pushSucceeded = true;

            // Record safe delivery attempt.
            await this.recordDeliveryAttempt({
              userNotificationId: notificationId,
              deviceRegistrationId: device.deviceId,
              channel: 'push',
              status: 'sent',
              providerMessageId: result.providerMessageId,
            });
            break;
          } else {
            attempt = { success: false, safeErrorCode: result.safeErrorCode };
            if (result.shouldDeactivateToken) {
              // Token is invalid — deactivate only this device registration.
              await this.prisma.pushDeviceRegistration.update({
                where: { id: device.deviceId },
                data: { isActive: false, revokedAt: new Date() },
              });
              break;
            }
            if (retry === MAX_PUSH_RETRIES) {
              await this.recordDeliveryAttempt({
                userNotificationId: notificationId,
                deviceRegistrationId: device.deviceId,
                channel: 'push',
                status: 'failed',
                safeErrorCode: attempt.safeErrorCode,
              });
            }
          }
        }
      }
    }

    // Record in-app delivery attempt.
    if (inAppDelivered && notificationId) {
      await this.recordDeliveryAttempt({
        userNotificationId: notificationId,
        channel: 'in_app',
        status: 'delivered',
      });
    }

    return { notificationId, inAppDelivered, pushAttempted, pushSucceeded };
  }

  /**
   * Deliver a notification to a bounded audience.
   * Creates an AdminNotificationBatch record and fan-outs delivery.
   *
   * Audience fan-out is synchronous for MVP (bounded by MAX_SYNC_AUDIENCE_SIZE).
   * TODO: Replace with background queue for large audiences.
   */
  async deliverToAudience(input: DeliverToAudienceInput): Promise<{
    batchId: string;
    recipientCount: number;
    createdAt: Date;
  }> {
    // Guard: confirmation required for mass sends.
    if (
      (input.audience === 'all_users' || input.audience === 'free_users') &&
      input.confirmed !== true
    ) {
      throw new AppError(
        400,
        'notification_confirmation_required',
        'Explicit confirmation is required for all_users or free_users audience.',
      );
    }

    // Guard: idempotency key.
    const existing = await this.prisma.adminNotificationBatch.findUnique({
      where: { idempotencyKey: input.idempotencyKey },
    });
    if (existing) {
      throw new AppError(
        409,
        'notification_duplicate_idempotency_key',
        'A notification batch with this idempotency key already exists.',
      );
    }

    // Resolve recipient user IDs.
    const recipientIds = await this.resolveAudienceUserIds(input);

    if (recipientIds.length > MAX_SYNC_AUDIENCE_SIZE) {
      // TODO: Emit to background queue instead of truncating.
      recipientIds.splice(MAX_SYNC_AUDIENCE_SIZE);
    }

    const batchId = crypto.randomUUID();
    const now = new Date();

    // Create batch record.
    await this.prisma.adminNotificationBatch.create({
      data: {
        id: batchId,
        category: input.category,
        audience: input.audience,
        title: input.title,
        previewText: input.previewText,
        body: input.body,
        actionType: input.actionType ?? null,
        relatedEntityId: input.relatedEntityId ?? null,
        recipientCount: recipientIds.length,
        reason: input.reason,
        idempotencyKey: input.idempotencyKey,
        createdByUserId: input.createdByUserId,
        createdAt: now,
      },
    });

    // Fan-out in-app notifications using createMany for efficiency.
    if (recipientIds.length > 0) {
      await this.notificationService.createNotificationsForUsers(
        recipientIds.map((userId) => ({
          userId,
          category: input.category,
          title: input.title,
          previewText: input.previewText,
          body: input.body,
          actionType: input.actionType,
          relatedEntityId: input.relatedEntityId,
          batchId,
        })),
        batchId,
      );
    }

    // TODO: Enqueue push delivery for each recipient in a background worker.
    // For MVP, push is not delivered in this path to avoid blocking the request thread.

    return { batchId, recipientCount: recipientIds.length, createdAt: now };
  }

  // -------------------------------------------------------------------------
  // Audience resolution
  // -------------------------------------------------------------------------

  private async resolveAudienceUserIds(input: DeliverToAudienceInput): Promise<string[]> {
    switch (input.audience) {
      case 'specific_user': {
        if (!input.targetUserId) {
          throw new AppError(400, 'notification_target_user_required', 'targetUserId is required for specific_user audience.');
        }
        const user = await this.prisma.user.findUnique({ where: { id: input.targetUserId } });
        if (!user || user.deletedAt || user.status === 'deleted') {
          return [];
        }
        return [input.targetUserId];
      }

      case 'admins': {
        const users = await this.prisma.user.findMany({
          where: { role: { in: ['admin', 'owner'] }, deletedAt: null, status: { not: 'deleted' } },
          select: { id: true },
        });
        return users.map((u) => u.id);
      }

      case 'members': {
        const users = await this.prisma.user.findMany({
          where: {
            subscriptionEntitlement: 'member_monthly',
            deletedAt: null,
            status: { not: 'deleted' },
          },
          select: { id: true },
        });
        return users.map((u) => u.id);
      }

      case 'free_users': {
        const users = await this.prisma.user.findMany({
          where: {
            subscriptionEntitlement: 'none',
            deletedAt: null,
            status: { not: 'deleted' },
          },
          select: { id: true },
        });
        return users.map((u) => u.id);
      }

      case 'all_users': {
        const users = await this.prisma.user.findMany({
          where: { deletedAt: null, status: 'active' },
          select: { id: true },
        });
        return users.map((u) => u.id);
      }

      case 'event_participants': {
        if (!input.eventId) {
          throw new AppError(400, 'notification_event_required', 'eventId is required for event_participants audience.');
        }
        const rsvps = await this.prisma.eventRsvp.findMany({
          where: {
            eventId: input.eventId,
            status: { in: ['going', 'maybe'] },
            user: { deletedAt: null, status: { not: 'deleted' } },
          },
          select: { userId: true },
        });
        return [...new Set(rsvps.map((r) => r.userId))];
      }

      default:
        throw new AppError(400, 'notification_invalid_audience', `Unknown audience '${input.audience}'.`);
    }
  }

  // -------------------------------------------------------------------------
  // Delivery attempt tracking
  // -------------------------------------------------------------------------

  private async recordDeliveryAttempt(input: {
    userNotificationId: string;
    deviceRegistrationId?: string;
    channel: 'in_app' | 'push';
    status: 'pending' | 'sent' | 'delivered' | 'failed' | 'skipped';
    providerMessageId?: string;
    safeErrorCode?: string;
  }): Promise<void> {
    try {
      await this.prisma.notificationDeliveryAttempt.create({
        data: {
          userNotificationId: input.userNotificationId,
          deviceRegistrationId: input.deviceRegistrationId ?? null,
          channel: input.channel,
          status: input.status,
          // Never log or store raw token values — only safe correlation IDs.
          providerMessageId: input.providerMessageId ?? null,
          completedAt: ['sent', 'delivered', 'failed', 'skipped'].includes(input.status) ? new Date() : null,
          safeErrorCode: input.safeErrorCode ?? null,
        },
      });
    } catch {
      // Delivery attempt recording failure must not affect the main notification flow.
    }
  }
}
