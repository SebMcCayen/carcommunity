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
 * - `userPrivate/{uid}/pushTokens/{tokenId}` — push token registrations, one
 *   per device. The SHA-256 token hash IS the document ID (making
 *   registration idempotent) and the raw FCM token is stored in the document,
 *   because FCM addresses a device by the token itself. The raw token is never
 *   logged or returned, and the collection is client-inaccessible.
 * - Push delivery: the `notifications-onNotificationCreated` Firestore trigger
 *   (notifications/sendPush.ts) pushes each inbox item to those devices. It
 *   hangs off the inbox write so push INHERITS the eligibility decision below
 *   rather than duplicating it — see decidePushDelivery.
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
 * Producers today. Each writes the in-app inbox item; the
 * notifications-onNotificationCreated trigger turns that item into a push, so
 * no producer sends push directly and none can bypass the opt-outs:
 *  - convoy_invite   — convoy/manageConvoy.ts (invite)
 *  - direct_message  — dm/manageDirectMessages.ts (sendMessage; first message
 *                      of an unread run only)
 *  - friend_request  — friends/manageFriends.ts (new request → invitee;
 *                      accept → requester; a decline is silent)
 *  - convoy_chat     — chatchannels/convoyChat.ts (post; fan-out to the other
 *                      accepted members, collapsed per time window)
 *  - community_chat  — chatchannels/communityChat.ts (post; @MENTIONS ONLY —
 *                      the members a message explicitly names, at most
 *                      MAX_MESSAGE_MENTIONS, collapsed per sender per window).
 *                      A message with no mentions notifies NOBODY: per-message
 *                      fan-out to every active member is a spam/cost
 *                      non-starter. See the communityChat.ts header.
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
 * SHA-256 hex of the raw push token — the pushTokens document ID, which makes
 * registration idempotent (re-registering the same token hits the same doc).
 *
 * NOTE: this used to be the ONLY representation stored. It could not stay that
 * way: FCM addresses a device BY the raw token, so a hash-only registry can
 * never actually send. The raw token now lives in the document's `token` field
 * (see buildPushTokenDocument) and the hash is demoted to what it is good at —
 * a stable, collision-free document ID. The compensating controls are in the
 * security rules (`pushTokens` is now fully client-inaccessible) rather than in
 * the storage format.
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

export type PushDeliveryDecision =
  | { deliver: true; includePreview: boolean }
  | { deliver: false; reason: 'deleted' | 'suspended' | 'opted_out' | 'push_opted_out' };

/**
 * Whether a PUSH may be sent for a notification that is being written to the
 * in-app inbox.
 *
 * This deliberately does NOT re-implement eligibility: it CALLS
 * decideInAppDelivery first and inherits its answer verbatim. Push is strictly
 * a subset of in-app — if a member does not get the inbox item (deleted,
 * suspended, or `inApp: false` for the category) they can never get a push for
 * it. Only after that passes is the push-specific `push: false` opt-out
 * consulted. Keeping the two decisions nested rather than parallel is the whole
 * point: a member who silenced `convoy_chat` silences it everywhere, and any
 * future category or eligibility rule added to decideInAppDelivery governs push
 * automatically without a second edit.
 *
 * `includePreview` carries the lock-screen content decision (see
 * pushPreviewsEnabled).
 */
export function decidePushDelivery(
  category: NotificationCategory,
  recipientState: UserAccessState,
  preferences: unknown,
): PushDeliveryDecision {
  const inApp = decideInAppDelivery(category, recipientState, preferences);
  if (!inApp.deliver) {
    return { deliver: false, reason: inApp.reason };
  }
  const entry =
    preferences && typeof preferences === 'object'
      ? (preferences as Record<string, unknown>)[category]
      : undefined;
  // Essential account notices ignore the push opt-out exactly as
  // decideInAppDelivery ignores the in-app one.
  if (
    !isEssentialCategory(category) &&
    entry &&
    typeof entry === 'object' &&
    (entry as Record<string, unknown>).push === false
  ) {
    return { deliver: false, reason: 'push_opted_out' };
  }
  return { deliver: true, includePreview: pushPreviewsEnabled(preferences) };
}

/**
 * Whether message previews may appear in the push payload — i.e. on a LOCK
 * SCREEN, where anyone holding the phone can read them.
 *
 * Decision for this slice: previews are ON by default. The content shown is the
 * already-truncated `previewText` the inbox item carries (<=200 chars), and
 * defaulting to silent "New message" notifications for a car-community social
 * app makes the feature substantially less useful than the platform norm every
 * member is used to.
 *
 * The READ side of the escape hatch ships now even though no UI writes it: an
 * optional boolean `userPrivate/{uid}.notificationPreferences.pushPreviews`,
 * absent === true. A member who wants titles-only on the lock screen therefore
 * needs an Android toggle writing one boolean, not a server change — which is
 * what keeps the eventual setting cheap instead of impossible.
 */
