/**
 * Shared diagnostics and error reporting contracts.
 *
 * Privacy rules enforced here and at the backend:
 * - No auth tokens.
 * - No exact live location coordinates.
 * - No route history.
 * - No personal messages.
 * - No raw request headers.
 * - Email is excluded by default.
 */

export const DIAGNOSTICS_SEVERITIES = ['info', 'warning', 'error', 'critical'] as const;
export type DiagnosticsSeverity = (typeof DIAGNOSTICS_SEVERITIES)[number];

export const DIAGNOSTICS_PLATFORMS = ['ios', 'android', 'web', 'unknown'] as const;
export type DiagnosticsPlatform = (typeof DIAGNOSTICS_PLATFORMS)[number];

export const DIAGNOSTICS_FEATURE_AREAS = [
  'auth',
  'live_location',
  'events',
  'subscription',
  'admin',
  'map',
  'network',
  'unknown',
] as const;
export type DiagnosticsFeatureArea = (typeof DIAGNOSTICS_FEATURE_AREAS)[number];

/**
 * A single diagnostics report entry returned in admin listings.
 * Sensitive metadata fields are excluded from this view.
 */
export interface AdminDiagnosticsEntry {
  id: string;
  /** null for unauthenticated reports. */
  userId: string | null;
  severity: DiagnosticsSeverity;
  platform: DiagnosticsPlatform;
  featureArea: DiagnosticsFeatureArea;
  appVersion: string | null;
  buildNumber: string | null;
  osVersion: string | null;
  errorCode: string | null;
  safeMessage: string;
  fingerprint: string | null;
  createdAt: string;
}

/**
 * Admin response for GET /v1/admin/diagnostics.
 *
 * TODO: Add deduplication and grouping.
 * TODO: Add severity-based alerting.
 * TODO: Add GitHub Issue creation (future step only).
 * TODO: Add privacy review before exposing metadata to admins.
 */
export interface AdminDiagnosticsListResponse {
  ok: true;
  data: {
    reports: AdminDiagnosticsEntry[];
  };
  meta: {
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
  };
}

export const DIAGNOSTICS_ROUTE_PATHS = {
  report: '/v1/diagnostics/report',
  adminList: '/v1/admin/diagnostics',
} as const;
