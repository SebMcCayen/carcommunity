/**
 * Sign-in-failure → public GitHub issue: pure domain logic.
 *
 * The Android app reports pre-authentication Google Sign-In failures through
 * the PUBLIC, unauthenticated `diagnostics.submitReport` callable (the only
 * telemetry path that works before auth). Those reports land in
 * `diagnosticsReports/{id}` with featureArea `sign_in`, carrying ONLY a
 * sanitized reason (the exception's simple class name / mapped code) plus
 * bounded client context — NEVER the exception message, credentials, tokens,
 * email, or any PII.
 *
 * The diagnostics-onSignInFailure Firestore trigger consumes those docs and,
 * for the `sign_in` area only, files ONE deduplicated public GitHub issue per
 * unique fingerprint (labelled `sign-in-failure` + `auto-generated`), tracking
 * occurrences in the server-only `signInIssueLinks/{fingerprint}` collection.
 *
 * This module holds the pure, unit-testable pieces:
 * - detecting + extracting a sign-in-failure report from a raw doc,
 * - the dedup decision (create vs increment),
 * - the `signInIssueLinks` doc builders,
 * - the PUBLIC-SAFE issue title/body/payload builders.
 *
 * PUBLIC-REPO SAFETY: the repo is world-readable. The issue body carries only
 * the sanitized error code/type, sanitized reason, app/build/OS version,
 * device model, the fingerprint, first-seen timestamp, and occurrence count.
 * There is NO uid (reports are unauthenticated → userId is null), no email,
 * no token. All client-controlled strings are neutralized (@mention/#ref) and
 * bounded before entering the issue. The test suite asserts this.
 *
 * Pure module — no Firebase Admin SDK and no network imports.
 */

import { neutralizeMentions, type GitHubIssuePayload } from '../shared/githubIssues';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Diagnostics featureArea reserved for pre-auth sign-in failures. */
export const SIGN_IN_FEATURE_AREA = 'sign_in';

/** Server-only collection linking a report fingerprint to its GitHub issue. */
export const SIGN_IN_ISSUE_LINKS_COLLECTION = 'signInIssueLinks';

/** Labels applied to every auto-filed sign-in-failure issue (must pre-exist). */
export const SIGN_IN_ISSUE_LABEL = 'sign-in-failure';
export const AUTO_GENERATED_LABEL = 'auto-generated';
export const SIGN_IN_ISSUE_LABELS = [SIGN_IN_ISSUE_LABEL, AUTO_GENERATED_LABEL];

/** Title tag identifying an auto-filed sign-in issue. */
export const SIGN_IN_TITLE_TAG = '[Sign-in]';

/** Bounds for the values echoed into the public issue (defence in depth). */
const MAX_ERROR_TYPE_LENGTH = 100;
const MAX_REASON_LENGTH = 300;
const MAX_CONTEXT_LENGTH = 120;

// ---------------------------------------------------------------------------
// Extraction
// ---------------------------------------------------------------------------

/** The sign-in-failure fields lifted out of a `diagnosticsReports/{id}` doc. */
export interface SignInFailureReport {
  /** Sanitized error type/code (exception simple class name or mapped code). */
  errorCode: string | null;
  /** Sanitized single-line reason (already PII-stripped on the client). */
  safeMessage: string;
  appVersion: string | null;
  buildNumber: string | null;
  osVersion: string | null;
  /** Device model, carried via sanitized metadata.deviceModel; may be absent. */
  deviceModel: string | null;
  /** Dedup/grouping fingerprint over the stable report attributes. */
  fingerprint: string;
}

function boundedString(value: unknown, max: number): string | null {
  if (typeof value !== 'string') return null;
  const trimmed = value.replace(/\s+/g, ' ').trim().slice(0, max);
  return trimmed.length > 0 ? trimmed : null;
}

/**
 * Returns the sign-in-failure view of a raw diagnostics doc, or `null` when the
 * doc is NOT a sign-in failure (wrong featureArea) or is missing the required
 * fingerprint/safeMessage. The trigger uses the null return as its "ignore
 * this doc" signal, so every non-sign-in report is a cheap no-op.
 */
export function extractSignInFailureReport(
  data: Record<string, unknown> | undefined | null,
): SignInFailureReport | null {
  if (!data || data.featureArea !== SIGN_IN_FEATURE_AREA) return null;

  const fingerprint = typeof data.fingerprint === 'string' ? data.fingerprint : null;
  const safeMessage = typeof data.safeMessage === 'string' ? data.safeMessage : null;
  if (!fingerprint || !safeMessage) return null;

  const metadata =
    data.metadata && typeof data.metadata === 'object' && !Array.isArray(data.metadata)
      ? (data.metadata as Record<string, unknown>)
      : null;

  return {
    errorCode: boundedString(data.errorCode, MAX_ERROR_TYPE_LENGTH),
    safeMessage: boundedString(safeMessage, MAX_REASON_LENGTH) ?? 'Sign-in failed',
    appVersion: boundedString(data.appVersion, MAX_CONTEXT_LENGTH),
    buildNumber: boundedString(data.buildNumber, MAX_CONTEXT_LENGTH),
    osVersion: boundedString(data.osVersion, MAX_CONTEXT_LENGTH),
    deviceModel: metadata ? boundedString(metadata.deviceModel, MAX_CONTEXT_LENGTH) : null,
    fingerprint,
  };
}

