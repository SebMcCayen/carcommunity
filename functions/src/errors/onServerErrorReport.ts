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
  buildServerErrorIssuePayload,
  computeServerErrorFingerprint,
  type ServerErrorIssueStatus,
  type ServerErrorReport,
} from './serverErrors-core';

/** Same secret bound to feedback.reportIssue + the other auto-issue triggers. */
const GITHUB_ISSUE_TOKEN = defineSecret('GITHUB_ISSUE_TOKEN');

const PIPELINE = 'errors.onServerErrorReport';

function toStringOrNull(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null;
}

/**
 * Rebuilds the publishable view from the stored document. The fingerprint is
 * RECOMPUTED here rather than trusted from the document, so the dedup key always
 * matches the allowlisted fields actually being published.
 *
 * `message`/`stack`/`context` are intentionally set to empty/null: this view
 * feeds the PUBLIC issue builder, and the builder must never be handed values it
 * is not allowed to render.
 */
function extractReport(data: Record<string, unknown> | undefined): ServerErrorReport | null {
  if (!data) return null;
  const source = toStringOrNull(data.source);
  const errorName = toStringOrNull(data.errorName);
  if (!source || !errorName) return null;
  const errorCode = toStringOrNull(data.errorCode);
  const frames = Array.isArray(data.frames)
    ? data.frames.filter((frame): frame is string => typeof frame === 'string')
    : [];
  return {
    source,
    errorName,
    errorCode,
    frames,
    message: '',
    stack: null,
    context: null,
    fingerprint: computeServerErrorFingerprint(source, errorName, errorCode, frames),
  };
}

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

    const report = extractReport(snapshot.data());
    if (!report) {
      logger.warn(`${PIPELINE}: report document missing source/errorName, skipping`);
      return;
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
