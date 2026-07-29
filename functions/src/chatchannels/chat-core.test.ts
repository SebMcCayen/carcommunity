import { describe, expect, it } from 'vitest';
import {
  CHAT_MESSAGE_MAX_LENGTH,
  CHAT_MESSAGE_PREVIEW_LENGTH,
  COMMUNITY_CHAT_RETENTION_DAYS,
  COMMUNITY_MENTION_NOTIFY_WINDOW_MS,
  CONVOY_CHAT_NOTIFY_WINDOW_MS,
  CONVOY_CHAT_RETENTION_DAYS,
  CONVOY_LAST_READ_MAX_ENTRIES,
  MAX_MESSAGE_MENTIONS,
  acceptedConvoyMemberUids,
  buildChatMessageDocument,
  chatMessageExpiry,
  communityMentionNotificationId,
  convoyChatNotificationId,
  isAcceptedConvoyMember,
  isAlreadyExistsError,
  messagePreview,
  normalizeMentionCandidates,
  parseListCommunityInput,
  parseListConvoyInput,
  parseMarkReadCommunityInput,
  parseMarkReadConvoyInput,
  parsePostCommunityInput,
  parsePostConvoyInput,
  pruneConvoyLastRead,
  toChatMessageSummary,
  toProfileProjection,
} from './chat-core';

