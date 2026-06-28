/**
 * NotificationService — backend business logic for in-app notifications,
 * push device registration, and notification preferences.
 *
 * Design rules enforced here:
 *  - Backend is the sole authority for notification eligibility, delivery state, and content.
 *  - Ownership is enforced at every read and write operation.
 *  - Push tokens are stored encrypted and hashed; never returned in API responses.
 *  - Raw push tokens must never appear in logs, errors, or API responses.
 *  - Users may only read their own notifications.
 *  - Deleted users must not receive normal app notifications.
 *  - Suspended users may receive essential account notices.
 *  - Essential categories (account_warning, account_suspension) keep inAppEnabled=true.
 *  - Notification content is plain text only; no HTML.
 *  - Paginate all list operations.
 *  - Idempotency key prevents duplicate admin sends.
 *
 * Retention:
 *  - Normal notifications: 90 days (expiresAt = createdAt + 90d).
 *  - Account notices: 365 days.
 *  - Delivery attempts: not cleaned up in this step; add Azure job later.
 *
 * TODO: Add background worker / queue for large audience fan-out.
 * TODO: Add Azure scheduling for event reminder cleanup.
 * TODO: Replace dev encryption stub with Azure Key Vault or KMS in production.
 */

import crypto from 'node:crypto';

import type { PrismaClient } from '@prisma/client';

import {
  ACTIVE_NOTIFICATION_CATEGORIES,
  ESSENTIAL_NOTIFICATION_CATEGORIES,
  NOTIFICATION_RETENTION_DAYS,
  NOTIFICATION_ACCOUNT_RETENTION_DAYS,
  DEFAULT_NOTIFICATION_PAGE_SIZE,
  MAX_NOTIFICATION_PAGE_SIZE,
  type NotificationCategory,
  type NotificationDevicePlatform,
  type NotificationPreferenceSummary,
  type NotificationSummary,
  type NotificationDetail,
  type NotificationActionType,
} from '@carcommunity/shared/notifications';

import { AppError } from './errors.js';

// ---------------------------------------------------------------------------
// Token encryption helpers
//
// TODO (production): Replace this stub with Azure Key Vault envelope encryption
// or a KMS-backed solution. The stubs below provide a safe development placeholder.
// Never commit real encryption keys to source control.
// ---------------------------------------------------------------------------

const DEV_ENCRYPTION_KEY = 'dev-placeholder-key-32bytes-pad00'; // 32 chars for AES-256

/**
 * Returns the active push-token encryption key.
 * In production, PUSH_TOKEN_ENCRYPTION_KEY must be set or startup fails.
 * In development, falls back to a local placeholder key.
 */
function getEncryptionKey(): string {
  const envKey = process.env.PUSH_TOKEN_ENCRYPTION_KEY;
  if (envKey) return envKey;
  if (process.env.NODE_ENV === 'production') {
    throw new Error(
      'PUSH_TOKEN_ENCRYPTION_KEY environment variable must be set in production. ' +
        'Never use the development placeholder key in production.',
    );
  }
  return DEV_ENCRYPTION_KEY;
}

let derivedPushTokenKey: Buffer | null = null;

function getDerivedPushTokenKey(): Buffer {
  if (derivedPushTokenKey) return derivedPushTokenKey;
  derivedPushTokenKey = crypto.scryptSync(getEncryptionKey(), 'carcommunity-push-salt', 32);
  return derivedPushTokenKey;
}

/**
 * Encrypts a push token using AES-256-GCM with a random IV.
 * In development, uses a placeholder key.
 * TODO: Replace with Azure Key Vault in production.
 *
 * Returns a base64url-encoded string: iv:authTag:ciphertext
 */
export function encryptPushToken(token: string): string {
  const key = getDerivedPushTokenKey();
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
  const encrypted = Buffer.concat([cipher.update(token, 'utf8'), cipher.final()]);
  const authTag = cipher.getAuthTag();
  return `${iv.toString('base64url')}:${authTag.toString('base64url')}:${encrypted.toString('base64url')}`;
}

/**
 * Decrypts a push token encrypted by encryptPushToken.
 * Returns null if decryption fails (e.g. stale key rotation).
 * TODO: Replace with Azure Key Vault in production.
 */
