/**
 * Feedback domain — "Report a problem" pure logic (input parsing, bounding,
 * private-record + public-issue builders, rate-limit helpers).
 *
 * An active signed-in user (non-suspended, non-deleted — no member entitlement
 * required) files a bug report from the Android app. Two records are produced
 * from one submission:
 *
 * - the PRIVATE record of record: `feedbackReports/{reportId}` (admin-only
 *   read), carrying the caller's uid, the typed text, the client context, a
 *   timestamp and — after creation — the GitHub issue number/url/status. The
 *   uid lives ONLY here.
 * - the PUBLIC GitHub issue on SebMcCayen/carcommunity (world-readable). The
 *   issue body is built by [buildGitHubIssueBody] and contains ONLY the typed
 *   description, appVersion/osVersion/deviceModel, the server-generated report
 *   id and the submitted-at timestamp. It NEVER contains the uid, email,
 *   coordinates, tokens or any PII beyond what the user chose to type — the
 *   test suite asserts this.
 *
 * Pure module — no Firebase Admin SDK and no network imports, so every branch
 * is unit-testable without emulators.
 */

import { z } from 'zod';

// ---------------------------------------------------------------------------
// Limits
// ---------------------------------------------------------------------------

/** Title budget for the public issue summary (excludes the `[Android] ` tag). */
export const MAX_SUMMARY_LENGTH = 80;
/** Body budget for the user's typed description. */
export const MAX_DESCRIPTION_LENGTH = 4000;
export const MAX_APP_VERSION_LENGTH = 50;
export const MAX_OS_VERSION_LENGTH = 100;
export const MAX_DEVICE_MODEL_LENGTH = 100;

/** GitHub label applied to every issue (must already exist on the repo). */
export const FEEDBACK_ISSUE_LABEL = 'android-issue';
/** Title tag identifying the source platform. */
export const FEEDBACK_TITLE_TAG = '[Android]';

// ---------------------------------------------------------------------------
// Rate limit (per user)
// ---------------------------------------------------------------------------

/** Max reports a single user may file per rolling window. */
export const FEEDBACK_RATE_LIMIT_MAX = 5;
/** Rolling window width: one hour. */
export const FEEDBACK_RATE_LIMIT_WINDOW_MS = 60 * 60 * 1000;

/** Start of the rate-limit window: reports at/after this instant count. */
export function feedbackRateLimitWindowStart(now: Date): Date {
  return new Date(now.getTime() - FEEDBACK_RATE_LIMIT_WINDOW_MS);
}

/** True when a fresh report would exceed the per-user cap. */
export function isFeedbackRateLimited(recentCount: number): boolean {
  return recentCount >= FEEDBACK_RATE_LIMIT_MAX;
}

// ---------------------------------------------------------------------------
// Sanitization
// ---------------------------------------------------------------------------

// C0/C1 control characters, minus tab (\t) and newline (\n), that could
// smuggle escape sequences into the public issue body.
const MULTILINE_CONTROL_CHARS = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F]/g;
// As above but also stripping tab/newline for single-line context scalars.
const SINGLELINE_CONTROL_CHARS = /[\u0000-\u001F\u007F-\u009F]/g;

/**
 * Bounds and cleans a free-text field: strips control characters (except tab
 * and newline), trims, and truncates to `max`. Returns the trimmed result
 * (possibly empty).
 */
export function boundText(raw: string, max: number): string {
  return raw.replace(MULTILINE_CONTROL_CHARS, '').trim().slice(0, max);
}

/**
 * Bounds a single-line context scalar (appVersion/osVersion/deviceModel):
 * newlines and control characters removed, collapsed whitespace, truncated.
 * Returns null when nothing safe remains.
 */
export function boundContext(raw: string | undefined | null, max: number): string | null {
  if (typeof raw !== 'string') return null;
  const value = raw
    .replace(SINGLELINE_CONTROL_CHARS, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, max);
  return value.length > 0 ? value : null;
}

// ---------------------------------------------------------------------------
// Input
// ---------------------------------------------------------------------------

const reportIssueInputSchema = z
  .object({
    description: z.string().min(1).max(MAX_DESCRIPTION_LENGTH),
    summary: z.string().max(MAX_SUMMARY_LENGTH).optional(),
    appVersion: z.string().max(MAX_APP_VERSION_LENGTH).optional(),
    osVersion: z.string().max(MAX_OS_VERSION_LENGTH).optional(),
    deviceModel: z.string().max(MAX_DEVICE_MODEL_LENGTH).optional(),
  })
  .strict();

export type ReportIssueInput = z.infer<typeof reportIssueInputSchema>;

