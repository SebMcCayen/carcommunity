/**
 * Crown Hunt COLLECT-LAG detection — pure domain logic (no Firebase Admin SDK,
 * no network), so every branch is unit-testable without emulators (mirrors the
 * sibling `*-core.ts` files).
 *
 * THE GAP THIS CLOSES. Collecting a crown is a two-fix stationary check
 * (claimSpawn.ts) or a geofence+speed check (submitClaim.ts). When a member is
 * right on the edge — a metre outside the radius, or rolling a hair too fast in a
 * car park — the collect is REFUSED and they tap again, and again, until a fix
 * lands inside. Today every one of those refusals is written as a per-attempt
 * claim doc with a `result` string and NOTHING ELSE: nothing is logged, nothing
 * is counted, so the "tap three or four times to finally collect" lag is
 * completely invisible to us. A member feels it as a broken button; we never see
 * it. This module turns the ALREADY-WRITTEN attempt docs into a signal.
 *
 * THE PATTERN. A single refusal is normal (you arrived, you were still moving,
 * you tried once more — fine). The signal is a BURST: one `(uid, target)` with
 * {@link RETRY_LAG_MIN_REJECTIONS} or more lag-rejections
 * ({@link SPAWN_LAG_RESULTS} / {@link HUNT_LAG_RESULTS} — outside-radius,
 * not-stationary, position-too-old) inside {@link RETRY_LAG_WINDOW_MS}, whether
 * or not it eventually succeeded. That is a member fighting the collect button.
 *
 * FINGERPRINT BY SHAPE, NOT BY USER. The dedup key is
 * `crownCollectRetry:<dominantResult>:<distanceBucket>:<accuracyBucket>`, so one
 * issue captures "many members hitting outside_radius at ~radius+X with accuracy
 * ~Y" and its occurrence counter shows the SCALE — never one issue per member.
 * The issue body carries only BUCKETS and COUNTS: no coordinates, no distance
 * values, no uid list. The whole point is a tuning signal (is the radius too
 * tight? is the stationary threshold too strict for real GPS jitter?), and a
 * tuning signal needs the shape and the headcount, never who.
 */

import type { GitHubIssuePayload } from '../shared/githubIssues';
import { neutralizeMentions } from '../shared/githubIssues';
import { AUTO_GENERATED_LABEL } from '../diagnostics/signInIssues-core';
import { buildNewIssueLink } from '../shared/issueLinks-core';

// ---------------------------------------------------------------------------
// Lag-result vocabularies
// ---------------------------------------------------------------------------

/**
 * The auto-spawn (`crownSpawnClaims`) rejection codes that count toward the
 * collect-lag pattern — the ones a member RETRIES through because the next GPS
 * fix might satisfy them. Ordered: ties in the dominant-result vote break toward
 * the earlier entry.
 *
 * Deliberately excludes the terminal refusals nobody retries usefully:
 * `already_taken` / `already_collected` (the crown is gone / already yours),
 * `crown_expired`, `daily_limit_reached`, `risk_review`, `feature_disabled`,
 * `not_eligible`. Retapping those is not lag, it is a different message.
 */
export const SPAWN_LAG_RESULTS = ['outside_radius', 'must_be_stationary', 'position_too_old'] as const;

/**
 * The hand-placed-point (`crownHuntClaims`) equivalents. `outside_geofence` and
 * `moving_too_fast` are submitClaim's names for the same two edge conditions;
 * `position_too_old` is shared.
 */
export const HUNT_LAG_RESULTS = ['outside_geofence', 'moving_too_fast', 'position_too_old'] as const;

/** Every lag result across both claim flows, order-preserving and de-duplicated. */
export const RETRY_LAG_RESULTS: readonly string[] = Array.from(
  new Set<string>([...SPAWN_LAG_RESULTS, ...HUNT_LAG_RESULTS]),
);

// ---------------------------------------------------------------------------
// Pattern thresholds
// ---------------------------------------------------------------------------

/** Minimum lag-rejections inside the window for a burst to count as retry-lag. */
export const RETRY_LAG_MIN_REJECTIONS = 3;

/** The sliding window a qualifying burst must fit inside. */
export const RETRY_LAG_WINDOW_MS = 2 * 60 * 1000;

// ---------------------------------------------------------------------------
// Issue labelling
// ---------------------------------------------------------------------------

/**
 * Source label for auto-filed Crown Hunt issues, alongside the shared
 * `auto-generated` label every auto-filing path carries (matches the
 * `server-error` + `auto-generated` / `sign-in-failure` + `auto-generated`
 * convention). BOTH labels must already exist on the repo.
 */
