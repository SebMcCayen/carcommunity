/**
 * Server-error reporting — pure domain logic (classification + scrubbing, dedup
 * fingerprint, private-record builder, public-issue builders).
 *
 * THE GAP THIS CLOSES. Before this module the only failures that ever reached a
 * human were the ones a CLIENT chose to report (errors.reportClientError) or a
 * sign-in failure the client reported pre-auth (diagnostics.submitReport). Every
 * BACKEND failure — a scheduled sweep throwing halfway through, a Firestore
 * trigger crashing, an unexpected non-HttpsError inside a callable — went to
 * Cloud Logging and nowhere else, i.e. nobody noticed. Scheduled functions are
 * the worst case: they have no user watching a spinner, so a silently broken
 * nightly purge or reminder sweep can stay broken indefinitely.
 *
 * THE PUBLIC-REPO PROBLEM. The natural fix — file a GitHub issue with the error
 * message and stack — is unsafe here, because SebMcCayen/carcommunity is PUBLIC
 * and issues are world-readable forever. Server-side error text is routinely
 * full of things that must never be published:
 *
 *   - Firestore errors embed the document path, and this schema's paths contain
 *     uids (`users/{uid}`, `notifications/{uid}/items/{id}`, `liveSessions/{uid}`);
 *   - validation and auth errors embed the offending value: an email, a display
 *     name, a phone number, a token prefix;
 *   - geo errors embed coordinates, which de-anonymise a member's home address;
 *   - a stack trace embeds absolute deploy paths and, via arguments in some
 *     runtimes, request payloads.
 *
 * THE DESIGN: OPAQUE CORRELATION ID. The public issue carries a strict ALLOWLIST
 * of server-controlled, non-identifying facts plus a fingerprint; the FULL detail
 * (message, stack, context) lives only in the private, admin-read-only
 * `serverErrorReports` collection, findable by that same fingerprint. The public
 * body is built by ADDING allowed fields, never by removing/redacting disallowed
 * ones — a redaction allowlist inverted into a denylist is how PII leaks.
 *
 * Public issue body allowlist (nothing else is ever rendered):
 *   source        — a hard-coded string constant chosen by a developer at the
 *                   call site (e.g. `account.purgeDeleted`), re-validated here.
 *   errorName     — the error's constructor/`name`, pattern-filtered.
 *   errorCode     — `error.code` only if it looks like a gRPC/Firebase status.
 *   frames        — up to 5 stack frames reduced to `basename:line`. No absolute
 *                   paths, no columns, no function names, no arguments.
 *   fingerprint   — sha256 hex; the correlation id.
 *   firstSeenIso  — server timestamp of the first occurrence.
 *   count         — occurrence tally.
 *   a pointer sentence explaining where the full detail lives.
 *
 * NEVER in the public issue: `error.message`, `error.stack` (raw), uid, email,
 * display name, coordinates, Firestore document paths, request payloads,
 * environment values, tokens/secrets. `serverErrors-core.test.ts` asserts this
 * against messages and stacks seeded with exactly those values.
 *
 * Pure module — no Firebase Admin SDK and no network imports, so every branch is
 * unit-testable without emulators (mirrors the sibling *-core.ts files).
 */

import { createHash } from 'node:crypto';
import { boundText, neutralizeMentions } from '../feedback/feedback-core';
import { AUTO_GENERATED_LABEL } from '../diagnostics/signInIssues-core';
import type { GitHubIssuePayload } from '../shared/githubIssues';
import {
  buildNewIssueLink,
  type IssueLinkState,
  type IssueLinkStatus,
} from '../shared/issueLinks-core';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Private record of record (admin-only read) — holds the FULL error detail. */
export const SERVER_ERROR_REPORTS_COLLECTION = 'serverErrorReports';
/** Server-only fingerprint → GitHub issue dedup index. */
export const SERVER_ERROR_ISSUE_LINKS_COLLECTION = 'serverErrorIssueLinks';

/**
 * Distinct source label, same convention as `auto-error` (client errors) and
 * `sign-in-failure` (pre-auth failures); `auto-generated` is shared by all
 * auto-filing paths. BOTH labels must already exist on the repo.
 */
