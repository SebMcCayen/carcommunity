/**
 * Daily-points-cap-reached → public GitHub issue: pure domain logic (no Firebase
 * Admin SDK, no network), so every branch is unit-testable without emulators
 * (mirrors the sibling `*-core.ts` files, and models crown-claim-lag-core.ts).
 *
 * THE SIGNAL. `DAILY_POINTS_CAP` (points-economy-core.ts) bounds the non-driving
 * points a member can earn in one Europe/Stockholm day; Kronjakt crowns fold into
 * the same counter. When legit players grind hard they can fill that budget and
 * every further non-driving award for the rest of the day is refused
 * `cap_reached`. The owner wants to NOTICE that automatically — a member hitting
 * the ceiling is a tuning signal ("is the cap too low now that auto-spawn added a
 * second crown lane?"), not an error. This module builds the ONE deduplicated
 * public issue that carries that signal.
 *
 * FINGERPRINT BY SHAPE, NOT BY USER. The dedup key is
 * `dailyCapReached:<capType>:<YYYY-MM-DD>` (Stockholm day), so ALL members who
 * reach the cap on one day collapse into ONE issue whose occurrence counter grows
 * — never one issue per member, never a flood. A fresh civil day is a fresh
 * fingerprint and therefore a fresh issue.
 *
 * PUBLIC-REPO SAFETY. The repo is world-readable, so the issue body carries ONLY
 * the cap value, the cap type, an aggregate member count and a day — NO uid, NO
 * coordinates, NO per-member data of any kind. Everything rendered is a
 * server-controlled scalar; the test suite asserts nothing user-derived leaks.
 */

import type { GitHubIssuePayload } from '../shared/githubIssues';
import { neutralizeMentions } from '../shared/githubIssues';
import { AUTO_GENERATED_LABEL } from '../diagnostics/signInIssues-core';
import { CROWN_HUNT_ISSUE_LABEL } from '../crownHunt/crown-claim-lag-core';
import { buildNewIssueLink } from '../shared/issueLinks-core';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/**
 * The kind of ceiling that was reached. This detector instruments the DAILY
 * POINTS cap (`DAILY_POINTS_CAP`) — the one legit crown grinders actually hit,
 * because crown point values (10-500) fill 2000 KP long before the crown-COUNT
 * caps (10 hand-placed + 20 spawned = 30 claims/day) bind. `'points'` names that
 * lane; a future crown-count instrument could file under a distinct type and get
 * its own fingerprint (and therefore its own issue) for free.
 */
export type DailyCapType = 'points';

/** The one cap type this detector files for today. */
export const DAILY_POINTS_CAP_TYPE: DailyCapType = 'points';

/** Server-only collection linking a per-day fingerprint to its GitHub issue. */
export const DAILY_CAP_ISSUE_LINKS_COLLECTION = 'dailyCapIssueLinks';

/**
 * Labels on every auto-filed daily-cap issue. `crown-hunt` is the existing
 * economy/crown label (grinders reach the cap via crowns) and `auto-generated`
 * is the shared auto-filing label — BOTH already exist on the repo (labels must
 * pre-exist or the create fails), matching the `sign-in-failure` + `auto-generated`
 * and `crown-hunt` + `auto-generated` conventions.
 */
export const DAILY_CAP_ISSUE_LABELS = [CROWN_HUNT_ISSUE_LABEL, AUTO_GENERATED_LABEL];

/** Title tag identifying an auto-filed daily-cap issue. */
export const DAILY_CAP_TITLE_TAG = '[Auto-economy]';

/** Fingerprint namespace — the literal prefix of every daily-cap dedup key. */
export const DAILY_CAP_FINGERPRINT_PREFIX = 'dailyCapReached';

// ---------------------------------------------------------------------------
// Fingerprint + cluster shape
// ---------------------------------------------------------------------------

/**
 * The dedup fingerprint: `dailyCapReached:<capType>:<YYYY-MM-DD>`. Carries NO
 * user id — one issue per cap type per Europe/Stockholm day, so a whole day of
 * cap-reachers is one issue whose occurrence counter is the scale.
 */
export function buildDailyCapFingerprint(capType: DailyCapType, dayKey: string): string {
  return `${DAILY_CAP_FINGERPRINT_PREFIX}:${capType}:${dayKey}`;
}

/** One day's worth of cap-reachers — the unit an issue is filed for. */
export interface DailyCapCluster {
  fingerprint: string;
  capType: DailyCapType;
  /** The cap value in force (`DAILY_POINTS_CAP`). */
  capValue: number;
  /** The Europe/Stockholm civil day (`YYYY-MM-DD`) the cap was reached on. */
  dayKey: string;
  /**
   * Distinct members whose daily total is at or above the cap at detection time.
   * An AGGREGATE count only — never a uid list. This is a snapshot at first-file:
   * later reachers that day increment the occurrence tally, not this number.
   */
  memberCount: number;
}