export const CROWN_HUNT_ISSUE_LABEL = 'crown-hunt';
export const CROWN_RETRY_LAG_LABELS = [CROWN_HUNT_ISSUE_LABEL, AUTO_GENERATED_LABEL];

/** Title tag identifying an auto-filed collect-lag issue. */
export const CROWN_RETRY_LAG_TITLE_TAG = '[Auto-crown-hunt]';

/** Fingerprint namespace — the literal prefix of every collect-lag dedup key. */
export const RETRY_LAG_FINGERPRINT_PREFIX = 'crownCollectRetry';

// ---------------------------------------------------------------------------
// Input shape
// ---------------------------------------------------------------------------

/** Which claim flow an attempt came from — keeps the two id-spaces from colliding. */
export type ClaimSource = 'spawn' | 'hunt';

/**
 * One per-attempt claim doc, reduced to the scalars this module reasons about.
 * NO coordinates ever enter here — only a server-computed distance, a reported
 * accuracy, a result string and a timestamp.
 */
export interface ClaimAttemptRecord {
  source: ClaimSource;
  uid: string;
  /** spawnId (auto-spawn) or pointId (hand-placed). */
  targetId: string;
  result: string;
  /** Server-computed metres to the crown; null when the reject path stored none. */
  distanceMeters: number | null;
  /** Reported GPS accuracy in metres; null when not stored. */
  accuracyMeters: number | null;
  claimedAtMs: number;
}

// ---------------------------------------------------------------------------
// Detection
// ---------------------------------------------------------------------------

/** A matched retry-lag episode for one `(source, uid, targetId)`. */
export interface RetryLagGroup {
  source: ClaimSource;
  uid: string;
  targetId: string;
  /** The most common lag-result inside the qualifying window. */
  dominantResult: string;
  /** Median server-computed distance across the window's rejections, or null. */
  representativeDistanceMeters: number | null;
  /** Median reported accuracy across the window's rejections, or null. */
  representativeAccuracyMeters: number | null;
  /** Lag-rejections inside the best qualifying window. */
  rejectionsInWindow: number;
  /** True when an `awarded` attempt followed the first window rejection. */
  endedInAward: boolean;
  /** Lag-rejections before the first success; null when it never succeeded. */
  attemptsBeforeSuccess: number | null;
}

function median(values: number[]): number | null {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  // Lower-middle: a real value from the data, so the bucket it lands in is one an
  // actual attempt produced rather than an interpolated midpoint.
  return sorted[Math.floor((sorted.length - 1) / 2)] ?? null;
}

function groupKey(a: ClaimAttemptRecord): string {
  // JSON-encoded tuple: unambiguous and text-safe (no collisions even if an
  // id contained the separator character).
  return JSON.stringify([a.source, a.uid, a.targetId]);
}

/**
 * Finds every `(source, uid, targetId)` whose lag-rejections burst past the
 * threshold inside the window. Single rejections and rejections spread wider than
 * the window are ignored.
 *
 * @param attempts  per-attempt records for the `(source, uid, targetId)` groups
 *                  to consider. Only lag results and `awarded` matter: lag results
 *                  form the burst, and an `awarded` after the first burst rejection
 *                  ends the episode and fixes `attemptsBeforeSuccess`. Records with
 *                  any other result are inert (safe to pass or omit).
 * @param lagOrder  the lag-result vocabulary, in tie-break order (earlier wins a
 *                  tie in the dominant-result vote). Defaults to both flows.
 */