export const SERVER_ERROR_ISSUE_LABEL = 'server-error';
export const SERVER_ERROR_ISSUE_LABELS = [SERVER_ERROR_ISSUE_LABEL, AUTO_GENERATED_LABEL];

/** Title tag identifying an auto-filed server-error issue. */
export const SERVER_ERROR_TITLE_TAG = '[Auto-server-error]';

/** Frames kept in the fingerprint + public body. Enough to locate, not to dump. */
export const MAX_SERVER_ERROR_FRAMES = 5;

/** Fallback when the error's name is missing or fails the allowlist. */
export const UNKNOWN_ERROR_NAME = 'UnknownError';
/** Fallback when the call site's `source` fails the allowlist. */
export const UNKNOWN_SOURCE = 'unknown';

// Bounds for the PRIVATE record (never rendered publicly, but still bounded so a
// pathological error cannot blow up the document-size limit).
export const MAX_PRIVATE_MESSAGE_LENGTH = 2000;
export const MAX_PRIVATE_STACK_LENGTH = 8000;
export const MAX_CONTEXT_KEYS = 12;
export const MAX_CONTEXT_KEY_LENGTH = 40;
export const MAX_CONTEXT_VALUE_LENGTH = 200;

// ---------------------------------------------------------------------------
// Allowlist patterns — the public body renders ONLY values matching these
// ---------------------------------------------------------------------------

/**
 * `source`: a developer-authored constant such as `account.purgeDeleted`. Dots
 * and dashes allowed (the domain.action convention); nothing else, so a source
 * string can never smuggle markdown, a URL or a newline into the public body.
 */
const SOURCE_PATTERN = /^[A-Za-z][A-Za-z0-9_.-]{0,79}$/;

/** `errorName`: a JS identifier, e.g. `TypeError`, `FirebaseFirestoreError`. */
const ERROR_NAME_PATTERN = /^[A-Za-z][A-Za-z0-9_]{0,59}$/;

/**
 * `errorCode`: gRPC/Firebase status style ONLY — lower-kebab (`not-found`,
 * `failed-precondition`, `permission-denied`) or SCREAMING_SNAKE
 * (`FAILED_PRECONDITION`, `ENOTFOUND`). Deliberately strict: `error.code` is
 * NOT always a status. Node fs/net errors put a path-bearing string there, and
 * some libraries put an arbitrary message. Anything that is not obviously an
 * enum member is dropped rather than published.
 */
const ERROR_CODE_LOWER_PATTERN = /^[a-z][a-z0-9-]{0,39}$/;
const ERROR_CODE_UPPER_PATTERN = /^[A-Z][A-Z0-9_]{0,39}$/;

/**
 * Stack-frame file BASENAME: no `/`, no `\`, no `:`, so a reduced frame can
 * never re-introduce an absolute deploy path or a URL.
 */
const FRAME_FILE_PATTERN = /^[A-Za-z0-9._-]{1,80}$/;

// ---------------------------------------------------------------------------
// Classification
// ---------------------------------------------------------------------------

/** Everything the public issue is allowed to know about an error. */
export interface ServerErrorClassification {
  errorName: string;
  errorCode: string | null;
  /** Up to MAX_SERVER_ERROR_FRAMES entries of the form `basename:line`. */
  frames: string[];
}

/** Validated call-site label. Falls back to `unknown` rather than publishing junk. */
export function normalizeServerErrorSource(source: unknown): string {
  return typeof source === 'string' && SOURCE_PATTERN.test(source) ? source : UNKNOWN_SOURCE;
}

/**
 * Names that are true but carry no signal, so they must never win over a more
 * specific candidate. `Error` is what `new Error()` and most library errors
 * report as their constructor even when they set a meaningful `name`; `Object` is
 * what a thrown plain object reports and is never a legitimate error kind.
 */
const GENERIC_ERROR_NAMES = new Set(['Error', 'Object']);

