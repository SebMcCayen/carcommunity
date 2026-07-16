/**
 * Notifications domain — constants, pure logic, and builders (Phase 9l).
 *
 * Ports packages/shared/src/notifications.ts and the pure parts of the
 * legacy notification-service.ts to the Firestore model:
 *
 * - `notifications/{uid}/items/{notificationId}` — the durable in-app
 *   inbox. Owner-only read; ALL writes are backend-only (delivery via the
 *   writeInAppNotification writer, read-state via the notifications.markRead
 *   / markAllRead callables), so the backend stays the sole authority for
 *   notification eligibility and content (legacy design rule).
 * - `userPrivate/{uid}/pushTokens/{tokenId}` — push token registrations.
 *   Per the migration mapping, only the SHA-256 token hash is stored (the
 *   hash IS the document ID, making registration idempotent); the raw FCM
 *   token is never persisted, logged, or returned. Actual FCM delivery
 *   (`sendPushNotification`) ships with the Firebase console/FCM setup at
 *   the end of the MVP.
 * - Delivery eligibility (legacy invariants): deleted users receive
 *   nothing; suspended users receive ONLY the essential account notices;
 *   users may opt out per category via
 *   `userPrivate/{uid}.notificationPreferences`, but the essential
 *   categories (account_warning, account_suspension) cannot be disabled —
 *   enforced at delivery time since the backend is the only writer.
 * - Retention (backend-domain-mapping.md, deliberately simplified from the
 *   legacy 90/365-day scheme): unread items are kept 30 days, read items
 *   7 days; the scheduled notifications-cleanupExpired function deletes
 *   the rest.
 *
 * Pure module — no Firebase Admin SDK imports.
 */

import { createHash } from 'node:crypto';
import { z } from 'zod';
import { isRestricted, type UserAccessState } from '../shared/access';

// ---------------------------------------------------------------------------
// Enums and limits (packages/shared/src/notifications.ts)
// ---------------------------------------------------------------------------

/**
 * The closed set of categories the notification domain recognizes: a value
 * must appear here before a producer can deliver under it or a preference can
 * be expressed for it. Membership is a precondition for delivery, not
 * evidence of it — being listed does NOT mean the category has an active
 * delivery surface. Some entries are preference-only: the id exists so the
 * opt-out is expressible and stable ahead of the producer that will use it
 * (the note below records which have producers today).
 *
 * The legacy contract also defines FUTURE categories (partner_offer,
 * event_chat, nearby_event) that must not be activated without product and
 * security review — they are deliberately NOT accepted here.
 *
 * The social categories (direct_message, community_chat, convoy_chat,
 * friend_request, convoy_invite) back the per-category preferences for the
 * chat/social features. The same review rule applies: they exist so users
 * can opt out and so producers have a category to check before writing, but
 * activating them as a delivery surface needs the product/security review
 * this domain mandates.
 *
 * Producers today (all IN-APP only — no push path is wired):
 *  - convoy_invite   — convoy/manageConvoy.ts (invite)
 *  - direct_message  — dm/manageDirectMessages.ts (sendMessage; first message
 *                      of an unread run only)
 *  - friend_request  — friends/manageFriends.ts (new request → invitee;
 *                      accept → requester; a decline is silent)
 *  - convoy_chat     — chatchannels/convoyChat.ts (post; fan-out to the other
 *                      accepted members, collapsed per time window)
 *  - community_chat  — NO producer, deliberately. Per-message fan-out to every
 *                      active member is a spam/cost non-starter; the category
 *                      is held for an @mentions or digest design. See the
 *                      chatchannels/communityChat.ts header.
 */
export const NOTIFICATION_CATEGORIES = [
  'event_reminder',
  'event_updated',
  'event_cancelled',
  'admin_message',
  'account_warning',
  'account_suspension',
  'subscription_status',
  'system_notice',
  'direct_message',
  'community_chat',
  'convoy_chat',
  'friend_request',
  'convoy_invite',
] as const;
export type NotificationCategory = (typeof NOTIFICATION_CATEGORIES)[number];

/**
 * Social categories: member-to-member activity. All optional (never
 * essential) — a user must always be able to silence other members.
 */
