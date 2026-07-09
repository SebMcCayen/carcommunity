/**
 * feedback.reportIssue — AUTHENTICATED callable
 * (contracts/functions/functions.json).
 *
 * The Android "Report a problem" flow. An active signed-in user (non-suspended,
 * non-deleted — no member entitlement required) files a bug report; the callable
 * persists a PRIVATE record of record and then
 * files a PUBLIC GitHub issue (labelled `android-issue`) on the public repo.
 *
 * Ordering & durability (buildFeedbackReportDocument first, GitHub second):
 * the Firestore doc is written BEFORE the GitHub call so a report is never
 * lost. If GitHub fails, the doc is marked `githubIssueStatus: 'failed'`, the
 * error is logged, and the caller still gets success — their report was
 * captured. Raw GitHub errors are never surfaced to the app.
 *
 * PUBLIC-REPO SAFETY: the issue body is built in feedback-core and carries
 * ONLY the typed description + appVersion/osVersion/deviceModel + report id +
 * timestamp. The uid lives only in the private feedbackReports doc, keyed by
 * report id. See feedback-core.ts.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { defineSecret } from 'firebase-functions/params';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import { createGitHubIssue } from '../shared/githubIssues';
import {
  FEEDBACK_RATE_LIMIT_WINDOW_MS,
  buildFeedbackReportDocument,
  buildGitHubIssuePayload,
  isFeedbackRateLimited,
  parseReportIssueInput,
} from './feedback-core';

/**
 * Fine-grained GitHub token with `issues: write` on SebMcCayen/carcommunity.
 * Bound to the function below; provided via `firebase functions:secrets:set
 * GITHUB_ISSUE_TOKEN`. Never committed, logged, or returned.
 */
const GITHUB_ISSUE_TOKEN = defineSecret('GITHUB_ISSUE_TOKEN');

const CALLABLE_OPTS = {
  region: 'europe-west1',
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
  secrets: [GITHUB_ISSUE_TOKEN],
};

export interface ReportIssueResponse {
  reportId: string;
  githubIssueNumber: number | null;
  githubIssueUrl: string | null;
  status: 'created' | 'failed';
}

export const reportIssue = onCall(CALLABLE_OPTS, async (request): Promise<ReportIssueResponse> => {
  // Auth REQUIRED (reject unauthenticated with `unauthenticated`) — any
  // active signed-in user may file; suspended/deleted are rejected too.
  const actor = await requireActiveActor(request);

  const parsed = parseReportIssueInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const report = parsed.input;

  // Per-user rate limit (5 reports / hour) enforced INSIDE the transaction:
  // the windowed count read and the new-report write serialize together, so
  // concurrent submissions can never race the cap past FEEDBACK_RATE_LIMIT_MAX
  // and spam the public issue tracker (mirrors MAX_VEHICLES_PER_USER in
  // garage/manageVehicle.ts). Composite index: uid ASC, createdAt ASC.
  //
  // The doc is also persisted here (before the GitHub call) so the report is
  // never lost even if GitHub is down; the slot is reserved transactionally
  // BEFORE the external issue creation below.
  const windowStart = Timestamp.fromMillis(Date.now() - FEEDBACK_RATE_LIMIT_WINDOW_MS);
  const feedbackReports = db.collection('feedbackReports');
  const ref = feedbackReports.doc();
  await db.runTransaction(async (tx) => {
    const countSnap = await tx.get(
      feedbackReports.where('uid', '==', actor.uid).where('createdAt', '>=', windowStart).count(),
    );
    if (isFeedbackRateLimited(countSnap.data().count)) {
      throw new HttpsError(
        'resource-exhausted',
        'Too many reports — please wait a while before submitting another.',
      );
    }
    tx.set(
      ref,
      buildFeedbackReportDocument(report, actor.uid, () => FieldValue.serverTimestamp()),
    );
  });
  const reportId = ref.id;

  const submittedAtIso = new Date().toISOString();
  const issue = await createGitHubIssue(
    buildGitHubIssuePayload(report, reportId, submittedAtIso),
    GITHUB_ISSUE_TOKEN.value(),
    'carcommunity-feedback-bot',
    { reportId },
  );

  if (issue) {
    await ref.update({
      githubIssueStatus: 'created',
      githubIssueNumber: issue.number,
      githubIssueUrl: issue.url,
    });
    return {
      reportId,
      githubIssueNumber: issue.number,
      githubIssueUrl: issue.url,
      status: 'created',
    };
  }

  // GitHub failed: mark the doc but still return success — the report was
  // captured and an admin can file/triage it from the private collection.
  await ref.update({ githubIssueStatus: 'failed' });
  return { reportId, githubIssueNumber: null, githubIssueUrl: null, status: 'failed' };
});