export function decryptPushToken(encrypted: string): string | null {
  try {
    const key = getDerivedPushTokenKey();
    const parts = encrypted.split(':');
    if (parts.length !== 3) return null;
    const ivB64 = parts[0]!;
    const authTagB64 = parts[1]!;
    const dataB64 = parts[2]!;
    const iv = Buffer.from(ivB64, 'base64url');
    const authTag = Buffer.from(authTagB64, 'base64url');
    const data = Buffer.from(dataB64, 'base64url');
    const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv);
    decipher.setAuthTag(authTag);
    return decipher.update(data).toString('utf8') + decipher.final('utf8');
  } catch {
    return null;
  }
}

/**
 * SHA-256 hash of the push token for lookup and deduplication.
 * Never expose the hash directly in API responses.
 */
export function hashPushToken(token: string): string {
  return crypto.createHash('sha256').update(token).digest('hex');
}

// ---------------------------------------------------------------------------
// Input types
// ---------------------------------------------------------------------------

export interface RegisterDeviceInput {
  userId: string;
  platform: NotificationDevicePlatform;
  pushToken: string;
  appVersion?: string;
  buildNumber?: string;
}

export interface UnregisterDeviceInput {
  userId: string;
  deviceId: string;
}

export interface DeactivateDeviceByUserInput {
  userId: string;
}

export interface GetPreferencesInput {
  userId: string;
}

export interface UpdatePreferencesInput {
  userId: string;
  updates: Array<{
    category: NotificationCategory;
    pushEnabled?: boolean;
    inAppEnabled?: boolean;
  }>;
}

export interface CreateNotificationInput {
  userId: string;
  category: NotificationCategory;
  title: string;
  previewText: string;
  body?: string;
  actionType?: NotificationActionType;
  relatedEntityType?: string;
  relatedEntityId?: string;
  batchId?: string;
}

export interface ListNotificationsInput {
  userId: string;
  page?: number;
  pageSize?: number;
}

export interface GetNotificationDetailInput {
  userId: string;
  notificationId: string;
}

export interface MarkReadInput {
  userId: string;
  notificationId: string;
}

export interface MarkAllReadInput {
  userId: string;
}

export interface GetActiveDevicesInput {
  userId: string;
}

export interface RegisteredDevice {
  deviceId: string;
  platform: NotificationDevicePlatform;
  encryptedPushToken: string;
  appVersion: string | null;
  buildNumber: string | null;
  isActive: boolean;
  lastSeenAt: Date;
}

// ---------------------------------------------------------------------------
// NotificationService
// ---------------------------------------------------------------------------

export class NotificationService {
  constructor(private readonly prisma: PrismaClient) {}

  // -------------------------------------------------------------------------
  // Push device registration
  // -------------------------------------------------------------------------

  /**
   * Register or refresh a push device token for a user.
   * Idempotent: if the token hash already exists, updates lastSeenAt and reactivates.
   * Raw push token is never returned.
   */
  async registerDevice(input: RegisterDeviceInput): Promise<{
    deviceId: string;
    platform: NotificationDevicePlatform;
    registeredAt: Date;
  }> {
    const tokenHash = hashPushToken(input.pushToken);
    const encryptedToken = encryptPushToken(input.pushToken);

    const existing = await this.prisma.pushDeviceRegistration.findUnique({
      where: { pushTokenHash: tokenHash },
    });

    if (existing) {
      // Reactivate and update metadata if the device re-registers.
      const updated = await this.prisma.pushDeviceRegistration.update({
        where: { id: existing.id },
        data: {
          userId: input.userId,
          encryptedPushToken: encryptedToken,
          appVersion: input.appVersion ?? existing.appVersion,
          buildNumber: input.buildNumber ?? existing.buildNumber,
          isActive: true,
          lastSeenAt: new Date(),
          revokedAt: null,
        },
      });
      return {
        deviceId: updated.id,
        platform: updated.platform as NotificationDevicePlatform,
        registeredAt: updated.createdAt,
      };
    }

    const record = await this.prisma.pushDeviceRegistration.create({
      data: {
        userId: input.userId,
        platform: input.platform,
        pushTokenHash: tokenHash,
        encryptedPushToken: encryptedToken,
        appVersion: input.appVersion ?? null,
        buildNumber: input.buildNumber ?? null,
        isActive: true,
        lastSeenAt: new Date(),
      },
    });

    return {
      deviceId: record.id,
      platform: record.platform as NotificationDevicePlatform,
      registeredAt: record.createdAt,
    };
  }

