/**
 * errors-onServerErrorReport — Firestore create trigger on
 * `serverErrorReports/{reportId}`.
 *
 * Files ONE deduplicated PUBLIC GitHub issue per unique server-error fingerprint
 * (labelled `server-error` + `auto-generated`), tallying occurrences in the
 * server-only `serverErrorIssueLinks/{fingerprint}` collection so a scheduled job
 * failing every five minutes bumps a counter instead of opening 288 issues a day.
 *
 * Mirrors errors-onClientErrorReport exactly, and shares its implementation via
 * shared/autoIssueFiling.ts (claim → global hourly budget → create → reconcile).
 * Running the GitHub call here rather than inside `reportServerError` keeps
 * GitHub's latency off the failing handler's critical path and confines the
 * GITHUB_ISSUE_TOKEN secret to this one function instead of all 15 scheduled jobs.
 *
 * PUBLIC-REPO SAFETY: the issue body is built by serverErrors-core.ts from a
 * strict allowlist (source, errorName, errorCode, reduced `file:line` frames,
 * fingerprint, first-seen, count). The message, stack and call-site context are
 * read from the report document ONLY to stay in the private collection — they are
 * never passed to the issue builder. The fingerprint is the correlation id an
 * admin uses to find the private record.
 *
 * The allowlist is RE-APPLIED here, by `buildPublishableServerErrorReport`, even
 * though `reportServerError` already applied it on the way in. This trigger does
 * not publish a value it just validated; it publishes a value it read back out of
 * a Firestore document, which may have been written by an older version of this
 * module, by a code path that forgets to normalise, or by anything that ever
 * gains write access to the collection. A field that fails is replaced by its
 * documented fallback and the redaction is logged — see that function for why the
 * report is redacted rather than dropped.
 *
 * Resilience: this trigger never rethrows. A failure here must not retry-loop a
 * function whose only job is to report someone else's failure — and it certainly
 * must not report ITSELF, which would be an unbounded feedback loop. Every
 * failure path is logged and the link is left in a retriable state.
 */

import { onDocumentCreated } from 'firebase-functions/v2/firestore';
import { defineSecret } from 'firebase-functions/params';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { fileAutoIssue } from '../shared/autoIssueFiling';
import {
  SERVER_ERROR_ISSUE_LINKS_COLLECTION,
  buildNewServerErrorIssueLink,
  buildPublishableServerErrorReport,
  buildServerErrorIssuePayload,
  type ServerErrorIssueStatus,
} from './serverErrors-core';

/** Same secret bound to feedback.reportIssue + the other auto-issue triggers. */
const GITHUB_ISSUE_TOKEN = defineSecret('GITHUB_ISSUE_TOKEN');

const PIPELINE = 'errors.onServerErrorReport';

async function patchReport(
  ref: { update: (data: Record<string, unknown>) => Promise<unknown> },
  status: ServerErrorIssueStatus,
  issue: { number: number; url: string } | null,
): Promise<void> {
  await ref
    .update({
      githubIssueStatus: status,
      githubIssueNumber: issue?.number ?? null,
      githubIssueUrl: issue?.url ?? null,
    })
    .catch(() => undefined);
}

export const onServerErrorReport = onDocumentCreated(
  {
    region: 'europe-west1',
    document: 'serverErrorReports/{reportId}',
    memory: '256MiB',
    timeoutSeconds: 30,
    secrets: [GITHUB_ISSUE_TOKEN],
  },
  async (event) => {
    const snapshot = event.data;
    if (!snapshot) return;

    const publishable = buildPublishableServerErrorReport(snapshot.data());
    if (!publishable) {
      logger.warn(`${PIPELINE}: report document missing source/errorName, skipping`);
      return;
    }

    const { report, redacted } = publishable;
    if (redacted.length > 0) {
      // Log WHICH fields were rejected, never their values: this is the private
      // side, but the offending text is exactly the text we refuse to publish.
      logger.warn(`${PIPELINE}: stored report failed the publish allowlist, fields redacted`, {
        fingerprint: report.fingerprint,
        redacted: redacted.join(','),
      });
    }

    const outcome = await fileAutoIssue({
      pipeline: PIPELINE,
      linkRef: db.collection(SERVER_ERROR_ISSUE_LINKS_COLLECTION).doc(report.fingerprint),
      buildNewLink: (serverTimestamp) => buildNewServerErrorIssueLink(report, serverTimestamp),
      buildPayload: (meta) => buildServerErrorIssuePayload(report, meta),
      token: GITHUB_ISSUE_TOKEN.value(),
      userAgent: 'carcommunity-server-error-bot',
      logContext: { fingerprint: report.fingerprint, source: report.source },
    });

    switch (outcome.status) {
      case 'created':
        await patchReport(snapshot.ref, 'created', outcome.issue);
        return;
      case 'deduped':
        await patchReport(snapshot.ref, outcome.issue ? 'created' : 'pending', outcome.issue);
        return;
      case 'skipped':
        await patchReport(snapshot.ref, 'skipped', null);
        return;
      default:
        await patchReport(snapshot.ref, 'failed', null);
        return;
    }
  },
);
