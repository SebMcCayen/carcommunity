import { describe, expect, it } from 'vitest';
import {
  buildFriendRequestDocument,
  buildFriendshipDocument,
  friendRequestId,
  parseListInput,
  parseRemoveFriendInput,
  parseRespondRequestInput,
  parseSendRequestInput,
  prefixUpperBound,
  toFriendRequestSummary,
  toFriendSummary,
  toProfileProjection,
  toSearchKey,
} from './friends-core';

/**
 * Firestore orders strings by their UTF-8 BYTES. These tests assert
 * prefixUpperBound against that same ordering rather than JS's UTF-16 code-unit
 * ordering (`<`), because UTF-16 ordering disagrees for astral characters and
 * would let the emoji regression below pass while prod still failed.
 */
const utf8 = (value: string) => Buffer.from(value, 'utf8');
const sortsBelow = (a: string, b: string) => Buffer.compare(utf8(a), utf8(b)) < 0;
/** True when `value` falls inside the half-open prefix range [key, bound). */
const inPrefixRange = (value: string, key: string) =>
  !sortsBelow(value, key) && sortsBelow(value, prefixUpperBound(key));

describe('toSearchKey', () => {
  it('folds case and trims, matching the stored displayNameLower key', () => {
    expect(toSearchKey('Gt86_swe')).toBe('gt86_swe');
    expect(toSearchKey('GT86_SWE')).toBe('gt86_swe');
    expect(toSearchKey('  Gt86_swe  ')).toBe('gt86_swe');
  });

  it('folds non-ASCII the same way regardless of the server locale', () => {
    expect(toSearchKey('ÅKE')).toBe('åke');
    // The Turkish trap: a locale-SENSITIVE fold maps 'I' to 'ı' (dotless i),
    // which would desync the stored key from the query key for every Turkish
    // -locale user. String.prototype.toLowerCase() is locale-invariant by spec,
    // so 'I' must always fold to ASCII 'i'.
    expect(toSearchKey('ISTANBUL')).toBe('istanbul');
    expect(toSearchKey('ISTANBUL').charCodeAt(0)).toBe('i'.charCodeAt(0));
  });
});

describe('prefixUpperBound', () => {
  it('bounds a plain ASCII prefix so every continuation is inside the range', () => {
    expect(inPrefixRange('gt86', 'gt86')).toBe(true);
    expect(inPrefixRange('gt86_swe', 'gt86')).toBe(true);
    expect(inPrefixRange('gt86zzzz', 'gt86')).toBe(true);
    // Just outside: a different prefix must not be swept in.
    expect(inPrefixRange('gt87', 'gt86')).toBe(false);
    expect(inPrefixRange('gt85', 'gt86')).toBe(false);
    expect(inPrefixRange('gt8', 'gt86')).toBe(false);
  });

  it('includes a name continuing with an ASTRAL character (the sentinel trap)', () => {
    // A '￿' sentinel bound encodes to 3 UTF-8 bytes (EF BF BF); an emoji
    // encodes to 4 (F0 9F 98 80) and therefore sorts ABOVE it. Appending a
    // sentinel would silently exclude this name from its own prefix.
    expect(inPrefixRange('gt86😀', 'gt86')).toBe(true);
    expect(sortsBelow('gt86￿', 'gt86😀')).toBe(true);
  });

  it('bounds a prefix that itself ends in an astral character', () => {
    expect(inPrefixRange('😀', '😀')).toBe(true);
    expect(inPrefixRange('😀abc', '😀')).toBe(true);
    expect(inPrefixRange('😁', '😀')).toBe(false);
  });

  it('never emits a lone surrogate when the increment lands in the surrogate block', () => {
    const bound = prefixUpperBound('퟿');
    expect(bound).toBe('');
    // A lone surrogate is not a valid scalar value and would corrupt the query.
    expect(bound.codePointAt(0)).toBeGreaterThan(0xdfff);
    expect(inPrefixRange('퟿x', '퟿')).toBe(true);
  });
});

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
