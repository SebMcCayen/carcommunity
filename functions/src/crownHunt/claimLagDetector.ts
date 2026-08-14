/**
 * crownHunt-detectClaimLag — the scheduled COLLECT-LAG detector.
 *
 * Every ~20 minutes it scans the RECENT per-attempt claim docs that the two
 * collect paths already write (`crownSpawnClaims` from claimSpawn.ts, and
 * optionally `crownHuntClaims` from submitClaim.ts) and looks for the retry-lag
 * pattern: one member hammering the collect button on one crown — three or more
 * edge-condition rejections (outside-radius / not-stationary / position-too-old)
 * within two minutes — whether or not it finally succeeded. See
 * crown-claim-lag-core.ts for the pattern and the shape-based fingerprint.
 *
 * It adds NO writes to the hot collect path: it only READS docs those paths have
 * already written (result + distanceMeters + accuracyMeters + createdAt). Matches
 * are auto-filed through the SHARED pipeline (shared/autoIssueFiling.ts), which
 * gives the atomic fingerprint dedup, the global hourly issue budget, and the
 * never-throws guarantee for free — exactly as errors-onServerErrorReport uses
 * it. Because the fingerprint is by SHAPE (dominant result + distance/accuracy
 * bucket), a wave of members hitting the same too-tight radius collapses into ONE
 * issue whose occurrence counter is the headcount, never one issue per member.
 *
 * ## Read cost
 * A single range read on `createdAt` per collection (the automatic single-field
 * index covers `where(createdAt >= cutoff).orderBy(createdAt)`, so NO composite
 * index is required), bounded by {@link MAX_CLAIMS_SCANNED}. The scan window is
 * wider than the run interval so a burst straddling a tick is not missed; the
 * fingerprint dedup means the overlap just increments a tally.
 *
 * ## Secret
 * Binds `GITHUB_ISSUE_TOKEN` (already live in prod, shared with feedback.reportIssue
 * and the error-report triggers) — it files issues DIRECTLY rather than via a
 * Firestore trigger, because there is no per-error document to hang a trigger on.
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { defineSecret } from 'firebase-functions/params';
import { Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { CPU_SCHEDULED } from '../shared/instanceLimits';
import { fileAutoIssue } from '../shared/autoIssueFiling';
import { withServerErrorReporting } from '../errors/serverErrors';
import {
  HUNT_LAG_RESULTS,
  RETRY_LAG_RESULTS,
  SPAWN_LAG_RESULTS,
  buildNewRetryLagIssueLink,
  buildRetryLagIssuePayload,
  clusterRetryLagGroups,
  detectRetryLagGroups,
  type ClaimAttemptRecord,
  type ClaimSource,
  type RetryLagCluster,
} from './crown-claim-lag-core';

/** Bound to feedback.reportIssue + the other auto-issue paths. */
const GITHUB_ISSUE_TOKEN = defineSecret('GITHUB_ISSUE_TOKEN');

const PIPELINE = 'crownHunt.detectClaimLag';

/** Server-only dedup links, keyed by the shape fingerprint. */
export const CROWN_RETRY_LAG_ISSUE_LINKS_COLLECTION = 'crownRetryLagIssueLinks';

/** Minutes between detector runs. */
export const RETRY_LAG_RUN_INTERVAL_MINUTES = 20;

/** Cron for {@link RETRY_LAG_RUN_INTERVAL_MINUTES} (epoch-aligned step). */
const RETRY_LAG_SCHEDULE_CRON = `*/${RETRY_LAG_RUN_INTERVAL_MINUTES} * * * *`;

/**
 * How far back each run reads. Wider than the run interval (2×) so a burst that
 * straddles a tick boundary is fully seen by at least one run; the fingerprint
 * dedup makes the overlap a tally bump, not a duplicate issue.
 */
export const RETRY_LAG_SCAN_WINDOW_MS = 2 * RETRY_LAG_RUN_INTERVAL_MINUTES * 60 * 1000;

/**
 * Per-collection read cap. At the active footprint we expect, a 40-minute window
 * holds far fewer than this; the cap is a backstop so a pathological burst cannot
 * blow up a run. A capped run still detects every burst it read.
 */
