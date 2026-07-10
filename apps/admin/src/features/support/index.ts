/**
 * Admin support / feedback-inbox feature module (Phase 13r — Firebase).
 *
 * The admin-side view of the Android "Report a problem" flow. Backed by
 * Firebase:
 *  - List/detail READS are direct rules-gated SDK reads on
 *    `feedbackReports/{reportId}` (`isAdmin()` in firestore.rules; the
 *    13a/13m precedent).
 *  - READ-ONLY by design: firestore.rules allows NO writes to
 *    `feedbackReports` at all — not from clients and not from admins. Every
 *    document is created exclusively by the `feedback.reportIssue` callable,
 *    which persists the private record of record and patches in the GitHub
 *    issue number/url/status after filing the public issue. This module
 *    therefore exposes no mutations whatsoever.
 *
 * Data shape (functions/src/feedback/feedback-core.ts,
 * buildFeedbackReportDocument): uid, platform ('android'), summary (nullable),
 * description, appVersion/osVersion/deviceModel (nullable context scalars),
 * githubIssueStatus ('pending' | 'created' | 'failed'), githubIssueNumber
 * (number | null), githubIssueUrl (string | null), createdAt (server ts).
 *
 * PRIVACY: the reporter's `uid` lives ONLY in this private document — it is
 * never attached to the world-readable GitHub issue (see feedback-core). The
 * inbox surfaces it to admins for triage but truncates it in the list view so
 * a full identifier is never leaked into a title attribute.
 *
 * Filtering note: status/platform filters are applied client-side over the
 * fetched page (the 13c/13m precedent) — a `where` + `orderBy(createdAt)`
 * combination would require a new composite index per filter permutation.
 * The existing `feedbackReports` composite (uid ASC, createdAt ASC) is used by
 * the callable's rate-limit count; the admin inbox instead orders by
 * `createdAt desc` with no where-clause (single-field, automatic index).
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

import { getAdminFirestore } from '../../lib/firestore';

import type { ApiError } from '../../lib/errors';

export type { ApiError };

/** Page size for the inbox list — never load all reports at once. */
export const SUPPORT_PAGE_SIZE = 50;

/**
 * GitHub-issue cross-reference statuses written by the callable. `pending` is
 * the transient state before the GitHub call resolves; `created`/`failed` are
 * the terminal states. An unknown/absent stored value coerces to `failed` so
 * the inbox never renders a non-existent cross-link.
 */
export const FEEDBACK_GITHUB_STATUSES = ['created', 'failed', 'pending'] as const;
export type FeedbackGithubStatus = (typeof FEEDBACK_GITHUB_STATUSES)[number];

/** Source platforms. Only `android` is written today (the sole reporter). */
export const FEEDBACK_PLATFORMS = ['android'] as const;
export type FeedbackPlatform = (typeof FEEDBACK_PLATFORMS)[number];

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/** Admin-safe feedback report row for the inbox list view. */
export interface AdminFeedbackReportSummary {
  id: string;
  /** The reporter's uid — private to admins, never on the public issue. */
  uid: string | null;
  platform: FeedbackPlatform;
  /** Short one-line summary (nullable — the app makes it optional). */
  summary: string | null;
  /** The full typed description. */
  description: string;
  appVersion: string | null;
  osVersion: string | null;
  deviceModel: string | null;
  githubIssueStatus: FeedbackGithubStatus;
  githubIssueNumber: number | null;
  githubIssueUrl: string | null;
  createdAt: string | null;
}

/**
 * Full report detail. The feedbackReports document carries no fields beyond
 * the summary set (no free-form metadata), so the detail shape matches the
 * summary — the read-by-id exists so the expanded view refetches the canonical
 * record (it may have been patched from `pending` to `created`/`failed`).
 */
export type AdminFeedbackReportDetail = AdminFeedbackReportSummary;

/** Optional client-side filters for the list view. */
export interface AdminFeedbackReportFilter {
  githubIssueStatus?: FeedbackGithubStatus;
  platform?: FeedbackPlatform;
}