describe('chat-core parsing', () => {
  it('parses communityChat.post strictly', () => {
    expect(parsePostCommunityInput({ text: '  hi  ' })).toEqual({
      ok: true,
      input: { text: '  hi  ' },
    });
    expect(parsePostCommunityInput({ text: '' }).ok).toBe(false);
    expect(parsePostCommunityInput({ text: 'x'.repeat(CHAT_MESSAGE_MAX_LENGTH + 1) }).ok).toBe(false);
    expect(parsePostCommunityInput({ text: 'hi', extra: 1 }).ok).toBe(false);
    expect(parsePostCommunityInput(null).ok).toBe(false);
  });

  it('parses optional mentionedUids, rejecting junk ids and more than the cap', () => {
    expect(parsePostCommunityInput({ text: 'hi', mentionedUids: [] }).ok).toBe(true);
    expect(parsePostCommunityInput({ text: 'hi', mentionedUids: ['u1', 'u2'] })).toEqual({
      ok: true,
      input: { text: 'hi', mentionedUids: ['u1', 'u2'] },
    });
    // Exactly at the cap is fine; one over is a hard reject (the picker enforces
    // the same limit, so exceeding it is a client bug worth surfacing).
    expect(
      parsePostCommunityInput({
        text: 'hi',
        mentionedUids: Array.from({ length: MAX_MESSAGE_MENTIONS }, (_, i) => `u${i}`),
      }).ok,
    ).toBe(true);
    expect(
      parsePostCommunityInput({
        text: 'hi',
        mentionedUids: Array.from({ length: MAX_MESSAGE_MENTIONS + 1 }, (_, i) => `u${i}`),
      }).ok,
    ).toBe(false);
    // A uid is a document id: path separators and the dot ids are not.
    expect(parsePostCommunityInput({ text: 'hi', mentionedUids: ['bad/uid'] }).ok).toBe(false);
    expect(parsePostCommunityInput({ text: 'hi', mentionedUids: ['..'] }).ok).toBe(false);
    expect(parsePostCommunityInput({ text: 'hi', mentionedUids: [''] }).ok).toBe(false);
    expect(parsePostCommunityInput({ text: 'hi', mentionedUids: [42] }).ok).toBe(false);
    expect(parsePostCommunityInput({ text: 'hi', mentionedUids: 'u1' }).ok).toBe(false);
  });

  it('parses an optional clientId idempotency key, rejecting junk', () => {
    // A valid key round-trips verbatim (no trimming — the alphabet forbids space).
    expect(parsePostCommunityInput({ text: 'hi', clientId: 'Abc-1_2' })).toEqual({
      ok: true,
      input: { text: 'hi', clientId: 'Abc-1_2' },
    });
    expect(parsePostConvoyInput({ convoyId: 'c-1', text: 'hi', clientId: 'Abc-1_2' })).toEqual({
      ok: true,
      input: { convoyId: 'c-1', text: 'hi', clientId: 'Abc-1_2' },
    });
    // Path separators, whitespace, empty, and over-length are all rejected.
    expect(parsePostCommunityInput({ text: 'hi', clientId: 'a/b' }).ok).toBe(false);
    expect(parsePostCommunityInput({ text: 'hi', clientId: ' abc' }).ok).toBe(false);
    expect(parsePostCommunityInput({ text: 'hi', clientId: '' }).ok).toBe(false);
    expect(parsePostCommunityInput({ text: 'hi', clientId: 'x'.repeat(65) }).ok).toBe(false);
    expect(parsePostConvoyInput({ convoyId: 'c-1', text: 'hi', clientId: 'a b' }).ok).toBe(false);
  });

  it('parses communityChat.list with an optional ISO cursor', () => {
    expect(parseListCommunityInput({}).ok).toBe(true);
    expect(parseListCommunityInput(undefined).ok).toBe(true);
    expect(parseListCommunityInput({ before: '2026-07-11T00:00:00.000Z' }).ok).toBe(true);
    expect(parseListCommunityInput({ before: 'not-a-date' }).ok).toBe(false);
    expect(parseListCommunityInput({ extra: 1 }).ok).toBe(false);
  });

  it('parses communityChat.markRead strictly', () => {
    expect(parseMarkReadCommunityInput({}).ok).toBe(true);
    expect(parseMarkReadCommunityInput(undefined).ok).toBe(true);
    expect(parseMarkReadCommunityInput({ foo: 1 }).ok).toBe(false);
  });

  it('parses convoyChat.post strictly', () => {
    expect(parsePostConvoyInput({ convoyId: 'c-1', text: 'hi' }).ok).toBe(true);
    expect(parsePostConvoyInput({ convoyId: '', text: 'hi' }).ok).toBe(false);
    expect(parsePostConvoyInput({ convoyId: 'c-1', text: '' }).ok).toBe(false);
    expect(parsePostConvoyInput({ convoyId: 'bad/id', text: 'hi' }).ok).toBe(false);
    expect(parsePostConvoyInput({ convoyId: '..', text: 'hi' }).ok).toBe(false);
    expect(parsePostConvoyInput({ convoyId: 'c-1', text: 'hi', extra: 1 }).ok).toBe(false);
  });

  it('parses convoyChat.list strictly', () => {
    expect(parseListConvoyInput({ convoyId: 'c-1' }).ok).toBe(true);
    expect(parseListConvoyInput({ convoyId: 'c-1', before: '2026-07-11T00:00:00.000Z' }).ok).toBe(
      true,
    );
    expect(parseListConvoyInput({ convoyId: 'c-1', before: 'nope' }).ok).toBe(false);
    expect(parseListConvoyInput({}).ok).toBe(false);
  });

  it('parses convoyChat.markRead strictly (the convoy id is required and is a doc id)', () => {
    expect(parseMarkReadConvoyInput({ convoyId: 'c-1' })).toEqual({
      ok: true,
      input: { convoyId: 'c-1' },
    });
    // Unlike community's markRead, an EMPTY object is not enough — this marker is
    // per convoy, so a missing id has no sensible default to fall back to.
    expect(parseMarkReadConvoyInput({}).ok).toBe(false);
    expect(parseMarkReadConvoyInput(undefined).ok).toBe(false);
    // The id becomes a MAP KEY in the caller's userPrivate document, so the same
    // document-id constraints as post/list apply.
    expect(parseMarkReadConvoyInput({ convoyId: '' }).ok).toBe(false);
    expect(parseMarkReadConvoyInput({ convoyId: 'bad/id' }).ok).toBe(false);
    expect(parseMarkReadConvoyInput({ convoyId: '..' }).ok).toBe(false);
    expect(parseMarkReadConvoyInput({ convoyId: 'c-1', extra: 1 }).ok).toBe(false);
  });
});

