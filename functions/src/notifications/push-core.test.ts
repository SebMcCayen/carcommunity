import { describe, expect, it } from 'vitest';
import {
  FCM_MULTICAST_LIMIT,
  MAX_PUSH_TOKENS_PER_USER,
  NOTIFICATION_CATEGORIES,
  SOCIAL_NOTIFICATION_CATEGORIES,
  buildPushDeepLink,
  buildPushPayload,
  buildPushTokenDocument,
  chunkTokens,
  decideInAppDelivery,
  decidePushDelivery,
  isDeadTokenError,
  pushPreviewsEnabled,
  selectEvictableTokenIds,
  type NotificationCategory,
} from './notifications-core';
import type { UserAccessState } from '../shared/access';

const active: UserAccessState = {
  role: 'member',
  activeMember: true,
  suspended: false,
  deleted: false,
};
const suspended: UserAccessState = { ...active, suspended: true };
const deleted: UserAccessState = { ...active, deleted: true };

describe('decidePushDelivery — inherits the in-app decision', () => {
  // The load-bearing property: push is a strict SUBSET of in-app. If this
  // holds for every category and every state, a member who silenced a category
  // in-app cannot be reached by push through some parallel path.
  it('never delivers push where decideInAppDelivery refuses in-app', () => {
    const preferenceSets: unknown[] = [
      undefined,
      {},
      Object.fromEntries(NOTIFICATION_CATEGORIES.map((c) => [c, { inApp: false }])),
      Object.fromEntries(NOTIFICATION_CATEGORIES.map((c) => [c, { push: false }])),
      Object.fromEntries(
        NOTIFICATION_CATEGORIES.map((c) => [c, { inApp: false, push: true }]),
      ),
    ];
    for (const state of [active, suspended, deleted]) {
      for (const preferences of preferenceSets) {
        for (const category of NOTIFICATION_CATEGORIES) {
          const inApp = decideInAppDelivery(category, state, preferences);
          const push = decidePushDelivery(category, state, preferences);
          if (!inApp.deliver) {
            expect(push.deliver, `${category} must not push when in-app refused`).toBe(false);
          }
        }
      }
    }
  });

  it('propagates the in-app refusal reason verbatim', () => {
    expect(decidePushDelivery('direct_message', deleted, undefined)).toEqual({
      deliver: false,
      reason: 'deleted',
    });
    expect(decidePushDelivery('direct_message', suspended, undefined)).toEqual({
      deliver: false,
      reason: 'suspended',
    });
    expect(
      decidePushDelivery('convoy_chat', active, { convoy_chat: { inApp: false } }),
    ).toEqual({ deliver: false, reason: 'opted_out' });
  });

  it('honours a push-only opt-out while in-app still delivers', () => {
    const preferences = { convoy_chat: { inApp: true, push: false } };
    expect(decideInAppDelivery('convoy_chat', active, preferences).deliver).toBe(true);
    expect(decidePushDelivery('convoy_chat', active, preferences)).toEqual({
      deliver: false,
      reason: 'push_opted_out',
    });
  });

  it('delivers by default for every social category', () => {
    for (const category of SOCIAL_NOTIFICATION_CATEGORIES) {
      expect(decidePushDelivery(category, active, undefined).deliver).toBe(true);
    }
  });

  it('essential account notices ignore suspension and both opt-outs', () => {
    for (const category of ['account_warning', 'account_suspension'] as NotificationCategory[]) {
      expect(
        decidePushDelivery(category, suspended, { [category]: { inApp: false, push: false } })
          .deliver,
      ).toBe(true);
    }
  });

  it('a deleted recipient gets nothing, essential included', () => {
    expect(decidePushDelivery('account_suspension', deleted, undefined).deliver).toBe(false);
  });
});

describe('pushPreviewsEnabled', () => {
  it('defaults to true when unset', () => {
    expect(pushPreviewsEnabled(undefined)).toBe(true);
    expect(pushPreviewsEnabled({})).toBe(true);
    expect(pushPreviewsEnabled({ pushPreviews: true })).toBe(true);
  });

  it('is false only on an explicit false', () => {
    expect(pushPreviewsEnabled({ pushPreviews: false })).toBe(false);
  });

  it('is surfaced on the delivery decision', () => {
    const decision = decidePushDelivery('direct_message', active, { pushPreviews: false });
    expect(decision).toEqual({ deliver: true, includePreview: false });
  });
});

