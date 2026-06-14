/**
 * Diagnostics reporting client for the mobile app.
 *
 * Privacy rules:
 * - No auth tokens or session secrets are sent.
 * - No exact live location or coordinates are sent.
 * - No raw request headers are sent.
 * - No route history or drive history is sent.
 * - No personal messages or identifiable data are sent.
 *
 * Failures are silently swallowed to avoid disrupting app startup or user flows.
 */

import Constants from 'expo-constants';

import { publicEnv } from '../config/env';
import {
  DIAGNOSTICS_ROUTE_PATHS,
  type DiagnosticsFeatureArea,
  type DiagnosticsPlatform,
  type DiagnosticsReportRequest,
  type DiagnosticsReportResponse,
  type DiagnosticsSeverity,
} from '@carcommunity/shared/diagnostics';

const base = publicEnv.apiBaseUrl.replace(/\/$/, '');

const buildUrl = (path: string) => `${base}${path.startsWith('/') ? path : `/${path}`}`;

/**
 * Returns the current app version and build number from Expo config.
 * Safe to call at any time; returns null values if unavailable.
 */
function getAppBuildInfo(): { appVersion: string | null; buildNumber: string | null } {
  try {
    const version = Constants.expoConfig?.version ?? null;
    // Expo stores the build number under ios.buildNumber / android.versionCode.
    const buildNumber =
      (Constants.expoConfig?.ios?.buildNumber ??
        String(Constants.expoConfig?.android?.versionCode ?? '')) ||
      null;
    return { appVersion: version, buildNumber };
  } catch {
    return { appVersion: null, buildNumber: null };
  }
}

/**
 * Returns the app platform based on React Native's Platform.OS.
 * Avoids importing Platform at module level to keep this file testable.
 */
function resolvePlatform(): DiagnosticsPlatform {
  try {
    // Dynamic require keeps this file tree-shakeable and testable without a full RN env.
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { Platform } = require('react-native') as { Platform: { OS: string } };
    if (Platform.OS === 'ios') return 'ios';
    if (Platform.OS === 'android') return 'android';
    if (Platform.OS === 'web') return 'web';
    return 'unknown';
  } catch {
    return 'unknown';
  }
}

export interface ReportDiagnosticOptions {
  severity: DiagnosticsSeverity;
  featureArea: DiagnosticsFeatureArea;
  /** Human-readable, privacy-safe message. No tokens or personal data. */
  safeMessage: string;
  /** Short machine-readable error code, e.g. "network_timeout". */
  errorCode?: string;
  /**
   * Optional structured metadata.
   * Must not include tokens, credentials, coordinates, or personal data.
   * The backend enforces additional sanitization before storage.
   */
  metadata?: Record<string, unknown>;
}

/**
 * Sends a diagnostics report to the backend API.
 *
 * This function:
 * - Includes app version, build number, and platform automatically.
 * - Does NOT include auth tokens, exact location, or raw headers.
 * - Fails silently if the API is unavailable or returns an error.
 * - Does NOT block app startup.
 *
 * @returns The report ID and fingerprint on success, or null on failure.
 */
export async function reportDiagnostic(
  options: ReportDiagnosticOptions,
): Promise<DiagnosticsReportResponse['data'] | null> {
  if (!base) {
    // API not configured — skip silently.
    return null;
  }

  try {
    const { appVersion, buildNumber } = getAppBuildInfo();
    const platform = resolvePlatform();

    const payload: DiagnosticsReportRequest = {
      severity: options.severity,
      featureArea: options.featureArea,
      platform,
      safeMessage: options.safeMessage,
      ...(options.errorCode !== undefined ? { errorCode: options.errorCode } : {}),
      ...(appVersion !== null ? { appVersion } : {}),
      ...(buildNumber !== null ? { buildNumber } : {}),
      ...(options.metadata !== undefined ? { metadata: options.metadata } : {}),
    };

    const response = await fetch(buildUrl(DIAGNOSTICS_ROUTE_PATHS.report), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });

    if (!response.ok) {
      // Silently swallow non-2xx — never throw to the caller.
      return null;
    }

    const result = (await response.json()) as DiagnosticsReportResponse;
    return result.ok ? result.data : null;
  } catch {
    // Silently swallow all errors — diagnostics must never disrupt the app.
    return null;
  }
}
