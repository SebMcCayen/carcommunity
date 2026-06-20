/**
 * Background location task for live location sharing.
 *
 * This module MUST be imported before any navigator renders so that
 * TaskManager.defineTask() is called at module scope — a requirement of
 * expo-task-manager. The import in index.ts satisfies this.
 *
 * NOTE: Background location requires a custom development build or EAS build.
 *       It does NOT work in Expo Go. Run `npx expo prebuild` or `eas build`
 *       before testing this feature on a device.
 *
 * TODO (physical device): Validate background location behavior on a real
 *   iOS and Android device before releasing.
 * TODO (App Store): Review Apple's App Store privacy requirements for the
 *   NSLocationAlwaysAndWhenInUseUsageDescription before App Store submission.
 * TODO (App Store): Verify that background location usage is described
 *   accurately in the App Store privacy nutrition label.
 *
 * Privacy:
 *  - Updates only the latest position — no route history is accumulated.
 *  - Coordinates are never logged.
 *  - Session is verified against local expiry on every update.
 *  - Task stops immediately when the session ends or expires.
 */

import * as ExpoLocation from 'expo-location';
import * as TaskManager from 'expo-task-manager';

import {
  clearLiveLocationSession,
  isSessionExpired,
  loadLiveLocationSession,
} from '../storage/liveLocationStorage';
import { loadSessionToken } from '../storage/tokenStorage';

/** Centralised task name — must match across defineTask, start, and stop calls. */
export const BACKGROUND_LOCATION_TASK_NAME = 'carcommunity-background-location';

// Throttle — same thresholds as the foreground watcher.
const BACKGROUND_INTERVAL_MS = 5000; // 5 seconds
const BACKGROUND_DISTANCE_M = 25; // 25 metres

/**
 * Android foreground service notification strings.
 *
 * These defaults are used when the caller cannot supply i18n-translated
 * strings (e.g. session restoration on mount). Callers that have i18n
 * access (e.g. the hook's requestBackgroundPermission path) should supply
 * translated strings via the options parameter.
 *
 * Note: app.config.ts also declares foregroundService strings for the
 * native build plugin. Those are build-time strings and cannot use runtime
 * i18n. Both sets should be kept in sync.
 */
const DEFAULT_NOTIFICATION_TITLE = 'Live location sharing is active';
const DEFAULT_NOTIFICATION_BODY =
  'Your location is being shared during the active time-limited session.';

/**
 * Idempotent guard: only call defineTask once per JS runtime.
 * Hot reloads in development may re-import modules; the guard prevents
 * re-registering an already-defined task.
 */
if (!TaskManager.isTaskDefined(BACKGROUND_LOCATION_TASK_NAME)) {
  TaskManager.defineTask(
    BACKGROUND_LOCATION_TASK_NAME,
    async ({
      data,
      error,
    }: TaskManager.TaskManagerTaskBody<{ locations: ExpoLocation.LocationObject[] }>) => {
      if (error) {
        // Non-sensitive diagnostic — do not log coordinates or tokens.
        console.warn('Background location task: error received; session remains active.');
        return;
      }

      const locations = data?.locations;
      if (!locations || locations.length === 0) return;

      // Read session reference from secure storage.
      const session = await loadLiveLocationSession().catch(() => null);
      if (!session) {
        // No active session — stop the task.
        await stopBackgroundLocationUpdates().catch(() => undefined);
        return;
      }

      if (isSessionExpired(session)) {
        // Session has expired — stop locally. Backend enforces expiry independently.
        await clearLiveLocationSession().catch(() => undefined);
        await stopBackgroundLocationUpdates().catch(() => undefined);
        return;
      }

      // Use only the most recent location — never accumulate history.
      const latest = locations[locations.length - 1];
      if (!latest) return;

      // Do not log coordinates.
      const coordinate = {
        latitude: latest.coords.latitude,
        longitude: latest.coords.longitude,
        accuracyMeters: latest.coords.accuracy ?? undefined,
        headingDegrees: latest.coords.heading ?? undefined,
        speedMetersPerSecond: latest.coords.speed ?? undefined,
        recordedAt: new Date(latest.timestamp).toISOString(),
      };

      // Send latest position to backend.
      // On auth/not-found errors, stop locally. On transient failures, skip — no retry loop.
      try {
        const auth = await loadSessionToken().catch(() => null);
        if (!auth?.token) {
          await clearLiveLocationSession().catch(() => undefined);
          await stopBackgroundLocationUpdates().catch(() => undefined);
          return;
        }

        const base = session.apiBaseUrl.replace(/\/$/, '');
        const path = `/v1/live-location/sessions/${session.sessionId}/position`;
        const response = await fetch(`${base}${path}`, {
          method: 'POST',
          headers: {
            'content-type': 'application/json',
            authorization: 'Bearer ' + auth.token,
          },
          body: JSON.stringify({ coordinate }),
        });

        if (response.status === 401 || response.status === 403 || response.status === 404) {
          // Session is no longer valid on the backend — stop locally and clean up.
          await clearLiveLocationSession().catch(() => undefined);
          await stopBackgroundLocationUpdates().catch(() => undefined);
        }
        // Other errors (5xx, network) are silently ignored — no retry loop.
      } catch {
        // Network failure — fail safely. Do not retain location history for retry.
      }
    },
  );
}

/**
 * Start background location updates for an active sharing session.
 *
 * Idempotent: returns true without starting a duplicate task if updates are
 * already running.
 *
 * @param notificationTitle - Android foreground service notification title (should be i18n-translated by caller).
 * @param notificationBody  - Android foreground service notification body (should be i18n-translated by caller).
 * @returns true if background updates are now running, false on failure.
 */
export async function startBackgroundLocationUpdates(
  notificationTitle = DEFAULT_NOTIFICATION_TITLE,
  notificationBody = DEFAULT_NOTIFICATION_BODY,
): Promise<boolean> {
  try {
    const alreadyRunning = await ExpoLocation.hasStartedLocationUpdatesAsync(
      BACKGROUND_LOCATION_TASK_NAME,
    );
    if (alreadyRunning) return true;

    await ExpoLocation.startLocationUpdatesAsync(BACKGROUND_LOCATION_TASK_NAME, {
      accuracy: ExpoLocation.Accuracy.Balanced,
      timeInterval: BACKGROUND_INTERVAL_MS,
      distanceInterval: BACKGROUND_DISTANCE_M,
      // Android: keep the foreground service notification visible during sharing.
      // The notification clearly states that location sharing is active.
      foregroundService: {
        notificationTitle,
        notificationBody,
      },
      // iOS: show the background location indicator in the status bar.
      // TODO (physical device): Verify the blue location indicator is visible on iOS.
      showsBackgroundLocationIndicator: true,
      pausesUpdatesAutomatically: false,
    });
    return true;
  } catch {
    // Start failed (e.g. no background permission, native error) — fail safely.
    return false;
  }
}

/**
 * Stop background location updates.
 *
 * Idempotent and safe to call even if updates are not running.
 * Must be called whenever sharing stops, is hidden, or expires.
 */
export async function stopBackgroundLocationUpdates(): Promise<void> {
  try {
    const isRunning = await ExpoLocation.hasStartedLocationUpdatesAsync(
      BACKGROUND_LOCATION_TASK_NAME,
    );
    if (isRunning) {
      await ExpoLocation.stopLocationUpdatesAsync(BACKGROUND_LOCATION_TASK_NAME);
    }
  } catch {
    // Fail safely — never block stop or hide actions.
  }
}