export function pushPreviewsEnabled(preferences: unknown): boolean {
  if (!preferences || typeof preferences !== 'object') {
    return true;
  }
  return (preferences as Record<string, unknown>).pushPreviews !== false;
}

// ---------------------------------------------------------------------------
// Deep links
// ---------------------------------------------------------------------------

/**
 * Where tapping a push should land. Values mirror screens the Android shell
 * ALREADY has (ShellRoute / ChatHub tabs) — this is a naming of existing
 * destinations, not a new navigation graph.
 */
export const PUSH_DEEP_LINK_TARGETS = [
  'dm',
  'community_chat',
  'convoy_chat',
  'convoys',
  'friends',
  'event',
  'subscription',
  'notifications',
] as const;
export type PushDeepLinkTarget = (typeof PUSH_DEEP_LINK_TARGETS)[number];

export interface PushDeepLink {
  target: PushDeepLinkTarget;
  /** Entity the target needs (otherUid, convoyId, eventId); null when none. */
  entityId: string | null;
}

/**
 * Derives the tap destination from the category + the `relatedEntityId` that
 * producers ALREADY write — no producer changes and no new wire field.
 *
 * The one non-obvious case is direct_message, whose relatedEntityId is the
 * conversation pairId (`uidA__uidB`, sorted). The Android DM screen opens by
 * the OTHER member's uid, so the recipient's own uid is subtracted out here.
 */
export function buildPushDeepLink(
  category: NotificationCategory,
  relatedEntityId: string | null | undefined,
  recipientUid: string,
): PushDeepLink {
  switch (category) {
    case 'direct_message': {
      // Strict: the pairId must split into EXACTLY two parts, one of which is
      // the recipient — then the other is the counterpart. A loose
      // "first segment that isn't me" search would, given anything that is not
      // a well-formed pairId, hand back an arbitrary segment as a uid and
      // deep-link the member into a stranger's thread.
      //
      // dm-core.ts states UIDs are alphanumeric so `__` is unambiguous, and
      // today that holds (Firebase auto-generates 28-char alphanumeric ids and
      // nothing here mints custom uids) — but its own uidSchema is
      // `z.string().max(128)` with no character class, so the property is
      // asserted rather than enforced. This parse does not depend on it: an
      // ambiguous split yields the wrong PART COUNT and falls back safely.
      const parts = relatedEntityId ? relatedEntityId.split('__') : [];
      const otherUid =
        parts.length === 2 && parts.includes(recipientUid)
          ? (parts.find((part) => part !== recipientUid) ?? null)
          : null;
      // Without a resolvable counterpart the thread cannot be opened; the
      // conversation list is the closest correct destination.
      return otherUid ? { target: 'dm', entityId: otherUid } : { target: 'dm', entityId: null };
    }
    case 'convoy_chat':
      return { target: 'convoy_chat', entityId: relatedEntityId ?? null };
    case 'community_chat':
      // relatedEntityId is the message id; the community channel has no
      // per-message anchor, so the channel itself is the destination.
      return { target: 'community_chat', entityId: null };
    case 'convoy_invite':
      return { target: 'convoys', entityId: null };
    case 'friend_request':
      return { target: 'friends', entityId: relatedEntityId ?? null };
    case 'event_reminder':
    case 'event_updated':
    case 'event_cancelled':
      return { target: 'event', entityId: relatedEntityId ?? null };
    case 'subscription_status':
      return { target: 'subscription', entityId: null };
    default:
      return { target: 'notifications', entityId: null };
  }
}

// ---------------------------------------------------------------------------
// FCM payload
// ---------------------------------------------------------------------------

export interface PushPayloadInput {
  category: NotificationCategory;
  title: string;
  previewText: string;
  notificationId: string;
  relatedEntityId?: string | null;
  recipientUid: string;
  includePreview: boolean;
}

/**
 * The FCM `data` map for one notification.
 *
 * DATA-ONLY on purpose — no `notification` block. A `notification` block is
 * rendered by the system while the app is backgrounded, which would defeat both
 * per-category channel routing and the "don't notify me about the chat I am
 * staring at" suppression: the client must own display in every state. All
 * values are strings (an FCM data map cannot hold anything else).
 */
export function buildPushPayload(input: PushPayloadInput): Record<string, string> {
  const link = buildPushDeepLink(input.category, input.relatedEntityId, input.recipientUid);
  const payload: Record<string, string> = {
    category: input.category,
    title: input.title.slice(0, MAX_NOTIFICATION_TITLE_LENGTH),
    notificationId: input.notificationId,
    target: link.target,
  };
  if (input.includePreview && input.previewText) {
    payload.previewText = input.previewText.slice(0, MAX_NOTIFICATION_PREVIEW_LENGTH);
  }
  if (link.entityId) {
    payload.entityId = link.entityId;
  }
  return payload;
}

