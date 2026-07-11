import { describe, expect, it } from 'vitest';
import {
  buildFriendRequestDocument,
  buildFriendshipDocument,
  friendRequestId,
  parseListInput,
  parseRemoveFriendInput,
  parseRespondRequestInput,
  parseSendRequestInput,
  toFriendRequestSummary,
  toFriendSummary,
  toProfileProjection,
} from './friends-core';

describe('friends-core parsing', () => {
  it('accepts a nickname or a toUid, but not both or neither', () => {
    expect(parseSendRequestInput({ nickname: '  Bob  ' })).toEqual({
      ok: true,
      input: { nickname: 'Bob' },
    });
    expect(parseSendRequestInput({ toUid: 'u-1' })).toEqual({ ok: true, input: { toUid: 'u-1' } });
    expect(parseSendRequestInput({ nickname: 'Bob', toUid: 'u-1' }).ok).toBe(false);
    expect(parseSendRequestInput({}).ok).toBe(false);
    expect(parseSendRequestInput(null).ok).toBe(false);
  });

  it('rejects empty/oversized nicknames and unknown fields', () => {
    expect(parseSendRequestInput({ nickname: '   ' }).ok).toBe(false);
    expect(parseSendRequestInput({ nickname: 'x'.repeat(121) }).ok).toBe(false);
    expect(parseSendRequestInput({ nickname: 'Bob', extra: 1 }).ok).toBe(false);
  });

  it('parses respondRequest with a bounded action enum', () => {
    expect(parseRespondRequestInput({ requestId: 'a__b', action: 'accept' }).ok).toBe(true);
    expect(parseRespondRequestInput({ requestId: 'a__b', action: 'decline' }).ok).toBe(true);
    expect(parseRespondRequestInput({ requestId: 'a__b', action: 'block' }).ok).toBe(false);
    expect(parseRespondRequestInput({ requestId: '', action: 'accept' }).ok).toBe(false);
    expect(parseRespondRequestInput({ action: 'accept' }).ok).toBe(false);
  });

  it('parses remove + list inputs strictly', () => {
    expect(parseRemoveFriendInput({ friendUid: '  u-2  ' })).toEqual({
      ok: true,
      input: { friendUid: 'u-2' },
    });
    expect(parseRemoveFriendInput({ friendUid: '' }).ok).toBe(false);
    expect(parseListInput({}).ok).toBe(true);
    expect(parseListInput(undefined).ok).toBe(true);
    expect(parseListInput({ foo: 1 }).ok).toBe(false);
  });
});

describe('friends-core id + projection', () => {
  it('derives a deterministic, directional request id (64-hex, collision-resistant)', () => {
    // Deterministic: same ordered pair → same id.
    expect(friendRequestId('a', 'b')).toBe(friendRequestId('a', 'b'));
    // Length-prefixed SHA-256 → 64 lowercase hex chars.
    expect(friendRequestId('a', 'b')).toMatch(/^[0-9a-f]{64}$/);
    // Directional: A→B is distinct from B→A.
    expect(friendRequestId('a', 'b')).not.toBe(friendRequestId('b', 'a'));
    // Collision-resistant across the historical `__` separator: a naive
    // `${fromUid}__${toUid}` join maps both of these to 'a__b__c'; the
    // length-prefixed hash keeps them distinct.
    expect(friendRequestId('a', 'b__c')).not.toBe(friendRequestId('a__b', 'c'));
  });

  it('projects a profile, coalescing missing/non-string fields to null', () => {
    expect(toProfileProjection({ displayName: 'Bob', avatarPath: 'profileImages/b/x.jpg' })).toEqual({
      displayName: 'Bob',
      avatarPath: 'profileImages/b/x.jpg',
    });
    expect(toProfileProjection({})).toEqual({ displayName: null, avatarPath: null });
    expect(toProfileProjection(undefined)).toEqual({ displayName: null, avatarPath: null });
    expect(toProfileProjection({ displayName: 42 })).toEqual({ displayName: null, avatarPath: null });
  });
});

describe('friends-core builders', () => {
  it('builds a pending request doc with both parties denormalized', () => {
    const doc = buildFriendRequestDocument(
      'a',
      'b',
      { displayName: 'Alice', avatarPath: null },
      { displayName: 'Bob', avatarPath: 'profileImages/b/x.jpg' },
      () => 'TS',
    );
    expect(doc).toEqual({
      fromUid: 'a',
      toUid: 'b',
      status: 'pending',
      fromDisplayName: 'Alice',
      fromAvatarPath: null,
      toDisplayName: 'Bob',
      toAvatarPath: 'profileImages/b/x.jpg',
      createdAt: 'TS',
      updatedAt: 'TS',
    });
  });

  it('builds a friendship doc', () => {
    expect(
      buildFriendshipDocument('b', { displayName: 'Bob', avatarPath: null }, () => 'TS'),
    ).toEqual({ friendUid: 'b', displayName: 'Bob', avatarPath: null, createdAt: 'TS' });
  });
});

describe('friends-core summaries', () => {
  it('maps a friendship into a summary', () => {
    expect(
      toFriendSummary('b', { displayName: 'Bob', avatarPath: 'p' }, '2026-07-11T00:00:00.000Z'),
    ).toEqual({ uid: 'b', displayName: 'Bob', avatarPath: 'p', friendsSince: '2026-07-11T00:00:00.000Z' });
    expect(toFriendSummary('b', undefined, '2026-07-11T00:00:00.000Z').displayName).toBeNull();
  });

  it('projects the OTHER party for an incoming request (caller is toUid)', () => {
    const summary = toFriendRequestSummary(
      'a__me',
      { fromUid: 'a', toUid: 'me', fromDisplayName: 'Alice', fromAvatarPath: 'pa', toDisplayName: 'Me', toAvatarPath: 'pm' },
      'me',
      '2026-07-11T00:00:00.000Z',
    );
    expect(summary).toEqual({
      requestId: 'a__me',
      fromUid: 'a',
      toUid: 'me',
      direction: 'incoming',
      otherUser: { uid: 'a', displayName: 'Alice', avatarPath: 'pa' },
      createdAt: '2026-07-11T00:00:00.000Z',
    });
  });

  it('projects the OTHER party for an outgoing request (caller is fromUid)', () => {
    const summary = toFriendRequestSummary(
      'me__b',
      { fromUid: 'me', toUid: 'b', fromDisplayName: 'Me', fromAvatarPath: 'pm', toDisplayName: 'Bob', toAvatarPath: 'pb' },
      'me',
      '2026-07-11T00:00:00.000Z',
    );
    expect(summary.direction).toBe('outgoing');
    expect(summary.otherUser).toEqual({ uid: 'b', displayName: 'Bob', avatarPath: 'pb' });
  });
});
