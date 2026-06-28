/**
 * Push notification setup for the mobile app.
 *
 * Responsibilities:
 *  - Prepare Android notification channels at app startup.
 *  - Request push permission only after contextual explanation and explicit user action.
 *  - Register the device token with the backend after permission is granted.
 *  - Never request push permission at app startup.
 *  - Never repeatedly prompt after denial.
 *  - Deactivate device registration on logout.
 *
 * Security rules:
 *  - Push tokens must never be logged at any log level.
 *  - Push tokens are sent only to the backend; never stored in plain state or logs.
 *  - Device registration is idempotent and safe to retry.
 *  - Backend validates all eligibility and preferences independently.
 *
 * TODO (production):
 *  - Install and configure expo-notifications.
 *  - Add iOS push entitlement in app.config.ts (aps-environment).
 *  - Add google-services.json to Android project (never commit real credentials).
 *  - Add APNs certificates or Expo push credentials to Azure Key Vault.
 *  - Test on physical iOS and Android devices before production deployment.
 *  - Configure Android notification channels with correct importance levels.
 *  - Handle Expo push token (ExponentPushToken[...]) vs raw APNs/FCM tokens.
 *  - Add notification received listener for foreground notifications.
 *  - Add notification response listener for tap-to-open navigation.
 */

import { Platform } from 'react-native';

import { registerPushDevice, unregisterPushDevice } from '../api/notifications';

// ---------------------------------------------------------------------------
// Android notification channels
// ---------------------------------------------------------------------------

/**
 * Android notification channel definitions.
 * Call setupAndroidNotificationChannels() once at app startup.
 *
 * Channel importance:
 *  - 'events': DEFAULT — event reminders and updates.
 *  - 'account': HIGH — account warnings and suspensions.
 *  - 'system': DEFAULT — system notices and admin messages.
 *
 * TODO: Create channels using expo-notifications setNotificationChannelAsync.
 */
export const ANDROID_CHANNELS = [
  {
    id: 'events',
    name: 'Event', // Swedish visible name
    description: 'Påminnelser och uppdateringar om event.',
    importance: 'default',
  },
  {
    id: 'account',
    name: 'Konto', // Swedish visible name
    description: 'Viktig kontoinformation.',
    importance: 'high',
  },
  {
    id: 'system',
    name: 'System', // Swedish visible name
    description: 'Systemmeddelanden och adminmeddelanden.',
    importance: 'default',
  },
] as const;

/**
 * Prepare Android notification channels.
 * Safe to call on iOS — the Platform guard below prevents any action.
 *
 * TODO: Replace the stub below with expo-notifications setNotificationChannelAsync
 * once expo-notifications is installed and configured.
 */
export async function setupAndroidNotificationChannels(): Promise<void> {
  if (Platform.OS !== 'android') {
    return;
  }

  // TODO: Uncomment and configure once expo-notifications is installed:
  //
  // const Notifications = await import('expo-notifications');
  // for (const channel of ANDROID_CHANNELS) {
  //   await Notifications.setNotificationChannelAsync(channel.id, {
  //     name: channel.name,
  //     description: channel.description,
  //     importance:
  //       channel.importance === 'high'
  //         ? Notifications.AndroidImportance.HIGH
  //         : Notifications.AndroidImportance.DEFAULT,
  //   });
  // }
}

// ---------------------------------------------------------------------------
// Push permission request
// ---------------------------------------------------------------------------

export type PushPermissionStatus = 'granted' | 'denied' | 'undetermined' | 'unavailable';

/**
 * Check current push notification permission status without prompting.
 * In this stub implementation, always returns 'undetermined'.
 *
 * TODO: Replace stub with expo-notifications getPermissionsAsync.
 */
export async function checkPushPermissionStatus(): Promise<PushPermissionStatus> {
  // TODO: Replace with real implementation:
  //
  // const Notifications = await import('expo-notifications');
  // const { status } = await Notifications.getPermissionsAsync();
  // return status;
  //
  // Development placeholder:
  return 'undetermined';
}

/**
 * Request push notification permission.
 * MUST only be called after a contextual explanation to the user.
 * MUST NOT be called at app startup.
 * MUST NOT be called again if the user has already denied permission.
 *
 * Returns the resulting permission status.
 *
 * TODO: Replace stub with expo-notifications requestPermissionsAsync.
 */
export async function requestPushPermission(): Promise<PushPermissionStatus> {
  // TODO: Replace with real implementation:
  //
  // const Notifications = await import('expo-notifications');
  // const { status } = await Notifications.requestPermissionsAsync();
  // return status;
  //
  // Development placeholder — simulate denial for safety:
  return 'denied';
}

// ---------------------------------------------------------------------------
// Device token acquisition and registration
// ---------------------------------------------------------------------------

/**
 * Acquire the Expo push token for this device.
 * Returns null if not available (e.g. simulator, permission not granted).
 *
 * NEVER log the returned token value.
 *
 * TODO: Replace stub with expo-notifications getExpoPushTokenAsync.
 * TODO: Pass the correct project ID / experience name for production Expo credentials.
 */
export async function getExpoPushToken(): Promise<string | null> {
  // TODO: Replace with real implementation:
  //
  // const Notifications = await import('expo-notifications');
  // const { data } = await Notifications.getExpoPushTokenAsync({
  //   projectId: 'your-expo-project-id', // from app.config.ts extra.eas.projectId
  // });
  // return data;
  //
  // Development placeholder:
  return null;
}

/**
 * Register the current device with the backend after push permission is granted.
 *
 * Steps:
 *  1. Acquire the push token (expo-notifications).
 *  2. POST to /v1/notifications/devices with the token.
 *  3. Persist the returned deviceId for later unregistration.
 *
 * Returns the opaque deviceId on success, or null on failure.
 *
 * NEVER log the pushToken value.
 * Backend validates the token format conservatively.
 * Registration is idempotent.
 */
export async function registerDeviceForPushNotifications(
  sessionToken: string,
  appVersion?: string,
): Promise<string | null> {
  const pushToken = await getExpoPushToken();

  if (!pushToken) {
    // Token not available (simulator, permission not granted, or setup not complete).
    return null;
  }

  const platform: 'ios' | 'android' = Platform.OS === 'ios' ? 'ios' : 'android';

  try {
    // Never log pushToken — send directly to backend.
    const result = await registerPushDevice(
      {
        platform,
        pushToken,
        appVersion,
      },
      sessionToken,
    );

    return result.data.deviceId;
  } catch {
    // Registration failure is non-fatal — in-app notifications continue regardless.
    return null;
  }
}

/**
 * Deactivate the device registration on logout.
 * Call this when the user explicitly logs out.
 *
 * deviceId was returned from registerDeviceForPushNotifications and
 * should be stored securely (e.g. in SecureStore) alongside the session token.
 */
export async function deactivateDeviceRegistration(
  deviceId: string,
  sessionToken: string,
): Promise<void> {
  try {
    await unregisterPushDevice(deviceId, sessionToken);
  } catch {
    // Non-fatal — stale tokens are deactivated by the backend cleanup job.
  }
}