describe('pruneConvoyLastRead', () => {
  /** A stored marker as the admin SDK hands it back: a Timestamp-like object. */
  const stamp = (ms: number) => ({ toMillis: () => ms });

  it('evicts nothing while the map is at or under the cap', () => {
    expect(pruneConvoyLastRead(undefined, 'c-1', 1000, 3)).toEqual([]);
    expect(pruneConvoyLastRead({ 'c-1': stamp(1) }, 'c-1', 1000, 3)).toEqual([]);
    expect(pruneConvoyLastRead({ 'c-1': stamp(1), 'c-2': stamp(2) }, 'c-3', 1000, 3)).toEqual([]);
  });

  it('evicts the OLDEST markers once the map would exceed the cap', () => {
    const existing = {
      old: stamp(100),
      older: stamp(50),
      oldest: stamp(10),
      recent: stamp(900),
    };
    expect(pruneConvoyLastRead(existing, 'fresh', 1000, 3).sort()).toEqual(['older', 'oldest']);
  });

  it('never evicts the convoy just stamped, even when it was the oldest entry', () => {
    const existing = { stale: stamp(1), a: stamp(500), b: stamp(600) };
    // `stale` is re-stamped as the newest, so the eviction must fall on `a`.
    expect(pruneConvoyLastRead(existing, 'stale', 1000, 2)).toEqual(['a']);
  });

  it('treats malformed entries as the oldest possible, so they are evicted first', () => {
    const existing = { junk: 'not-a-timestamp', alsoJunk: null, good: stamp(5) };
    expect(pruneConvoyLastRead(existing, 'fresh', 1000, 2)).toEqual(['alsoJunk', 'junk']);
  });

  it('prunes nothing when the stored field is not a map (nothing to evict)', () => {
    expect(pruneConvoyLastRead('nonsense', 'c-1', 1000, 1)).toEqual([]);
    expect(pruneConvoyLastRead([stamp(1), stamp(2)], 'c-1', 1000, 1)).toEqual([]);
    expect(pruneConvoyLastRead(null, 'c-1', 1000, 1)).toEqual([]);
  });

  it('defaults to the shipped cap', () => {
    const existing = Object.fromEntries(
      Array.from({ length: CONVOY_LAST_READ_MAX_ENTRIES }, (_, i) => [`c${i}`, stamp(i)]),
    );
    // One over the cap: exactly the single oldest marker goes.
    expect(pruneConvoyLastRead(existing, 'fresh', 10_000)).toEqual(['c0']);
  });
});

