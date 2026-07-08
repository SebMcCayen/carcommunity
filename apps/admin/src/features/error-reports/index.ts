/**
 * Admin error-reports feature module (Phase 13m — Firebase migration).
 *
 * Replaces the error-reports placeholder. Backed by Firebase:
 *  - List/detail READS are direct rules-gated SDK reads on
 *    `diagnosticsReports/{reportId}` (`isAdmin()` in firestore.rules; the
 *    13a/13l precedent).
 *  - READ-ONLY by design: firestore.rules allows NO client writes to
 *    `diagnosticsReports` — every report is created exclusively by the
 *    public diagnostics.submitReport callable so the server-side privacy
 *    sanitization (tokens/credentials/coordinates/stack traces stripped,
 *    bounded scalars only) runs on every document. This module therefore
 *    exposes no mutations at all.
 *
 * Data shape (functions/src/diagnostics/diagnostics-core.ts,
 * buildDiagnosticsReportDocument): userId (null for anonymous reports),
 * severity, platform, featureArea, safeMessage, appVersion, buildNumber,
 * osVersion, errorCode, metadata (sanitized scalars or null), fingerprint,
 * createdAt. Retention is 90 days (scheduled cleanup), so the newest-first
 * first pages are the operationally relevant window.
 *
 * Filtering note: severity/platform filters are applied client-side over
 * the fetched page (the 13c events-module precedent) — a `where` +
 * `orderBy(createdAt)` combination would require composite indexes for
 * every filter permutation.
 */

import {
  collection,
  doc,
  getDoc,
  getDocs,
  limit as fsLimit,
  orderBy,
  query,
} from 'firebase/firestore';

import {
  DIAGNOSTICS_FEATURE_AREAS,
  DIAGNOSTICS_PLATFORMS,
  DIAGNOSTICS_SEVERITIES,
  type DiagnosticsFeatureArea,
  type DiagnosticsPlatform,
  type DiagnosticsSeverity,
} from '@carcommunity/shared/diagnostics';

import { getAdminFirestore } from '../../lib/firestore';

import type { ApiError } from '../../lib/api';

export type { ApiError, DiagnosticsFeatureArea, DiagnosticsPlatform, DiagnosticsSeverity };
export { DIAGNOSTICS_FEATURE_AREAS, DIAGNOSTICS_PLATFORMS, DIAGNOSTICS_SEVERITIES };

/** Page size for the reports list — never load all reports at once. */
export const ERROR_REPORTS_PAGE_SIZE = 50;

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/** Admin-safe report summary for the list view (metadata excluded). */
export interface AdminErrorReportSummary {
  id: string;
  /** null for anonymous (pre-auth) reports. */
  userId: string | null;
  severity: DiagnosticsSeverity;
  platform: DiagnosticsPlatform;
  featureArea: DiagnosticsFeatureArea;
  safeMessage: string;
  errorCode: string | null;
  appVersion: string | null;
  buildNumber: string | null;
  osVersion: string | null;
  fingerprint: string | null;
  createdAt: string | null;
}

/**
 * Full report detail. `metadata` is already sanitized server-side by the
 * submitReport callable (bounded scalars only, sensitive keys stripped).
 */
export interface AdminErrorReportDetail extends AdminErrorReportSummary {
  metadata: Record<string, unknown> | null;
}

/** Optional client-side filters for the list view. */
export interface AdminErrorReportFilter {
  severity?: DiagnosticsSeverity;
  platform?: DiagnosticsPlatform;
}

/** List page: filtered rows + whether an older (unfiltered) page exists. */
export interface AdminErrorReportPage {
  reports: AdminErrorReportSummary[];
  /** Based on the unfiltered fetch — true when a full page came back. */
  hasNext: boolean;
}

// ---------------------------------------------------------------------------
// Coercion helpers
// ---------------------------------------------------------------------------

/**
 * Normalizes a stored timestamp field to an ISO string. Permissive by design —
 * documents may be old, partial, or hand-edited, so this accepts a Firestore
 * Timestamp (toDate()), a native Date, or an already-serialized date string,
 * and returns null only when the value is absent or unparseable.
 */
function toIso(value: unknown): string | null {
  if (value == null) return null;
  if (typeof (value as { toDate?: unknown }).toDate === 'function') {
    const date = (value as { toDate: () => Date }).toDate();
    return date instanceof Date && !Number.isNaN(date.getTime()) ? date.toISOString() : null;
  }
  if (value instanceof Date) {
    return Number.isNaN(value.getTime()) ? null : value.toISOString();
  }
  if (typeof value === 'string') {
    const parsed = new Date(value);
    return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString();
  }
  return null;
}

/**
 * Validates a stored severity against the known set. An unknown stored value
 * must never read as critical/error, so the fallback is the least alarming
 * level ('info').
 */