/**
 * The error's kind, from `error.name` or the constructor name, whichever is more
 * SPECIFIC — both orderings are wrong on their own:
 *
 *  - constructor-name-first loses the real kind for the commonest case in this
 *    codebase, `Object.assign(new Error(msg), { name: 'FirebaseFirestoreError' })`
 *    style errors from the Firebase/gRPC libraries (constructor is `Error`);
 *  - name-first loses the kind for a `class FooError extends Error` subclass that
 *    never assigns `name` (name is inherited as `Error`).
 *
 * So: take the first candidate that passes the identifier allowlist AND is not
 * generic; otherwise settle for the honest `Error`; otherwise `UnknownError`. A
 * thrown non-object (string, number) has no trustworthy kind at all, and a name
 * that fails the allowlist is discarded rather than published — its text stays in
 * the private record.
 */
function classifyErrorName(error: unknown): string {
  if (typeof error !== 'object' || error === null) return UNKNOWN_ERROR_NAME;

  const allowed = (value: unknown): string | null =>
    typeof value === 'string' && ERROR_NAME_PATTERN.test(value) ? value : null;

  const name = allowed((error as { name?: unknown }).name);
  const constructorName = allowed(
    (error as { constructor?: { name?: unknown } }).constructor?.name,
  );

  for (const candidate of [name, constructorName]) {
    if (candidate && !GENERIC_ERROR_NAMES.has(candidate)) return candidate;
  }
  if (name === 'Error' || constructorName === 'Error') return 'Error';
  return UNKNOWN_ERROR_NAME;
}

/** `error.code`, but only when it is unmistakably a status enum member. */
function classifyErrorCode(error: unknown): string | null {
  if (typeof error !== 'object' || error === null) return null;
  const code = (error as { code?: unknown }).code;
  if (typeof code !== 'string') return null;
  if (ERROR_CODE_LOWER_PATTERN.test(code) || ERROR_CODE_UPPER_PATTERN.test(code)) {
    return code;
  }
  return null;
}

/**
 * Reduces one raw V8 stack line to `basename:line`, or null when it is not a
 * publishable application frame.
 *
 * Handled shapes:
 *   `    at Object.run (/srv/lib/account/scheduled.js:494:20)`
 *   `    at /srv/lib/errors/serverErrors.js:88:11`
 *   `    at async Promise.all (index 0)`                        → dropped
 *   `    at processTicksAndRejections (node:internal/...:95:5)` → dropped
 *   `    at Module._load (/srv/node_modules/foo/index.js:3:1)`  → dropped
 *
 * Dropped on purpose: node-internal and node_modules frames (they say nothing
 * about OUR bug and destabilise the fingerprint across runtime upgrades), and
 * anything whose basename fails FRAME_FILE_PATTERN. The function name is
 * discarded even though it is usually harmless — it can be a closure name
 * derived from user data, and it is not needed to locate the line.
 */
function reduceStackFrame(line: string): string | null {
  // Prefer the parenthesised location; fall back to a bare `at <path>:line:col`.
  const match =
    /\(([^()]+):(\d+):(\d+)\)\s*$/.exec(line) ?? /at\s+([^\s()]+):(\d+):(\d+)\s*$/.exec(line);
  if (!match) return null;

  const rawPath = match[1];
  const rawLine = match[2];
  if (rawPath === undefined || rawLine === undefined) return null;

  if (rawPath.includes('node_modules')) return null;
  if (rawPath.startsWith('node:') || rawPath.startsWith('internal/')) return null;

  const basename = rawPath.split(/[\\/]/).pop() ?? '';
  if (!FRAME_FILE_PATTERN.test(basename)) return null;
  if (!/^\d{1,7}$/.test(rawLine)) return null;

  return `${basename}:${rawLine}`;
}

/** The publishable frames of a stack, top-first, capped. */
export function reduceStackFrames(stack: unknown): string[] {
  if (typeof stack !== 'string') return [];
  const frames: string[] = [];
  for (const line of stack.split('\n')) {
    const reduced = reduceStackFrame(line);
    if (reduced) {
      frames.push(reduced);
      if (frames.length >= MAX_SERVER_ERROR_FRAMES) break;
    }
  }
  return frames;
}

