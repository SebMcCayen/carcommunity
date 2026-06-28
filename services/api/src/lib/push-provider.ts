/**
 * Push notification provider abstraction.
 *
 * This interface decouples the notification delivery service from any specific
 * push provider (APNs, FCM, Expo Push Service, etc.).
 *
 * Design rules:
 *  - Push payloads must contain minimal data: notification ID, category, safe title, safe preview,
 *    and an internal action type. No protected details.
 *  - No exact event coordinates in push payloads.
 *  - No live location data in push payloads.
 *  - No private chat content.
 *  - No arbitrary external URLs.
 *  - No discount codes.
 *  - Raw push tokens must never be logged at any level.
 *  - The development provider is a safe no-op that logs a redacted message.
 *
 * TODO (production):
 *  - Replace DevPushNotificationProvider with the Expo Push Service provider
 *    (see https://docs.expo.dev/push-notifications/sending-notifications/) after security review.
 *  - Or integrate APNs and FCM directly via HTTP/2 APIs after reviewing token handling requirements.
 *  - Add iOS capabilities (push notifications entitlement) in app.config.ts / Xcode project.
 *  - Add Android notification channels and google-services.json (never commit real credentials).
 *  - Add APNs or Expo push credentials to Azure Key Vault; never commit to source control.
 *  - Run physical-device tests before any production deployment.
 */

import type { NotificationCategory } from '@carcommunity/shared/notifications';

// ---------------------------------------------------------------------------
// Push payload
// ---------------------------------------------------------------------------

/**
 * Minimal push notification payload.
 *
 * Protected details must never appear in this payload; they are loaded by the app
 * after opening from the authenticated in-app notification screen.
 */
export interface PushPayload {
  /** Opaque notification ID — used by the mobile app to load details from the API. */
  notificationId: string;
  category: NotificationCategory;
  /** Safe Swedish title. No moderation details, location, or private data. */
  title: string;
  /** Safe Swedish preview. No protected details. Max 200 chars. */
  previewText: string;
  /** Internal action type for routing. Must be from the allowlisted set. */
  actionType: string;
}

// ---------------------------------------------------------------------------
// Provider interface
// ---------------------------------------------------------------------------

export type PushSendResult =
  | { success: true; providerMessageId?: string }
  | { success: false; safeErrorCode: string; shouldDeactivateToken?: boolean };

/**
 * Provider-independent push notification interface.
 * Implement this for each provider (Expo, APNs, FCM).
 */
export interface PushNotificationProvider {
  /**
   * Send a push notification to a single encrypted device token.
   *
   * @param encryptedToken - Encrypted push token retrieved from the database.
   *   Decrypt internally; never log the decrypted value.
   * @param payload - Minimal safe push payload.
   */
  sendPushNotification(encryptedToken: string, payload: PushPayload): Promise<PushSendResult>;
}

// ---------------------------------------------------------------------------
// Development / no-op provider
// ---------------------------------------------------------------------------

/**
 * Development-only no-op push provider.
 *
 * Logs a redacted message (no token values) and returns success.
 * This is the default provider in non-production environments.
 *
 * TODO: Replace with a real provider for production deployment.
 *
 * NEVER use this provider in production. The caller should ensure the
 * `pushNotifications` feature flag is checked before calling this.
 */
export class DevPushNotificationProvider implements PushNotificationProvider {
  async sendPushNotification(
    _encryptedToken: string,
    payload: PushPayload,
  ): Promise<PushSendResult> {
    if (process.env.NODE_ENV === 'production') {
      return { success: false, safeErrorCode: 'dev_push_provider_not_allowed_in_production' };
    }

    // Intentionally not logging here to keep notification content out of logs.

    return { success: true };
  }
}
