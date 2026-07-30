/**
 * Client-error reporting — pure domain logic (input parsing/bounding, dedup
 * fingerprint, private-record + audit + public-issue builders, dedup decision +
 * link documents).
 *
 * A single reporting pipeline for genuine RUNTIME errors surfaced in the
 * Android client (e.g. the Messages inbox listener failing). One authenticated
 * `errors.reportClientError` submission produces THREE effects, wired in the
 * callable (reportClientError.ts) + trigger (onClientErrorReport.ts):
 *
 *  - a PRIVATE record of record: `clientErrorReports/{reportId}` (admin-only
 *    read) carrying the reporter's uid + the bounded error context. The uid
 *    lives here (and in the audit event's admin-only details) and NOWHERE
 *    public.
 *  - an AUDIT-LOG entry: `adminAuditEvents/{id}` with action `client.error`, so
 *    the failure shows up in the KCC admin Audit Log alongside admin actions
 *    (built with the shared buildAdminAuditEvent in the callable).
 *  - a DEDUPLICATED PUBLIC GitHub issue (labelled `auto-error`), one per unique
 *    fingerprint, filed by the onClientErrorReport trigger and tallied in the
 *    server-only `clientErrorIssueLinks/{fingerprint}` collection — so a
 *    recurring error bumps an occurrence counter instead of spamming issues.
 *
 * This mirrors the two proven existing pipelines rather than inventing a new
 * one: the authenticated feedback flow (feedback/feedback-core.ts — reused
 * boundText/boundContext, rate limit) and the sign-in-failure auto-issue dedup
 * (diagnostics/signInIssues-core.ts — link-doc claim/increment model).
 *
 * Pure module — no Firebase Admin SDK and no network imports, so every branch
 * is unit-testable without emulators (mirrors the sibling *-core.ts files).
 */

import { createHash } from 'node:crypto';
import { z } from 'zod';
import {
  boundContext,
  boundText,
  neutralizeMentions,
} from '../feedback/feedback-core';
import { AUTO_GENERATED_LABEL } from '../diagnostics/signInIssues-core';
import type { GitHubIssuePayload } from '../shared/githubIssues';
import {
  buildIssueLinkCreated,
  buildIssueLinkFailed,
  buildIssueLinkIncrement,
  buildIssueLinkRetry,
  buildNewIssueLink,
  decideIssueAction,
  type IssueLinkState,
  type IssueLinkStatus,
} from '../shared/issueLinks-core';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Private record of record (admin-only read). */
export const CLIENT_ERROR_REPORTS_COLLECTION = 'clientErrorReports';
/** Server-only fingerprint → GitHub issue dedup index. */
export const CLIENT_ERROR_ISSUE_LINKS_COLLECTION = 'clientErrorIssueLinks';

/** adminAuditEvents action for a reported client error (shows in the Audit Log). */
export const CLIENT_ERROR_AUDIT_ACTION = 'client.error';
export const CLIENT_ERROR_AUDIT_TARGET_TYPE = 'clientError';

/**
 * Distinct label so auto-filed error issues are separable from manual
 * `android-issue` bug reports. `auto-generated` is shared with the sign-in
 * auto-issue path. Both must already exist on the repo.
 */
export const CLIENT_ERROR_ISSUE_LABEL = 'auto-error';
export const CLIENT_ERROR_ISSUE_LABELS = [CLIENT_ERROR_ISSUE_LABEL, AUTO_GENERATED_LABEL];

/** Title tag identifying an auto-filed client-error issue. */
export const CLIENT_ERROR_TITLE_TAG = '[Auto-error]';

/** Default platform when the client omits it. */
export const DEFAULT_CLIENT_ERROR_PLATFORM = 'android';

// Field bounds (defence in depth; the client already bounds these).
export const MAX_FEATURE_LENGTH = 120;
export const MAX_MESSAGE_LENGTH = 2000;
export const MAX_CODE_LENGTH = 100;
export const MAX_APP_VERSION_LENGTH = 50;
export const MAX_OS_VERSION_LENGTH = 100;
export const MAX_DEVICE_MODEL_LENGTH = 100;
export const MAX_PLATFORM_LENGTH = 20;