function coerceSeverity(raw: unknown): DiagnosticsSeverity {
  return (DIAGNOSTICS_SEVERITIES as readonly string[]).includes(raw as string)
    ? (raw as DiagnosticsSeverity)
    : 'info';
}

function coercePlatform(raw: unknown): DiagnosticsPlatform {
  return (DIAGNOSTICS_PLATFORMS as readonly string[]).includes(raw as string)
    ? (raw as DiagnosticsPlatform)
    : 'unknown';
}

function coerceFeatureArea(raw: unknown): DiagnosticsFeatureArea {
  return (DIAGNOSTICS_FEATURE_AREAS as readonly string[]).includes(raw as string)
    ? (raw as DiagnosticsFeatureArea)
    : 'unknown';
}

function coerceString(raw: unknown): string {
  return typeof raw === 'string' ? raw : '';
}

function coerceOptionalString(raw: unknown): string | null {
  return typeof raw === 'string' ? raw : null;
}

/**
 * Metadata is server-sanitized on write, but is still validated defensively.
 * The container must be a plain object (prototype Object.prototype or null —
 * arrays, class instances, Dates, Timestamps etc. are rejected), and per the
 * "bounded scalars only" contract each entry is kept only when its value is a
 * scalar (string/number/boolean/null); nested objects/arrays/functions are
 * dropped. Resolves to null when nothing scalar remains.
 *
 * The accumulator has a null prototype so a stored `__proto__` (or
 * `constructor`) key becomes an ordinary own-property instead of triggering
 * the prototype setter — no prototype pollution from hostile stored data.
 * Callers only ever iterate own keys (Object.entries), so a null prototype is
 * transparent to them.
 */
function coerceMetadata(raw: unknown): Record<string, unknown> | null {
  if (!raw || typeof raw !== 'object') return null;
  const proto: unknown = Object.getPrototypeOf(raw);
  if (proto !== Object.prototype && proto !== null) return null;
  const scalars: Record<string, unknown> = Object.create(null);
  for (const [key, value] of Object.entries(raw as Record<string, unknown>)) {
    if (
      value === null ||
      typeof value === 'string' ||
      typeof value === 'number' ||
      typeof value === 'boolean'
    ) {
      scalars[key] = value;
    }
  }
  return Object.keys(scalars).length > 0 ? scalars : null;
}

function toSummary(id: string, data: Record<string, unknown>): AdminErrorReportSummary {
  return {
    id,
    userId: coerceOptionalString(data.userId),
    severity: coerceSeverity(data.severity),
    platform: coercePlatform(data.platform),
    featureArea: coerceFeatureArea(data.featureArea),
    safeMessage: coerceString(data.safeMessage),
    errorCode: coerceOptionalString(data.errorCode),
    appVersion: coerceOptionalString(data.appVersion),
    buildNumber: coerceOptionalString(data.buildNumber),
    osVersion: coerceOptionalString(data.osVersion),
    fingerprint: coerceOptionalString(data.fingerprint),
    createdAt: toIso(data.createdAt),
  };
}

// ---------------------------------------------------------------------------
// Reads (the module is read-only — rules allow no client writes)
// ---------------------------------------------------------------------------

/**
 * Lists the most recent diagnostics reports (newest first, one page — never
 * loads the whole collection). Direct rules-gated read on
 * `diagnosticsReports`; severity/platform filters are applied client-side
 * over the fetched page.
 */
export async function adminListErrorReports(
  filter: AdminErrorReportFilter = {},
): Promise<AdminErrorReportPage> {
  const snapshot = await getDocs(
    query(
      collection(getAdminFirestore(), 'diagnosticsReports'),
      orderBy('createdAt', 'desc'),
      fsLimit(ERROR_REPORTS_PAGE_SIZE),
    ),
  );
  const all = snapshot.docs.map((d) => toSummary(d.id, d.data() as Record<string, unknown>));
  const reports = all.filter(
    (report) =>
      (!filter.severity || report.severity === filter.severity) &&
      (!filter.platform || report.platform === filter.platform),
  );
  // hasNext reflects the unfiltered fetch: a full page means older reports
  // exist in Firestore (matching the crown-hunt precedent).
  return { reports, hasNext: all.length === ERROR_REPORTS_PAGE_SIZE };
}

/**
 * Returns the full detail (incl. server-sanitized metadata) for one report.
 * A missing document resolves to null (the page renders a not-found state).
 */
export async function adminGetErrorReport(
  reportId: string,
): Promise<AdminErrorReportDetail | null> {
  const snap = await getDoc(doc(getAdminFirestore(), 'diagnosticsReports', reportId));
  const data = snap.data() as Record<string, unknown> | undefined;
  if (!data) return null;
  return {
    ...toSummary(reportId, data),
    metadata: coerceMetadata(data.metadata),
  };
}
