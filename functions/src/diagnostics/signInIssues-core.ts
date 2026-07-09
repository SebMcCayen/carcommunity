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
 * PUBLIC-REPO SAFETY: the repo is world-readable AND `diagnostics.submitReport`
 * is UNAUTHENTICATED, so every report field is attacker-controllable. The issue
 * body therefore carries only a STRICTLY-VALIDATED exception type (a class-name
 * token, or the fixed `Unknown` placeholder — never client free-text), a fixed
 * reason string, app/build/OS version, device model, the server-derived
 * fingerprint, first-seen timestamp, and occurrence count. The client-supplied
 * `safeMessage` free text is NEVER echoed into the public issue (it stays only
 * in the private diagnosticsReports doc). There is NO uid (userId is null), no
 * email, no token. Remaining client scalars are still neutralized (@mention/
 * #ref) and bounded as defence in depth. The test suite asserts this.
 *
 * Pure module — no Firebase Admin SDK and no network imports.
 */

import { createHash } from 'node:crypto';
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
const MAX_CONTEXT_LENGTH = 120;

/**
 * The ONLY shape the Android reporter ever sends for the error type:
 * `throwable.javaClass.simpleName` (optionally fully-qualified). Anchored, no
 * whitespace, and none of `@ # : / " '` or `.@`-style separators, so a token
 * that matches CANNOT carry an email, an @mention/#ref, or free-text PII.
 */
const EXCEPTION_TYPE_PATTERN = /^[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*$/;

/** Fixed placeholder used whenever the reported type is absent or invalid. */
export const UNKNOWN_ERROR_TYPE = 'Unknown';

/** Fixed, non-client reason line shown in the public issue. */
export const SIGN_IN_PUBLIC_REASON = 'pre-authentication sign-in failure';

/**
 * Server-side whitelist for the one client-derived token allowed into the
 * PUBLIC issue. Accepts a value ONLY when it is a bounded, valid (optionally
 * fully-qualified) class-name token; anything else — free text, an email, an
 * over-long string, a non-string, empty — collapses to `Unknown`. Because
 * `diagnostics.submitReport` is unauthenticated, this is the server-side
 * guarantee that no attacker-controlled free text reaches the world-readable
 * repo via the error type.
 */
export function validateExceptionType(value: unknown): string {
  if (typeof value !== 'string') return UNKNOWN_ERROR_TYPE;
  const trimmed = value.trim();
  if (trimmed.length === 0 || trimmed.length > MAX_ERROR_TYPE_LENGTH) return UNKNOWN_ERROR_TYPE;
  return EXCEPTION_TYPE_PATTERN.test(trimmed) ? trimmed : UNKNOWN_ERROR_TYPE;
}

/**
 * Dedup key for a sign-in failure, derived SERVER-SIDE from the fixed feature
 * area + the VALIDATED exception type ONLY — never from client free text
 * (safeMessage / appVersion / etc). This keeps dedup stable and unpollutable:
 * an unauthenticated caller cannot spawn unbounded public issues, since there
 * is at most one issue per distinct valid class name plus a single `Unknown`
 * bucket for everything that fails validation.
 */
export function computeSignInFingerprint(errorType: string): string {
  return createHash('sha256')
    .update(`${SIGN_IN_FEATURE_AREA}|${errorType}`)
    .digest('hex')
    .slice(0, 64);
}

// ---------------------------------------------------------------------------
// Extraction
// ---------------------------------------------------------------------------

/** The sign-in-failure fields lifted out of a `diagnosticsReports/{id}` doc. */
export interface SignInFailureReport {
  /**
   * The VALIDATED exception type — a class-name token, or `Unknown` when the
   * reported value failed validation. This is the only client-derived string
   * ever echoed into the public issue; it is safe by construction.
   */
  errorType: string;
  appVersion: string | null;
  buildNumber: string | null;
  osVersion: string | null;
  /** Device model, carried via sanitized metadata.deviceModel; may be absent. */
  deviceModel: string | null;
  /** Server-derived dedup fingerprint over the VALIDATED exception type only. */
  fingerprint: string;
}

function boundedString(value: unknown, max: number): string | null {
  if (typeof value !== 'string') return null;
  const trimmed = value.replace(/\s+/g, ' ').trim().slice(0, max);
  return trimmed.length > 0 ? trimmed : null;
}

/**
 * Returns the sign-in-failure view of a raw diagnostics doc, or `null` when the
 * doc is NOT a sign-in failure (wrong featureArea) or carries no `safeMessage`
 * (the presence signal for a populated report). The trigger uses the null
 * return as its "ignore this doc" signal, so every non-sign-in report is a
 * cheap no-op.
 *
 * The client-supplied `errorCode` is passed through `validateExceptionType`,
 * and the client-supplied `safeMessage` free text is deliberately NOT surfaced
 * (it stays in the private diagnosticsReports doc). The dedup fingerprint is
 * recomputed server-side from the validated type — the raw `data.fingerprint`
 * (which upstream folds in client free text) is intentionally ignored so a
 * malicious caller cannot pollute dedup.
 */
export function extractSignInFailureReport(
  data: Record<string, unknown> | undefined | null,
): SignInFailureReport | null {
  if (!data || data.featureArea !== SIGN_IN_FEATURE_AREA) return null;

  // A populated sign-in report always carries a safeMessage; we use it only as
  // a presence signal. Its free-text CONTENT is never echoed into the public
  // issue (unauthenticated endpoint + world-readable repo).
  const safeMessage = typeof data.safeMessage === 'string' ? data.safeMessage : null;
  if (!safeMessage || safeMessage.trim().length === 0) return null;

  const metadata =
    data.metadata && typeof data.metadata === 'object' && !Array.isArray(data.metadata)
      ? (data.metadata as Record<string, unknown>)
      : null;

  const errorType = validateExceptionType(data.errorCode);

  return {
    errorType,
    appVersion: boundedString(data.appVersion, MAX_CONTEXT_LENGTH),
    buildNumber: boundedString(data.buildNumber, MAX_CONTEXT_LENGTH),
    osVersion: boundedString(data.osVersion, MAX_CONTEXT_LENGTH),
    deviceModel: metadata ? boundedString(metadata.deviceModel, MAX_CONTEXT_LENGTH) : null,
    fingerprint: computeSignInFingerprint(errorType),
  };
}

// ---------------------------------------------------------------------------
// Dedup decision + link documents
// ---------------------------------------------------------------------------

/**
 * `creating` — placeholder written before the GitHub call; `created` — the
 * issue exists; `failed` — a create attempt failed but the link was kept
 * because concurrent occurrences had already been tallied, so a future
 * occurrence must RETRY creation (see decideSignInIssueAction) rather than lose
 * the count.
 */
export type SignInIssueLinkStatus = 'creating' | 'created' | 'failed';

/** `signInIssueLinks/{fingerprint}` — server-only issue link + occurrence tally. */
export interface SignInIssueLink {
  fingerprint: string;
  status: SignInIssueLinkStatus;
  issueNumber: number | null;
  issueUrl: string | null;
  count: number;
}

/**
 * Dedup decision:
 * - no existing link → CREATE the issue;
 * - a `failed` link → CREATE (retry): a previous attempt failed but concurrent
 *   occurrences were preserved, so this occurrence re-attempts issue creation
 *   while the caller keeps the existing tally (never resets it);
 * - any other existing link (`creating` in flight, or `created`) → only
 *   INCREMENT the occurrence tally — one unique error is one issue, never one
 *   per tester/occurrence, and we never comment on every occurrence.
 */
export function decideSignInIssueAction(
  existing: SignInIssueLink | null | undefined,
): 'create' | 'increment' {
  if (!existing) return 'create';
  if (existing.status === 'failed') return 'create';
  return 'increment';
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

/**
 * Patch that RE-CLAIMS a previously-`failed` link for another create attempt:
 * flips status back to `creating` (so concurrent occurrences during the retry
 * increment instead of double-filing) and counts this occurrence — WITHOUT
 * resetting the preserved `count`/`firstSeenAt`. `increment` is the
 * FieldValue.increment(1) sentinel (injected to stay pure).
 */
export function buildSignInIssueLinkRetry(
  increment: unknown,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    status: 'creating' as SignInIssueLinkStatus,
    count: increment,
    lastSeenAt: serverTimestamp(),
  };
}

/**
 * Patch applied when a create attempt failed BUT the placeholder must be kept
 * because concurrent occurrences already bumped the tally: mark the link
 * `failed` (retriable) while preserving `count`/`firstSeenAt`/`lastSeenAt`, so
 * no occurrence is lost and a future occurrence retries issue creation.
 */
export function buildSignInIssueLinkFailed(): Record<string, unknown> {
  return {
    status: 'failed' as SignInIssueLinkStatus,
  };
}

// ---------------------------------------------------------------------------
// Public GitHub issue (world-readable — no uid/PII)
// ---------------------------------------------------------------------------

/**
 * Public issue title: `[Sign-in] <validated exception type>` (or `Unknown`).
 * The subject is the already-validated class-name token, so it is safe by
 * construction; neutralizeMentions is applied as belt-and-suspenders only.
 */
export function buildSignInIssueTitle(report: SignInFailureReport): string {
  return `${SIGN_IN_TITLE_TAG} ${neutralizeMentions(report.errorType)}`;
}

export interface SignInIssueMeta {
  firstSeenIso: string;
  count: number;
}

/**
 * Public issue body. CONTAINS ONLY: the VALIDATED exception type, a FIXED
 * (non-client) reason string, app/build/OS version, device model, the
 * server-derived fingerprint, first-seen timestamp and occurrence count. It
 * NEVER echoes the client-supplied `safeMessage` free text, and never a uid
 * (reports are unauthenticated), email, coordinates or tokens. The error type
 * is validated to a class-name token; the remaining client scalars are bounded
 * and neutralized against @mention/#ref abuse; the fingerprint and
 * server-generated timestamp/count are left as-is.
 */
export function buildSignInIssueBody(report: SignInFailureReport, meta: SignInIssueMeta): string {
  const field = (value: string | null): string => (value ? neutralizeMentions(value) : 'unknown');

  const lines = [
    'Automatically filed from an Android sign-in failure reported via the public diagnostics channel (pre-authentication — no account is associated).',
    '',
    `- Error type: ${neutralizeMentions(report.errorType)}`,
    `- Reason: ${SIGN_IN_PUBLIC_REASON}`,
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
