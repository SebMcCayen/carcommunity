/**
 * Crashlytics → GitHub-issue bridge — pure domain logic.
 *
 * Native crashes and ANRs reach Firebase Crashlytics (SDK + gradle plugin are
 * wired in the Android app, collection-on for release builds — see
 * docs/crashlytics.md), but nothing turned a NEW Crashlytics issue into a
 * GitHub issue, so crashes were invisible to the issue tracker. This module is
 * the mapping half of that bridge: it turns a Firebase Alerts Crashlytics
 * payload into the deduplicated PUBLIC GitHub issue the shared auto-filing
 * flow (shared/autoIssueFiling.ts) then creates.
 *
 * It mirrors the two existing auto-filers (errors/clientErrors-core.ts +
 * errors/serverErrors-core.ts) rather than inventing a new shape:
 *
 *  - the DEDUP FINGERPRINT is the Crashlytics ISSUE ID itself — the same crash
 *    issue therefore files exactly one GitHub issue, and every later occurrence
 *    or a re-emergence (regression) of that same id bumps the occurrence tally
 *    in the server-only `crashlyticsIssueLinks/{issueId}` collection instead of
 *    opening a new issue;
 *  - the create is charged against the SAME global hourly issue budget
 *    (shared/issueBudget-core.ts, 20 issues/hour across ALL auto-filers), so a
 *    crash storm across many distinct issue ids cannot spam the public repo.
 *
 * PUBLIC-REPO SAFETY: a Crashlytics alert payload carries only the issue id,
 * a title (exception class + top frame), a subtitle, and an app version — no
 * uid, no device id, no user data (crash telemetry has no consent gate but also
 * no PII in the alert). The FULL multi-frame stack trace is NOT in the alert
 * payload; it lives behind the Crashlytics console deep link. The issue body
 * says so honestly. Every alert-supplied scalar is neutralized + inline-coded
 * before it reaches the world-readable issue.
 *
 * Pure module — no Firebase Admin SDK and no network imports, so every branch
 * is unit-testable without emulators (Firebase Alerts triggers are not
 * emulable), exactly like the sibling *-core.ts files. Firestore sentinels are
 * injected by the caller as opaque values.
 */

import { AUTO_GENERATED_LABEL } from '../diagnostics/signInIssues-core';
import { neutralizeMentions, type GitHubIssuePayload } from '../shared/githubIssues';
import { buildNewIssueLink } from '../shared/issueLinks-core';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Server-only Crashlytics-issue-id → GitHub issue dedup index + tally. */
export const CRASHLYTICS_ISSUE_LINKS_COLLECTION = 'crashlyticsIssueLinks';

/** Shared label so every auto-filed crash issue is separable from manual reports. */
export const CRASH_ISSUE_LABEL = 'android-crash';
/** Distinct label for ANR (Application Not Responding) issues. */
export const ANR_ISSUE_LABEL = 'anr';
/** Extra label flagging a re-emerged (previously-resolved) crash. */
export const REGRESSION_ISSUE_LABEL = 'regression';

/** The three alert kinds this bridge subscribes to. */
export type CrashAlertKind = 'fatal' | 'anr' | 'regression';

/** Bounds (defence in depth; alert fields are Google-controlled but bounded anyway). */
export const MAX_ISSUE_ID_LENGTH = 200;
export const MAX_TITLE_LENGTH = 300;
export const MAX_SUBTITLE_LENGTH = 500;
export const MAX_APP_VERSION_LENGTH = 100;
export const MAX_REGRESSION_TYPE_LENGTH = 100;

// ---------------------------------------------------------------------------
// Normalized alert
// ---------------------------------------------------------------------------

/** The Crashlytics `Issue` sub-object carried by every alert payload. */
export interface CrashlyticsIssueLike {
  id?: unknown;
  title?: unknown;
  subtitle?: unknown;
  appVersion?: unknown;
}

/** The subset of a Crashlytics alert payload this bridge reads. */
export interface CrashAlertPayloadLike {
  issue?: CrashlyticsIssueLike;
  /** Present only on regression payloads: the Crashlytics issue type string. */
  type?: unknown;
  /** Present only on regression payloads: when the issue was last resolved. */
  resolveTime?: unknown;
}

/** Normalized, bounded view of one Crashlytics alert used by every builder. */
export interface NormalizedCrashAlert {
  kind: CrashAlertKind;
  /** The Crashlytics issue id — ALSO the dedup fingerprint / link doc id. */
  issueId: string;
  /** Exception class + top frame (Crashlytics issue title); may be unknown. */
  title: string | null;
  /** Crashlytics issue subtitle; may be unknown. */
  subtitle: string | null;
  /** App version the issue was seen on; may be unknown. */
  appVersion: string | null;
  /** Regression only: the underlying crash type Crashlytics reports. */
  regressionType: string | null;
  /** Regression only: ISO time the issue was last resolved before re-emerging. */
  resolveTime: string | null;
  /** Deep link to the Crashlytics issue in the Firebase console. */
  deepLink: string;
}