export const SOCIAL_NOTIFICATION_CATEGORIES: readonly NotificationCategory[] = [
  'direct_message',
  'community_chat',
  'convoy_chat',
  'friend_request',
  'convoy_invite',
] as const;

/**
 * Categories notifications.adminSend may broadcast — deliberately NOT all of
 * NOTIFICATION_CATEGORIES. The social categories describe activity between
 * members, so accepting one from adminSend would let a broadcast impersonate
 * a DM / friend request / convoy invite the recipient never received. Admin
 * sends stay on the operational categories; social notices are producer-only.
 */
export const ADMIN_SENDABLE_CATEGORIES = [
  'event_reminder',
  'event_updated',
  'event_cancelled',
  'admin_message',
  'account_warning',
  'account_suspension',
  'subscription_status',
  'system_notice',
] as const;
export type AdminSendableCategory = (typeof ADMIN_SENDABLE_CATEGORIES)[number];

/**
 * Essential account notices that can never be disabled in-app (legacy:
 * legally/operationally necessary) and are the only categories delivered
 * to suspended users.
 */
export const ESSENTIAL_NOTIFICATION_CATEGORIES: readonly NotificationCategory[] = [
  'account_warning',
  'account_suspension',
] as const;

export const NOTIFICATION_ACTION_TYPES = [
  'open_notifications',
  'open_event',
  'open_profile',
  'open_subscription',
  'open_settings',
  'none',
] as const;
export type NotificationActionType = (typeof NOTIFICATION_ACTION_TYPES)[number];

export const PUSH_TOKEN_PLATFORMS = ['android', 'ios'] as const;
export type PushTokenPlatform = (typeof PUSH_TOKEN_PLATFORMS)[number];

export const MAX_NOTIFICATION_TITLE_LENGTH = 100;
export const MAX_NOTIFICATION_PREVIEW_LENGTH = 200;
export const MAX_NOTIFICATION_BODY_LENGTH = 1000;

/**
 * Retention windows (backend-domain-mapping.md): "retain unread for
 * 30 days, read for 7 days". A deliberate simplification of the legacy
 * 90-day / 365-day expiresAt scheme.
 */
export const UNREAD_RETENTION_DAYS = 30;
export const READ_RETENTION_DAYS = 7;

/** Feature flag key (contracts/features/feature-flags.json), default true. */
export const PUSH_NOTIFICATIONS_FLAG_KEY = 'pushNotifications';
export const PUSH_NOTIFICATIONS_FLAG_DEFAULT = true;

// ---------------------------------------------------------------------------
// Push token hashing
// ---------------------------------------------------------------------------

/**
 * SHA-256 hex of the raw push token — the ONLY representation ever stored
 * (doubles as the pushTokens document ID for idempotent registration).
 */
export function hashPushToken(token: string): string {
  return createHash('sha256').update(token, 'utf8').digest('hex');
}

// ---------------------------------------------------------------------------
// Inputs
// ---------------------------------------------------------------------------

const firestoreIdSchema = z
  .string()
  .trim()
  .min(1)
  .max(300)
  .regex(/^[A-Za-z0-9._-]+$/)
  .refine((id) => id !== '.' && id !== '..');

const markNotificationReadInputSchema = z
  .object({ notificationId: firestoreIdSchema })
  .strict();

const registerPushTokenInputSchema = z
  .object({
    token: z.string().min(1).max(4096),
    platform: z.enum(PUSH_TOKEN_PLATFORMS),
    appVersion: z.string().trim().min(1).max(50).optional(),
    buildNumber: z.string().trim().min(1).max(50).optional(),
  })
  .strict();

const unregisterPushTokenInputSchema = z
  .object({ tokenId: z.string().regex(/^[a-f0-9]{64}$/) })
  .strict();

export type MarkNotificationReadInput = z.infer<typeof markNotificationReadInputSchema>;
export type RegisterPushTokenInput = z.infer<typeof registerPushTokenInputSchema>;
export type UnregisterPushTokenInput = z.infer<typeof unregisterPushTokenInputSchema>;

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

function parse<T>(schema: z.ZodType<T>, data: unknown, expected: string): ParseResult<T> {
  const result = schema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: expected };
  }
  return { ok: true, input: result.data };
}