/**
 * Classifies an arbitrary thrown value into the publishable triple. Total: every
 * input (Error, subclass, string, null, object with a hostile `name`/`code`)
 * yields a valid classification, because a reporting path must never itself throw.
 */
export function classifyServerError(error: unknown): ServerErrorClassification {
  return {
    errorName: classifyErrorName(error),
    errorCode: classifyErrorCode(error),
    frames: reduceStackFrames(
      typeof error === 'object' && error !== null
        ? (error as { stack?: unknown }).stack
        : undefined,
    ),
  };
}

// ---------------------------------------------------------------------------
// Expected errors (never reported)
// ---------------------------------------------------------------------------

/**
 * True for a `functions.https.HttpsError`. These are DELIBERATE, client-facing
 * outcomes ("not-found", "permission-denied", "resource-exhausted") — the
 * documented contract of every callable, thrown thousands of times a day by
 * normal use. Reporting them would bury the real bugs and burn the issue budget.
 *
 * Duck-typed (rather than `instanceof`) so this stays in the pure core and keeps
 * working across the two HttpsError classes firebase-functions ships (v1
 * `https.HttpsError` and v2 `v2/https.HttpsError`) and across module-duplication
 * in bundling. The impure wrapper additionally does an `instanceof` check.
 */
export function isDeliberateHttpsError(error: unknown): boolean {
  if (typeof error !== 'object' || error === null) return false;
  const candidate = error as { name?: unknown; code?: unknown; httpErrorCode?: unknown };
  if (candidate.name === 'HttpsError') return true;
  // v2 HttpsError carries an httpErrorCode descriptor alongside a status `code`.
  return typeof candidate.code === 'string' && typeof candidate.httpErrorCode === 'object';
}

// ---------------------------------------------------------------------------
// Fingerprint
// ---------------------------------------------------------------------------

/**
 * Dedup fingerprint over (source, errorName, errorCode, frames) — all four are
 * server-controlled and stable across occurrences, so the same bug recurring
 * every 5 minutes maps to ONE issue.
 *
 * The message is deliberately EXCLUDED (unlike the client-error fingerprint,
 * which normalises it): server messages embed doc paths and ids, so even
 * normalised they would fragment the fingerprint per uid — turning one bug into
 * one issue PER AFFECTED USER, which is both spam and a slow PII leak by
 * enumeration. Frames give better locality anyway.
 *
 * Consequence, accepted: because frames carry line numbers, editing a file
 * shifts the fingerprint and a still-unfixed error can file a second issue after
 * a refactor. That is the standard trade-off (Sentry behaves the same) and is
 * strictly safer than the alternative.
 */
export function computeServerErrorFingerprint(
  source: string,
  errorName: string,
  errorCode: string | null,
  frames: string[],
): string {
  const signature = [
    normalizeServerErrorSource(source),
    errorName,
    errorCode ?? '',
    frames.slice(0, MAX_SERVER_ERROR_FRAMES).join('>'),
  ].join('|');
  return createHash('sha256').update(signature).digest('hex').slice(0, 64);
}

// ---------------------------------------------------------------------------
// Private Firestore record of record (FULL detail, admin-only read)
// ---------------------------------------------------------------------------

export type ServerErrorIssueStatus = 'pending' | 'created' | 'failed' | 'skipped';

/** The normalized view every builder consumes. */
export interface ServerErrorReport {
  source: string;
  errorName: string;
  errorCode: string | null;
  frames: string[];
  /** FULL message — private record only, NEVER published. */
  message: string;
  /** Trimmed raw stack — private record only, NEVER published. */
  stack: string | null;
  /** Bounded scalar context from the call site — private record only. */
  context: Record<string, string> | null;
  fingerprint: string;
}

/**
 * Bounds free-form call-site context to a small map of short scalars. Objects,
 * arrays and functions are dropped rather than serialised: nested structures are
 * where whole request payloads sneak in, and this map is only meant for a handful
 * of triage hints. Values live ONLY in the private record.
 */