export function detectRetryLagGroups(
  attempts: ClaimAttemptRecord[],
  lagOrder: readonly string[] = RETRY_LAG_RESULTS,
): RetryLagGroup[] {
  const lagRank = new Map<string, number>(lagOrder.map((r, i) => [r, i]));
  const isLag = (result: string): boolean => lagRank.has(result);

  const byGroup = new Map<string, ClaimAttemptRecord[]>();
  for (const attempt of attempts) {
    const key = groupKey(attempt);
    const bucket = byGroup.get(key);
    if (bucket) bucket.push(attempt);
    else byGroup.set(key, [attempt]);
  }

  const groups: RetryLagGroup[] = [];
  for (const records of byGroup.values()) {
    const sorted = [...records].sort((a, b) => a.claimedAtMs - b.claimedAtMs);
    const rejections = sorted.filter((r) => isLag(r.result));
    if (rejections.length < RETRY_LAG_MIN_REJECTIONS) continue;

    // Two-pointer sliding window over the (time-sorted) rejections: find the
    // window of at most RETRY_LAG_WINDOW_MS that holds the MOST rejections, and
    // require that maximum to clear the threshold.
    let bestStart = 0;
    let bestEnd = -1; // inclusive; bestEnd < bestStart means "none found yet"
    let bestCount = 0;
    let i = 0;
    for (let j = 0; j < rejections.length; j += 1) {
      while (rejections[j]!.claimedAtMs - rejections[i]!.claimedAtMs > RETRY_LAG_WINDOW_MS) {
        i += 1;
      }
      const count = j - i + 1;
      if (count > bestCount) {
        bestCount = count;
        bestStart = i;
        bestEnd = j;
      }
    }
    if (bestCount < RETRY_LAG_MIN_REJECTIONS) continue;

    const window = rejections.slice(bestStart, bestEnd + 1);
    const dominantResult = pickDominantResult(window, lagRank);
    const representativeDistanceMeters = median(
      window
        .map((r) => r.distanceMeters)
        .filter((d): d is number => typeof d === 'number' && Number.isFinite(d)),
    );
    const representativeAccuracyMeters = median(
      window
        .map((r) => r.accuracyMeters)
        .filter((a): a is number => typeof a === 'number' && Number.isFinite(a)),
    );

    // Success accounting spans the WHOLE group (not just the window): the first
    // `awarded` after the first windowed rejection ends the episode, and every
    // lag-rejection before that award is an attempt the member spent.
    const firstRejectionMs = window[0]!.claimedAtMs;
    const firstAward = sorted.find(
      (r) => r.result === 'awarded' && r.claimedAtMs >= firstRejectionMs,
    );
    const endedInAward = firstAward !== undefined;
    const attemptsBeforeSuccess = firstAward
      ? rejections.filter((r) => r.claimedAtMs < firstAward.claimedAtMs).length
      : null;

    groups.push({
      source: records[0]!.source,
      uid: records[0]!.uid,
      targetId: records[0]!.targetId,
      dominantResult,
      representativeDistanceMeters,
      representativeAccuracyMeters,
      rejectionsInWindow: bestCount,
      endedInAward,
      attemptsBeforeSuccess,
    });
  }

  return groups;
}

/** Most frequent result in the window; ties break toward the lower lag-rank. */
function pickDominantResult(
  window: ClaimAttemptRecord[],
  lagRank: Map<string, number>,
): string {
  const counts = new Map<string, number>();
  for (const r of window) counts.set(r.result, (counts.get(r.result) ?? 0) + 1);
  let best = window[0]!.result;
  let bestCount = -1;
  for (const [result, count] of counts) {
    if (
      count > bestCount ||
      (count === bestCount &&
        (lagRank.get(result) ?? Infinity) < (lagRank.get(best) ?? Infinity))
    ) {
      best = result;
      bestCount = count;
    }
  }
  return best;
}

// ---------------------------------------------------------------------------
// Bucketing + fingerprint
// ---------------------------------------------------------------------------

/**
 * Buckets a server-computed distance (metres) into a coarse band. The raw value
 * never leaves this module — only the band name reaches the issue. `unknown`
 * covers a reject path that stored no distance (e.g. `position_too_old`).
 */
export function bucketDistanceMeters(distanceMeters: number | null): string {
  if (typeof distanceMeters !== 'number' || !Number.isFinite(distanceMeters)) return 'unknown';
  const d = distanceMeters;
  if (d < 10) return '0-10';
  if (d < 25) return '10-25';
  if (d < 50) return '25-50';
  if (d < 75) return '50-75';
  if (d < 100) return '75-100';
  if (d < 150) return '100-150';
  return '150+';
}

/** Buckets a reported GPS accuracy (metres) into a coarse band. */
export function bucketAccuracyMeters(accuracyMeters: number | null): string {
  if (typeof accuracyMeters !== 'number' || !Number.isFinite(accuracyMeters)) return 'unknown';
  const a = accuracyMeters;
  if (a < 10) return '0-10';
  if (a < 20) return '10-20';
  if (a < 35) return '20-35';
  if (a < 50) return '35-50';
  if (a < 100) return '50-100';
  return '100+';
}

/**
 * The dedup fingerprint: `crownCollectRetry:<dominantResult>:<distanceBucket>:
 * <accuracyBucket>`. Deliberately carries NO user or target id, so every member
 * hitting the same edge condition at the same distance/accuracy shape collapses
 * into ONE issue whose occurrence counter is the scale.
 */
