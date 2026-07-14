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
import { logger } from 'firebase-functions';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { createGitHubIssue } from '../shared/githubIssues';
import {
  CLIENT_ERROR_ISSUE_LINKS_COLLECTION,
  buildClientErrorIssueLinkCreated,
  buildClientErrorIssueLinkFailed,
  buildClientErrorIssueLinkIncrement,
  buildClientErrorIssueLinkRetry,
  buildClientErrorIssuePayload,
  buildNewClientErrorIssueLink,
  computeClientErrorFingerprint,
  decideClientErrorIssueAction,
  type ClientErrorIssueLink,
  type ClientErrorReport,
} from './clientErrors-core';

/** Same secret bound to feedback.reportIssue + diagnostics-onSignInFailure. */
const GITHUB_ISSUE_TOKEN = defineSecret('GITHUB_ISSUE_TOKEN');

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

    const reportRef = snapshot.ref;
    const linkRef = db.collection(CLIENT_ERROR_ISSUE_LINKS_COLLECTION).doc(report.fingerprint);

    // Atomically claim the fingerprint: only the first (or a retry of a failed)
    // occurrence gets 'create'; every other occurrence increments the tally.
    let action: 'create' | 'increment';
    try {
      action = await db.runTransaction(async (tx) => {
        const existing = await tx.get(linkRef);
        const link = existing.exists ? (existing.data() as ClientErrorIssueLink) : null;
        const decision = decideClientErrorIssueAction(link);
        if (decision === 'increment') {
          tx.update(
            linkRef,
            buildClientErrorIssueLinkIncrement(FieldValue.increment(1), () =>
              FieldValue.serverTimestamp(),
            ),
          );
        } else if (link) {
          tx.update(
            linkRef,
            buildClientErrorIssueLinkRetry(FieldValue.increment(1), () =>
              FieldValue.serverTimestamp(),
            ),
          );
        } else {
          tx.set(
            linkRef,
            buildNewClientErrorIssueLink(report, () => FieldValue.serverTimestamp()),
          );
        }
        return decision;
      });
    } catch (error) {
      logger.error('errors.onClientErrorReport: link transaction failed', {
        fingerprint: report.fingerprint,
        error: String(error),
      });
      return;
    }

    // Dedup: the issue exists (or is being created concurrently) — the tally was
    // bumped; patch the report doc so admins can find the existing issue.
    if (action === 'increment') {
      const claimed = (await linkRef.get()).data();
      const number = typeof claimed?.issueNumber === 'number' ? claimed.issueNumber : null;
      const url = typeof claimed?.issueUrl === 'string' ? claimed.issueUrl : null;
      await reportRef
        .update({
          githubIssueStatus: number !== null ? 'created' : 'pending',
          githubIssueNumber: number,
          githubIssueUrl: url,
        })
        .catch(() => undefined);
      return;
    }

    // We claimed the fingerprint → file the single public issue. Read the link
    // back so the body's first-seen/occurrences reflect the actual doc.
    const claimed = (await linkRef.get()).data();
    const count = typeof claimed?.count === 'number' ? claimed.count : 1;
    const firstSeenAt = claimed?.firstSeenAt;
    const firstSeenIso =
      firstSeenAt instanceof Timestamp
        ? firstSeenAt.toDate().toISOString()
        : new Date().toISOString();

    const issue = await createGitHubIssue(
      buildClientErrorIssuePayload(report, { firstSeenIso, count }),
      GITHUB_ISSUE_TOKEN.value(),
      'carcommunity-error-bot',
      { fingerprint: report.fingerprint },
    );

    if (issue) {
      await Promise.all([
        linkRef.update(buildClientErrorIssueLinkCreated(issue)).catch((error) => {
          logger.error('errors.onClientErrorReport: failed to record issue link', {
            fingerprint: report.fingerprint,
            issueNumber: issue.number,
            error: String(error),
          });
        }),
        reportRef
          .update({
            githubIssueStatus: 'created',
            githubIssueNumber: issue.number,
            githubIssueUrl: issue.url,
          })
          .catch(() => undefined),
      ]);
      return;
    }

    // GitHub failed (already logged, no throw). Concurrency-safe rollback: a
    // pristine placeholder (count 1) is deleted so a future occurrence retries;
    // a placeholder a concurrent occurrence already bumped (count > 1) is marked
    // `failed` (retriable) so no occurrence is lost.
    try {
      await db.runTransaction(async (tx) => {
        const current = await tx.get(linkRef);
        if (!current.exists) return;
        const link = current.data() as ClientErrorIssueLink;
        if (link.status !== 'creating') return;
        if (link.count === 1) {
          tx.delete(linkRef);
        } else {
          tx.update(linkRef, buildClientErrorIssueLinkFailed());
        }
      });
    } catch (error) {
      logger.error('errors.onClientErrorReport: failed to roll back placeholder link', {
        fingerprint: report.fingerprint,
        error: String(error),
      });
    }
    await reportRef.update({ githubIssueStatus: 'failed' }).catch(() => undefined);
  },
);