// ---------------------------------------------------------------------------
// Rate limit (per user) — mirrors feedback.reportIssue, higher cap because an
// error can recur legitimately (a flaky network re-hitting the same screen).
// ---------------------------------------------------------------------------

export const CLIENT_ERROR_RATE_LIMIT_MAX = 30;
export const CLIENT_ERROR_RATE_LIMIT_WINDOW_MS = 60 * 60 * 1000;

export function clientErrorRateLimitWindowStart(now: Date): Date {
  return new Date(now.getTime() - CLIENT_ERROR_RATE_LIMIT_WINDOW_MS);
}

export function isClientErrorRateLimited(recentCount: number): boolean {
  return recentCount >= CLIENT_ERROR_RATE_LIMIT_MAX;
}

// ---------------------------------------------------------------------------
// Input
// ---------------------------------------------------------------------------

const reportClientErrorInputSchema = z
  .object({
    /** Stable screen/feature key, e.g. "messages.conversationList". */
    feature: z.string().min(1).max(MAX_FEATURE_LENGTH),
    /** Human-readable error summary (app-generated, not user free-text). */
    message: z.string().min(1).max(MAX_MESSAGE_LENGTH),
    /** Optional stable error code, e.g. a Firestore/Functions status name. */
    code: z.string().max(MAX_CODE_LENGTH).optional(),
    appVersion: z.string().max(MAX_APP_VERSION_LENGTH).optional(),
    osVersion: z.string().max(MAX_OS_VERSION_LENGTH).optional(),
    deviceModel: z.string().max(MAX_DEVICE_MODEL_LENGTH).optional(),
    platform: z.string().max(MAX_PLATFORM_LENGTH).optional(),
  })
  .strict();

export type ReportClientErrorInput = z.infer<typeof reportClientErrorInputSchema>;

/** Normalized, bounded error fields used by all builders. */
export interface ClientErrorReport {
  feature: string;
  message: string;
  code: string | null;
  appVersion: string | null;
  osVersion: string | null;
  deviceModel: string | null;
  platform: string;
  /** Server-derived dedup key (feature + normalized signature). */
  fingerprint: string;
}

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

export const REPORT_CLIENT_ERROR_EXPECTED =
  'Expected { feature, message, code?, appVersion?, osVersion?, deviceModel?, platform? }.';

/**
 * Parses + bounds a client-error submission. Returns a normalized report with a
 * server-derived fingerprint, or a validation message.
 */
export function parseReportClientErrorInput(data: unknown): ParseResult<ClientErrorReport> {
  const result = reportClientErrorInputSchema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: REPORT_CLIENT_ERROR_EXPECTED };
  }

  const feature = boundContext(result.data.feature, MAX_FEATURE_LENGTH);
  if (!feature) {
    return { ok: false, message: 'feature cannot be empty.' };
  }
  const message = boundText(result.data.message, MAX_MESSAGE_LENGTH);
  if (message.length === 0) {
    return { ok: false, message: 'message cannot be empty.' };
  }

  const code = result.data.code ? boundContext(result.data.code, MAX_CODE_LENGTH) : null;
  const platform =
    boundContext(result.data.platform, MAX_PLATFORM_LENGTH) ?? DEFAULT_CLIENT_ERROR_PLATFORM;

  return {
    ok: true,
    input: {
      feature,
      message,
      code,
      appVersion: boundContext(result.data.appVersion, MAX_APP_VERSION_LENGTH),
      osVersion: boundContext(result.data.osVersion, MAX_OS_VERSION_LENGTH),
      deviceModel: boundContext(result.data.deviceModel, MAX_DEVICE_MODEL_LENGTH),
      platform,
      fingerprint: computeClientErrorFingerprint(feature, message, code),
    },
  };
}

// ---------------------------------------------------------------------------
// Fingerprint
// ---------------------------------------------------------------------------

/**
 * Stable error signature for dedup: the `code` when present (upper-cased), else
 * a normalized form of the message with volatile tokens (uuids, hex, digits)
 * collapsed to `#` so "load failed after 3 retries" and "…after 7 retries" map
 * to the SAME fingerprint. This bounds distinct issues to roughly one per
 * (feature, error kind).
 */
