/**
 * errors-onClientErrorReport — Firestore create trigger on
 * `clientErrorReports/{reportId}`.
 *
 * Files ONE deduplicated PUBLIC GitHub issue per unique error fingerprint
 * (labelled `auto-error` + `auto-generated`), tallying occurrences in the
 * server-only `clientErrorIssueLinks/{fingerprint}` collection so a recurring
 * error bumps a counter instead of spamming hundreds of issues.
 *
 * Mirrors diagnostics-onSignInFailure (the sign-in auto-issue path) but for
 * AUTHENTICATED client errors: the fingerprint is computed by the callable and
 * stored on the report, so this trigger just claims/increments it.
 *
 * The claim → budget → create → reconcile flow now lives in
 * shared/autoIssueFiling.ts, shared with errors-onServerErrorReport. Behaviour is
 * unchanged apart from one deliberate addition: the create is now also charged
 * against the GLOBAL hourly issue budget (shared/issueBudget-core.ts,
 * 20 issues/hour across ALL auto-filing paths). Per-fingerprint dedup bounds
 * issues per DISTINCT error, which does not bound a bad release that produces
 * hundreds of distinct fingerprints at once — on a PUBLIC repo that burst is
 * permanent. Over budget, the private report is still written and the occurrence
 * still tallied; only the GitHub create is skipped, and the link is left
 * retriable so the next occurrence in a fresh hourly bucket files it.
 *
 * Dedup (transaction on the link doc):
 *  - first occurrence → write a `creating` placeholder + file the issue;
 *  - a previously-`failed` link → re-claim + retry the create;
 *  - any other (`creating` in-flight, or `created`) → only increment the tally.
 *
 * Resilience: createGitHubIssue never throws; a GitHub failure rolls back a
 * pristine placeholder (so a future occurrence retries) or, if a concurrent
 * occurrence already incremented, marks the link `failed` (retriable). The
 * report doc is patched with the issue number/url/status for admin triage. The
 * GITHUB_ISSUE_TOKEN secret is never logged.
 */

import { onDocumentCreated } from 'firebase-functions/v2/firestore';
import { defineSecret } from 'firebase-functions/params';
import { db } from '../firebase';
import { fileAutoIssue } from '../shared/autoIssueFiling';
import {
  CLIENT_ERROR_ISSUE_LINKS_COLLECTION,
  buildClientErrorIssuePayload,
  buildNewClientErrorIssueLink,
  computeClientErrorFingerprint,
  type ClientErrorReport,
  type GitHubIssueStatus,
} from './clientErrors-core';
import { MAX_INSTANCES_TRIGGER } from '../shared/instanceLimits';

/** Same secret bound to feedback.reportIssue + diagnostics-onSignInFailure. */
const GITHUB_ISSUE_TOKEN = defineSecret('GITHUB_ISSUE_TOKEN');

const PIPELINE = 'errors.onClientErrorReport';

function toStringOrNull(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null;
}

/**
 * Rebuilds the normalized report view from the stored doc. The fingerprint is
 * RECOMPUTED server-side (never trusts the stored value) so the dedup key can't
 * be forged — though writes are Admin-SDK-only, this keeps the invariant local.
 */
function extractReport(data: Record<string, unknown> | undefined): ClientErrorReport | null {
  if (!data) return null;
  const feature = toStringOrNull(data.feature);
  const message = toStringOrNull(data.message);
  if (!feature || !message) return null;
  const code = toStringOrNull(data.code);
  return {
    feature,
    message,
    code,
    appVersion: toStringOrNull(data.appVersion),
    osVersion: toStringOrNull(data.osVersion),
    deviceModel: toStringOrNull(data.deviceModel),
    platform: toStringOrNull(data.platform) ?? 'android',
    fingerprint: computeClientErrorFingerprint(feature, message, code),
  };
}

export const onClientErrorReport = onDocumentCreated(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_TRIGGER,
    document: 'clientErrorReports/{reportId}',
    memory: '256MiB',
    timeoutSeconds: 30,
    secrets: [GITHUB_ISSUE_TOKEN],
  },
  async (event) => {
    const snapshot = event.data;
    if (!snapshot) return;

    const report = extractReport(snapshot.data());
    if (!report) return;

    const outcome = await fileAutoIssue({
      pipeline: PIPELINE,
      linkRef: db.collection(CLIENT_ERROR_ISSUE_LINKS_COLLECTION).doc(report.fingerprint),
      buildNewLink: (serverTimestamp) => buildNewClientErrorIssueLink(report, serverTimestamp),
      buildPayload: (meta) => buildClientErrorIssuePayload(report, meta),
      token: GITHUB_ISSUE_TOKEN.value(),
      userAgent: 'carcommunity-error-bot',
      logContext: { fingerprint: report.fingerprint },
    });

    // Patch the private report doc so admins can find the existing issue. The
    // status vocabulary is unchanged (`pending` | `created` | `failed`): a
    // budget-skipped create reports `failed`, which is accurate (no issue was
    // filed) and retriable.
    const issue =
      outcome.status === 'created'
        ? outcome.issue
        : outcome.status === 'deduped'
          ? outcome.issue
          : null;
    let status: GitHubIssueStatus;
    if (issue) status = 'created';
    else if (outcome.status === 'deduped') status = 'pending';
    else status = 'failed';

    await snapshot.ref
      .update({
        githubIssueStatus: status,
        githubIssueNumber: issue?.number ?? null,
        githubIssueUrl: issue?.url ?? null,
      })
      .catch(() => undefined);
  },
);