export function buildRetryLagFingerprint(
  dominantResult: string,
  distanceBucket: string,
  accuracyBucket: string,
): string {
  return `${RETRY_LAG_FINGERPRINT_PREFIX}:${dominantResult}:${distanceBucket}:${accuracyBucket}`;
}

// ---------------------------------------------------------------------------
// Clustering by shape
// ---------------------------------------------------------------------------

/** Bucket key for the attempts-before-success histogram (capped tail). */
export function attemptsBeforeSuccessBucket(attempts: number): string {
  if (attempts <= 5) return String(attempts);
  return '6+';
}

/** One fingerprint's worth of episodes — the unit an issue is filed for. */
export interface RetryLagCluster {
  fingerprint: string;
  dominantResult: string;
  distanceBucket: string;
  accuracyBucket: string;
  /** Distinct members who hit this shape. */
  affectedUserCount: number;
  /** Distinct `(uid, target)` episodes. */
  episodeCount: number;
  /** Episodes that eventually succeeded. */
  endedInAwardCount: number;
  /** Episodes that never succeeded in the scan window. */
  neverSucceededCount: number;
  /** attempts-before-success → episode count, for the succeeded episodes. */
  attemptsBeforeSuccessHistogram: Record<string, number>;
}

/**
 * Collapses matched episodes into per-fingerprint clusters. Sorted by
 * affected-user count (descending, then fingerprint) so the loudest shape is
 * filed first when the run is budget-capped.
 */
export function clusterRetryLagGroups(groups: RetryLagGroup[]): RetryLagCluster[] {
  interface Acc {
    dominantResult: string;
    distanceBucket: string;
    accuracyBucket: string;
    users: Set<string>;
    episodeCount: number;
    endedInAwardCount: number;
    neverSucceededCount: number;
    histogram: Map<string, number>;
  }
  const byFingerprint = new Map<string, Acc>();

  for (const group of groups) {
    const distanceBucket = bucketDistanceMeters(group.representativeDistanceMeters);
    const accuracyBucket = bucketAccuracyMeters(group.representativeAccuracyMeters);
    const fingerprint = buildRetryLagFingerprint(
      group.dominantResult,
      distanceBucket,
      accuracyBucket,
    );
    let acc = byFingerprint.get(fingerprint);
    if (!acc) {
      acc = {
        dominantResult: group.dominantResult,
        distanceBucket,
        accuracyBucket,
        users: new Set<string>(),
        episodeCount: 0,
        endedInAwardCount: 0,
        neverSucceededCount: 0,
        histogram: new Map<string, number>(),
      };
      byFingerprint.set(fingerprint, acc);
    }
    acc.users.add(group.uid);
    acc.episodeCount += 1;
    if (group.endedInAward) {
      acc.endedInAwardCount += 1;
      if (group.attemptsBeforeSuccess !== null) {
        const bucket = attemptsBeforeSuccessBucket(group.attemptsBeforeSuccess);
        acc.histogram.set(bucket, (acc.histogram.get(bucket) ?? 0) + 1);
      }
    } else {
      acc.neverSucceededCount += 1;
    }
  }

  const clusters: RetryLagCluster[] = [];
  for (const [fingerprint, acc] of byFingerprint) {
    clusters.push({
      fingerprint,
      dominantResult: acc.dominantResult,
      distanceBucket: acc.distanceBucket,
      accuracyBucket: acc.accuracyBucket,
      affectedUserCount: acc.users.size,
      episodeCount: acc.episodeCount,
      endedInAwardCount: acc.endedInAwardCount,
      neverSucceededCount: acc.neverSucceededCount,
      attemptsBeforeSuccessHistogram: Object.fromEntries(
        // Stable, human-readable order: numeric buckets ascending, '6+' last.
        [...acc.histogram.entries()].sort(([a], [b]) => histogramKeyOrder(a) - histogramKeyOrder(b)),
      ),
    });
  }
  clusters.sort(
    (a, b) =>
      b.affectedUserCount - a.affectedUserCount || a.fingerprint.localeCompare(b.fingerprint),
  );
  return clusters;
}

function histogramKeyOrder(key: string): number {
  return key === '6+' ? Number.MAX_SAFE_INTEGER : Number(key);
}

// ---------------------------------------------------------------------------
// Public GitHub issue (world-readable forever — buckets + counts only)
// ---------------------------------------------------------------------------