/**
 * FCM send errors that mean the token is permanently dead and must be dropped
 * from the registry. Anything else (quota, transient unavailability) is a
 * retryable condition and must NOT delete a live registration.
 */
const DEAD_TOKEN_ERROR_CODES = new Set([
  'messaging/registration-token-not-registered',
  'messaging/invalid-registration-token',
  'messaging/invalid-argument',
]);

export function isDeadTokenError(code: string | undefined | null): boolean {
  return code ? DEAD_TOKEN_ERROR_CODES.has(code) : false;
}

/** FCM caps a single multicast at 500 tokens. */
export const FCM_MULTICAST_LIMIT = 500;

export function chunkTokens<T>(items: readonly T[], size = FCM_MULTICAST_LIMIT): T[][] {
  if (size < 1) {
    throw new Error('chunk size must be >= 1');
  }
  const out: T[][] = [];
  for (let i = 0; i < items.length; i += size) {
    out.push(items.slice(i, i + size));
  }
  return out;
}

/**
 * How many device registrations one member may hold.
 *
 * WHY A CAP AT ALL
 * ----------------
 * The document ID is the token hash, so registration is idempotent per device
 * — but the token is client-supplied, so the number of DISTINCT tokens a uid
 * can create is bounded only by how many times the client calls the callable.
 * Without a cap, `userPrivate/{uid}/pushTokens` grows without limit, and every
 * inbox item for that uid then pays for it twice: the send trigger reads the
 * whole collection, and the prune pass writes against it.
 *
 * The cap turns that unbounded fan-out into a constant. It also bounds the
 * prune batch and the FCM multicast, so neither can grow into a request-size
 * problem no matter what a client does.
 *
 * 12 is deliberately generous: a member with a phone, a tablet and a head unit
 * uses three, and the rest is slack for reinstalls and OS-level token rotation
 * (each of which mints a token that the old device never unregisters).
 */
export const MAX_PUSH_TOKENS_PER_USER = 12;

/** A registry row considered for eviction: its id and when it last checked in. */
export interface PushTokenEvictionCandidate {
  tokenId: string;
  /** Epoch millis of lastSeenAt, or null when the row predates that field. */
  lastSeenAtMs: number | null;
}

/**
 * Which existing registrations must go so that adding ONE new token leaves the
 * member at or under `limit`.
 *
 * Least-recently-seen first: the token most likely to be dead is the one whose
 * device has not checked in for longest. Rows with no `lastSeenAt` are legacy
 * and sort oldest — they are also the unsendable hash-only rows, so evicting
 * them first is doubly correct. Ties break on tokenId purely so the result is
 * deterministic (and therefore testable).
 */
export function selectEvictableTokenIds(
  existing: readonly PushTokenEvictionCandidate[],
  limit = MAX_PUSH_TOKENS_PER_USER,
): string[] {
  if (limit < 1) {
    throw new Error('limit must be >= 1');
  }
  // +1 for the token about to be written.
  const overflow = existing.length + 1 - limit;
  if (overflow <= 0) {
    return [];
  }
  return [...existing]
    .sort((a, b) => {
      const aSeen = a.lastSeenAtMs ?? Number.NEGATIVE_INFINITY;
      const bSeen = b.lastSeenAtMs ?? Number.NEGATIVE_INFINITY;
      if (aSeen !== bSeen) return aSeen - bSeen;
      return a.tokenId < b.tokenId ? -1 : a.tokenId > b.tokenId ? 1 : 0;
    })
    .slice(0, overflow)
    .map((entry) => entry.tokenId);
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

/**
 * userPrivate/{uid}/pushTokens/{tokenId} document.
 *
 * Stores the RAW FCM token in `token` — FCM has no way to address a device by
 * a hash, so the previous hash-only registry made sending impossible. The
 * token is treated as personal data (a device identifier):
 *  - `pushTokens` denies ALL client access in firebase/firestore.rules (the
 *    client already knows its own token from the FCM SDK and never needs to
 *    read the registry back), so the raw token is reachable only by the Admin
 *    SDK.
 *  - It is never logged or returned by a callable — registerPushToken still
 *    responds with the tokenId hash only.
 *  - It is erased with the rest of `userPrivate/{uid}` on account deletion
 *    (functions/src/account/scheduled.ts recursively deletes the subcollection).
 */
export function buildPushTokenDocument(
  input: RegisterPushTokenInput,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    token: input.token,
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