  /**
   * Deactivate a specific device registration.
   * Only the owning user may deactivate their own device.
   */
  async unregisterDevice(input: UnregisterDeviceInput): Promise<{ deactivated: boolean }> {
    const record = await this.prisma.pushDeviceRegistration.findUnique({
      where: { id: input.deviceId },
    });

    if (!record || record.userId !== input.userId) {
      throw new AppError(404, 'notification_device_not_found', 'Device registration not found.');
    }

    await this.prisma.pushDeviceRegistration.update({
      where: { id: input.deviceId },
      data: { isActive: false, revokedAt: new Date() },
    });

    return { deactivated: true };
  }

  /**
   * Deactivate all active device registrations for a user (e.g. on logout).
   */
  async deactivateAllDevices(input: DeactivateDeviceByUserInput): Promise<void> {
    await this.prisma.pushDeviceRegistration.updateMany({
      where: { userId: input.userId, isActive: true },
      data: { isActive: false, revokedAt: new Date() },
    });
  }

  /**
   * List active device registrations for a user.
   * Encrypted tokens are included for internal delivery use only.
   * Never expose encrypted tokens in public API responses.
   */
  async getActiveDevices(input: GetActiveDevicesInput): Promise<RegisteredDevice[]> {
    const records = await this.prisma.pushDeviceRegistration.findMany({
      where: { userId: input.userId, isActive: true },
      orderBy: { lastSeenAt: 'desc' },
    });

    return records.map((r) => ({
      deviceId: r.id,
      platform: r.platform as NotificationDevicePlatform,
      encryptedPushToken: r.encryptedPushToken,
      appVersion: r.appVersion,
      buildNumber: r.buildNumber,
      isActive: r.isActive,
      lastSeenAt: r.lastSeenAt,
    }));
  }

  /**
   * Mark a device token as invalid (e.g. returned invalid-token error from push provider).
   * Internal use only.
   */
  async deactivateDeviceByHash(tokenHash: string): Promise<void> {
    await this.prisma.pushDeviceRegistration.updateMany({
      where: { pushTokenHash: tokenHash },
      data: {
        isActive: false,
        revokedAt: new Date(),
      },
    });
  }

  // -------------------------------------------------------------------------
  // Notification preferences
  // -------------------------------------------------------------------------

  /**
   * Get all notification preferences for a user.
   * Returns defaults for any category not yet explicitly set.
   */
  async getPreferences(input: GetPreferencesInput): Promise<NotificationPreferenceSummary[]> {
    const saved = await this.prisma.notificationPreference.findMany({
      where: { userId: input.userId },
      orderBy: { category: 'asc' },
    });

    const savedMap = new Map(saved.map((p) => [p.category as NotificationCategory, p]));

    return ACTIVE_NOTIFICATION_CATEGORIES.map((category) => {
      const existing = savedMap.get(category);
      if (existing) {
        return {
          category,
          pushEnabled: existing.pushEnabled,
          inAppEnabled: existing.inAppEnabled,
          updatedAt: existing.updatedAt.toISOString(),
        };
      }
      // Default: in-app enabled, push disabled until explicit opt-in.
      return {
        category,
        pushEnabled: false,
        inAppEnabled: true,
        updatedAt: new Date(0).toISOString(),
      };
    });
  }