export function clientErrorSignature(message: string, code: string | null): string {
  if (code && code.trim().length > 0) {
    return code.trim().toUpperCase().slice(0, MAX_CODE_LENGTH);
  }
  return message
    .toLowerCase()
    .replace(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/g, '#')
    .replace(/0x[0-9a-f]+/g, '#')
    .replace(/\d+/g, '#')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 160);
}

/**
 * Dedup fingerprint, derived SERVER-SIDE from the feature + the stable
 * signature only (never volatile context like appVersion/deviceModel), so the
 * same error recurring across devices/versions maps to ONE issue.
 */
export function computeClientErrorFingerprint(
  feature: string,
  message: string,
  code: string | null,
): string {
  return createHash('sha256')
    .update(`${feature}|${clientErrorSignature(message, code)}`)
    .digest('hex')
    .slice(0, 64);
}

// ---------------------------------------------------------------------------
// Private Firestore record of record
// ---------------------------------------------------------------------------

export type GitHubIssueStatus = 'pending' | 'created' | 'failed';

/**
 * `clientErrorReports/{reportId}` — the private record of record (admin-only
 * read). Written FIRST (before the GitHub issue) so a report is never lost. The
 * uid lives here; the trigger patches githubIssue* after the issue attempt.
 */
export function buildClientErrorReportDocument(
  report: ClientErrorReport,
  uid: string,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    uid,
    feature: report.feature,
    message: report.message,
    code: report.code,
    appVersion: report.appVersion,
    osVersion: report.osVersion,
    deviceModel: report.deviceModel,
    platform: report.platform,
    fingerprint: report.fingerprint,
    githubIssueStatus: 'pending' as GitHubIssueStatus,
    githubIssueNumber: null,
    githubIssueUrl: null,
    createdAt: serverTimestamp(),
  };
}

// ---------------------------------------------------------------------------
// Audit-log entry (adminAuditEvents) — details are admin-only readable
// ---------------------------------------------------------------------------

/**
 * The `details` payload merged into the adminAuditEvents record. Carries the
 * bounded error context (admin-only read); the uid is the audit event's
 * `adminId`. Never includes secrets.
 */
export function buildClientErrorAuditDetails(report: ClientErrorReport): Record<string, unknown> {
  return {
    message: report.message,
    code: report.code,
    platform: report.platform,
    appVersion: report.appVersion,
    osVersion: report.osVersion,
    deviceModel: report.deviceModel,
    fingerprint: report.fingerprint,
  };
}

// ---------------------------------------------------------------------------
// Dedup decision + link documents (clientErrorIssueLinks)
// ---------------------------------------------------------------------------

/**
 * The link-doc state machine itself now lives in shared/issueLinks-core.ts (it
 * is identical for client errors and server errors). The names below are kept as
 * thin, domain-named delegations so this module's public API — and the callers
 * and tests that depend on it — are unchanged.
 */
export type ClientErrorIssueLinkStatus = IssueLinkStatus;

/** `clientErrorIssueLinks/{fingerprint}` — server-only issue link + tally. */
export interface ClientErrorIssueLink extends IssueLinkState {
  fingerprint: string;
  feature: string;
  status: ClientErrorIssueLinkStatus;
  issueNumber: number | null;
  issueUrl: string | null;
  count: number;
  firstSeenAt?: unknown;
  lastSeenAt?: unknown;
}

/**
 * Dedup decision:
 * - no existing link → CREATE the issue;
 * - a `failed` link → CREATE (retry a previously-failed create);
 * - any other link (`creating` in-flight, or `created`) → only INCREMENT the
 *   occurrence tally, so one unique error is one issue.
 */
export function decideClientErrorIssueAction(
  existing: ClientErrorIssueLink | null | undefined,
): 'create' | 'increment' {
  return decideIssueAction(existing);
}

/** Placeholder link written BEFORE the GitHub call (status `creating`). */
export function buildNewClientErrorIssueLink(
  report: ClientErrorReport,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return buildNewIssueLink(
    { fingerprint: report.fingerprint, feature: report.feature },
    serverTimestamp,
  );
}

