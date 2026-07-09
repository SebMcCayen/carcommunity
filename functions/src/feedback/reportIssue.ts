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
import { logger } from 'firebase-functions';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import {
  FEEDBACK_RATE_LIMIT_WINDOW_MS,
  buildFeedbackReportDocument,
  buildGitHubIssuePayload,
  isFeedbackRateLimited,
  parseReportIssueInput,
  type FeedbackReport,
} from './feedback-core';

/**
 * Fine-grained GitHub token with `issues: write` on SebMcCayen/carcommunity.
 * Bound to the function below; provided via `firebase functions:secrets:set
 * GITHUB_ISSUE_TOKEN`. Never committed, logged, or returned.
 */
const GITHUB_ISSUE_TOKEN = defineSecret('GITHUB_ISSUE_TOKEN');

const GITHUB_ISSUES_URL = 'https://api.github.com/repos/SebMcCayen/carcommunity/issues';

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

interface GitHubIssueResult {
  number: number;
  url: string;
}

/**
 * Files the public issue via the GitHub REST API using the Node global
 * `fetch` (functions run on Node 22 — see functions/package.json engines; no
 * octokit dependency). Returns the created issue's number/url, or null on any
 * failure (network, auth, rate limit, missing token). Never throws.
 */
async function createGitHubIssue(
  report: FeedbackReport,
  reportId: string,
  submittedAtIso: string,
  token: string,
): Promise<GitHubIssueResult | null> {
  if (!token) {
    logger.error('feedback.reportIssue: GITHUB_ISSUE_TOKEN is empty', { reportId });
    return null;
  }

  const payload = buildGitHubIssuePayload(report, reportId, submittedAtIso);

  try {
    const response = await fetch(GITHUB_ISSUES_URL, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28',
        'User-Agent': 'carcommunity-feedback-bot',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload),
    });

    if (!response.ok) {
      // Body may carry the GitHub error message; log the status only, never
      // the token, and never surface it to the caller.
      logger.error('feedback.reportIssue: GitHub issue creation failed', {
        reportId,
        status: response.status,
      });
      return null;
    }

    const body = (await response.json()) as { number?: number; html_url?: string };
    if (typeof body.number !== 'number' || typeof body.html_url !== 'string') {
      logger.error('feedback.reportIssue: unexpected GitHub response shape', { reportId });
      return null;
    }
    return { number: body.number, url: body.html_url };
  } catch (error) {
    logger.error('feedback.reportIssue: GitHub request threw', {
      reportId,
      error: String(error),
    });
    return null;
  }
}

export const reportIssue = onCall(
  CALLABLE_OPTS,
  async (request): Promise<ReportIssueResponse> => {
    // Auth REQUIRED (reject unauthenticated with `unauthenticated`) — any
    // active signed-in user may file; suspended/deleted are rejected too.
    const actor = await requireActiveActor(request);

    const parsed = parseReportIssueInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const report = parsed.input;

    // Per-user rate limit (5 reports / hour). Best-effort count query on
    // feedbackReports (composite index uid ASC, createdAt ASC).
    const windowStart = Timestamp.fromMillis(Date.now() - FEEDBACK_RATE_LIMIT_WINDOW_MS);
    const recent = await db
      .collection('feedbackReports')
      .where('uid', '==', actor.uid)
      .where('createdAt', '>', windowStart)
      .count()
      .get();
    if (isFeedbackRateLimited(recent.data().count)) {
      throw new HttpsError(
        'resource-exhausted',
        'Too many reports — please wait a while before submitting another.',
      );
    }

    // Persist FIRST so the report is never lost, even if GitHub is down.
    const ref = await db
      .collection('feedbackReports')
      .add(buildFeedbackReportDocument(report, actor.uid, () => FieldValue.serverTimestamp()));
    const reportId = ref.id;

    const submittedAtIso = new Date().toISOString();
    const issue = await createGitHubIssue(
      report,
      reportId,
      submittedAtIso,
      GITHUB_ISSUE_TOKEN.value(),
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
  },
);