export const MAX_CLAIMS_SCANNED = 5000;

/**
 * Issues filed per run. The shared hourly GitHub budget is the real ceiling; this
 * just stops one run from spending the whole budget on a long tail of tiny
 * one-user shapes. Clusters are filed loudest-first (clusterRetryLagGroups sorts
 * by affected-user count), so a cap drops only the smallest shapes.
 */
export const MAX_ISSUES_PER_RUN = 10;

export interface ClaimLagDetectionResult {
  /** Attempt docs read across both collections. */
  attemptsScanned: number;
  /** Matched `(uid, target)` retry-lag episodes. */
  episodesMatched: number;
  /** Distinct shape clusters. */
  clusters: number;
  /** Issues newly filed this run. */
  filed: number;
  /** Clusters that deduped onto an existing/in-flight issue. */
  deduped: number;
  /** Clusters skipped because the hourly issue budget was exhausted. */
  budgetSkipped: number;
  /** Clusters whose filing failed (claim/GitHub) — retriable next run. */
  failed: number;
  /** True when the per-run issue cap dropped remaining clusters. */
  capped: boolean;
}

/**
 * Maps one attempt doc to a {@link ClaimAttemptRecord}, or null when it lacks the
 * fields the detector needs (uid + a `createdAt` timestamp). `distanceMeters` and
 * `accuracyMeters` are optional — a reject path that stored neither still counts
 * toward the burst, it just lands in the `unknown` bucket.
 */
function toAttemptRecord(
  source: ClaimSource,
  data: FirebaseFirestore.DocumentData,
): ClaimAttemptRecord | null {
  const uid = typeof data.userId === 'string' ? data.userId : null;
  const targetId =
    typeof data.spawnId === 'string'
      ? data.spawnId
      : typeof data.pointId === 'string'
        ? data.pointId
        : null;
  const result = typeof data.result === 'string' ? data.result : null;
  const createdAt = data.createdAt;
  const claimedAtMs =
    createdAt instanceof Timestamp
      ? createdAt.toMillis()
      : data.claimedAt instanceof Timestamp
        ? data.claimedAt.toMillis()
        : null;
  if (uid === null || targetId === null || result === null || claimedAtMs === null) return null;
  return {
    source,
    uid,
    targetId,
    result,
    distanceMeters:
      typeof data.distanceMeters === 'number' && Number.isFinite(data.distanceMeters)
        ? data.distanceMeters
        : null,
    accuracyMeters:
      typeof data.accuracyMeters === 'number' && Number.isFinite(data.accuracyMeters)
        ? data.accuracyMeters
        : null,
    claimedAtMs,
  };
}

/**
 * Reads recent attempt docs from one collection. Only docs whose result is a lag
 * result or `awarded` are kept — `awarded` because the burst's before-success
 * accounting needs it, everything else because it never participates in the
 * pattern. Filtered in memory (a single range read stays on the automatic
 * `createdAt` index; adding an `in` filter would force a composite index for no
 * real saving at this volume).
 */
async function loadRecentAttempts(
  collection: string,
  source: ClaimSource,
  lagResults: readonly string[],
  cutoff: Timestamp,
): Promise<ClaimAttemptRecord[]> {
  const keep = new Set<string>([...lagResults, 'awarded']);
  const snap = await db
    .collection(collection)
    .where('createdAt', '>=', cutoff)
    .orderBy('createdAt', 'asc')
    .limit(MAX_CLAIMS_SCANNED)
    .get();
  const records: ClaimAttemptRecord[] = [];
  for (const doc of snap.docs) {
    const data = doc.data();
    if (typeof data.result !== 'string' || !keep.has(data.result)) continue;
    const record = toAttemptRecord(source, data);
    if (record) records.push(record);
  }
  return records;
}

/**
 * Runs one detection pass against `now`. Exported so an emulator test can drive
 * it deterministically (mirrors runCrownAreaSpawnPass / runCrownSpawnCleanup).
 *
 * `scanHunt` defaults to true; the hand-placed flow shares the same edge
 * conditions and benefits from the same signal. Set false to scan only the
 * auto-spawn collect path.
 *
 * `token` is the GITHUB_ISSUE_TOKEN passed through to the shared filer. It is an
 * argument (not read from the secret param here) so this runner can be driven
 * in-process by a test without evaluating a bound secret outside a deployed
 * function context; the scheduled wrapper supplies the real value.
 */