describe('buildPushDeepLink', () => {
  it('resolves a DM to the OTHER member of the pairId', () => {
    expect(buildPushDeepLink('direct_message', 'aaa__bbb', 'aaa')).toEqual({
      target: 'dm',
      entityId: 'bbb',
    });
    expect(buildPushDeepLink('direct_message', 'aaa__bbb', 'bbb')).toEqual({
      target: 'dm',
      entityId: 'aaa',
    });
  });

  it('falls back to the conversation list when the counterpart is unresolvable', () => {
    expect(buildPushDeepLink('direct_message', null, 'aaa')).toEqual({
      target: 'dm',
      entityId: null,
    });
    // Self-pair: nothing left after removing the recipient.
    expect(buildPushDeepLink('direct_message', 'aaa__aaa', 'aaa')).toEqual({
      target: 'dm',
      entityId: null,
    });
  });

  it('maps convoy chat to the convoy id', () => {
    expect(buildPushDeepLink('convoy_chat', 'convoy-1', 'me')).toEqual({
      target: 'convoy_chat',
      entityId: 'convoy-1',
    });
  });

  it('drops the message id for community chat (no per-message anchor)', () => {
    expect(buildPushDeepLink('community_chat', 'msg-9', 'me')).toEqual({
      target: 'community_chat',
      entityId: null,
    });
  });

  it('maps friend requests and convoy invites to their screens', () => {
    expect(buildPushDeepLink('friend_request', 'requester-uid', 'me')).toEqual({
      target: 'friends',
      entityId: 'requester-uid',
    });
    expect(buildPushDeepLink('convoy_invite', 'invite-1', 'me')).toEqual({
      target: 'convoys',
      entityId: null,
    });
  });

  it('gives every category a target', () => {
    for (const category of NOTIFICATION_CATEGORIES) {
      expect(buildPushDeepLink(category, 'x', 'me').target).toBeTruthy();
    }
  });
});

describe('buildPushPayload', () => {
  const base = {
    category: 'direct_message' as NotificationCategory,
    title: 'Nytt meddelande',
    previewText: 'Hej, ses vi på lördag?',
    notificationId: 'n1',
    relatedEntityId: 'me__them',
    recipientUid: 'me',
  };

  it('includes the preview when allowed', () => {
    expect(buildPushPayload({ ...base, includePreview: true })).toEqual({
      category: 'direct_message',
      title: 'Nytt meddelande',
      notificationId: 'n1',
      target: 'dm',
      entityId: 'them',
      previewText: 'Hej, ses vi på lördag?',
    });
  });

  it('omits the preview entirely when previews are off — no lock-screen leak', () => {
    const payload = buildPushPayload({ ...base, includePreview: false });
    expect(payload.previewText).toBeUndefined();
    expect(Object.values(payload)).not.toContain('Hej, ses vi på lördag?');
    // The title still routes the member to the right place.
    expect(payload.title).toBe('Nytt meddelande');
    expect(payload.target).toBe('dm');
  });

  it('emits string-only values (FCM data maps cannot hold anything else)', () => {
    const payload = buildPushPayload({ ...base, includePreview: true });
    for (const value of Object.values(payload)) {
      expect(typeof value).toBe('string');
    }
  });

  it('truncates to the notification content limits', () => {
    const payload = buildPushPayload({
      ...base,
      title: 'a'.repeat(500),
      previewText: 'b'.repeat(500),
      includePreview: true,
    });
    expect(payload.title).toHaveLength(100);
    expect(payload.previewText).toHaveLength(200);
  });
});

describe('token registry + send batching', () => {
  it('stores the raw token — a hash-only row cannot be sent to', () => {
    const doc = buildPushTokenDocument(
      { token: 'raw-fcm-token', platform: 'android' },
      () => 'TS',
    );
    expect(doc.token).toBe('raw-fcm-token');
    expect(doc.platform).toBe('android');
  });

  it('chunks at the FCM multicast limit', () => {
    const tokens = Array.from({ length: 1001 }, (_, i) => i);
    const chunks = chunkTokens(tokens);
    expect(chunks).toHaveLength(3);
    expect(chunks[0]).toHaveLength(FCM_MULTICAST_LIMIT);
    expect(chunks[2]).toHaveLength(1);
    expect(chunks.flat()).toEqual(tokens);
  });

  it('chunks an empty and an exact-multiple list cleanly', () => {
    expect(chunkTokens([])).toEqual([]);
    expect(chunkTokens([1, 2, 3, 4], 2)).toEqual([
      [1, 2],
      [3, 4],
    ]);
  });

  it('treats only permanent FCM errors as dead tokens', () => {
    expect(isDeadTokenError('messaging/registration-token-not-registered')).toBe(true);
    expect(isDeadTokenError('messaging/invalid-registration-token')).toBe(true);
    expect(isDeadTokenError('messaging/invalid-argument')).toBe(true);
    // Transient — pruning on these would delete live registrations.
    expect(isDeadTokenError('messaging/server-unavailable')).toBe(false);
    expect(isDeadTokenError('messaging/internal-error')).toBe(false);
    expect(isDeadTokenError('messaging/quota-exceeded')).toBe(false);
    expect(isDeadTokenError(undefined)).toBe(false);
  });
});

