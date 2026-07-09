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
 * body therefore carries only a SERVER-BUCKETED exception type — either one of a
 * fixed server-side allowlist of known sign-in exception class names, or the
 * fixed `Unknown` placeholder into which EVERY other valid-but-unknown token
 * collapses (never client free-text) — a fixed reason string, app/build/OS
 * version, device model, the server-derived fingerprint, first-seen timestamp,
 * and occurrence count. This bounds the number of distinct public issues to
 * (allowlist size + 1), so an unauthenticated caller cannot spawn unbounded
 * public issues by cycling through distinct valid tokens (A0Exception,
 * A1Exception, …) — they all land in the single `Unknown` bucket. The
 * client-supplied `safeMessage` free text is NEVER echoed into the public issue
 * (it stays only in the private diagnosticsReports doc). There is NO uid (userId
 * is null), no email, no token. Client context scalars (appVersion/osVersion/
 * deviceModel/buildNumber) are rendered as markdown inline-code spans and
 * neutralized (@mention/#ref) so no attacker-controlled markdown link/image/html
 * can render in the world-readable body. The test suite asserts this.
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

/**
 * Fixed placeholder used whenever the reported type is absent, invalid, OR a
 * valid class-name token that is not on the known-exception allowlist below.
 * ALL non-allowlisted valid tokens collapse into this single bucket.
 */
export const UNKNOWN_ERROR_TYPE = 'Unknown';

/**
 * Server-side allowlist of KNOWN Google/Firebase Android sign-in exception
 * SIMPLE class names. This is the anti-abuse gate: because
 * `diagnostics.submitReport` is unauthenticated, a caller who passes App Check
 * could otherwise mint an unbounded number of distinct public GitHub issues by
 * submitting many distinct valid class-name tokens (`A0Exception`, `A1Exception`
 * …). Only a token whose simple class name is in this set gets its OWN public
 * issue/fingerprint; every other valid token collapses to the single
 * {@link UNKNOWN_ERROR_TYPE} bucket, bounding the distinct-issue count to
 * (allowlist size + 1).
 *
 * Derived from the real Android sign-in path (SignInCoordinator →
 * GoogleCredentialTokenProvider → FirebaseAuthRepository): the app's own
 * `SignInFailedException`/`SignInUnavailableException`, the Credential Manager
 * `GetCredential*` family, the Firebase Auth exception hierarchy, Google Play
 * services `ApiException`/`ResolvableApiException`, and the common JVM/coroutine
 * types those flows can surface. Intentionally EXTENSIBLE — add new known types
 * here as they are observed; unknown types are never lost, they simply share the
 * `Unknown` bucket.
 */
export const KNOWN_SIGN_IN_EXCEPTION_TYPES: ReadonlySet<string> = new Set([
  // App-defined sign-in wrappers (the dominant real-world types).
  'SignInFailedException',
  'SignInUnavailableException',
  // AndroidX Credential Manager.
  'GetCredentialException',
  'GetCredentialCancellationException',
  'GetCredentialInterruptedException',
  'GetCredentialProviderConfigurationException',
  'GetCredentialUnknownException',
  'GetCredentialUnsupportedException',
  'NoCredentialException',
  // Firebase Auth hierarchy.
  'FirebaseAuthException',
  'FirebaseAuthInvalidCredentialsException',
  'FirebaseAuthInvalidUserException',
  'FirebaseAuthUserCollisionException',
  'FirebaseAuthWebException',
  'FirebaseNetworkException',
  'FirebaseTooManyRequestsException',
  'FirebaseApiNotAvailableException',
  // Google Play services / GMS.
  'ApiException',
  'ResolvableApiException',
  // Common JVM / coroutine types those flows can surface.
  'IllegalStateException',
  'IllegalArgumentException',
  'CancellationException',
  'IOException',
  'TimeoutException',
]);

/**
 * Anti-abuse bucketing: maps a strictly-validated class-name token to the type
 * that reaches the PUBLIC issue + fingerprint. Uses the SIMPLE class name (the
 * last dot-separated segment, so `com.google.FirebaseAuthException` and
 * `FirebaseAuthException` collapse together). An allowlisted simple name is
 * returned as-is (its own bucket); anything else — including every otherwise
 * valid but unknown token — collapses to {@link UNKNOWN_ERROR_TYPE}. This is a
 * pure post-validation step: callers still reject non-class-name tokens BEFORE
 * bucketing (see strictExceptionType), so this never sees free text.
 */
export function bucketExceptionType(validToken: string): string {
  const simpleName = validToken.slice(validToken.lastIndexOf('.') + 1);
  return KNOWN_SIGN_IN_EXCEPTION_TYPES.has(simpleName) ? simpleName : UNKNOWN_ERROR_TYPE;
}

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
 * STRICT variant used by the extractor gate: returns the value UNCHANGED when it
 * is EXACTLY the shape the Android reporter sends for `errorCode` (a bounded,
 * anchored, whitespace-free simple/qualified class-name token), or `null`
 * otherwise. Unlike {@link validateExceptionType}, a non-matching value is
 * REJECTED (→ the report is ignored, no public issue) rather than collapsed to
 * `Unknown` — because on the unauthenticated endpoint an off-format `errorCode`
 * means the report was not produced by the real client, so it should not file a
 * public issue at all. No trim: the client never pads the token, and the value
 * must match the raw `safeMessage` (`Sign-in failed: <errorCode>`) byte-for-byte.
 */
function strictExceptionType(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  if (value.length === 0 || value.length > MAX_ERROR_TYPE_LENGTH) return null;
  return EXCEPTION_TYPE_PATTERN.test(value) ? value : null;
}

/**
 * Diagnostics platform reserved for the Android sign-in reporter (the ONLY
 * client that files these). Matches DIAGNOSTICS_PLATFORMS in diagnostics-core.
 */
const ANDROID_PLATFORM = 'android';

/** Diagnostics severity the sign-in reporter always sends. Matches DIAGNOSTICS_SEVERITIES. */
const ERROR_SEVERITY = 'error';

/**
 * Exact `safeMessage` the Android reporter emits for a sign-in failure, given
 * the validated `errorCode`. Mirrors DiagnosticsSignInFailureReporter.kt:
 * `safeMessage = "Sign-in failed: $errorType"` with `errorCode = errorType`.
 */
function expectedSignInSafeMessage(errorCode: string): string {
  return `Sign-in failed: ${errorCode}`;
}

/**
 * Dedup key for a sign-in failure, derived SERVER-SIDE from the fixed feature
 * area + the server-BUCKETED exception type ONLY (see {@link bucketExceptionType})
 * — never from client free text (safeMessage / appVersion / etc). This keeps
 * dedup stable, unpollutable, AND bounded: there is at most ONE public issue per
 * ALLOWLISTED exception type, plus a SINGLE `Unknown` bucket into which every
 * other valid-but-unknown token collapses (so distinct fabricated tokens like
 * `A0Exception`, `A1Exception`, … all map to the same fingerprint/issue).
 * Tokens that are not valid class-name shapes are rejected OUTRIGHT upstream by
 * the extractor and never reach this function (no issue is filed for them).
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
   * The server-BUCKETED exception type — an allowlisted known class-name token,
   * or `Unknown` when the (valid) reported token is not on the allowlist. This
   * is the only client-derived string ever echoed into the public issue; it is
   * safe AND bounded by construction (see {@link bucketExceptionType}).
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
 * doc must NOT file a public issue. The trigger uses the null return as its
 * "ignore this doc" signal, so anything rejected here is a cheap no-op.
 *
 * `diagnostics.submitReport` is UNAUTHENTICATED, so any caller that passes App
 * Check can craft an arbitrary report. To shrink that abuse surface (public
 * issue spam, non-Android/non-error reports filing issues), extraction is
 * STRICT: it returns `null` UNLESS the doc matches EXACTLY what the real Android
 * sign-in reporter (DiagnosticsSignInFailureReporter.kt) sends —
 *   - `featureArea === 'sign_in'`,
 *   - `platform === 'android'`,
 *   - `severity === 'error'`,
 *   - `errorCode` is a strictly-shaped simple/qualified class-name token, AND
 *   - `safeMessage` is exactly `Sign-in failed: <errorCode>`.
 * A non-matching `errorCode` REJECTS the report (no `Unknown` fallback here):
 * an off-format code means the report was not produced by the real client. A
 * VALID token is then anti-abuse BUCKETED (see {@link bucketExceptionType}) — an
 * allowlisted known type keeps its own bucket, every other valid token collapses
 * to the single `Unknown` bucket — before it reaches the public issue/fingerprint,
 * so the distinct-issue count is bounded. The `safeMessage` is validated only as
 * a shape/consistency gate against the RAW client `errorCode` (proving the report
 * came from the real reporter); its content is never surfaced into the public
 * issue (it stays in the private diagnosticsReports doc). The dedup fingerprint is
 * recomputed server-side from the BUCKETED type — the raw `data.fingerprint`
 * (which upstream folds in client free text) is intentionally ignored so a
 * malicious caller cannot pollute dedup.
 */
export function extractSignInFailureReport(
  data: Record<string, unknown> | undefined | null,
): SignInFailureReport | null {
  if (!data) return null;
  if (data.featureArea !== SIGN_IN_FEATURE_AREA) return null;
  if (data.platform !== ANDROID_PLATFORM) return null;
  if (data.severity !== ERROR_SEVERITY) return null;

  // errorCode must be the exact class-name shape the client sends; anything else
  // (free text, email, over-long, non-string) rejects the whole report.
  const rawToken = strictExceptionType(data.errorCode);
  if (rawToken === null) return null;

  // safeMessage must be byte-for-byte the client's `Sign-in failed: <errorCode>`
  // format, checked against the RAW client token (the client's self-consistency
  // gate proving the report came from the real reporter). Its CONTENT is never
  // echoed into the public issue.
  if (data.safeMessage !== expectedSignInSafeMessage(rawToken)) return null;

  // Anti-abuse: only the BUCKETED type (allowlisted known type, else the single
  // `Unknown` bucket) reaches the public issue + fingerprint. This bounds the
  // number of distinct public issues an unauthenticated caller can create.
  const errorType = bucketExceptionType(rawToken);

  const metadata =
    data.metadata && typeof data.metadata === 'object' && !Array.isArray(data.metadata)
      ? (data.metadata as Record<string, unknown>)
      : null;

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
  /**
   * Server timestamps written by the doc builders (Firestore `Timestamp` at
   * runtime). Typed loosely to keep this module free of firebase-admin imports;
   * the trigger reads `lastSeenAt.toMillis()` to detect a stale `creating` claim.
   */
  firstSeenAt?: unknown;
  lastSeenAt?: unknown;
}

/**
 * How long a `creating` claim may stay in-flight before it is treated as STALE
 * and eligible for a repair retry. A create attempt (write placeholder → call
 * GitHub → write `created`) completes in seconds; anything still `creating` well
 * beyond that has almost certainly stranded (e.g. the process died AFTER filing
 * the issue but BEFORE writing the `created` fields, so the issueNumber/issueUrl
 * were lost and the dedup index is stuck in-flight). 10 minutes is comfortably
 * longer than any legitimate in-flight window yet short enough to self-heal.
 */
export const SIGN_IN_ISSUE_STALE_CREATING_MS = 10 * 60 * 1000;

/**
 * Dedup decision:
 * - no existing link → CREATE the issue;
 * - a `failed` link → CREATE (retry): a previous attempt failed but concurrent
 *   occurrences were preserved, so this occurrence re-attempts issue creation
 *   while the caller keeps the existing tally (never resets it);
 * - a STALE `creating` link → CREATE (repair retry): the claim has been in-flight
 *   past {@link SIGN_IN_ISSUE_STALE_CREATING_MS}, which means the create almost
 *   certainly stranded (typically the issue WAS filed but the follow-up
 *   `created` write failed, losing the issueNumber/issueUrl). Rather than leave
 *   the dedup index stuck forever, re-attempt creation. `context` must carry
 *   `nowMs` and the link's last-activity millis for this to trigger; without
 *   `context` (the pure default) a `creating` link is always treated as fresh;
 * - any other existing link (a FRESH `creating` in flight, or `created`) → only
 *   INCREMENT the occurrence tally — one unique error is one issue, never one
 *   per tester/occurrence, and we never comment on every occurrence.
 *
 * Only ONE occurrence ever re-files: the repair retry flips the link back to a
 * FRESH `creating` (bumping lastSeenAt), so any concurrent occurrence re-reading
 * the link sees a non-stale claim and increments instead of double-filing.
 */
export function decideSignInIssueAction(
  existing: SignInIssueLink | null | undefined,
  context?: { nowMs: number; lastActivityMs: number | null },
): 'create' | 'increment' {
  if (!existing) return 'create';
  if (existing.status === 'failed') return 'create';
  if (
    existing.status === 'creating' &&
    context &&
    context.lastActivityMs !== null &&
    context.nowMs - context.lastActivityMs > SIGN_IN_ISSUE_STALE_CREATING_MS
  ) {
    return 'create';
  }
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
 * Patch that RE-CLAIMS a link for another create attempt — used both for a
 * previously-`failed` link AND for a STALE `creating` link (see
 * decideSignInIssueAction). Flips status to `creating` and REFRESHES `lastSeenAt`
 * (so concurrent occurrences during the retry see a fresh, non-stale claim and
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
 * Renders a client-supplied context scalar as a markdown INLINE-CODE span so it
 * cannot inject a link/image/html into the WORLD-READABLE issue body. Markdown
 * is not interpreted inside a code span, so `[x](http://evil)` renders literally
 * instead of as a clickable link; the only char that could break out of the span
 * is a backtick, which is stripped (replaced with `'`) first. @mention/#ref are
 * neutralized too. Applied to every attacker-controllable scalar (app/build/OS
 * version, device model) since `diagnostics.submitReport` is unauthenticated.
 */
function inlineCodeScalar(value: string): string {
  const safe = neutralizeMentions(value).replace(/`/g, "'");
  return `\`${safe}\``;
}

/**
 * Public issue body. CONTAINS ONLY: the server-bucketed exception type, a FIXED
 * (non-client) reason string, app/build/OS version, device model, the
 * server-derived fingerprint, first-seen timestamp and occurrence count. It
 * NEVER echoes the client-supplied `safeMessage` free text, and never a uid
 * (reports are unauthenticated), email, coordinates or tokens. The error type is
 * bucketed to an allowlisted class-name token or `Unknown` (safe by
 * construction); the remaining client scalars are bounded, neutralized against
 * @mention/#ref abuse, AND wrapped in inline-code spans so no attacker-controlled
 * markdown link/image/html can render; the fingerprint and server-generated
 * timestamp/count are left as-is.
 */
export function buildSignInIssueBody(report: SignInFailureReport, meta: SignInIssueMeta): string {
  const field = (value: string | null): string =>
    value ? inlineCodeScalar(value) : 'unknown';

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
