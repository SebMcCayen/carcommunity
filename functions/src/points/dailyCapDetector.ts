/**
 * points-detectDailyCapReached — the scheduled DAILY-CAP detector.
 *
 * Every hour it reads the per-member daily-total docs the award engine ALREADY
 * writes (`pointsDailyTotals/{uid}__{day}`, incremented inside every non-driving
 * award transaction and by the Kronjakt crown fold) for the current
 * Europe/Stockholm day, counts how many distinct members have reached
 * `DAILY_POINTS_CAP`, and — if any have — files ONE deduplicated public GitHub
 * issue for that day through the SHARED pipeline (shared/autoIssueFiling.ts).
 *
 * WHY SCHEDULED, NOT INLINE. The cap-reached refusal happens on the hot award
 * path, which must NEVER be slowed by an outbound GitHub call, and a Cloud
 * Functions handler cannot reliably fire-and-forget async work after it returns.
 * So — exactly like crownHunt-detectClaimLag reads already-written claim docs —
 * this reads the daily-total docs the awards already wrote and does the filing
 * out of band. The award path's only cap-reached cost is a single structured log
 * line (economy-award.ts). Reading the counter collection also gives an ACCURATE
 * distinct-member headcount for free, with no hot-path writes.
 *
 * ## Read cost
 * A single equality range read on `pointsDailyTotals where day == <today>` (the
 * automatic single-field index covers it — NO composite index), the cap filter
 * applied in memory, bounded by {@link MAX_DAILY_TOTALS_SCANNED}. Each doc is one
 * member (id is `{uid}__{day}`), so the count of docs at/over the cap IS the
 * distinct-member headcount.
 *
 * ## Dedup
 * The fingerprint is `dailyCapReached:points:<YYYY-MM-DD>` (Stockholm day). The
 * first hourly pass that sees ≥1 reacher files the issue; every later pass that
 * day increments the occurrence tally; a fresh civil day is a fresh fingerprint
 * and a fresh issue. One issue per day, never a flood.
 *
 * ## Secret
 * Binds `GITHUB_ISSUE_TOKEN` (already live in prod, shared with feedback.reportIssue
 * and the other auto-issue paths). With no token, detection still runs and logs,
 * but filing is skipped entirely — fileAutoIssue charges the GLOBAL hourly GitHub
 * budget before it bails, so calling it under a token misconfig would silently
 * drain the budget shared with the error-report and crown-lag pipelines.
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { defineSecret } from 'firebase-functions/params';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { CPU_SCHEDULED } from '../shared/instanceLimits';
import { fileAutoIssue } from '../shared/autoIssueFiling';
import { withServerErrorReporting } from '../errors/serverErrors';
import {
  DAILY_POINTS_CAP,
  POINTS_DAILY_TOTALS_COLLECTION,
  readCount,
  stockholmDayKey,
} from './points-economy-core';
import {
  DAILY_CAP_ISSUE_LINKS_COLLECTION,
  DAILY_POINTS_CAP_TYPE,
  buildDailyCapCluster,
  buildDailyCapIssuePayload,
  buildNewDailyCapIssueLink,
} from './daily-cap-issue-core';

/** Bound to feedback.reportIssue + the other auto-issue paths. */
const GITHUB_ISSUE_TOKEN = defineSecret('GITHUB_ISSUE_TOKEN');

const PIPELINE = 'points.detectDailyCapReached';

/** Minutes between detector runs. Hourly is ample for a once-a-day notice. */
export const DAILY_CAP_RUN_INTERVAL_MINUTES = 60;

/** Cron for {@link DAILY_CAP_RUN_INTERVAL_MINUTES} (top of every hour). */
const DAILY_CAP_SCHEDULE_CRON = '0 * * * *';

/**
 * Per-run read cap on today's daily-total docs. At the active footprint this is
 * far more than the number of members who earn anything in a day; it is a
 * backstop so a pathological day cannot blow up a run. A capped run still
 * detects (and files for) every reacher it read.
 */
export const MAX_DAILY_TOTALS_SCANNED = 20000;

export interface DailyCapDetectionResult {
  /** Daily-total docs read for today. */
  totalsScanned: number;
  /** Distinct members whose total is at/over the cap. */
  membersOverCap: number;
  /** 1 when an issue was newly filed this run, else 0. */
  filed: number;
  /** 1 when the day's issue already existed/was in flight (tally bumped), else 0. */
  deduped: number;
  /** 1 when the hourly issue budget was exhausted, else 0. */
  budgetSkipped: number;
  /** 1 when filing failed (claim/GitHub) — retriable next run, else 0. */
  failed: number;
  /**
   * True when filing was skipped entirely because GITHUB_ISSUE_TOKEN was
   * missing/empty. Detection still ran (membersOverCap is populated), but no
   * issue was attempted — so a token misconfig cannot silently burn the global
   * hourly GitHub budget on every run.
   */
  filingSkippedMissingToken: boolean;
}