export async function runClaimLagDetection(
  now: Date,
  opts: { scanHunt?: boolean; token?: string } = {},
): Promise<ClaimLagDetectionResult> {
  const scanHunt = opts.scanHunt ?? true;
  const token = opts.token ?? '';
  const cutoff = Timestamp.fromMillis(now.getTime() - RETRY_LAG_SCAN_WINDOW_MS);

  const [spawnAttempts, huntAttempts] = await Promise.all([
    loadRecentAttempts('crownSpawnClaims', 'spawn', SPAWN_LAG_RESULTS, cutoff),
    scanHunt
      ? loadRecentAttempts('crownHuntClaims', 'hunt', HUNT_LAG_RESULTS, cutoff)
      : Promise.resolve<ClaimAttemptRecord[]>([]),
  ]);

  const attempts = [...spawnAttempts, ...huntAttempts];
  const groups = detectRetryLagGroups(attempts, RETRY_LAG_RESULTS);
  const clusters = clusterRetryLagGroups(groups);

  const result: ClaimLagDetectionResult = {
    attemptsScanned: attempts.length,
    episodesMatched: groups.length,
    clusters: clusters.length,
    filed: 0,
    deduped: 0,
    budgetSkipped: 0,
    failed: 0,
    capped: false,
  };

  let issuesConsidered = 0;
  for (const cluster of clusters) {
    if (issuesConsidered >= MAX_ISSUES_PER_RUN) {
      result.capped = true;
      break;
    }
    issuesConsidered += 1;
    await fileOne(cluster, now, token, result);
  }

  logger.info('crownHunt.detectClaimLag pass complete', { ...result });
  return result;
}

/** Files (or dedups) one cluster and folds the outcome into `result`. */
async function fileOne(
  cluster: RetryLagCluster,
  now: Date,
  token: string,
  result: ClaimLagDetectionResult,
): Promise<void> {
  const outcome = await fileAutoIssue({
    pipeline: PIPELINE,
    linkRef: db.collection(CROWN_RETRY_LAG_ISSUE_LINKS_COLLECTION).doc(cluster.fingerprint),
    buildNewLink: (serverTimestamp) => buildNewRetryLagIssueLink(cluster, serverTimestamp),
    buildPayload: (meta) => buildRetryLagIssuePayload(cluster, meta),
    token,
    userAgent: 'carcommunity-crown-hunt-bot',
    logContext: {
      fingerprint: cluster.fingerprint,
      dominantResult: cluster.dominantResult,
      affectedUsers: cluster.affectedUserCount,
      episodes: cluster.episodeCount,
    },
    now,
  });

  switch (outcome.status) {
    case 'created':
      result.filed += 1;
      return;
    case 'deduped':
      result.deduped += 1;
      return;
    case 'skipped':
      result.budgetSkipped += 1;
      return;
    default:
      result.failed += 1;
      return;
  }
}

/**
 * Scheduled every 20 minutes (Europe/Stockholm). `maxInstances: 1` +
 * `concurrency: 1` keep two passes from overlapping — harmless for correctness
 * (the shared pipeline is idempotent per fingerprint) but wasteful in reads.
 * `withServerErrorReporting` reports an unexpected throw to `serverErrorReports`
 * (which the error trigger turns into its own issue) and rethrows, so Cloud
 * Scheduler's retry/alerting still keys off the failure.
 */
export const detectClaimLag = onSchedule(
  {
    region: 'europe-west1',
    timeZone: 'Europe/Stockholm',
    schedule: RETRY_LAG_SCHEDULE_CRON,
    memory: '256MiB',
    timeoutSeconds: 120,
    maxInstances: 1,
    cpu: CPU_SCHEDULED,
    concurrency: 1,
    secrets: [GITHUB_ISSUE_TOKEN],
  },
  withServerErrorReporting(PIPELINE, async () => {
    await runClaimLagDetection(new Date(), { token: GITHUB_ISSUE_TOKEN.value() });
  }),
);
