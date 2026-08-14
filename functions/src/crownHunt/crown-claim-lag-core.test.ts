/**
 * Unit tests for the db-free collect-lag detection core.
 */

import { describe, expect, it } from 'vitest';
import {
  RETRY_LAG_RESULTS,
  attemptsBeforeSuccessBucket,
  bucketAccuracyMeters,
  bucketDistanceMeters,
  buildRetryLagFingerprint,
  buildRetryLagIssueBody,
  buildRetryLagIssuePayload,
  buildRetryLagIssueTitle,
  clusterRetryLagGroups,
  detectRetryLagGroups,
  type ClaimAttemptRecord,
  type RetryLagCluster,
} from './crown-claim-lag-core';

const T0 = Date.UTC(2026, 7, 14, 12, 0, 0);

function rec(over: Partial<ClaimAttemptRecord> & { claimedAtMs: number }): ClaimAttemptRecord {
  return {
    source: 'spawn',
    uid: 'u1',
    targetId: 'crownA',
    result: 'outside_radius',
    distanceMeters: 80,
    accuracyMeters: 15,
    ...over,
  };
}

describe('detectRetryLagGroups', () => {
  it('flags 3 lag-rejections within 2 minutes', () => {
    const attempts = [
      rec({ claimedAtMs: T0 }),
      rec({ claimedAtMs: T0 + 20_000 }),
      rec({ claimedAtMs: T0 + 100_000 }), // 100s < 120s window
    ];
    const groups = detectRetryLagGroups(attempts);
    expect(groups).toHaveLength(1);
    expect(groups[0]!.rejectionsInWindow).toBe(3);
    expect(groups[0]!.dominantResult).toBe('outside_radius');
    expect(groups[0]!.endedInAward).toBe(false);
    expect(groups[0]!.attemptsBeforeSuccess).toBeNull();
  });

  it('ignores a single reject', () => {
    expect(detectRetryLagGroups([rec({ claimedAtMs: T0 })])).toHaveLength(0);
  });

  it('ignores two rejects (below the threshold)', () => {
    expect(
      detectRetryLagGroups([rec({ claimedAtMs: T0 }), rec({ claimedAtMs: T0 + 5_000 })]),
    ).toHaveLength(0);
  });

  it('ignores 3 rejects spread wider than the window', () => {
    const attempts = [
      rec({ claimedAtMs: T0 }),
      rec({ claimedAtMs: T0 + 90_000 }),
      rec({ claimedAtMs: T0 + 200_000 }), // spans 200s across all three
    ];
    // No single 120s window holds all three; each pair is <3.
    expect(detectRetryLagGroups(attempts)).toHaveLength(0);
  });

  it('still flags when a later triple fits the window even if an early one is far', () => {
    const attempts = [
      rec({ claimedAtMs: T0 }), // far outlier
      rec({ claimedAtMs: T0 + 300_000 }),
      rec({ claimedAtMs: T0 + 320_000 }),
      rec({ claimedAtMs: T0 + 340_000 }), // three within 40s
    ];
    const groups = detectRetryLagGroups(attempts);
    expect(groups).toHaveLength(1);
    expect(groups[0]!.rejectionsInWindow).toBe(3);
  });

  it('does not mix two different users on the same crown', () => {
    const attempts = [
      rec({ uid: 'a', claimedAtMs: T0 }),
      rec({ uid: 'a', claimedAtMs: T0 + 10_000 }),
      rec({ uid: 'b', claimedAtMs: T0 + 20_000 }),
    ];
    expect(detectRetryLagGroups(attempts)).toHaveLength(0);
  });

  it('does not mix spawn and hunt id-spaces even with the same target id', () => {
    const attempts = [
      rec({ source: 'spawn', targetId: 'X', claimedAtMs: T0 }),
      rec({ source: 'spawn', targetId: 'X', claimedAtMs: T0 + 5_000 }),
      rec({ source: 'hunt', targetId: 'X', result: 'outside_geofence', claimedAtMs: T0 + 10_000 }),
    ];
    expect(detectRetryLagGroups(attempts)).toHaveLength(0);
  });

  it('counts attempts-before-success when the burst ends in an award', () => {
    const attempts = [
      rec({ claimedAtMs: T0 }),
      rec({ claimedAtMs: T0 + 10_000 }),
      rec({ claimedAtMs: T0 + 20_000 }),
      rec({ result: 'awarded', distanceMeters: 40, claimedAtMs: T0 + 30_000 }),
    ];
    const groups = detectRetryLagGroups(attempts);
    expect(groups).toHaveLength(1);
    expect(groups[0]!.endedInAward).toBe(true);
    expect(groups[0]!.attemptsBeforeSuccess).toBe(3);
  });

  it('a non-lag rejection does not count toward the burst', () => {
    const attempts = [
      rec({ claimedAtMs: T0 }),
      rec({ result: 'risk_review', claimedAtMs: T0 + 10_000 }),
      rec({ claimedAtMs: T0 + 20_000 }),
    ];
    // Only two lag rejections (risk_review is not a lag result).
    expect(detectRetryLagGroups(attempts)).toHaveLength(0);
  });

  it('is insensitive to input ordering (records may arrive newest-first)', () => {
    // The DB read pages createdAt DESC (so the cap keeps the NEWEST docs) and
    // reverses to ascending; detection also sorts internally, so a newest-first
    // or shuffled input must yield the same match.
    const ascending = [
      rec({ claimedAtMs: T0 }),
      rec({ claimedAtMs: T0 + 20_000 }),
      rec({ claimedAtMs: T0 + 40_000 }),
    ];
    const descending = [...ascending].reverse();
    const shuffled = [ascending[1]!, ascending[2]!, ascending[0]!];
    expect(detectRetryLagGroups(descending)).toEqual(detectRetryLagGroups(ascending));
    expect(detectRetryLagGroups(shuffled)).toEqual(detectRetryLagGroups(ascending));
    expect(detectRetryLagGroups(descending)[0]!.rejectionsInWindow).toBe(3);
  });

  it('picks the most frequent lag-result as dominant', () => {
    const attempts = [
      rec({ result: 'must_be_stationary', distanceMeters: 30, claimedAtMs: T0 }),
      rec({ result: 'must_be_stationary', distanceMeters: 30, claimedAtMs: T0 + 10_000 }),
      rec({ result: 'outside_radius', distanceMeters: 90, claimedAtMs: T0 + 20_000 }),
    ];
    expect(detectRetryLagGroups(attempts)[0]!.dominantResult).toBe('must_be_stationary');
  });
});