describe('chat-core projections + builders', () => {
  it('projects a profile, coalescing missing/non-string to null', () => {
    expect(toProfileProjection({ displayName: 'Bob', avatarPath: 'p' })).toEqual({
      displayName: 'Bob',
      avatarPath: 'p',
    });
    expect(toProfileProjection(undefined)).toEqual({ displayName: null, avatarPath: null });
    expect(toProfileProjection({ displayName: 42 })).toEqual({ displayName: null, avatarPath: null });
  });

  it('builds a message doc (trimmed) with denormalized sender profile + expireAt', () => {
    expect(
      buildChatMessageDocument(
        {
          senderUid: 'a',
          text: '  hi  ',
          senderProfile: { displayName: 'Al', avatarPath: 'pa' },
          expireAt: 'EXP',
        },
        () => 'TS',
      ),
    ).toEqual({
      senderUid: 'a',
      text: 'hi',
      senderDisplayName: 'Al',
      senderAvatarPath: 'pa',
      // Always written, so both channels store one uniform message shape — a
      // convoy message (which can carry no mentions) still gets the field.
      mentionedUids: [],
      createdAt: 'TS',
      expireAt: 'EXP',
    });
  });

  it('stores the resolved mentions on the message doc', () => {
    const doc = buildChatMessageDocument(
      {
        senderUid: 'a',
        text: 'hi',
        senderProfile: { displayName: 'Al', avatarPath: null },
        expireAt: 'EXP',
        mentionedUids: ['u1', 'u2'],
      },
      () => 'TS',
    );
    expect(doc.mentionedUids).toEqual(['u1', 'u2']);
  });

  it('stores the clientId on the message doc only when supplied', () => {
    const withKey = buildChatMessageDocument(
      {
        senderUid: 'a',
        text: 'hi',
        senderProfile: { displayName: 'Al', avatarPath: null },
        expireAt: 'EXP',
        clientId: 'c-1',
      },
      () => 'TS',
    );
    expect(withKey.clientId).toBe('c-1');
    // Legacy (key-less) send: no clientId field at all.
    expect(
      buildChatMessageDocument(
        { senderUid: 'a', text: 'hi', senderProfile: { displayName: null, avatarPath: null }, expireAt: 'EXP' },
        () => 'TS',
      ),
    ).not.toHaveProperty('clientId');
  });

  it('computes the retention TTL instant as now + retentionDays', () => {
    const now = new Date('2026-07-12T00:00:00.000Z');
    // Community: 120 days out.
    expect(chatMessageExpiry(now, COMMUNITY_CHAT_RETENTION_DAYS).toISOString()).toBe(
      '2026-11-09T00:00:00.000Z',
    );
    // Convoy: 30 days out.
    expect(chatMessageExpiry(now, CONVOY_CHAT_RETENTION_DAYS).toISOString()).toBe(
      '2026-08-11T00:00:00.000Z',
    );
    // Convoy window is shorter than community.
    expect(CONVOY_CHAT_RETENTION_DAYS).toBeLessThan(COMMUNITY_CHAT_RETENTION_DAYS);
  });

  it('maps a message summary, coalescing missing fields', () => {
    expect(
      toChatMessageSummary(
        'm1',
        {
          senderUid: 'a',
          text: 'hi',
          senderDisplayName: 'Al',
          senderAvatarPath: 'pa',
          mentionedUids: ['u1'],
        },
        'T1',
      ),
    ).toEqual({
      id: 'm1',
      senderUid: 'a',
      text: 'hi',
      senderDisplayName: 'Al',
      senderAvatarPath: 'pa',
      mentionedUids: ['u1'],
      createdAt: 'T1',
    });
    expect(toChatMessageSummary('m2', {}, 'T2')).toEqual({
      id: 'm2',
      senderUid: '',
      text: '',
      senderDisplayName: null,
      senderAvatarPath: null,
      mentionedUids: [],
      createdAt: 'T2',
    });
  });

  it('defaults mentionedUids to [] for pre-mentions messages and junk values', () => {
    // Messages written before mentions existed carry no field at all — they must
    // still list as ordinary messages rather than crash the mapper.
    expect(toChatMessageSummary('m1', { text: 'old' }, 'T1').mentionedUids).toEqual([]);
    expect(toChatMessageSummary('m2', { mentionedUids: 'u1' }, 'T2').mentionedUids).toEqual([]);
    expect(
      toChatMessageSummary('m3', { mentionedUids: ['u1', 42, null] }, 'T3').mentionedUids,
    ).toEqual(['u1']);
  });

  it('echoes the clientId on a summary only when the doc carries one', () => {
    expect(
      toChatMessageSummary('c-1', { senderUid: 'a', text: 'hi', clientId: 'c-1' }, 'T1').clientId,
    ).toBe('c-1');
    // A doc without the field (legacy / received message) omits it entirely.
    expect(toChatMessageSummary('m1', { senderUid: 'a', text: 'hi' }, 'T1')).not.toHaveProperty(
      'clientId',
    );
  });
});