// ---------------------------------------------------------------------------
// Dedup decision + link documents
// ---------------------------------------------------------------------------

export type SignInIssueLinkStatus = 'creating' | 'created';

/** `signInIssueLinks/{fingerprint}` — server-only issue link + occurrence tally. */
export interface SignInIssueLink {
  fingerprint: string;
  status: SignInIssueLinkStatus;
  issueNumber: number | null;
  issueUrl: string | null;
  count: number;
}

/**
 * Dedup decision: with no existing link we CREATE the issue; with any existing
 * link (whether the create is still in flight or already done) we only
 * INCREMENT the occurrence tally — one unique error is one issue, never one per
 * tester/occurrence, and we never comment on every occurrence.
 */
export function decideSignInIssueAction(
  existing: SignInIssueLink | null | undefined,
): 'create' | 'increment' {
  return existing ? 'increment' : 'create';
}

/**
 * The placeholder link written BEFORE the GitHub call (status `creating`), so a
 * concurrent trigger for the same fingerprint sees it and increments instead of
 * filing a duplicate issue. `serverTimestamp` is injected to keep this pure.
 */
export function buildNewSignInIssueLink(
  report: SignInFailureReport,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    fingerprint: report.fingerprint,
    status: 'creating' as SignInIssueLinkStatus,
    issueNumber: null,
    issueUrl: null,
    count: 1,
    firstSeenAt: serverTimestamp(),
    lastSeenAt: serverTimestamp(),
  };
}

/** Patch applied to the link once the issue exists (status `created`). */
export function buildSignInIssueLinkCreated(issue: {
  number: number;
  url: string;
}): Record<string, unknown> {
  return {
    status: 'created' as SignInIssueLinkStatus,
    issueNumber: issue.number,
    issueUrl: issue.url,
  };
}

/**
 * Patch applied on a repeat occurrence: bump the tally and touch lastSeenAt.
 * `increment` is the FieldValue.increment(1) sentinel (injected to stay pure).
 */
export function buildSignInIssueLinkIncrement(
  increment: unknown,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    count: increment,
    lastSeenAt: serverTimestamp(),
  };
}

// ---------------------------------------------------------------------------
// Public GitHub issue (world-readable — no uid/PII)
// ---------------------------------------------------------------------------

/** Best label for the issue title: the error code/type, else the reason. */
function titleSubject(report: SignInFailureReport): string {
  const source = report.errorCode ?? report.safeMessage;
  const subject = source.replace(/\s+/g, ' ').trim().slice(0, MAX_ERROR_TYPE_LENGTH);
  return subject.length > 0 ? subject : 'Sign-in failed';
}

/**
 * Public issue title: `[Sign-in] <error code/type>`. Single-lined; the
 * caller-controlled subject is neutralized so it can't ping/cross-reference.
 */
export function buildSignInIssueTitle(report: SignInFailureReport): string {
  return `${SIGN_IN_TITLE_TAG} ${neutralizeMentions(titleSubject(report))}`;
}

export interface SignInIssueMeta {
  firstSeenIso: string;
  count: number;
}

/**
 * Public issue body. CONTAINS ONLY: the sanitized error code/type, the
 * sanitized reason, app/build/OS version, device model, the fingerprint,
 * first-seen timestamp and occurrence count. NEVER a uid (reports are
 * unauthenticated), email, coordinates or tokens. All client-controlled
 * scalars are neutralized against @mention/#ref abuse; the fingerprint and
 * server-generated timestamp/count are left as-is.
 */
export function buildSignInIssueBody(report: SignInFailureReport, meta: SignInIssueMeta): string {
  const field = (value: string | null): string => (value ? neutralizeMentions(value) : 'unknown');

  const lines = [
    'Automatically filed from an Android sign-in failure reported via the public diagnostics channel (pre-authentication — no account is associated).',
    '',
    `- Error code/type: ${field(report.errorCode)}`,
    `- Reason: ${neutralizeMentions(report.safeMessage)}`,
    `- App version: ${field(report.appVersion)}`,
    `- Build number: ${field(report.buildNumber)}`,
    `- OS version: ${field(report.osVersion)}`,
    `- Device model: ${field(report.deviceModel)}`,
    `- Fingerprint: ${report.fingerprint}`,
    `- First seen: ${meta.firstSeenIso}`,
    `- Occurrences: ${meta.count}`,
    '',
    '_Filed by diagnostics-onSignInFailure. This issue is public and never includes account identifiers or personal data; repeat occurrences update the occurrence counter in signInIssueLinks rather than filing new issues._',
  ];
  return lines.join('\n');
}

/** Full `POST /issues` request body for an auto-filed sign-in-failure issue. */
export function buildSignInIssuePayload(
  report: SignInFailureReport,
  meta: SignInIssueMeta,
): GitHubIssuePayload {
  return {
    title: buildSignInIssueTitle(report),
    body: buildSignInIssueBody(report, meta),
    labels: [...SIGN_IN_ISSUE_LABELS],
  };
}