const TITLE_TAG: Record<CrashAlertKind, string> = {
  fatal: '[Crash]',
  anr: '[ANR]',
  regression: '[Crash regression]',
};

const KIND_LABEL: Record<CrashAlertKind, string> = {
  fatal: 'Fatal crash',
  anr: 'ANR (Application Not Responding)',
  regression: 'Regressed crash (re-emerged after being resolved)',
};

/**
 * Human-readable label for the crash kind, used in the issue body. Exposed so
 * tests pin the fatal/anr/regression branching without reaching into the body.
 */
export function crashKindLabel(kind: CrashAlertKind): string {
  return KIND_LABEL[kind];
}

/**
 * Trims a possibly-non-string alert field to a bounded single-ish value, or
 * null when it is absent/blank. Never throws on a missing or wrong-typed field.
 */
function boundedField(value: unknown, max: number): string | null {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  if (trimmed.length === 0) return null;
  return trimmed.slice(0, max);
}

/**
 * Firestore document ids must be a single non-empty path segment. Reject a
 * blank id, one containing '/', and the '.'/'..' reserved segments (so the
 * dedup key can never be coerced into a subcollection path or a bad ref), AND
 * any id carrying whitespace or a control character. Crashlytics issue ids are
 * opaque alphanumeric strings, so a legitimate id never contains a newline/tab —
 * whereas `boundedField` only trims the ends, so an INTERNAL newline would
 * otherwise survive into a log line (log injection) and into the console deep
 * link (a broken URL). `\s` covers space/tab/newline/CR/formfeed/vtab;
 * explicit ranges cover the remaining C0 control chars and DEL (0x7f).
 */
const UNSAFE_DOC_ID_CHAR = /[\s\u0000-\u001f\u007f]/;

function isSafeDocId(id: string): boolean {
  return (
    id.length > 0 &&
    id.length <= MAX_ISSUE_ID_LENGTH &&
    !id.includes('/') &&
    id !== '.' &&
    id !== '..' &&
    !UNSAFE_DOC_ID_CHAR.test(id)
  );
}

/**
 * Builds the Crashlytics-issue deep link. `projectId` defaults to `_`, which
 * the Firebase console resolves to the caller's current project — so the link
 * still works even when the project id is unavailable at the trigger.
 */
export function crashlyticsIssueDeepLink(appId: string, issueId: string, projectId = '_'): string {
  const project = typeof projectId === 'string' && projectId.length > 0 ? projectId : '_';
  return `https://console.firebase.google.com/project/${project}/crashlytics/app/${appId}/issues/${issueId}`;
}

/**
 * Normalizes a raw Crashlytics alert payload into the bounded view the builders
 * consume, or `null` when the payload has no usable issue id (without which
 * there is no dedup key and nothing to link). Every field is read defensively:
 * a missing/blank/wrong-typed field becomes null rather than throwing.
 */
export function normalizeCrashAlert(
  kind: CrashAlertKind,
  payload: CrashAlertPayloadLike | null | undefined,
  appId: string | null | undefined,
  projectId?: string,
): NormalizedCrashAlert | null {
  const issue = payload?.issue;
  const rawId = boundedField(issue?.id, MAX_ISSUE_ID_LENGTH);
  if (!rawId || !isSafeDocId(rawId)) return null;

  const safeAppId = boundedField(appId, MAX_ISSUE_ID_LENGTH) ?? 'unknown';

  return {
    kind,
    issueId: rawId,
    title: boundedField(issue?.title, MAX_TITLE_LENGTH),
    subtitle: boundedField(issue?.subtitle, MAX_SUBTITLE_LENGTH),
    appVersion: boundedField(issue?.appVersion, MAX_APP_VERSION_LENGTH),
    regressionType: kind === 'regression' ? boundedField(payload?.type, MAX_REGRESSION_TYPE_LENGTH) : null,
    resolveTime: kind === 'regression' ? boundedField(payload?.resolveTime, MAX_APP_VERSION_LENGTH) : null,
    deepLink: crashlyticsIssueDeepLink(safeAppId, rawId, projectId),
  };
}

// ---------------------------------------------------------------------------
// Dedup link (crashlyticsIssueLinks) — fingerprint = the Crashlytics issue id
// ---------------------------------------------------------------------------

/**
 * Placeholder link written BEFORE the GitHub call (status `creating`). Keyed by
 * the Crashlytics issue id, so exactly one issue is filed per crash issue and
 * repeats/regressions of the same id bump the tally. Carries only
 * server-controlled, non-identifying scalars.
 */
export function buildNewCrashIssueLink(
  alert: NormalizedCrashAlert,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return buildNewIssueLink({ issueId: alert.issueId, kind: alert.kind }, serverTimestamp);
}

// ---------------------------------------------------------------------------
// Public GitHub issue (world-readable — no uid, no secrets)
// ---------------------------------------------------------------------------

export interface CrashIssueMeta {
  firstSeenIso: string;
  count: number;
}