export const parseMarkNotificationReadInput = (d: unknown) =>
  parse(markNotificationReadInputSchema, d, 'Expected { notificationId }.');
export const parseRegisterPushTokenInput = (d: unknown) =>
  parse(
    registerPushTokenInputSchema,
    d,
    `Expected { token, platform: ${PUSH_TOKEN_PLATFORMS.join('|')}, appVersion?, buildNumber? }.`,
  );
export const parseUnregisterPushTokenInput = (d: unknown) =>
  parse(
    unregisterPushTokenInputSchema,
    d,
    'Expected { tokenId } (the 64-hex tokenId returned by registerPushToken).',
  );

// ---------------------------------------------------------------------------
// Delivery eligibility (legacy NotificationService invariants)
// ---------------------------------------------------------------------------

export function isEssentialCategory(category: NotificationCategory): boolean {
  return ESSENTIAL_NOTIFICATION_CATEGORIES.includes(category);
}

export type DeliveryDecision =
  | { deliver: true }
  | { deliver: false; reason: 'deleted' | 'suspended' | 'opted_out' };

/**
 * Whether an in-app notification may be written for this recipient.
 * Preferences are the owner-writable map `userPrivate/{uid}
 * .notificationPreferences` ({ [category]: { inApp?, push? } }); since the
 * backend is the only inbox writer, the essential-categories-cannot-be-
 * disabled rule is enforced HERE rather than in security rules.
 */
export function decideInAppDelivery(
  category: NotificationCategory,
  recipientState: UserAccessState,
  preferences: unknown,
): DeliveryDecision {
  if (recipientState.deleted) {
    return { deliver: false, reason: 'deleted' };
  }
  if (isEssentialCategory(category)) {
    // Essential account notices ignore suspension AND preferences.
    return { deliver: true };
  }
  if (isRestricted(recipientState)) {
    return { deliver: false, reason: 'suspended' };
  }
  const entry =
    preferences && typeof preferences === 'object'
      ? (preferences as Record<string, unknown>)[category]
      : undefined;
  if (entry && typeof entry === 'object' && (entry as Record<string, unknown>).inApp === false) {
    return { deliver: false, reason: 'opted_out' };
  }
  return { deliver: true };
}

// ---------------------------------------------------------------------------
// Document builders
// ---------------------------------------------------------------------------

export interface InAppNotificationInput {
  category: NotificationCategory;
  title: string;
  previewText: string;
  body?: string | null;
  actionType?: NotificationActionType;
  relatedEntityId?: string | null;
  batchId?: string | null;
}

/**
 * notifications/{uid}/items/{notificationId} document. Content is truncated
 * to the legacy limits (plain text only — no HTML).
 */
export function buildNotificationDocument(
  input: InAppNotificationInput,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    category: input.category,
    title: input.title.slice(0, MAX_NOTIFICATION_TITLE_LENGTH),
    previewText: input.previewText.slice(0, MAX_NOTIFICATION_PREVIEW_LENGTH),
    body: input.body ? input.body.slice(0, MAX_NOTIFICATION_BODY_LENGTH) : null,
    actionType: input.actionType ?? 'none',
    relatedEntityId: input.relatedEntityId ?? null,
    batchId: input.batchId ?? null,
    read: false,
    readAt: null,
    createdAt: serverTimestamp(),
  };
}

/** userPrivate/{uid}/pushTokens/{tokenId} document — never the raw token. */
export function buildPushTokenDocument(
  input: RegisterPushTokenInput,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    platform: input.platform,
    appVersion: input.appVersion ?? null,
    buildNumber: input.buildNumber ?? null,
    createdAt: serverTimestamp(),
    lastSeenAt: serverTimestamp(),
  };
}

// ---------------------------------------------------------------------------
// Retention cutoffs (scheduled cleanup)
// ---------------------------------------------------------------------------

const DAY_MS = 24 * 60 * 60 * 1000;

/** Read items with readAt before this instant are deleted. */
export function readRetentionCutoff(now: Date): Date {
  return new Date(now.getTime() - READ_RETENTION_DAYS * DAY_MS);
}

/** Unread items created before this instant are deleted. */
export function unreadRetentionCutoff(now: Date): Date {
  return new Date(now.getTime() - UNREAD_RETENTION_DAYS * DAY_MS);
}
