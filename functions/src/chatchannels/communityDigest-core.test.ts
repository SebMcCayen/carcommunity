/**
 * Unit tests for the pure community-chat digest decision logic
 * (communityDigest-core.ts). No emulator — every branch is exercised in isolation.
 */

import { describe, expect, it } from 'vitest';
import {
  COMMUNITY_DIGEST_MIN_UNREAD,
  COMMUNITY_DIGEST_TITLE,
  communityDigestNotificationId,
  communityDigestPreview,
  decideMemberDigest,
  digestBaseline,
  hasNewSinceBaseline,
  type MemberDigestInputs,
} from './communityDigest-core';

// Fixed instants (ms) for readable cases.
const T0 = 1_000; // oldest
const T1 = 2_000;
const T2 = 3_000;
const T3 = 4_000; // newest

const THRESHOLD = COMMUNITY_DIGEST_MIN_UNREAD;

function inputs(over: Partial<MemberDigestInputs>): MemberDigestInputs {
  return {
    latestMessageAtMs: T3,
    lastReadAtMs: T0,
    digestedUpToMs: null,
    unreadCount: THRESHOLD,
    threshold: THRESHOLD,
    ...over,
  };
}

describe('digestBaseline', () => {
  it('returns null only when both markers are null', () => {
    expect(digestBaseline(null, null)).toBeNull();
  });

  it('falls back to whichever marker is present', () => {
    expect(digestBaseline(T1, null)).toBe(T1);
    expect(digestBaseline(null, T2)).toBe(T2);
  });

  it('takes the LATER of the two markers', () => {
    expect(digestBaseline(T1, T2)).toBe(T2);
    expect(digestBaseline(T2, T1)).toBe(T2);
    expect(digestBaseline(T2, T2)).toBe(T2);
  });
});

describe('hasNewSinceBaseline', () => {
  it('is false for an empty channel (null latest)', () => {
    expect(hasNewSinceBaseline(null, T0)).toBe(false);
    expect(hasNewSinceBaseline(null, null)).toBe(false);
  });

  it('is true when the member has never read/been digested', () => {
    expect(hasNewSinceBaseline(T3, null)).toBe(true);
  });

  it('is true only when the newest message is STRICTLY after the baseline', () => {
    expect(hasNewSinceBaseline(T3, T0)).toBe(true);
    expect(hasNewSinceBaseline(T3, T3)).toBe(false); // exactly caught up
    expect(hasNewSinceBaseline(T2, T3)).toBe(false); // baseline ahead of newest
  });
});

describe('decideMemberDigest', () => {
  it('notifies when unread meets the threshold', () => {
    const d = decideMemberDigest(inputs({ unreadCount: THRESHOLD }));
    expect(d).toEqual({ notify: true, unreadCount: THRESHOLD });
  });

  it('notifies above the threshold and echoes the count', () => {
    const d = decideMemberDigest(inputs({ unreadCount: THRESHOLD + 5 }));
    expect(d).toEqual({ notify: true, unreadCount: THRESHOLD + 5 });
  });

  it('does NOT notify below the threshold (accumulate silently)', () => {
    const d = decideMemberDigest(inputs({ unreadCount: THRESHOLD - 1 }));
    expect(d).toEqual({ notify: false, reason: 'below_threshold' });
  });

  it('reports caught_up when the channel is empty', () => {
    const d = decideMemberDigest(inputs({ latestMessageAtMs: null }));
    expect(d).toEqual({ notify: false, reason: 'caught_up' });
  });

  it('reports caught_up when the member read up TO the newest message', () => {
    const d = decideMemberDigest(inputs({ lastReadAtMs: T3, unreadCount: 0 }));
    expect(d).toEqual({ notify: false, reason: 'caught_up' });
  });

  it('reports caught_up when the member read PAST the newest message', () => {
    const d = decideMemberDigest(
      inputs({ lastReadAtMs: T3 + 1, latestMessageAtMs: T3, unreadCount: 0 }),
    );
    expect(d).toEqual({ notify: false, reason: 'caught_up' });
  });

  it('reports already_digested when a prior digest covered the newest message', () => {
    // Behind on last-read, but digestedUpTo already reached the newest instant —
    // the primary no-double-notify guard.
    const d = decideMemberDigest(
      inputs({ lastReadAtMs: T0, digestedUpToMs: T3, unreadCount: 0 }),
    );
    expect(d).toEqual({ notify: false, reason: 'already_digested' });
  });

  it('re-notifies once NEW messages arrive after a prior digest', () => {
    // digestedUpTo was T2; newest is now T3 with >= threshold messages after T2.
    const d = decideMemberDigest(
      inputs({ lastReadAtMs: T0, digestedUpToMs: T2, latestMessageAtMs: T3, unreadCount: THRESHOLD }),
    );
    expect(d).toEqual({ notify: true, unreadCount: THRESHOLD });
  });

  it('uses the LATER of lastRead/digestedUpTo as the baseline for the gate', () => {
    // lastRead behind but digestedUpTo == newest → gated as already_digested even
    // though lastReadAt alone would look behind.
    const d = decideMemberDigest(
      inputs({ lastReadAtMs: T1, digestedUpToMs: T3, latestMessageAtMs: T3, unreadCount: 99 }),
    );
    expect(d).toEqual({ notify: false, reason: 'already_digested' });
  });

  it('a never-read, never-digested member with enough unread is notified', () => {
    const d = decideMemberDigest(
      inputs({ lastReadAtMs: null, digestedUpToMs: null, unreadCount: THRESHOLD }),
    );
    expect(d).toEqual({ notify: true, unreadCount: THRESHOLD });
  });

  it('respects a caller-supplied threshold', () => {
    expect(decideMemberDigest(inputs({ threshold: 10, unreadCount: 9 }))).toEqual({
      notify: false,
      reason: 'below_threshold',
    });
    expect(decideMemberDigest(inputs({ threshold: 10, unreadCount: 10 }))).toEqual({
      notify: true,
      unreadCount: 10,
    });
  });
});

describe('communityDigestNotificationId', () => {
  it('is stable within a UTC day and changes across days', () => {
    const morning = new Date('2026-07-20T06:00:00.000Z');
    const evening = new Date('2026-07-20T21:30:00.000Z');
    const nextDay = new Date('2026-07-21T06:00:00.000Z');
    expect(communityDigestNotificationId(morning)).toBe('community-digest-2026-07-20');
    expect(communityDigestNotificationId(evening)).toBe(
      communityDigestNotificationId(morning),
    );
    expect(communityDigestNotificationId(nextDay)).not.toBe(
      communityDigestNotificationId(morning),
    );
  });

  it('stays within the notificationId charset', () => {
    const id = communityDigestNotificationId(new Date('2026-01-02T00:00:00.000Z'));
    expect(id).toMatch(/^[A-Za-z0-9._-]+$/);
  });
});

describe('communityDigestPreview', () => {
  it('is singular for exactly one message', () => {
    expect(communityDigestPreview(1)).toBe(
      '1 nytt meddelande i community-chatten sedan du var här senast.',
    );
  });

  it('is plural for more than one', () => {
    expect(communityDigestPreview(7)).toBe(
      '7 nya meddelanden i community-chatten sedan du var här senast.',
    );
  });
});

describe('constants', () => {
  it('holds the documented minimum-unread threshold', () => {
    expect(COMMUNITY_DIGEST_MIN_UNREAD).toBe(3);
  });

  it('exposes the localized title', () => {
    expect(COMMUNITY_DIGEST_TITLE).toBe('Nytt i community-chatten');
  });
});
