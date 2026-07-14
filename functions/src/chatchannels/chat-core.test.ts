import { describe, expect, it } from 'vitest';
import {
  CHAT_MESSAGE_MAX_LENGTH,
  COMMUNITY_CHAT_RETENTION_DAYS,
  CONVOY_CHAT_RETENTION_DAYS,
  buildChatMessageDocument,
  chatMessageExpiry,
  isAcceptedConvoyMember,
  parseListCommunityInput,
  parseListConvoyInput,
  parseMarkReadCommunityInput,
  parsePostCommunityInput,
  parsePostConvoyInput,
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
      createdAt: 'TS',
      expireAt: 'EXP',
    });
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
        { senderUid: 'a', text: 'hi', senderDisplayName: 'Al', senderAvatarPath: 'pa' },
        'T1',
      ),
    ).toEqual({
      id: 'm1',
      senderUid: 'a',
      text: 'hi',
      senderDisplayName: 'Al',
      senderAvatarPath: 'pa',
      createdAt: 'T1',
    });
    expect(toChatMessageSummary('m2', {}, 'T2')).toEqual({
      id: 'm2',
      senderUid: '',
      text: '',
      senderDisplayName: null,
      senderAvatarPath: null,
      createdAt: 'T2',
    });
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