describe('selectEvictableTokenIds — the per-user registration cap', () => {
  const candidate = (tokenId: string, lastSeenAtMs: number | null) => ({
    tokenId,
    lastSeenAtMs,
  });

  it('evicts nothing while the new token still fits under the cap', () => {
    const existing = Array.from({ length: MAX_PUSH_TOKENS_PER_USER - 1 }, (_, i) =>
      candidate(`t${i}`, i),
    );
    expect(selectEvictableTokenIds(existing)).toEqual([]);
  });

  it('evicts exactly one when the new token would be one over', () => {
    const existing = Array.from({ length: MAX_PUSH_TOKENS_PER_USER }, (_, i) =>
      candidate(`t${i}`, 1000 + i),
    );
    // t0 is the least-recently-seen.
    expect(selectEvictableTokenIds(existing)).toEqual(['t0']);
  });

  it('evicts least-recently-seen first, not insertion order', () => {
    const existing = [
      candidate('newest', 900),
      candidate('oldest', 100),
      candidate('middle', 500),
    ];
    expect(selectEvictableTokenIds(existing, 2)).toEqual(['oldest', 'middle']);
  });

  it('evicts legacy rows with no lastSeenAt before any timestamped row', () => {
    const existing = [
      candidate('stamped-old', 1),
      candidate('legacy-a', null),
      candidate('legacy-b', null),
    ];
    // Both legacy rows sort ahead of the oldest timestamped one. They are also
    // the hash-only unsendable rows, so this is the right eviction order.
    expect(selectEvictableTokenIds(existing, 2)).toEqual(['legacy-a', 'legacy-b']);
  });

  it('caps an unbounded registry back to the limit in a single pass', () => {
    // The abuse case: a client that hammered registerPushToken with fabricated
    // tokens. One further registration must bring the collection to the cap,
    // not merely trim one row.
    const existing = Array.from({ length: 5000 }, (_, i) => candidate(`t${i}`, i));
    const evict = selectEvictableTokenIds(existing);
    expect(evict).toHaveLength(5000 + 1 - MAX_PUSH_TOKENS_PER_USER);
    expect(existing.length - evict.length + 1).toBe(MAX_PUSH_TOKENS_PER_USER);
    // Least-recently-seen went first; the freshest survive.
    expect(evict).toContain('t0');
    expect(evict).not.toContain('t4999');
  });

  it('bounds the send fan-out and the prune batch by construction', () => {
    // WHY THIS TEST EXISTS: Copilot flagged the prune path as able to exceed a
    // 500-op Firestore batch. Firestore no longer imposes a per-commit write
    // count limit (only a 10 MiB request size), so that specific failure was
    // not real — but the underlying worry, an unbounded prune, is answered
    // here: the cap means loadTokens can never return more than the cap, so
    // both the FCM multicast and the delete batch are bounded well under any
    // limit that does exist.
    expect(MAX_PUSH_TOKENS_PER_USER).toBeLessThan(FCM_MULTICAST_LIMIT);
    const atCap = Array.from({ length: MAX_PUSH_TOKENS_PER_USER }, (_, i) => i);
    expect(chunkTokens(atCap)).toHaveLength(1);
  });

  it('rejects a nonsensical limit rather than evicting everything', () => {
    expect(() => selectEvictableTokenIds([], 0)).toThrow(/limit must be >= 1/);
  });
});

describe('buildPushDeepLink — direct_message pairId parsing is strict', () => {
  const me = 'uidRecipient';

  it('resolves the counterpart from a well-formed pairId, either side', () => {
    expect(buildPushDeepLink('direct_message', `${me}__uidOther`, me)).toEqual({
      target: 'dm',
      entityId: 'uidOther',
    });
    expect(buildPushDeepLink('direct_message', `uidOther__${me}`, me)).toEqual({
      target: 'dm',
      entityId: 'uidOther',
    });
  });

  it('falls back to the conversation list when the recipient is not in the pair', () => {
    // A loose "first segment that isn't me" search would return 'uidA' here and
    // deep-link the member into a thread that is not theirs.
    expect(buildPushDeepLink('direct_message', 'uidA__uidB', me)).toEqual({
      target: 'dm',
      entityId: null,
    });
  });

  it('falls back when the id does not split into exactly two parts', () => {
    for (const malformed of [
      me, // no separator at all
      `${me}__uidOther__uidThird`, // three parts
      `${me}____uidOther`, // empty middle segment
      '',
    ]) {
      expect(buildPushDeepLink('direct_message', malformed, me)).toEqual({
        target: 'dm',
        entityId: null,
      });
    }
  });

  it('falls back for a self-pair rather than opening a thread with yourself', () => {
    expect(buildPushDeepLink('direct_message', `${me}__${me}`, me)).toEqual({
      target: 'dm',
      entityId: null,
    });
  });

  it('handles a missing relatedEntityId', () => {
    expect(buildPushDeepLink('direct_message', null, me)).toEqual({
      target: 'dm',
      entityId: null,
    });
    expect(buildPushDeepLink('direct_message', undefined, me)).toEqual({
      target: 'dm',
      entityId: null,
    });
  });
});