describe('bucketing + fingerprint', () => {
  it('buckets distance into coarse bands', () => {
    expect(bucketDistanceMeters(5)).toBe('0-10');
    expect(bucketDistanceMeters(80)).toBe('75-100');
    expect(bucketDistanceMeters(200)).toBe('150+');
    expect(bucketDistanceMeters(null)).toBe('unknown');
    expect(bucketDistanceMeters(Number.NaN)).toBe('unknown');
  });

  it('buckets accuracy into coarse bands', () => {
    expect(bucketAccuracyMeters(8)).toBe('0-10');
    expect(bucketAccuracyMeters(40)).toBe('35-50');
    expect(bucketAccuracyMeters(500)).toBe('100+');
    expect(bucketAccuracyMeters(null)).toBe('unknown');
  });

  it('fingerprints by shape, prefixed and stable', () => {
    expect(buildRetryLagFingerprint('outside_radius', '75-100', '10-20')).toBe(
      'crownCollectRetry:outside_radius:75-100:10-20',
    );
  });

  it('attempts-before-success bucket caps the tail at 6+', () => {
    expect(attemptsBeforeSuccessBucket(3)).toBe('3');
    expect(attemptsBeforeSuccessBucket(5)).toBe('5');
    expect(attemptsBeforeSuccessBucket(9)).toBe('6+');
  });
});