/**
 * Renders an alert-supplied scalar as a markdown inline-code span so it cannot
 * inject a link/image/html into the world-readable issue body: backticks are
 * neutralized, @mention/#ref are defanged, and all whitespace collapses to
 * single spaces so a crafted title cannot break out of the bullet layout.
 * Mirrors the client-error path's inlineCodeScalar.
 */
function inlineCodeScalar(value: string): string {
  const safe = neutralizeMentions(value).replace(/`/g, "'").replace(/\s+/g, ' ').trim();
  return `\`${safe}\``;
}

/**
 * GitHub rejects an issue title longer than 256 chars, and a `createGitHubIssue`
 * failure routes through the RETRYING auto-issue path — so an overlong or
 * multi-line title would retry that crash forever, burning the global hourly
 * budget without ever filing. Cap safely under 256 (the summary field alone is
 * bounded to 500). An ellipsis marks truncation.
 */
export const MAX_ISSUE_TITLE_LENGTH = 250;

/**
 * Public issue title, e.g. `[Crash] NullPointerException in FooActivity.onCreate`.
 * The summary (Crashlytics title, else subtitle, else issue id) is neutralized,
 * collapsed to a single line, and the FINAL title — tag included — is bounded to
 * MAX_ISSUE_TITLE_LENGTH so GitHub never rejects it. The tag is always preserved
 * because the summary is what gets truncated, never the prefix.
 */
export function buildCrashIssueTitle(alert: NormalizedCrashAlert): string {
  const raw = alert.title ?? alert.subtitle ?? alert.issueId;
  const summary = neutralizeMentions(raw).replace(/\s+/g, ' ').trim();
  const prefix = `${TITLE_TAG[alert.kind]} `;
  const budget = MAX_ISSUE_TITLE_LENGTH - prefix.length;
  const bounded =
    summary.length > budget ? `${summary.slice(0, Math.max(0, budget - 1)).trimEnd()}…` : summary;
  return `${prefix}${bounded}`;
}

/**
 * Labels per kind. Fatal → `android-crash`; ANR → `anr`; regression →
 * `android-crash` + `regression`. All carry `auto-generated` (shared with the
 * other auto-filers) so the whole auto-issue stream is filterable at once.
 */
export function crashIssueLabels(kind: CrashAlertKind): string[] {
  switch (kind) {
    case 'anr':
      return [ANR_ISSUE_LABEL, AUTO_GENERATED_LABEL];
    case 'regression':
      return [CRASH_ISSUE_LABEL, REGRESSION_ISSUE_LABEL, AUTO_GENERATED_LABEL];
    case 'fatal':
    default:
      return [CRASH_ISSUE_LABEL, AUTO_GENERATED_LABEL];
  }
}

/**
 * Public issue body. Carries the crash kind, the (Google-generated) title +
 * subtitle, the app version, the Crashlytics issue id + occurrence tally, and a
 * deep link to the full record. It is HONEST that the multi-frame stack trace
 * is NOT here — the title/subtitle give the exception + top frame, the console
 * link has the rest. Never a uid, token, or secret (the alert carries none).
 */
export function buildCrashIssueBody(alert: NormalizedCrashAlert, meta: CrashIssueMeta): string {
  const field = (value: string | null): string => (value ? inlineCodeScalar(value) : 'unknown');
  const lines: string[] = [
    'Automatically filed from a new Firebase Crashlytics issue. Repeat occurrences (and re-emergences of the same Crashlytics issue id) update the occurrence counter in crashlyticsIssueLinks instead of filing new issues.',
    '',
    `- Type: ${crashKindLabel(alert.kind)}`,
    `- Title (exception + top frame): ${field(alert.title)}`,
    `- Subtitle: ${field(alert.subtitle)}`,
    `- App version: ${field(alert.appVersion)}`,
  ];
  if (alert.kind === 'regression') {
    lines.push(`- Crashlytics type: ${field(alert.regressionType)}`);
    lines.push(`- Last resolved before re-emerging: ${field(alert.resolveTime)}`);
  }
  lines.push(
    `- Crashlytics issue id: ${inlineCodeScalar(alert.issueId)}`,
    `- Crashlytics issue (full stack trace, breadcrumbs, device data): ${alert.deepLink}`,
    `- First seen (by this bridge): ${meta.firstSeenIso}`,
    `- Occurrences (seen by this bridge): ${meta.count}`,
    '',
    '_The full, symbolicated multi-frame stack trace is NOT in the Crashlytics alert payload — the title/subtitle above give the exception class and the top frame only. Open the Crashlytics link for the complete trace, breadcrumbs and affected-version breakdown._',
    '',
    '_Filed by crashReporting-* (Firebase Alerts → Crashlytics). This issue is public and never includes account identifiers or secrets._',
  );
  return lines.join('\n');
}

/** Full `POST /issues` request body for an auto-filed Crashlytics issue. */
export function buildCrashIssuePayload(
  alert: NormalizedCrashAlert,
  meta: CrashIssueMeta,
): GitHubIssuePayload {
  return {
    title: buildCrashIssueTitle(alert),
    body: buildCrashIssueBody(alert, meta),
    labels: crashIssueLabels(alert.kind),
  };
}