/** Normalized, bounded report fields used by both builders. */
export interface FeedbackReport {
  description: string;
  summary: string | null;
  appVersion: string | null;
  osVersion: string | null;
  deviceModel: string | null;
}

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

export function parseReportIssueInput(data: unknown): ParseResult<FeedbackReport> {
  const result = reportIssueInputSchema.safeParse(data ?? {});
  if (!result.success) {
    return {
      ok: false,
      message: 'Expected { description, summary?, appVersion?, osVersion?, deviceModel? }.',
    };
  }

  const description = boundText(result.data.description, MAX_DESCRIPTION_LENGTH);
  if (description.length === 0) {
    return { ok: false, message: 'Description cannot be empty.' };
  }

  const summaryRaw = result.data.summary ? boundText(result.data.summary, MAX_SUMMARY_LENGTH) : '';

  return {
    ok: true,
    input: {
      description,
      summary: summaryRaw.length > 0 ? summaryRaw : null,
      appVersion: boundContext(result.data.appVersion, MAX_APP_VERSION_LENGTH),
      osVersion: boundContext(result.data.osVersion, MAX_OS_VERSION_LENGTH),
      deviceModel: boundContext(result.data.deviceModel, MAX_DEVICE_MODEL_LENGTH),
    },
  };
}

// ---------------------------------------------------------------------------
// Public GitHub issue (world-readable — no uid/PII beyond typed text)
// ---------------------------------------------------------------------------

/** First non-empty line of the description, used when no summary was given. */
function firstLine(text: string): string {
  return text.split('\n').map((s) => s.trim()).find((s) => s.length > 0) ?? '';
}

/**
 * Public issue title: `[Android] ` + a bounded summary. Falls back to the
 * first line of the description, then to a generic label so the title is never
 * empty. Single-lined and capped at [MAX_SUMMARY_LENGTH].
 */
export function buildGitHubIssueTitle(report: FeedbackReport): string {
  const source = report.summary ?? firstLine(report.description);
  const summary = source.replace(/\s+/g, ' ').trim().slice(0, MAX_SUMMARY_LENGTH);
  const safe = summary.length > 0 ? summary : 'Problem report';
  return `${FEEDBACK_TITLE_TAG} ${safe}`;
}

/**
 * Public issue body. CONTAINS ONLY: the user's typed description, the client
 * context (appVersion/osVersion/deviceModel), the server report id and the
 * submitted-at timestamp. NEVER the uid, email, coordinates or tokens — the
 * uid is cross-referenced solely by report id in the private Firestore doc.
 */
export function buildGitHubIssueBody(
  report: FeedbackReport,
  reportId: string,
  submittedAtIso: string,
): string {
  const context = [
    `- App version: ${report.appVersion ?? 'unknown'}`,
    `- OS version: ${report.osVersion ?? 'unknown'}`,
    `- Device model: ${report.deviceModel ?? 'unknown'}`,
    `- Report ID: ${reportId}`,
    `- Submitted at: ${submittedAtIso}`,
  ].join('\n');

  return [
    report.description,
    '',
    '---',
    context,
    '',
    '_Filed via the in-app Report a problem flow. Reports are public — they never include account identifiers._',
  ].join('\n');
}

export interface GitHubIssuePayload {
  title: string;
  body: string;
  labels: string[];
}

/** Full `POST /issues` request body for the public repo. */
export function buildGitHubIssuePayload(
  report: FeedbackReport,
  reportId: string,
  submittedAtIso: string,
): GitHubIssuePayload {
  return {
    title: buildGitHubIssueTitle(report),
    body: buildGitHubIssueBody(report, reportId, submittedAtIso),
    labels: [FEEDBACK_ISSUE_LABEL],
  };
}

// ---------------------------------------------------------------------------
// Private Firestore record of record
// ---------------------------------------------------------------------------

export type GitHubIssueStatus = 'pending' | 'created' | 'failed';

/**
 * `feedbackReports/{reportId}` document — the private record of record.
 * Written FIRST (before the GitHub call) so a report is never lost. The uid
 * lives here and nowhere public; githubIssueNumber/url/status are patched in
 * after the issue attempt.
 */
export function buildFeedbackReportDocument(
  report: FeedbackReport,
  uid: string,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    uid,
    platform: 'android',
    summary: report.summary,
    description: report.description,
    appVersion: report.appVersion,
    osVersion: report.osVersion,
    deviceModel: report.deviceModel,
    githubIssueStatus: 'pending' as GitHubIssueStatus,
    githubIssueNumber: null,
    githubIssueUrl: null,
    createdAt: serverTimestamp(),
  };
}
