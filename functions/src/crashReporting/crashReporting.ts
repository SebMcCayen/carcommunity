/**
 * crashReporting-* — Firebase Alerts (Crashlytics) triggers.
 *
 * The backend half of the Crashlytics → GitHub bridge. Native crashes and ANRs
 * already reach Firebase Crashlytics (Android SDK + gradle plugin, collection-on
 * for release — docs/crashlytics.md); these triggers turn a NEW Crashlytics
 * issue into ONE deduplicated PUBLIC GitHub issue so crashes are triaged from
 * the issue tracker instead of only the Crashlytics console.
 *
 * Three alerts are handled, each deployed as its own function:
 *  - crashReporting-onNewFatalIssue   ← onNewFatalIssuePublished
 *  - crashReporting-onNewAnrIssue     ← onNewAnrIssuePublished
 *  - crashReporting-onCrashRegression ← onRegressionAlertPublished (re-emerged)
 *
 * All three share ONE implementation via shared/autoIssueFiling.ts (claim →
 * global hourly budget → create → reconcile), exactly like errors-onClient/
 * ServerErrorReport. The dedup fingerprint is the Crashlytics ISSUE ID, tallied
 * in the server-only crashlyticsIssueLinks/{issueId} collection, and the create
 * is charged against the SAME 20-issues/hour global budget, so a crash storm
 * cannot spam the public repo.
 *
 * Regression note: a regression re-uses the ORIGINAL Crashlytics issue id, so if
 * this bridge already filed an issue for that id the regression bumps that
 * issue's occurrence tally; if no link exists yet (e.g. the original crash
 * pre-dates this bridge, or its budget-skipped link is retriable) it files the
 * issue. Reopening/commenting on an already-closed GitHub issue on regression is
 * a deliberate follow-up, not done here.
 *
 * Resilience: the mapping is defensive (a missing/blank field never throws; a
 * payload with no usable issue id is skipped) and the shared flow never rethrows
 * — a bridge that reports failures must not itself crash-loop. The
 * GITHUB_ISSUE_TOKEN secret is never logged.
 */

import {
  onNewFatalIssuePublished,
  onNewAnrIssuePublished,
  onRegressionAlertPublished,
} from 'firebase-functions/v2/alerts/crashlytics';
import { defineSecret } from 'firebase-functions/params';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { MAX_INSTANCES_TRIGGER, CPU_TRIGGER } from '../shared/instanceLimits';
import { fileAutoIssue } from '../shared/autoIssueFiling';
import {
  CRASHLYTICS_ISSUE_LINKS_COLLECTION,
  buildCrashIssuePayload,
  buildNewCrashIssueLink,
  normalizeCrashAlert,
  type CrashAlertKind,
  type CrashAlertPayloadLike,
} from './crashReporting-core';

/** Same secret bound to feedback.reportIssue + the other auto-issue triggers. */
const GITHUB_ISSUE_TOKEN = defineSecret('GITHUB_ISSUE_TOKEN');

/** Shared Crashlytics-trigger options (region + limits mirror the error triggers). */
const CRASHLYTICS_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_TRIGGER,
  cpu: CPU_TRIGGER,
  concurrency: 1,
  memory: '256MiB' as const,
  timeoutSeconds: 60,
  secrets: [GITHUB_ISSUE_TOKEN],
};

/**
 * The shared per-alert handler: normalize → file one deduplicated issue keyed on
 * the Crashlytics issue id. Pure of the alerts SDK so it is unit-testable; the
 * SDK-typed wrappers below just unwrap `event.data.payload` and `event.appId`.
 */
async function handleCrashAlert(
  kind: CrashAlertKind,
  payload: CrashAlertPayloadLike | null | undefined,
  appId: string | null | undefined,
): Promise<void> {
  const pipeline = `crashReporting.${kind}`;
  const alert = normalizeCrashAlert(kind, payload, appId, process.env.GCLOUD_PROJECT);
  if (!alert) {
    logger.warn(`${pipeline}: Crashlytics alert missing a usable issue id, skipping`);
    return;
  }

  const outcome = await fileAutoIssue({
    pipeline,
    linkRef: db.collection(CRASHLYTICS_ISSUE_LINKS_COLLECTION).doc(alert.issueId),
    buildNewLink: (serverTimestamp) => buildNewCrashIssueLink(alert, serverTimestamp),
    buildPayload: (meta) => buildCrashIssuePayload(alert, meta),
    token: GITHUB_ISSUE_TOKEN.value(),
    userAgent: 'carcommunity-crash-bot',
    logContext: { issueId: alert.issueId, kind },
  });

  if (outcome.status === 'created') {
    logger.info(`${pipeline}: filed GitHub issue for Crashlytics issue`, {
      issueId: alert.issueId,
      kind,
      issueNumber: outcome.issue.number,
    });
  }
}

export const onNewFatalIssue = onNewFatalIssuePublished(CRASHLYTICS_OPTS, async (event) =>
  handleCrashAlert('fatal', event.data?.payload, event.appId),
);

export const onNewAnrIssue = onNewAnrIssuePublished(CRASHLYTICS_OPTS, async (event) =>
  handleCrashAlert('anr', event.data?.payload, event.appId),
);

export const onCrashRegression = onRegressionAlertPublished(CRASHLYTICS_OPTS, async (event) =>
  handleCrashAlert('regression', event.data?.payload, event.appId),
);