/** List page: filtered rows + whether an older (unfiltered) page exists. */
export interface AdminFeedbackReportPage {
  reports: AdminFeedbackReportSummary[];
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
    // A hand-edited doc could carry a `toDate` that throws or returns a bad
    // value; guard so one malformed timestamp can never reject the whole
    // list/detail read (matching error-reports/account-deletions).
    try {
      const date = (value as { toDate: () => Date }).toDate();
      return date instanceof Date && !Number.isNaN(date.getTime()) ? date.toISOString() : null;
    } catch {
      return null;
    }
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

function coerceString(raw: unknown): string {
  return typeof raw === 'string' ? raw : '';
}

function coerceOptionalString(raw: unknown): string | null {
  return typeof raw === 'string' ? raw : null;
}

function coerceOptionalNumber(raw: unknown): number | null {
  return typeof raw === 'number' && Number.isFinite(raw) ? raw : null;
}

/**
 * Validates a stored GitHub-issue status. A missing/unknown value must never
 * masquerade as `created` (which implies a live cross-link exists), so the
 * fallback is `failed` — the inbox then shows no external link for it.
 */
function coerceGithubStatus(raw: unknown): FeedbackGithubStatus {
  return (FEEDBACK_GITHUB_STATUSES as readonly string[]).includes(raw as string)
    ? (raw as FeedbackGithubStatus)
    : 'failed';
}

function coercePlatform(raw: unknown): FeedbackPlatform {
  return (FEEDBACK_PLATFORMS as readonly string[]).includes(raw as string)
    ? (raw as FeedbackPlatform)
    : 'android';
}

function toSummary(id: string, data: Record<string, unknown>): AdminFeedbackReportSummary {
  return {
    id,
    uid: coerceOptionalString(data.uid),
    platform: coercePlatform(data.platform),
    summary: coerceOptionalString(data.summary),
    description: coerceString(data.description),
    appVersion: coerceOptionalString(data.appVersion),
    osVersion: coerceOptionalString(data.osVersion),
    deviceModel: coerceOptionalString(data.deviceModel),
    githubIssueStatus: coerceGithubStatus(data.githubIssueStatus),
    githubIssueNumber: coerceOptionalNumber(data.githubIssueNumber),
    githubIssueUrl: coerceOptionalString(data.githubIssueUrl),
    createdAt: toIso(data.createdAt),
  };
}

/** Hosts accepted for the GitHub-issue cross-link. */
const GITHUB_ISSUE_HOSTS = new Set(['github.com', 'www.github.com']);

/**
 * Validates a stored `githubIssueUrl` before it is ever used as an anchor
 * href. The value is free/hand-editable data, so a malformed doc could carry a
 * `javascript:`/`data:` scheme or an off-site host and turn the admin badge
 * into an XSS/phishing vector. Returns the URL only when it parses as an
 * absolute `https:` URL whose host is github.com (or www.github.com);
 * otherwise null, so the UI falls back to the non-link failed/plain state.
 */
export function safeGitHubIssueUrl(url: string | null | undefined): string | null {
  if (typeof url !== 'string' || url.length === 0) return null;
  try {
    const parsed = new URL(url);
    if (parsed.protocol !== 'https:') return null;
    if (!GITHUB_ISSUE_HOSTS.has(parsed.host.toLowerCase())) return null;
    return parsed.toString();
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------
// Reads (the module is read-only — rules allow no writes at all)
// ---------------------------------------------------------------------------

/**
 * Lists the most recent feedback reports (newest first, one page — never loads
 * the whole collection). Direct rules-gated read on `feedbackReports` ordered
 * by `createdAt desc` with no where-clause (single-field automatic index);
 * status/platform filters are applied client-side over the fetched page.
 */
export async function adminListFeedbackReports(
  filter: AdminFeedbackReportFilter = {},
): Promise<AdminFeedbackReportPage> {
  const snapshot = await getDocs(
    query(
      collection(getAdminFirestore(), 'feedbackReports'),
      orderBy('createdAt', 'desc'),
      fsLimit(SUPPORT_PAGE_SIZE),
    ),
  );
  const all = snapshot.docs.map((d) => toSummary(d.id, d.data() as Record<string, unknown>));
  const reports = all.filter(
    (report) =>
      (!filter.githubIssueStatus || report.githubIssueStatus === filter.githubIssueStatus) &&
      (!filter.platform || report.platform === filter.platform),
  );
  // hasNext reflects the unfiltered fetch: a full page means older reports
  // exist in Firestore (matching the error-reports/crown-hunt precedent).
  return { reports, hasNext: all.length === SUPPORT_PAGE_SIZE };
}

/**
 * Returns the full detail for one report. A missing document resolves to null
 * (the page renders a not-found state).
 */
export async function adminGetFeedbackReport(
  reportId: string,
): Promise<AdminFeedbackReportDetail | null> {
  const snap = await getDoc(doc(getAdminFirestore(), 'feedbackReports', reportId));
  const data = snap.data() as Record<string, unknown> | undefined;
  if (!data) return null;
  return toSummary(reportId, data);
}