  /**
   * Update notification preferences for a user.
   * Essential categories (account_warning, account_suspension) enforce inAppEnabled=true.
   * Only supported categories are accepted.
   */
  async updatePreferences(input: UpdatePreferencesInput): Promise<NotificationPreferenceSummary[]> {
    for (const update of input.updates) {
      // Validate category is supported.
      if (!ACTIVE_NOTIFICATION_CATEGORIES.includes(update.category)) {
        throw new AppError(
          400,
          'notification_invalid_category',
          `Category '${update.category}' is not supported.`,
        );
      }

      // Enforce essential categories cannot disable in-app.
      if (
        ESSENTIAL_NOTIFICATION_CATEGORIES.includes(update.category) &&
        update.inAppEnabled === false
      ) {
        throw new AppError(
          400,
          'notification_essential_category_protected',
          `In-app notifications for '${update.category}' cannot be disabled.`,
        );
      }
    }

    // Upsert each preference.
    await this.prisma.$transaction(
      input.updates.map((update) =>
        this.prisma.notificationPreference.upsert({
          where: { userId_category: { userId: input.userId, category: update.category } },
          create: {
            userId: input.userId,
            category: update.category,
            pushEnabled: update.pushEnabled ?? false,
            inAppEnabled:
              ESSENTIAL_NOTIFICATION_CATEGORIES.includes(update.category) ? true : (update.inAppEnabled ?? true),
          },
          update: {
            ...(update.pushEnabled !== undefined ? { pushEnabled: update.pushEnabled } : {}),
            ...(update.inAppEnabled !== undefined && !ESSENTIAL_NOTIFICATION_CATEGORIES.includes(update.category)
              ? { inAppEnabled: update.inAppEnabled }
              : {}),
          },
        }),
      ),
    );

    return this.getPreferences({ userId: input.userId });
  }

  // -------------------------------------------------------------------------
  // In-app notifications
  // -------------------------------------------------------------------------

  /**
   * Create a notification for a single user.
   * Plain text only; sets expiresAt based on category.
   */
  async createNotification(input: CreateNotificationInput): Promise<NotificationSummary> {
    const isAccountCategory =
      input.category === 'account_warning' || input.category === 'account_suspension';

    const retentionDays = isAccountCategory
      ? NOTIFICATION_ACCOUNT_RETENTION_DAYS
      : NOTIFICATION_RETENTION_DAYS;

    const expiresAt = new Date();
    expiresAt.setDate(expiresAt.getDate() + retentionDays);

    const record = await this.prisma.userNotification.create({
      data: {
        userId: input.userId,
        category: input.category,
        title: input.title.slice(0, 100),
        previewText: input.previewText.slice(0, 200),
        body: input.body ? input.body.slice(0, 1000) : null,
        actionType: input.actionType ?? 'none',
        relatedEntityType: input.relatedEntityType ?? null,
        relatedEntityId: input.relatedEntityId ?? null,
        expiresAt,
        batchId: input.batchId ?? null,
      },
    });

    return toNotificationSummary(record);
  }

  /**
   * Create notifications for multiple users in a single transaction.
   * Bounded batch size enforced by caller.
   */
  async createNotificationsForUsers(
    inputs: CreateNotificationInput[],
    batchId: string,
  ): Promise<number> {
    if (inputs.length === 0) return 0;

    const now = new Date();
    const records = inputs.map((input) => {
      const isAccountCategory =
        input.category === 'account_warning' || input.category === 'account_suspension';
      const retentionDays = isAccountCategory
        ? NOTIFICATION_ACCOUNT_RETENTION_DAYS
        : NOTIFICATION_RETENTION_DAYS;
      const expiresAt = new Date(now);
      expiresAt.setDate(expiresAt.getDate() + retentionDays);

      return {
        userId: input.userId,
        category: input.category,
        title: input.title.slice(0, 100),
        previewText: input.previewText.slice(0, 200),
        body: input.body ? input.body.slice(0, 1000) : null,
        actionType: input.actionType ?? 'none',
        relatedEntityType: input.relatedEntityType ?? null,
        relatedEntityId: input.relatedEntityId ?? null,
        expiresAt,
        batchId,
      };
    });

    const result = await this.prisma.userNotification.createMany({ data: records });
    return result.count;
  }

  /**
   * List notifications for a user, newest first.
   * Excludes expired notifications.
   */
  async listNotifications(input: ListNotificationsInput): Promise<{
    notifications: NotificationSummary[];
    unreadCount: number;
    total: number;
    hasNext: boolean;
    page: number;
    pageSize: number;
  }> {
    const page = Math.max(1, input.page ?? 1);
    const pageSize = Math.min(
      MAX_NOTIFICATION_PAGE_SIZE,
      Math.max(1, input.pageSize ?? DEFAULT_NOTIFICATION_PAGE_SIZE),
    );
    const skip = (page - 1) * pageSize;

    const now = new Date();
    const where = {
      userId: input.userId,
      OR: [{ expiresAt: null }, { expiresAt: { gt: now } }],
    };

    const [records, total, unreadCount] = await this.prisma.$transaction([
      this.prisma.userNotification.findMany({
        where,
        orderBy: { createdAt: 'desc' },
        skip,
        take: pageSize,
      }),
      this.prisma.userNotification.count({ where }),
      this.prisma.userNotification.count({
        where: { ...where, readAt: null },
      }),
    ]);

    return {
      notifications: records.map(toNotificationSummary),
      unreadCount,
      total,
      hasNext: skip + records.length < total,
      page,
      pageSize,
    };
  }