describe('clusterRetryLagGroups', () => {
  it('collapses same-shape episodes across users and counts distinct members', () => {
    // Three members, same shape (outside_radius, dist 75-100, acc 10-20) on
    // different crowns → ONE cluster, affectedUserCount 3.
    const attempts: ClaimAttemptRecord[] = [];
    for (const uid of ['a', 'b', 'c']) {
      const base = T0 + (uid.charCodeAt(0) - 97) * 1_000_000;
      attempts.push(
        rec({ uid, targetId: `crown-${uid}`, claimedAtMs: base }),
        rec({ uid, targetId: `crown-${uid}`, claimedAtMs: base + 10_000 }),
        rec({ uid, targetId: `crown-${uid}`, claimedAtMs: base + 20_000 }),
      );
    }
    const clusters = clusterRetryLagGroups(detectRetryLagGroups(attempts));
    expect(clusters).toHaveLength(1);
    expect(clusters[0]!.affectedUserCount).toBe(3);
    expect(clusters[0]!.episodeCount).toBe(3);
    expect(clusters[0]!.fingerprint).toBe('crownCollectRetry:outside_radius:75-100:10-20');
  });

  it('separates distinct shapes and sorts loudest-first', () => {
    const shapeA: ClaimAttemptRecord[] = ['a', 'b'].flatMap((uid) => {
      const base = T0 + uid.charCodeAt(0) * 1_000_000;
      return [
        rec({ uid, targetId: `A-${uid}`, distanceMeters: 90, claimedAtMs: base }),
        rec({ uid, targetId: `A-${uid}`, distanceMeters: 90, claimedAtMs: base + 5_000 }),
        rec({ uid, targetId: `A-${uid}`, distanceMeters: 90, claimedAtMs: base + 10_000 }),
      ];
    });
    const shapeB: ClaimAttemptRecord[] = (() => {
      const base = T0 + 9_000_000;
      return [
        rec({ uid: 'z', targetId: 'B', result: 'must_be_stationary', distanceMeters: 5, claimedAtMs: base }),
        rec({ uid: 'z', targetId: 'B', result: 'must_be_stationary', distanceMeters: 5, claimedAtMs: base + 5_000 }),
        rec({ uid: 'z', targetId: 'B', result: 'must_be_stationary', distanceMeters: 5, claimedAtMs: base + 10_000 }),
      ];
    })();
    const clusters = clusterRetryLagGroups(detectRetryLagGroups([...shapeA, ...shapeB]));
    expect(clusters).toHaveLength(2);
    // shapeA has 2 users, shapeB has 1 → shapeA first.
    expect(clusters[0]!.affectedUserCount).toBe(2);
    expect(clusters[0]!.dominantResult).toBe('outside_radius');
    expect(clusters[1]!.dominantResult).toBe('must_be_stationary');
  });

  it('builds an attempts-before-success histogram over succeeded episodes', () => {
    const succeeded = [
      rec({ uid: 'a', targetId: 'c1', claimedAtMs: T0 }),
      rec({ uid: 'a', targetId: 'c1', claimedAtMs: T0 + 5_000 }),
      rec({ uid: 'a', targetId: 'c1', claimedAtMs: T0 + 10_000 }),
      rec({ uid: 'a', targetId: 'c1', result: 'awarded', claimedAtMs: T0 + 15_000 }),
    ];
    const gaveUp = [
      rec({ uid: 'b', targetId: 'c2', claimedAtMs: T0 + 1_000_000 }),
      rec({ uid: 'b', targetId: 'c2', claimedAtMs: T0 + 1_005_000 }),
      rec({ uid: 'b', targetId: 'c2', claimedAtMs: T0 + 1_010_000 }),
    ];
    const clusters = clusterRetryLagGroups(detectRetryLagGroups([...succeeded, ...gaveUp]));
    expect(clusters).toHaveLength(1);
    expect(clusters[0]!.endedInAwardCount).toBe(1);
    expect(clusters[0]!.neverSucceededCount).toBe(1);
    expect(clusters[0]!.attemptsBeforeSuccessHistogram).toEqual({ '3': 1 });
  });
});