describe('chat-core mention candidate normalization', () => {
  it('dedups and preserves the input order', () => {
    expect(normalizeMentionCandidates(['u1', 'u2', 'u1'], 'me')).toEqual(['u1', 'u2']);
  });

  it('drops self-mentions rather than rejecting them', () => {
    // @-ing yourself mid-sentence is a normal thing to type; it just isn't a
    // notice anyone needs, so it is silently not one.
    expect(normalizeMentionCandidates(['me'], 'me')).toEqual([]);
    expect(normalizeMentionCandidates(['u1', 'me', 'u2'], 'me')).toEqual(['u1', 'u2']);
  });

  it('handles the no-mentions cases', () => {
    expect(normalizeMentionCandidates(undefined, 'me')).toEqual([]);
    expect(normalizeMentionCandidates([], 'me')).toEqual([]);
  });
});

describe('chat-core community mention notification id (per-sender collapse)', () => {
  const start = new Date(1_800_000_000_000);

  it('is stable for every message the same sender posts inside one window', () => {
    const later = new Date(start.getTime() + COMMUNITY_MENTION_NOTIFY_WINDOW_MS - 1);
    expect(communityMentionNotificationId('s1', later)).toBe(
      communityMentionNotificationId('s1', start),
    );
  });

  it('changes once the window rolls over', () => {
    const next = new Date(start.getTime() + COMMUNITY_MENTION_NOTIFY_WINDOW_MS);
    expect(communityMentionNotificationId('s1', next)).not.toBe(
      communityMentionNotificationId('s1', start),
    );
  });

  it('separates DIFFERENT senders in the same window', () => {
    // The collapse must only ever silence a repeat from the SAME person — two
    // different members mentioning you still produce two notices.
    expect(communityMentionNotificationId('s1', start)).not.toBe(
      communityMentionNotificationId('s2', start),
    );
  });

  it('never collides with a convoy-chat notification id', () => {
    expect(communityMentionNotificationId('x', start)).not.toBe(convoyChatNotificationId('x', start));
  });

  it('stays within the id charset the markRead callable accepts', () => {
    expect(communityMentionNotificationId('s1', start)).toMatch(/^[A-Za-z0-9._-]+$/);
  });
});

describe('chat-core accepted-convoy-member predicate', () => {
  const convoy = {
    memberUids: ['owner', 'a', 'b', 'c'],
    members: {
      owner: { uid: 'owner', inviteStatus: 'accepted' },
      a: { uid: 'a', inviteStatus: 'accepted' },
      b: { uid: 'b', inviteStatus: 'invited' },
      c: { uid: 'c', inviteStatus: 'declined' },
    },
  };

  it('accepts the owner and accepted members only', () => {
    expect(isAcceptedConvoyMember(convoy, 'owner')).toBe(true);
    expect(isAcceptedConvoyMember(convoy, 'a')).toBe(true);
  });

  it('rejects still-invited, declined, non-member, and empty inputs', () => {
    expect(isAcceptedConvoyMember(convoy, 'b')).toBe(false);
    expect(isAcceptedConvoyMember(convoy, 'c')).toBe(false);
    expect(isAcceptedConvoyMember(convoy, 'stranger')).toBe(false);
    expect(isAcceptedConvoyMember(undefined, 'a')).toBe(false);
    expect(isAcceptedConvoyMember({}, 'a')).toBe(false);
  });
});