export function boundServerErrorContext(
  context: Record<string, unknown> | undefined | null,
): Record<string, string> | null {
  if (!context || typeof context !== 'object') return null;
  const bounded: Record<string, string> = {};
  let kept = 0;
  for (const [key, value] of Object.entries(context)) {
    if (kept >= MAX_CONTEXT_KEYS) break;
    if (!/^[A-Za-z][A-Za-z0-9_.-]{0,39}$/.test(key)) continue;
    if (key.length > MAX_CONTEXT_KEY_LENGTH) continue;
    let rendered: string | null = null;
    if (typeof value === 'string') rendered = value;
    else if (typeof value === 'number' && Number.isFinite(value)) rendered = String(value);
    else if (typeof value === 'boolean') rendered = String(value);
    if (rendered === null) continue;
    const safe = boundText(rendered, MAX_CONTEXT_VALUE_LENGTH);
    if (safe.length === 0) continue;
    bounded[key] = safe;
    kept += 1;
  }
  return kept > 0 ? bounded : null;
}

/**
 * Builds the normalized report from a raw thrown value. This is the single place
 * where the public/private split is decided: `classifyServerError` produces the
 * publishable triple, and the unfiltered message/stack are carried alongside it
 * for the PRIVATE document only.
 */
export function buildServerErrorReport(
  source: string,
  error: unknown,
  context?: Record<string, unknown> | null,
): ServerErrorReport {
  const normalizedSource = normalizeServerErrorSource(source);
  const { errorName, errorCode, frames } = classifyServerError(error);

  const rawMessage =
    typeof error === 'object' &&
    error !== null &&
    typeof (error as { message?: unknown }).message === 'string'
      ? (error as { message: string }).message
      : String(error);
  const rawStack =
    typeof error === 'object' &&
    error !== null &&
    typeof (error as { stack?: unknown }).stack === 'string'
      ? (error as { stack: string }).stack
      : null;

  return {
    source: normalizedSource,
    errorName,
    errorCode,
    frames,
    message: boundText(rawMessage, MAX_PRIVATE_MESSAGE_LENGTH) || errorName,
    stack: rawStack ? rawStack.slice(0, MAX_PRIVATE_STACK_LENGTH) : null,
    context: boundServerErrorContext(context),
    fingerprint: computeServerErrorFingerprint(normalizedSource, errorName, errorCode, frames),
  };
}

/**
 * `serverErrorReports/{reportId}` — the private record of record (admin-only
 * read). Written FIRST so the report is durable before any GitHub attempt, and
 * patched by the trigger with the issue number/url/status for triage. This is the
 * ONLY place the message/stack/context exist; the public issue points here via
 * the fingerprint.
 */
export function buildServerErrorReportDocument(
  report: ServerErrorReport,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    source: report.source,
    errorName: report.errorName,
    errorCode: report.errorCode,
    frames: report.frames,
    message: report.message,
    stack: report.stack,
    context: report.context,
    fingerprint: report.fingerprint,
    githubIssueStatus: 'pending' as ServerErrorIssueStatus,
    githubIssueNumber: null,
    githubIssueUrl: null,
    createdAt: serverTimestamp(),
  };
}

// ---------------------------------------------------------------------------
// Dedup link documents (serverErrorIssueLinks) — shared state machine
// ---------------------------------------------------------------------------

/** `serverErrorIssueLinks/{fingerprint}` — server-only issue link + tally. */
export interface ServerErrorIssueLink extends IssueLinkState {
  fingerprint: string;
  source: string;
  status: IssueLinkStatus;
  issueNumber: number | null;
  issueUrl: string | null;
  count: number;
  firstSeenAt?: unknown;
  lastSeenAt?: unknown;
}

/**
 * There are deliberately no domain-named aliases for the claim/increment/retry
 * builders here (unlike clientErrors-core.ts, which keeps them for backwards
 * compatibility). The whole claim → budget → create → reconcile flow lives in
 * shared/autoIssueFiling.ts and drives shared/issueLinks-core.ts directly, so a
 * second set of pass-through names would be dead code. Only the placeholder
 * builder below is domain-specific, because it decides which descriptor fields
 * the link document carries.
 */

/** Placeholder link written BEFORE the GitHub call (status `creating`). */
export function buildNewServerErrorIssueLink(
  report: ServerErrorReport,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return buildNewIssueLink(
    { fingerprint: report.fingerprint, source: report.source },
    serverTimestamp,
  );
}