describe('issue payload — no PII, buckets + counts only', () => {
  const cluster: RetryLagCluster = {
    fingerprint: 'crownCollectRetry:outside_radius:75-100:10-20',
    dominantResult: 'outside_radius',
    distanceBucket: '75-100',
    accuracyBucket: '10-20',
    affectedUserCount: 12,
    episodeCount: 15,
    endedInAwardCount: 10,
    neverSucceededCount: 5,
    attemptsBeforeSuccessHistogram: { '3': 6, '4': 3, '6+': 1 },
  };

  it('renders buckets and counts', () => {
    const body = buildRetryLagIssueBody(cluster, { firstSeenIso: '2026-08-14T10:00:00.000Z', count: 4 });
    expect(body).toContain('outside_radius');
    expect(body).toContain('75-100');
    expect(body).toContain('Affected members: 12');
    expect(body).toContain('Occurrences: 4');
  });

  it('never leaks a uid, a coordinate or a raw distance', () => {
    // Detect from records seeded with a plausible uid and coordinate-like numbers,
    // then assert none of them reach the rendered issue.
    const secretUid = 'firebase-uid-SECRET-9and';
    const attempts = [
      rec({ uid: secretUid, distanceMeters: 59.123456, accuracyMeters: 12.98765, claimedAtMs: T0 }),
      rec({ uid: secretUid, distanceMeters: 59.123456, accuracyMeters: 12.98765, claimedAtMs: T0 + 5_000 }),
      rec({ uid: secretUid, distanceMeters: 59.123456, accuracyMeters: 12.98765, claimedAtMs: T0 + 10_000 }),
    ];
    const detected = clusterRetryLagGroups(detectRetryLagGroups(attempts))[0]!;
    const payload = buildRetryLagIssuePayload(detected, {
      firstSeenIso: '2026-08-14T10:00:00.000Z',
      count: 1,
    });
    const rendered = `${payload.title}\n${payload.body}`;
    expect(rendered).not.toContain(secretUid);
    expect(rendered).not.toContain('59.123456');
    expect(rendered).not.toContain('12.98765');
    // The coarse buckets ARE present.
    expect(rendered).toContain('50-75'); // 59m → 50-75 band
    expect(rendered).toContain('10-20'); // 12m → 10-20 band
  });

  it('labels with crown-hunt + auto-generated', () => {
    const payload = buildRetryLagIssuePayload(cluster, { firstSeenIso: 'x', count: 1 });
    expect(payload.labels).toEqual(['crown-hunt', 'auto-generated']);
  });

  it('formats title bucket units cleanly (spaced m; no unit on unknown)', () => {
    const title = buildRetryLagIssuePayload(cluster, { firstSeenIso: 'x', count: 1 }).title;
    expect(title).toContain('at 75-100 m (acc 10-20 m)');
    expect(title).not.toMatch(/\d\d?m\b/); // never "100m" jammed together

    const openEnded = buildRetryLagIssueTitle({ ...cluster, distanceBucket: '150+', accuracyBucket: '100+' });
    expect(openEnded).toContain('at 150+ m (acc 100+ m)');

    const unknown = buildRetryLagIssueTitle({ ...cluster, distanceBucket: 'unknown', accuracyBucket: 'unknown' });
    expect(unknown).toContain('at unknown (acc unknown)');
    expect(unknown).not.toContain('unknownm');
  });
});

describe('RETRY_LAG_RESULTS', () => {
  it('unions both flows without duplicating position_too_old', () => {
    expect(RETRY_LAG_RESULTS).toEqual([
      'outside_radius',
      'must_be_stationary',
      'position_too_old',
      'outside_geofence',
      'moving_too_fast',
    ]);
  });
});
