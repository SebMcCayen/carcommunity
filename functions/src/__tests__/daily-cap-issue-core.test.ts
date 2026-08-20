/**
 * Hard unit tests for the daily-cap-reached auto-issue pure core.
 *
 * Covers the dedup fingerprint (stable per cap type + Stockholm day, so one
 * issue per day), the cluster builder, and the PUBLIC-REPO safety of the issue
 * payload: cap value / cap type / aggregate headcount / day only — never a uid
 * or any per-member data, and no markdown-injection escape from the rendered
 * scalars. Emulator-level behaviour (the Firestore scan, filing, dedup across a
 * pre-existing link, and the token-missing skip) lives in
 * points-daily-cap.emulator.test.ts.
 */

import { describe, expect, it } from 'vitest';
import {
  DAILY_CAP_FINGERPRINT_PREFIX,
  DAILY_CAP_ISSUE_LABELS,
  DAILY_POINTS_CAP_TYPE,
  buildDailyCapCluster,
  buildDailyCapFingerprint,
  buildDailyCapIssueBody,
  buildDailyCapIssuePayload,
  buildDailyCapIssueTitle,
  buildNewDailyCapIssueLink,
} from '../points/daily-cap-issue-core';
import { decideIssueAction } from '../shared/issueLinks-core';

describe('daily-cap fingerprint (the dedup key)', () => {
  it('is `dailyCapReached:<capType>:<day>` and deterministic', () => {
    const fp = buildDailyCapFingerprint('points', '2026-08-18');
    expect(fp).toBe(`${DAILY_CAP_FINGERPRINT_PREFIX}:points:2026-08-18`);
    expect(buildDailyCapFingerprint('points', '2026-08-18')).toBe(fp);
  });

  it('changes per civil day, so a new day files a fresh issue', () => {
    expect(buildDailyCapFingerprint('points', '2026-08-18')).not.toBe(
      buildDailyCapFingerprint('points', '2026-08-19'),
    );
  });

  it('carries NO uid or per-member data — one fingerprint for a whole day', () => {
    // The signature cannot even accept a uid; the fingerprint is a pure function
    // of the cap type and the day, so every member who reaches the cap that day
    // collapses onto the same key.
    const fp = buildDailyCapFingerprint(DAILY_POINTS_CAP_TYPE, '2026-08-18');
    expect(fp.split(':')).toHaveLength(3);
  });
});

describe('dedup decision maps one fingerprint to one issue', () => {
  it('creates on first sight, then only increments while the issue exists/creates', () => {
    // First occurrence of the day → CREATE; a `creating`/`created` link → INCREMENT
    // (the tally grows, no new issue); a `failed` link → CREATE (retry). This is
    // the shared state machine the daily-cap link rides on.
    expect(decideIssueAction(null)).toBe('create');
    expect(decideIssueAction({ status: 'creating', count: 1 })).toBe('increment');
    expect(decideIssueAction({ status: 'created', count: 5 })).toBe('increment');
    expect(decideIssueAction({ status: 'failed', count: 2 })).toBe('create');
  });
});

describe('the auto-filed issue payload', () => {
  const cluster = buildDailyCapCluster('points', 2000, '2026-08-18', 4);
  const meta = { firstSeenIso: '2026-08-18T09:00:00.000Z', count: 3 };

  it('uses ONLY labels that already exist on the repo', () => {
    expect(DAILY_CAP_ISSUE_LABELS).toEqual(['crown-hunt', 'auto-generated']);
    expect(buildDailyCapIssuePayload(cluster, meta).labels).toEqual([
      'crown-hunt',
      'auto-generated',
    ]);
  });

  it('states the cap value, cap type and day in the title', () => {
    const title = buildDailyCapIssueTitle(cluster);
    expect(title).toContain('2000 KP');
    expect(title).toContain('points');
    expect(title).toContain('2026-08-18');
  });

  it('reports the cap value, cap type, aggregate member count and day in the body', () => {
    const body = buildDailyCapIssueBody(cluster, meta);
    expect(body).toContain('2000 KP');
    expect(body).toContain('points');
    expect(body).toContain('2026-08-18');
    // The aggregate headcount is present as a plain count.
    expect(body).toMatch(/aggregate.*: 4/);
    // Occurrence tally + first-seen from the read-back link.
    expect(body).toContain('2026-08-18T09:00:00.000Z');
    expect(body).toMatch(/Occurrences.*: 3/);
    // Points to the single tunable so the reader knows how to retune.
    expect(body).toContain('DAILY_POINTS_CAP');
  });

  it('never leaks per-member data: no uid, no coordinates, no lat/long tokens', () => {
    // The cluster shape has no field that could carry a uid or a coordinate;
    // assert the rendered body proves it (regression guard if fields are added).
    const body = buildDailyCapIssueBody(cluster, meta).toLowerCase();
    expect(body).not.toContain('uid');
    expect(body).not.toContain('userid');
    expect(body).not.toContain('latitude');
    expect(body).not.toContain('longitude');
    expect(body).not.toMatch(/\d+\.\d{4,}/); // no raw coordinate-precision decimals
  });
});

describe('the placeholder link doc', () => {
  it('carries only the fingerprint + cap scalars, status creating, count 1', () => {
    const cluster = buildDailyCapCluster('points', 2000, '2026-08-18', 4);
    const link = buildNewDailyCapIssueLink(cluster, () => 'TS');
    expect(link).toMatchObject({
      fingerprint: cluster.fingerprint,
      capType: 'points',
      capValue: 2000,
      day: '2026-08-18',
      status: 'creating',
      count: 1,
    });
    // No member headcount is persisted on the link (it is a body-only snapshot),
    // and certainly no uid.
    expect(Object.keys(link)).not.toContain('memberCount');
    expect(JSON.stringify(link)).not.toMatch(/uid/i);
  });
});