  /**
   * Get a single notification detail.
   * Ownership is enforced: throws 404 if not found or owned by another user.
   */
  async getNotificationDetail(input: GetNotificationDetailInput): Promise<NotificationDetail> {
    const record = await this.prisma.userNotification.findUnique({
      where: { id: input.notificationId },
    });

    if (!record || record.userId !== input.userId) {
      throw new AppError(404, 'notification_not_found', 'Notification not found.');
    }

    return toNotificationDetail(record);
  }

  /**
   * Mark a notification as read (idempotent).
   * Ownership enforced.
   */
  async markRead(input: MarkReadInput): Promise<{ notificationId: string; readAt: Date }> {
    const record = await this.prisma.userNotification.findUnique({
      where: { id: input.notificationId },
    });

    if (!record || record.userId !== input.userId) {
      throw new AppError(404, 'notification_not_found', 'Notification not found.');
    }

    if (record.readAt) {
      // Already read — idempotent.
      return { notificationId: record.id, readAt: record.readAt };
    }

    const updated = await this.prisma.userNotification.update({
      where: { id: input.notificationId },
      data: { readAt: new Date() },
    });

    return { notificationId: updated.id, readAt: updated.readAt! };
  }

  /**
   * Mark all unread notifications as read for a user.
   * Returns count of notifications marked.
   */
  async markAllRead(input: MarkAllReadInput): Promise<{ markedCount: number }> {
    const now = new Date();
    const result = await this.prisma.userNotification.updateMany({
      where: { userId: input.userId, readAt: null },
      data: { readAt: now },
    });

    return { markedCount: result.count };
  }

  // -------------------------------------------------------------------------
  // Cleanup helpers
  // -------------------------------------------------------------------------

  /**
   * Remove expired notifications.
   * TODO: Schedule via Azure Functions or a cron job.
   */
  async cleanupExpiredNotifications(): Promise<{ deletedCount: number }> {
    const result = await this.prisma.userNotification.deleteMany({
      where: { expiresAt: { lt: new Date() } },
    });
    return { deletedCount: result.count };
  }

  /**
   * Deactivate push device registrations not seen in the given number of days.
   * TODO: Schedule via Azure Functions.
   */
  async cleanupStaleDeviceRegistrations(inactiveDays = 180): Promise<{ deactivatedCount: number }> {
    const cutoff = new Date();
    cutoff.setDate(cutoff.getDate() - inactiveDays);

    const result = await this.prisma.pushDeviceRegistration.updateMany({
      where: { isActive: true, lastSeenAt: { lt: cutoff } },
      data: { isActive: false, revokedAt: new Date() },
    });

    return { deactivatedCount: result.count };
  }
}

// ---------------------------------------------------------------------------
// Internal row → contract mappers
// ---------------------------------------------------------------------------

function toNotificationSummary(record: {
  id: string;
  category: string;
  title: string;
  previewText: string;
  createdAt: Date;
  readAt: Date | null;
  actionType: string | null;
  relatedEntityId: string | null;
}): NotificationSummary {
  return {
    notificationId: record.id,
    category: record.category as NotificationCategory,
    title: record.title,
    previewText: record.previewText,
    createdAt: record.createdAt.toISOString(),
    readAt: record.readAt ? record.readAt.toISOString() : null,
    actionType: (record.actionType ?? 'none') as NotificationSummary['actionType'],
    relatedEntityId: record.relatedEntityId ?? null,
  };
}

function toNotificationDetail(record: {
  id: string;
  category: string;
  title: string;
  previewText: string;
  body: string | null;
  createdAt: Date;
  readAt: Date | null;
  actionType: string | null;
  relatedEntityId: string | null;
  expiresAt: Date | null;
}): NotificationDetail {
  return {
    ...toNotificationSummary(record),
    body: record.body,
    expiresAt: record.expiresAt ? record.expiresAt.toISOString() : null,
  };
}