/** Patch applied once the issue exists (status `created`). */
export function buildClientErrorIssueLinkCreated(issue: {
  number: number;
  url: string;
}): Record<string, unknown> {
  return buildIssueLinkCreated(issue);
}

/** Patch on a repeat occurrence: bump the tally and touch lastSeenAt. */
export function buildClientErrorIssueLinkIncrement(
  increment: unknown,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return buildIssueLinkIncrement(increment, serverTimestamp);
}

/**
 * Patch that RE-CLAIMS a `failed` link for another create attempt: flip status
 * back to `creating`, refresh lastSeenAt, and count this occurrence — without
 * resetting the preserved count/firstSeenAt.
 */
export function buildClientErrorIssueLinkRetry(
  increment: unknown,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return buildIssueLinkRetry(increment, serverTimestamp);
}

/** Patch when a create attempt failed but concurrent occurrences bumped the tally. */
export function buildClientErrorIssueLinkFailed(): Record<string, unknown> {
  return buildIssueLinkFailed();
}

// ---------------------------------------------------------------------------
// Public GitHub issue (world-readable — no uid, no secrets)
// ---------------------------------------------------------------------------

export interface ClientErrorIssueMeta {
  firstSeenIso: string;
  count: number;
}

/**
 * Renders a client-supplied scalar as a markdown inline-code span so it cannot
 * inject a link/image/html into the world-readable issue body; backticks are
 * neutralized first, and @mention/#ref are defanged. All whitespace (including
 * the newlines/tabs that `boundText` preserves on the `message` field) is
 * collapsed to single spaces so a crafted payload cannot break out of the
 * bullet layout — mirroring the single-line guarantee `boundContext` gives the
 * sign-in path.
 */
function inlineCodeScalar(value: string): string {
  const safe = neutralizeMentions(value)
    .replace(/`/g, "'")
    .replace(/\s+/g, ' ')
    .trim();
  return `\`${safe}\``;
}

/** Public issue title: `[Auto-error] <feature>` (feature is an app-controlled key). */
export function buildClientErrorIssueTitle(report: ClientErrorReport): string {
  return `${CLIENT_ERROR_TITLE_TAG} ${neutralizeMentions(report.feature)}`;
}

/**
 * Public issue body. Carries the feature key, the (app-generated) error
 * message + code, non-identifying client context, the fingerprint, first-seen
 * timestamp and occurrence count. NEVER the uid (it stays in the private
 * clientErrorReports doc + admin-only audit details), never a token or secret.
 * Every client-supplied scalar is bounded, neutralized and inline-coded.
 */
export function buildClientErrorIssueBody(
  report: ClientErrorReport,
  meta: ClientErrorIssueMeta,
): string {
  const field = (value: string | null): string => (value ? inlineCodeScalar(value) : 'unknown');
  return [
    'Automatically filed from a client-side runtime error. Repeat occurrences update the occurrence counter in clientErrorIssueLinks instead of filing new issues.',
    '',
    `- Feature: ${field(report.feature)}`,
    `- Message: ${field(report.message)}`,
    `- Code: ${field(report.code)}`,
    `- Platform: ${field(report.platform)}`,
    `- App version: ${field(report.appVersion)}`,
    `- OS version: ${field(report.osVersion)}`,
    `- Device model: ${field(report.deviceModel)}`,
    `- Fingerprint: ${report.fingerprint}`,
    `- First seen: ${meta.firstSeenIso}`,
    `- Occurrences: ${meta.count}`,
    '',
    '_Filed by errors-onClientErrorReport. This issue is public and never includes account identifiers or secrets._',
  ].join('\n');
}

/** Full `POST /issues` request body for an auto-filed client-error issue. */
export function buildClientErrorIssuePayload(
  report: ClientErrorReport,
  meta: ClientErrorIssueMeta,
): GitHubIssuePayload {
  return {
    title: buildClientErrorIssueTitle(report),
    body: buildClientErrorIssueBody(report, meta),
    labels: [...CLIENT_ERROR_ISSUE_LABELS],
  };
}