/**
 * Runs one detection pass against `now`. Exported so an emulator test can drive
 * it deterministically (mirrors runClaimLagDetection / runCrownSpawnCleanup).
 *
 * `token` is the GITHUB_ISSUE_TOKEN passed through to the shared filer — an
 * argument (not read from the secret param here) so the runner can be driven
 * in-process by a test without evaluating a bound secret outside a deployed
 * function context; the scheduled wrapper supplies the real value. `capValue`
 * defaults to `DAILY_POINTS_CAP` and is overridable so a test can seed a low
 * threshold without minting cap-worth of points.
 */
export async function runDailyCapDetection(
  now: Date,
  opts: { token?: string; capValue?: number } = {},
): Promise<DailyCapDetectionResult> {
  const token = opts.token ?? '';
  const capValue = opts.capValue ?? DAILY_POINTS_CAP;
  const dayKey = stockholmDayKey(now);

  const snap = await db
    .collection(POINTS_DAILY_TOTALS_COLLECTION)
    .where('day', '==', dayKey)
    .limit(MAX_DAILY_TOTALS_SCANNED)
    .get();

  let membersOverCap = 0;
  for (const doc of snap.docs) {
    // Each doc is one member for this day (id `{uid}__{day}`). A corrupt total
    // reads as 0 (readCount), so it never spuriously counts as a reacher.
    if (readCount(doc.data()?.total) >= capValue) {
      membersOverCap += 1;
    }
  }

  const result: DailyCapDetectionResult = {
    totalsScanned: snap.size,
    membersOverCap,
    filed: 0,
    deduped: 0,
    budgetSkipped: 0,
    failed: 0,
    filingSkippedMissingToken: false,
  };

  if (membersOverCap === 0) {
    logger.info('points.detectDailyCapReached pass complete', { ...result, day: dayKey });
    return result;
  }

  // Structured signal on every pass that sees a reacher — a private log line that
  // does not depend on the GitHub filing succeeding (or on a token being set).
  logger.info('points.detectDailyCapReached: members reached the daily points cap', {
    day: dayKey,
    capValue,
    membersOverCap,
  });

  // GUARD: with no token, createGitHubIssue can only fail — but fileAutoIssue
  // charges the GLOBAL hourly GitHub budget BEFORE it bails, so calling it every
  // run under a token misconfig would silently drain the budget shared with the
  // error-report, sign-in and crown-lag pipelines. Skip filing entirely.
  if (!token) {
    result.filingSkippedMissingToken = true;
    logger.warn('points.detectDailyCapReached: GITHUB_ISSUE_TOKEN missing, skipping issue filing', {
      day: dayKey,
      membersOverCap,
    });
    return result;
  }

  const cluster = buildDailyCapCluster(DAILY_POINTS_CAP_TYPE, capValue, dayKey, membersOverCap);
  const outcome = await fileAutoIssue({
    pipeline: PIPELINE,
    linkRef: db.collection(DAILY_CAP_ISSUE_LINKS_COLLECTION).doc(cluster.fingerprint),
    buildNewLink: (serverTimestamp) => buildNewDailyCapIssueLink(cluster, serverTimestamp),
    buildPayload: (meta) => buildDailyCapIssuePayload(cluster, meta),
    token,
    userAgent: 'carcommunity-economy-bot',
    logContext: {
      fingerprint: cluster.fingerprint,
      capType: cluster.capType,
      capValue: cluster.capValue,
      membersOverCap: cluster.memberCount,
    },
    now,
  });

  switch (outcome.status) {
    case 'created':
      result.filed = 1;
      break;
    case 'deduped':
      result.deduped = 1;
      break;
    case 'skipped':
      result.budgetSkipped = 1;
      break;
    default:
      result.failed = 1;
      break;
  }

  logger.info('points.detectDailyCapReached pass complete', { ...result, day: dayKey });
  return result;
}

/**
 * Scheduled hourly (Europe/Stockholm). `maxInstances` + `concurrency: 1` keep two
 * passes from overlapping — harmless for correctness (the shared pipeline is
 * idempotent per fingerprint) but wasteful in reads. `withServerErrorReporting`
 * reports an unexpected throw to `serverErrorReports` (which the error trigger
 * turns into its own issue) and rethrows, so Cloud Scheduler's retry/alerting
 * still keys off the failure.
 */
export const detectDailyCapReached = onSchedule(
  {
    region: 'europe-west1',
    timeZone: 'Europe/Stockholm',
    schedule: DAILY_CAP_SCHEDULE_CRON,
    memory: '256MiB',
    timeoutSeconds: 120,
    maxInstances: 1,
    cpu: CPU_SCHEDULED,
    concurrency: 1,
    secrets: [GITHUB_ISSUE_TOKEN],
  },
  withServerErrorReporting(PIPELINE, async () => {
    await runDailyCapDetection(new Date(), { token: GITHUB_ISSUE_TOKEN.value() });
  }),
);