/** Occurrence metadata read back from the claimed dedup link. */
export interface RetryLagIssueMeta {
  firstSeenIso: string;
  count: number;
}

/**
 * Placeholder link written BEFORE the GitHub call. Carries only the fingerprint
 * and the shape scalars — no uid, no target, no coordinates.
 */
export function buildNewRetryLagIssueLink(
  cluster: RetryLagCluster,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return buildNewIssueLink(
    {
      fingerprint: cluster.fingerprint,
      dominantResult: cluster.dominantResult,
      distanceBucket: cluster.distanceBucket,
      accuracyBucket: cluster.accuracyBucket,
    },
    serverTimestamp,
  );
}

/** Renders a bucket/scalar as a defanged markdown inline-code span. */
function inlineCodeScalar(value: string): string {
  const safe = neutralizeMentions(value).replace(/`/g, "'").replace(/\s+/g, ' ').trim();
  return `\`${safe}\``;
}

/**
 * Issue title: `[Auto-crown-hunt] collect-lag: <dominantResult> at <distance>m
 * (acc <accuracy>m)`. Every token is a bucket name or a controlled constant — no
 * uid, no coordinate, no raw distance.
 */
export function buildRetryLagIssueTitle(cluster: RetryLagCluster): string {
  return (
    `${CROWN_RETRY_LAG_TITLE_TAG} collect-lag: ${cluster.dominantResult} ` +
    `at ${cluster.distanceBucket}m (acc ${cluster.accuracyBucket}m)`
  );
}

/**
 * Issue body — buckets and counts ONLY. No coordinates, no distance values, no
 * accuracy values, no uid list. `crown-claim-lag-core.test.ts` seeds uids and
 * raw coordinate-like numbers into the input and asserts none of them appear
 * here.
 */
export function buildRetryLagIssueBody(cluster: RetryLagCluster, meta: RetryLagIssueMeta): string {
  const histogramEntries = Object.entries(cluster.attemptsBeforeSuccessHistogram);
  const histogramRendered =
    histogramEntries.length > 0
      ? histogramEntries
          .map(([bucket, n]) => `${inlineCodeScalar(bucket)}×${n}`)
          .join(', ')
      : 'none recorded';

  return [
    'Automatically filed: members are RETRYING a Kronjakt collect several times in quick succession before it succeeds (or gives up). This is a collect-lag signal — the collect button feels broken at the edge of the geofence / stationary check. Repeat occurrences increment the tally instead of filing new issues.',
    '',
    `- Dominant rejection: ${inlineCodeScalar(cluster.dominantResult)}`,
    `- Distance bucket (server-computed, metres): ${inlineCodeScalar(cluster.distanceBucket)}`,
    `- Accuracy bucket (reported GPS, metres): ${inlineCodeScalar(cluster.accuracyBucket)}`,
    `- Affected members: ${cluster.affectedUserCount}`,
    `- Retry episodes: ${cluster.episodeCount} (succeeded ${cluster.endedInAwardCount}, gave up ${cluster.neverSucceededCount})`,
    `- Attempts-before-success distribution: ${histogramRendered}`,
    `- Fingerprint: ${cluster.fingerprint}`,
    `- First seen: ${meta.firstSeenIso}`,
    `- Occurrences: ${meta.count}`,
    '',
    `A burst is ${RETRY_LAG_MIN_REJECTIONS}+ lag-rejections for one member on one crown within ${RETRY_LAG_WINDOW_MS / 1000}s. Lag results are the retryable edge-condition codes each collect flow emits: \`${SPAWN_LAG_RESULTS.join('` / `')}\` (auto-spawn crowns, claimSpawn) and \`${HUNT_LAG_RESULTS.join('` / `')}\` (hand-placed points, submitClaim). This is DETECTION only — the client-side collect UX fix (clearer "move closer / hold still" guidance, edge tolerance) is tracked separately.`,
    '',
    '_Filed by crownHunt-detectClaimLag. This issue is public and never includes account identifiers, coordinates, raw distances or GPS accuracies — only coarse buckets and counts._',
  ].join('\n');
}

/** Full `POST /issues` request body for an auto-filed collect-lag issue. */
export function buildRetryLagIssuePayload(
  cluster: RetryLagCluster,
  meta: RetryLagIssueMeta,
): GitHubIssuePayload {
  return {
    title: buildRetryLagIssueTitle(cluster),
    body: buildRetryLagIssueBody(cluster, meta),
    labels: [...CROWN_RETRY_LAG_LABELS],
  };
}