// ---------------------------------------------------------------------------
// Public GitHub issue (world-readable forever — strict allowlist)
// ---------------------------------------------------------------------------

export interface ServerErrorIssueMeta {
  firstSeenIso: string;
  count: number;
}

/**
 * Renders an allowlisted scalar as a markdown inline-code span so it cannot
 * inject a link/image/html into the world-readable issue body; backticks are
 * neutralized first, @mention/#ref are defanged, and all whitespace is collapsed
 * so nothing can break out of the bullet layout. Every value reaching this
 * function has ALREADY passed a pattern allowlist — this is defence in depth, not
 * the primary control. Mirrors clientErrors-core.inlineCodeScalar.
 */
function inlineCodeScalar(value: string): string {
  const safe = neutralizeMentions(value).replace(/`/g, "'").replace(/\s+/g, ' ').trim();
  return `\`${safe}\``;
}

/**
 * Public issue title: `[Auto-server-error] <source>: <errorName>`. Both halves
 * are allowlisted, server-controlled tokens — no error message, so the title
 * cannot leak a doc path even in a notification email preview.
 */
export function buildServerErrorIssueTitle(report: ServerErrorReport): string {
  return `${SERVER_ERROR_TITLE_TAG} ${neutralizeMentions(report.source)}: ${report.errorName}`;
}

/**
 * Public issue body — the ALLOWLIST rendered, and nothing else.
 *
 * Included: source, errorName, errorCode, reduced `basename:line` frames,
 * fingerprint, first-seen ISO timestamp, occurrence count, and a pointer to the
 * private record.
 *
 * Excluded by construction (this function never reads them, even though the
 * report object carries them): `report.message`, `report.stack`,
 * `report.context`. That is the point — a maintainer adding a "just the message,
 * it's usually fine" line has to defeat serverErrors-core.test.ts, which seeds a
 * uid, an email, a Firestore doc path and coordinates into the message/stack and
 * asserts none of them appear here.
 */
export function buildServerErrorIssueBody(
  report: ServerErrorReport,
  meta: ServerErrorIssueMeta,
): string {
  const framesRendered =
    report.frames.length > 0
      ? report.frames.map((frame) => inlineCodeScalar(frame)).join(' ← ')
      : 'unavailable';
  return [
    'Automatically filed from an UNHANDLED SERVER-SIDE error (scheduled job, trigger, or wrapped handler). Repeat occurrences increment the tally on the private issue link instead of filing new issues.',
    '',
    `- Source: ${inlineCodeScalar(report.source)}`,
    `- Error: ${inlineCodeScalar(report.errorName)}`,
    `- Code: ${report.errorCode ? inlineCodeScalar(report.errorCode) : 'none'}`,
    `- Frames (top ${MAX_SERVER_ERROR_FRAMES}, innermost first): ${framesRendered}`,
    `- Fingerprint: ${report.fingerprint}`,
    `- First seen: ${meta.firstSeenIso}`,
    `- Occurrences: ${meta.count}`,
    '',
    `**Full detail is deliberately NOT in this issue.** This repository is public, and server-side error text routinely embeds Firestore document paths (which contain uids), user-supplied values and coordinates. The complete message, stack trace and call-site context live in the private, admin-read-only \`${SERVER_ERROR_REPORTS_COLLECTION}\` collection — query it by the fingerprint above (\`where('fingerprint', '==', …)\`) from the KCC admin tooling.`,
    '',
    '_Filed by errors-onServerErrorReport. This issue is public and never includes account identifiers, user content, coordinates, document paths or secrets._',
  ].join('\n');
}

/** Full `POST /issues` request body for an auto-filed server-error issue. */
export function buildServerErrorIssuePayload(
  report: ServerErrorReport,
  meta: ServerErrorIssueMeta,
): GitHubIssuePayload {
  return {
    title: buildServerErrorIssueTitle(report),
    body: buildServerErrorIssueBody(report, meta),
    labels: [...SERVER_ERROR_ISSUE_LABELS],
  };
}
