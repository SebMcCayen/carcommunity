import { describe, expect, it } from 'vitest';
import {
  buildFriendRequestDocument,
  buildFriendshipDocument,
  friendRequestId,
  hydrateFriendRequestSummary,
  hydrateFriendSummary,
  isMissingIndexError,
  parseCancelRequestInput,
  parseListInput,
  parseRemoveFriendInput,
  parseRespondRequestInput,
  parseSendRequestInput,
  prefixUpperBound,
  profileUidsToHydrate,
  toFriendRequestSummary,
  toFriendSummary,
  toProfileProjection,
  toSearchKey,
  type FriendRequestSummary,
  type FriendSummary,
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

  it('parses the cancel input strictly, by RECIPIENT and nothing else', () => {
    expect(parseCancelRequestInput({ toUid: '  u-3  ' })).toEqual({
      ok: true,
      input: { toUid: 'u-3' },
    });
    expect(parseCancelRequestInput({ toUid: '' }).ok).toBe(false);
    expect(parseCancelRequestInput({}).ok).toBe(false);
    expect(parseCancelRequestInput(undefined).ok).toBe(false);
    // .strict(): a requestId-shaped payload (the shape the OTHER friend
    // callables take) must be REJECTED, not silently ignored — accepting it
    // would let a caller believe they had cancelled a request they named.
    expect(parseCancelRequestInput({ requestId: 'abc' }).ok).toBe(false);
    expect(parseCancelRequestInput({ toUid: 'u-3', requestId: 'abc' }).ok).toBe(false);
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

/**
 * REGRESSION (2026-07-27): the friends LIST showed no picture for friends whose
 * avatar was set or changed AFTER the friendship was established, while the
 * SAME member's profile screen showed it — because the list served the
 * displayName/avatarPath copied onto users/{uid}/friends/{friendUid} at accept
 * time and never rewritten, whereas the profile screen reads live users/{uid}.
 */
describe('live profile hydration', () => {
  const friend = (uid: string, avatarPath: string | null, displayName: string | null = 'stored'): FriendSummary => ({
    uid,
    displayName,
    avatarPath,
    friendsSince: '2026-07-11T00:00:00.000Z',
  });
  const request = (otherUid: string, avatarPath: string | null): FriendRequestSummary => ({
    requestId: 'r',
    fromUid: otherUid,
    toUid: 'me',
    direction: 'incoming',
    otherUser: { uid: otherUid, displayName: 'stored', avatarPath },
    createdAt: '2026-07-11T00:00:00.000Z',
  });

  it('collects every named member exactly once, across friends AND requests', () => {
    const uids = profileUidsToHydrate(
      [friend('a', null), friend('b', null)],
      [request('b', null), request('c', null)],
    );
    // 'b' is both a friend and a request counterparty — paying for the same
    // user document twice would be pure waste.
    expect(uids.sort()).toEqual(['a', 'b', 'c']);
  });

  it('has nothing to read for an empty graph', () => {
    expect(profileUidsToHydrate([], [])).toEqual([]);
  });

  it('fills in an avatar the stored friendship copy never had (the reported bug)', () => {
    const live = new Map([['a', { displayName: 'Alice', avatarPath: 'profileImages/a/new.jpg' }]]);
    expect(hydrateFriendSummary(friend('a', null), live)).toEqual({
      uid: 'a',
      displayName: 'Alice',
      avatarPath: 'profileImages/a/new.jpg',
      friendsSince: '2026-07-11T00:00:00.000Z',
    });
  });

  it('replaces a stale avatar and a stale name with the current ones', () => {
    const live = new Map([['a', { displayName: 'NewName', avatarPath: 'profileImages/a/new.jpg' }]]);
    const hydrated = hydrateFriendSummary(friend('a', 'profileImages/a/old.jpg', 'OldName'), live);
    expect(hydrated.avatarPath).toBe('profileImages/a/new.jpg');
    expect(hydrated.displayName).toBe('NewName');
  });

  it('lets a live NULL win, so a removed avatar actually disappears', () => {
    // The stored copy must not be treated as a fallback-fill here: a member who
    // deleted their picture would otherwise keep showing it to every friend
    // they made before deleting it.
    const live = new Map([['a', { displayName: null, avatarPath: null }]]);
    const hydrated = hydrateFriendSummary(friend('a', 'profileImages/a/old.jpg', 'OldName'), live);
    expect(hydrated.avatarPath).toBeNull();
    expect(hydrated.displayName).toBeNull();
  });

  it('keeps the stored copy when there is no live profile at all', () => {
    // Deleted account, or a batched read that failed: the last known name and
    // picture beat an anonymous row.
    const stored = friend('a', 'profileImages/a/old.jpg', 'OldName');
    expect(hydrateFriendSummary(stored, new Map())).toEqual(stored);
    const storedRequest = request('a', 'profileImages/a/old.jpg');
    expect(hydrateFriendRequestSummary(storedRequest, new Map())).toEqual(storedRequest);
  });

  it('hydrates the OTHER party of a pending request without touching the rest', () => {
    const live = new Map([['a', { displayName: 'Alice', avatarPath: 'profileImages/a/new.jpg' }]]);
    expect(hydrateFriendRequestSummary(request('a', null), live)).toEqual({
      requestId: 'r',
      fromUid: 'a',
      toUid: 'me',
      direction: 'incoming',
      otherUser: { uid: 'a', displayName: 'Alice', avatarPath: 'profileImages/a/new.jpg' },
      createdAt: '2026-07-11T00:00:00.000Z',
    });
  });

  it('never hands one member the profile of another', () => {
    const live = new Map([['b', { displayName: 'Bob', avatarPath: 'profileImages/b/x.jpg' }]]);
    const hydrated = hydrateFriendSummary(friend('a', null, 'Alice'), live);
    expect(hydrated.displayName).toBe('Alice');
    expect(hydrated.avatarPath).toBeNull();
  });
});

/**
 * REGRESSION (2026-07-19): production `friend.list` failed for every caller
 * because the friendRequests composite indexes had never been deployed. The
 * Firestore rejection escaped the callable as an opaque INTERNAL, so the app
 * showed a generic "couldn't load your friends" on both the Friends page and
 * the convoy invite picker and the deployment fault looked like an app bug.
 *
 * The exact shape below — a plain Error carrying a NUMERIC gRPC `code` 9 and
 * the "The query requires an index." message — is copied from the real
 * production log line for `friend-list`.
 */
describe('isMissingIndexError', () => {
  const missingIndex = () =>
    Object.assign(
      new Error(
        '9 FAILED_PRECONDITION: The query requires an index. You can create it here: ' +
          'https://console.firebase.google.com/v1/r/project/example/firestore/indexes?create_composite=abc',
      ),
      { code: 9 },
    );

  it('recognises the production missing-index rejection', () => {
    expect(isMissingIndexError(missingIndex())).toBe(true);
  });

  it('is case-insensitive about the message', () => {
    expect(
      isMissingIndexError(Object.assign(new Error('The Query REQUIRES AN INDEX.'), { code: 9 })),
    ).toBe(true);
  });

  it('does not claim an unrelated failed-precondition', () => {
    // Same gRPC status, different cause: must NOT be reported as a missing
    // index, or a genuine business-rule refusal would be mislabelled as a
    // backend outage and auto-reported as a fault.
    expect(
      isMissingIndexError(Object.assign(new Error('9 FAILED_PRECONDITION: doc changed'), { code: 9 })),
    ).toBe(false);
  });

  it('does not match the right message under a different status code', () => {
    expect(
      isMissingIndexError(Object.assign(new Error('the query requires an index'), { code: 5 })),
    ).toBe(false);
  });

  it('tolerates non-error inputs', () => {
    for (const value of [null, undefined, 'boom', 42, {}]) {
      expect(isMissingIndexError(value)).toBe(false);
    }
  });
});