/** Builds the cluster for `dayKey` from a distinct-member headcount. */
export function buildDailyCapCluster(
  capType: DailyCapType,
  capValue: number,
  dayKey: string,
  memberCount: number,
): DailyCapCluster {
  return {
    fingerprint: buildDailyCapFingerprint(capType, dayKey),
    capType,
    capValue,
    dayKey,
    memberCount,
  };
}

// ---------------------------------------------------------------------------
// Public GitHub issue (world-readable forever — cap value + counts only)
// ---------------------------------------------------------------------------

/** Occurrence metadata read back from the claimed dedup link. */
export interface DailyCapIssueMeta {
  firstSeenIso: string;
  count: number;
}

/**
 * Placeholder link written BEFORE the GitHub call. Carries only the fingerprint
 * and the cap scalars — no uid, no per-member data.
 */
export function buildNewDailyCapIssueLink(
  cluster: DailyCapCluster,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return buildNewIssueLink(
    {
      fingerprint: cluster.fingerprint,
      capType: cluster.capType,
      capValue: cluster.capValue,
      day: cluster.dayKey,
    },
    serverTimestamp,
  );
}

/** Renders a scalar as a defanged markdown inline-code span. */
function inlineCodeScalar(value: string): string {
  const safe = neutralizeMentions(value).replace(/`/g, "'").replace(/\s+/g, ' ').trim();
  return `\`${safe}\``;
}

/**
 * Issue title: `[Auto-economy] daily points cap reached (2000 KP) — YYYY-MM-DD`.
 * Every token is a server constant — no uid, no per-member data.
 */
export function buildDailyCapIssueTitle(cluster: DailyCapCluster): string {
  return (
    `${DAILY_CAP_TITLE_TAG} daily ${cluster.capType} cap reached ` +
    `(${cluster.capValue} KP) — ${cluster.dayKey}`
  );
}

/**
 * Issue body — cap value, cap type, an aggregate member count and the day ONLY.
 * No uid, no coordinates, no per-member data. `daily-cap-issue-core.test.ts`
 * seeds uid-like strings into the cluster and asserts none of them appear here.
 */
export function buildDailyCapIssueBody(cluster: DailyCapCluster, meta: DailyCapIssueMeta): string {
  return [
    'Automatically filed: one or more members reached the daily Kronpoäng cap ' +
      `(${cluster.capValue} KP, non-driving + folded Kronjakt crowns) on ${cluster.dayKey}. ` +
      'Once a member fills that budget, every further non-driving award that day is refused ' +
      '`cap_reached`. This is a TUNING signal — legit players grinding hard now reach it — not an ' +
      'error. Repeat detections increment the tally below instead of filing new issues; a new ' +
      'civil day files a fresh issue.',
    '',
    `- Cap type: ${inlineCodeScalar(cluster.capType)} (points/day — \`DAILY_POINTS_CAP\`)`,
    `- Cap value: ${cluster.capValue} KP`,
    `- Members at/over the cap (aggregate, at first detection): ${cluster.memberCount}`,
    `- Day (Europe/Stockholm): ${inlineCodeScalar(cluster.dayKey)}`,
    `- Fingerprint: ${cluster.fingerprint}`,
    `- First seen: ${meta.firstSeenIso}`,
    `- Occurrences (detection passes that saw ≥1 reacher today): ${meta.count}`,
    '',
    'The crown-COUNT caps (`MAX_DAILY_SUCCESSFUL_CLAIMS` = 10 hand-placed, ' +
      '`MAX_DAILY_SPAWN_CLAIMS` = 20 spawned) remain the real anti-farm bound on crowns; this ' +
      'points cap exists so a lucky/boosted day cannot run away. If honest players hit it often, ' +
      'raise `DAILY_POINTS_CAP` (single tunable in points-economy-core.ts).',
    '',
    '_Filed by points-detectDailyCapReached. This issue is public and never includes account ' +
      'identifiers, coordinates or any per-member data — only the cap value, cap type, an ' +
      'aggregate headcount and the day._',
  ].join('\n');
}

/** Full `POST /issues` request body for an auto-filed daily-cap issue. */
export function buildDailyCapIssuePayload(
  cluster: DailyCapCluster,
  meta: DailyCapIssueMeta,
): GitHubIssuePayload {
  return {
    title: buildDailyCapIssueTitle(cluster),
    body: buildDailyCapIssueBody(cluster, meta),
    labels: [...DAILY_CAP_ISSUE_LABELS],
  };
}
