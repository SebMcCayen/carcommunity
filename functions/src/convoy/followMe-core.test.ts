/**
 * Unit tests for the convoy FOLLOW-ME leader-trail pure logic (followMe-core.ts).
 * No emulator.
 *
 * Pins the invariants the callable + renderer both depend on: the exclusivity /
 * takeover / toggle-off decision, the member-side freshness + render gate (so a
 * vanished leader's stale line stops drawing without an inactivity timer), and
 * the input parser.
 */

import { describe, expect, it } from 'vitest';
import {
  FOLLOW_ME_DOC_ID,
  FOLLOW_ME_STALE_MS,
  FOLLOW_ME_TRAIL_WINDOW_METERS,
  decideSetFollowMe,
  isFollowMeTrailFresh,
  parseSetFollowMeInput,
  shouldDrawFollowMeTrail,
} from './followMe-core';

describe('constants', () => {
  it('uses a fixed single-doc id so takeover is a plain overwrite', () => {
    expect(FOLLOW_ME_DOC_ID).toBe('current');
  });

  it('rolls the trail over ~15 km (far longer than the ~1 km self-trail)', () => {
    expect(FOLLOW_ME_TRAIL_WINDOW_METERS).toBe(15_000);
  });
});

describe('decideSetFollowMe — exclusivity / takeover / toggle', () => {
  it('activation with no current leader makes the caller leader', () => {
    expect(decideSetFollowMe(null, 'alice', true)).toEqual({ kind: 'set', leaderUid: 'alice' });
  });

  it('activation TAKES OVER from a different current leader (newest presser wins)', () => {
    // bob was leading; alice presses Follow me -> alice becomes the sole leader.
    expect(decideSetFollowMe('bob', 'alice', true)).toEqual({ kind: 'set', leaderUid: 'alice' });
  });

  it('re-activation by the current leader is a NO-OP (idempotent — must not reset their polyline)', () => {
    expect(decideSetFollowMe('alice', 'alice', true)).toEqual({ kind: 'noop' });
  });

  it('activation with NO current leader sets the caller as leader', () => {
    expect(decideSetFollowMe(null, 'alice', true)).toEqual({ kind: 'set', leaderUid: 'alice' });
  });

  it('the current leader can toggle their own trail OFF', () => {
    expect(decideSetFollowMe('alice', 'alice', false)).toEqual({ kind: 'clear' });
  });

  it('a NON-leader turning off is a no-op — one member cannot wipe another trail', () => {
    expect(decideSetFollowMe('bob', 'alice', false)).toEqual({ kind: 'noop' });
  });

  it('turning off when there is no trail at all is a no-op', () => {
    expect(decideSetFollowMe(null, 'alice', false)).toEqual({ kind: 'noop' });
  });
});

describe('isFollowMeTrailFresh', () => {
  const now = 1_000_000_000;

  it('a just-written trail is fresh', () => {
    expect(isFollowMeTrailFresh(now - 1_000, now)).toBe(true);
  });

  it('is fresh right up to (but not at) the window boundary', () => {
    expect(isFollowMeTrailFresh(now - (FOLLOW_ME_STALE_MS - 1), now)).toBe(true);
    expect(isFollowMeTrailFresh(now - FOLLOW_ME_STALE_MS, now)).toBe(false);
  });

  it('a long-silent trail (crashed/vanished leader) is stale', () => {
    expect(isFollowMeTrailFresh(now - 10 * 60_000, now)).toBe(false);
  });

  it('fails closed on a missing / non-finite timestamp', () => {
    expect(isFollowMeTrailFresh(null, now)).toBe(false);
    expect(isFollowMeTrailFresh(undefined, now)).toBe(false);
    expect(isFollowMeTrailFresh(Number.NaN, now)).toBe(false);
    expect(isFollowMeTrailFresh(Number.POSITIVE_INFINITY, now)).toBe(false);
  });

  it('honours a custom window', () => {
    expect(isFollowMeTrailFresh(now - 5_000, now, 10_000)).toBe(true);
    expect(isFollowMeTrailFresh(now - 5_000, now, 4_000)).toBe(false);
  });
});

describe('shouldDrawFollowMeTrail — member render gate', () => {
  const now = 1_000_000_000;
  const base = {
    leaderUid: 'bob',
    selfUid: 'alice',
    leaderIsMember: true,
    lastFreshMs: now - 1_000,
    nowMs: now,
  };

  it('draws a fresh trail led by another member', () => {
    expect(shouldDrawFollowMeTrail(base)).toBe(true);
  });

  it('does NOT draw when there is no leader', () => {
    expect(shouldDrawFollowMeTrail({ ...base, leaderUid: null })).toBe(false);
  });

  it('does NOT draw the viewer their OWN shared trail (they keep the self-trail)', () => {
    expect(shouldDrawFollowMeTrail({ ...base, leaderUid: 'alice' })).toBe(false);
  });

  it('does NOT draw a trail whose leader left the convoy', () => {
    expect(shouldDrawFollowMeTrail({ ...base, leaderIsMember: false })).toBe(false);
  });

  it('does NOT draw a stale trail (vanished leader) even if still a member', () => {
    expect(shouldDrawFollowMeTrail({ ...base, lastFreshMs: now - 10 * 60_000 })).toBe(false);
  });
});

describe('parseSetFollowMeInput', () => {
  it('accepts a well-formed toggle', () => {
    const r = parseSetFollowMeInput({ convoyId: 'abc123', active: true });
    expect(r.ok).toBe(true);
    if (r.ok) expect(r.input).toEqual({ convoyId: 'abc123', active: true });
  });

  it('rejects a missing/blank convoyId', () => {
    expect(parseSetFollowMeInput({ active: true }).ok).toBe(false);
    expect(parseSetFollowMeInput({ convoyId: '', active: true }).ok).toBe(false);
  });

  it('rejects a non-boolean active', () => {
    expect(parseSetFollowMeInput({ convoyId: 'abc', active: 'yes' }).ok).toBe(false);
    expect(parseSetFollowMeInput({ convoyId: 'abc' }).ok).toBe(false);
  });

  it('rejects an id with path separators and unknown extra keys', () => {
    expect(parseSetFollowMeInput({ convoyId: 'a/b', active: true }).ok).toBe(false);
    expect(parseSetFollowMeInput({ convoyId: 'abc', active: true, extra: 1 }).ok).toBe(false);
  });
});