describe('chat-core convoy-chat notification fan-out set', () => {
  const convoy = {
    memberUids: ['owner', 'a', 'b', 'c'],
    members: {
      owner: { uid: 'owner', inviteStatus: 'accepted' },
      a: { uid: 'a', inviteStatus: 'accepted' },
      b: { uid: 'b', inviteStatus: 'invited' },
      c: { uid: 'c', inviteStatus: 'declined' },
    },
  };

  it('returns the accepted members minus the poster', () => {
    expect(acceptedConvoyMemberUids(convoy, 'owner')).toEqual(['a']);
    expect(acceptedConvoyMemberUids(convoy, 'a')).toEqual(['owner']);
  });

  it('never includes still-invited or declined members', () => {
    const recipients = acceptedConvoyMemberUids(convoy, 'owner');
    expect(recipients).not.toContain('b');
    expect(recipients).not.toContain('c');
  });

  it('is empty when the poster is the only accepted member, and tolerates junk', () => {
    expect(
      acceptedConvoyMemberUids(
        { memberUids: ['solo'], members: { solo: { inviteStatus: 'accepted' } } },
        'solo',
      ),
    ).toEqual([]);
    expect(acceptedConvoyMemberUids(undefined, 'owner')).toEqual([]);
    expect(acceptedConvoyMemberUids({}, 'owner')).toEqual([]);
    // Non-string entries in memberUids must not crash or leak through.
    expect(acceptedConvoyMemberUids({ memberUids: [42, null], members: {} }, 'owner')).toEqual([]);
  });
});

describe('chat-core convoy-chat notification id (per-window collapse)', () => {
  it('is stable for every message inside the same window', () => {
    const start = new Date(1_800_000_000_000);
    const later = new Date(start.getTime() + CONVOY_CHAT_NOTIFY_WINDOW_MS - 1);
    expect(convoyChatNotificationId('cv1', later)).toBe(convoyChatNotificationId('cv1', start));
  });

  it('changes once the window rolls over', () => {
    const start = new Date(1_800_000_000_000);
    const next = new Date(start.getTime() + CONVOY_CHAT_NOTIFY_WINDOW_MS);
    expect(convoyChatNotificationId('cv1', next)).not.toBe(convoyChatNotificationId('cv1', start));
  });

  it('separates convoys in the same window', () => {
    const now = new Date(1_800_000_000_000);
    expect(convoyChatNotificationId('cv1', now)).not.toBe(convoyChatNotificationId('cv2', now));
  });

  it('stays within the id charset the markRead callable accepts', () => {
    const id = convoyChatNotificationId('cv1', new Date(1_800_000_000_000));
    expect(id).toMatch(/^[A-Za-z0-9._-]+$/);
  });
});

describe('chat-core message preview', () => {
  it('trims and truncates to the preview length', () => {
    expect(messagePreview('  hej  ')).toBe('hej');
    expect(messagePreview('x'.repeat(500))).toHaveLength(CHAT_MESSAGE_PREVIEW_LENGTH);
  });
});

describe('chat-core isAlreadyExistsError (atomic keyed-send guard)', () => {
  // The keyed-send idempotency guarantee rests on `create()` losing the race
  // rather than a read-then-write, so misclassifying its rejection would either
  // resurface as a 500 to a perfectly ordinary concurrent retry, or (worse)
  // swallow a genuinely different failure as a duplicate.
  it('matches every shape the admin SDK surfaces ALREADY_EXISTS as', () => {
    expect(isAlreadyExistsError({ code: 6 })).toBe(true);
    expect(isAlreadyExistsError({ code: 'already-exists' })).toBe(true);
    expect(
      isAlreadyExistsError(new Error('6 ALREADY_EXISTS: entity already exists: app=dev')),
    ).toBe(true);
  });

  it('does not classify other failures as a duplicate', () => {
    // Notably code 10 (ABORTED, a contention retry) and 7 (PERMISSION_DENIED):
    // treating either as "already committed" would return a success for a
    // message that was never written.
    expect(isAlreadyExistsError({ code: 10, message: 'ABORTED' })).toBe(false);
    expect(isAlreadyExistsError({ code: 7, message: 'PERMISSION_DENIED' })).toBe(false);
    expect(isAlreadyExistsError(new Error('deadline exceeded'))).toBe(false);
  });

  it('tolerates non-error throwables instead of crashing the handler', () => {
    expect(isAlreadyExistsError(undefined)).toBe(false);
    expect(isAlreadyExistsError(null)).toBe(false);
    expect(isAlreadyExistsError('boom')).toBe(false);
  });
});
