import { describe, expect, it } from 'vitest';
import {
  DM_MESSAGE_MAX_LENGTH,
  DM_MESSAGE_PREVIEW_LENGTH,
  buildMessageDocument,
  buildNewConversationDocument,
  buildReplyToSnapshot,
  dmMembers,
  dmPairId,
  isConversationMember,
  messagePreview,
  parseGetMessagesInput,
  parseListConversationsInput,
  parseMarkReadInput,
  parseSendMessageInput,
  toConversationSummary,
  toMessageSummary,
  toProfileProjection,
} from './dm-core';

describe('dm-core parsing', () => {
  it('parses sendMessage strictly', () => {
    expect(parseSendMessageInput({ toUid: '  u-2  ', text: 'hi' })).toEqual({
      ok: true,
      input: { toUid: 'u-2', text: 'hi' },
    });
    expect(parseSendMessageInput({ toUid: '', text: 'hi' }).ok).toBe(false);
    expect(parseSendMessageInput({ toUid: 'u-2', text: '' }).ok).toBe(false);
    expect(
      parseSendMessageInput({ toUid: 'u-2', text: 'x'.repeat(DM_MESSAGE_MAX_LENGTH + 1) }).ok,
    ).toBe(false);
    expect(parseSendMessageInput({ toUid: 'u-2', text: 'hi', extra: 1 }).ok).toBe(false);
    expect(parseSendMessageInput(null).ok).toBe(false);
  });

  it('accepts an optional client idempotency key and rejects malformed ones', () => {
    // A UUID-shaped key is accepted and carried through.
    const uuid = '3f2504e0-4f89-41d3-9a0c-0305e82c3301';
    expect(parseSendMessageInput({ toUid: 'u-2', text: 'hi', clientId: uuid })).toEqual({
      ok: true,
      input: { toUid: 'u-2', text: 'hi', clientId: uuid },
    });
    // Omitted is fine (legacy path).
    expect(parseSendMessageInput({ toUid: 'u-2', text: 'hi' }).ok).toBe(true);
    // A `/` would break the doc-id use; `..`/empty/over-long are rejected too.
    expect(parseSendMessageInput({ toUid: 'u-2', text: 'hi', clientId: 'a/b' }).ok).toBe(false);
    expect(parseSendMessageInput({ toUid: 'u-2', text: 'hi', clientId: '..' }).ok).toBe(false);
    expect(parseSendMessageInput({ toUid: 'u-2', text: 'hi', clientId: '' }).ok).toBe(false);
    expect(parseSendMessageInput({ toUid: 'u-2', text: 'hi', clientId: 'x'.repeat(65) }).ok).toBe(
      false,
    );
    // Validated VERBATIM: surrounding whitespace is rejected, never trimmed into
    // a different id (which would break optimistic de-dupe against the doc id).
    expect(parseSendMessageInput({ toUid: 'u-2', text: 'hi', clientId: ' abc' }).ok).toBe(false);
    expect(parseSendMessageInput({ toUid: 'u-2', text: 'hi', clientId: 'abc ' }).ok).toBe(false);
  });

  it('accepts an optional replyToMessageId (message doc id) and rejects malformed ones', () => {
    // Both an auto-id and a prior clientId are valid parent ids.
    expect(
      parseSendMessageInput({ toUid: 'u-2', text: 'hi', replyToMessageId: 'AbC0d_1-2' }),
    ).toEqual({
      ok: true,
      input: { toUid: 'u-2', text: 'hi', replyToMessageId: 'AbC0d_1-2' },
    });
    // Path separators / dot ids / empty / over-long are rejected (doc-id use).
    expect(parseSendMessageInput({ toUid: 'u-2', text: 'hi', replyToMessageId: 'a/b' }).ok).toBe(
      false,
    );
    expect(parseSendMessageInput({ toUid: 'u-2', text: 'hi', replyToMessageId: '' }).ok).toBe(false);
    expect(
      parseSendMessageInput({ toUid: 'u-2', text: 'hi', replyToMessageId: 'x'.repeat(65) }).ok,
    ).toBe(false);
    // Reply + idempotency key coexist on one send.
    expect(
      parseSendMessageInput({ toUid: 'u-2', text: 'hi', clientId: 'k', replyToMessageId: 'm-1' }).ok,
    ).toBe(true);
  });

  it('parses getMessages with an optional ISO cursor', () => {
    expect(parseGetMessagesInput({ conversationId: 'a__b' }).ok).toBe(true);
    expect(
      parseGetMessagesInput({ conversationId: 'a__b', before: '2026-07-11T00:00:00.000Z' }).ok,
    ).toBe(true);
    expect(parseGetMessagesInput({ conversationId: 'a__b', before: 'not-a-date' }).ok).toBe(false);
    expect(parseGetMessagesInput({ conversationId: '' }).ok).toBe(false);
    expect(parseGetMessagesInput({ conversationId: 'a__b', extra: 1 }).ok).toBe(false);
  });

  it('parses markRead + listConversations strictly', () => {
    expect(parseMarkReadInput({ conversationId: 'a__b' }).ok).toBe(true);
    expect(parseMarkReadInput({}).ok).toBe(false);
    expect(parseListConversationsInput({}).ok).toBe(true);
    expect(parseListConversationsInput(undefined).ok).toBe(true);
    expect(parseListConversationsInput({ foo: 1 }).ok).toBe(false);
  });
});

describe('dm-core canonical pair id', () => {
  it('is order-independent (canonical 1:1 doc)', () => {
    expect(dmPairId('a', 'b')).toBe('a__b');
    expect(dmPairId('b', 'a')).toBe('a__b');
    expect(dmPairId('b', 'a')).toBe(dmPairId('a', 'b'));
  });

  it('returns a sorted member pair', () => {
    expect(dmMembers('b', 'a')).toEqual(['a', 'b']);
    expect(dmMembers('a', 'b')).toEqual(['a', 'b']);
  });
});

describe('dm-core projections + preview', () => {
  it('projects a profile, coalescing missing/non-string to null', () => {
    expect(toProfileProjection({ displayName: 'Bob', avatarPath: 'p' })).toEqual({
      displayName: 'Bob',
      avatarPath: 'p',
    });
    expect(toProfileProjection(undefined)).toEqual({ displayName: null, avatarPath: null });
    expect(toProfileProjection({ displayName: 42 })).toEqual({
      displayName: null,
      avatarPath: null,
    });
  });

  it('trims + truncates the preview', () => {
    expect(messagePreview('  hello  ')).toBe('hello');
    expect(messagePreview('x'.repeat(DM_MESSAGE_PREVIEW_LENGTH + 50)).length).toBe(
      DM_MESSAGE_PREVIEW_LENGTH,
    );
  });
});

describe('dm-core builders', () => {
  it('builds a message doc (trimmed)', () => {
    expect(buildMessageDocument({ senderUid: 'a', text: '  hi  ' }, () => 'TS')).toEqual({
      senderUid: 'a',
      text: 'hi',
      createdAt: 'TS',
    });
  });

  it('stores the clientId on the message doc only when supplied', () => {
    expect(
      buildMessageDocument({ senderUid: 'a', text: 'hi', clientId: 'c-1' }, () => 'TS'),
    ).toEqual({ senderUid: 'a', text: 'hi', createdAt: 'TS', clientId: 'c-1' });
    // Legacy send: no clientId field at all.
    expect(buildMessageDocument({ senderUid: 'a', text: 'hi' }, () => 'TS')).not.toHaveProperty(
      'clientId',
    );
  });

  it('stores the replyTo snapshot on the message doc only when supplied', () => {
    const replyTo = buildReplyToSnapshot({
      messageId: 'parent-1',
      senderUid: 'a',
      senderDisplayName: 'Alice',
      text: '  original  ',
    })!;
    expect(buildMessageDocument({ senderUid: 'b', text: 'hi', replyTo }, () => 'TS')).toEqual({
      senderUid: 'b',
      text: 'hi',
      createdAt: 'TS',
      replyTo: {
        messageId: 'parent-1',
        senderUid: 'a',
        senderDisplayName: 'Alice',
        textPreview: 'original',
      },
    });
    // Ordinary message: no replyTo field.
    expect(buildMessageDocument({ senderUid: 'a', text: 'hi' }, () => 'TS')).not.toHaveProperty(
      'replyTo',
    );
  });

  it('builds the reply snapshot server-side, omitting it for a missing/malformed parent', () => {
    expect(
      buildReplyToSnapshot({
        messageId: 'p1',
        senderUid: 'a',
        senderDisplayName: 'Alice',
        text: 'x'.repeat(500),
      })!.textPreview,
    ).toHaveLength(DM_MESSAGE_PREVIEW_LENGTH);
    // A missing/expired parent (null) and a malformed one both yield null — the
    // send proceeds without a quote rather than failing.
    expect(buildReplyToSnapshot(null)).toBeNull();
    expect(
      buildReplyToSnapshot({ messageId: 'p1', senderUid: '', senderDisplayName: null, text: 'x' }),
    ).toBeNull();
    expect(
      buildReplyToSnapshot({ messageId: '', senderUid: 'a', senderDisplayName: null, text: 'x' }),
    ).toBeNull();
    expect(
      buildReplyToSnapshot({ messageId: 'p1', senderUid: 'a', senderDisplayName: null, text: '  ' }),
    ).toBeNull();
  });

  it('builds a new conversation with recipient unread seeded to 1', () => {
    const doc = buildNewConversationDocument(
      {
        senderUid: 'b',
        recipientUid: 'a',
        senderProfile: { displayName: 'Bob', avatarPath: null },
        recipientProfile: { displayName: 'Alice', avatarPath: 'pa' },
        text: '  hey  ',
      },
      () => 'TS',
    );
    expect(doc.members).toEqual(['a', 'b']);
    expect(doc.unread).toEqual({ b: 0, a: 1 });
    expect(doc.lastReadAt).toEqual({ b: null, a: null });
    expect(doc.lastMessage).toEqual({ text: 'hey', senderUid: 'b', createdAt: 'TS' });
    expect(doc.lastMessageAt).toBe('TS');
    expect(doc.memberProfiles).toEqual({
      b: { displayName: 'Bob', avatarPath: null },
      a: { displayName: 'Alice', avatarPath: 'pa' },
    });
  });
});

describe('dm-core summaries', () => {
  const iso = (v: unknown): string | null => (typeof v === 'string' ? v : null);

  it('projects the OTHER member + the caller own unread/read state', () => {
    const summary = toConversationSummary(
      'a__b',
      {
        members: ['a', 'b'],
        memberProfiles: {
          a: { displayName: 'Alice', avatarPath: 'pa' },
          b: { displayName: 'Bob', avatarPath: null },
        },
        lastMessage: { text: 'hey', senderUid: 'b', createdAt: 'T1' },
        unread: { a: 3, b: 0 },
        lastReadAt: { a: null, b: 'T0' },
      },
      'a',
      iso,
    );
    expect(summary).toEqual({
      conversationId: 'a__b',
      otherUser: { uid: 'b', displayName: 'Bob', avatarPath: null },
      lastMessage: { text: 'hey', senderUid: 'b', createdAt: 'T1' },
      unreadCount: 3,
      lastReadAt: null,
    });
  });

  it('coalesces missing/negative unread to 0 and null lastMessage', () => {
    const summary = toConversationSummary(
      'a__b',
      { members: ['a', 'b'], unread: {}, lastMessage: null },
      'b',
      iso,
    );
    expect(summary.unreadCount).toBe(0);
    expect(summary.lastMessage).toBeNull();
    expect(summary.otherUser.uid).toBe('a');
  });

  it('maps a message summary', () => {
    expect(toMessageSummary('m1', { senderUid: 'a', text: 'hi' }, 'T1')).toEqual({
      id: 'm1',
      senderUid: 'a',
      text: 'hi',
      createdAt: 'T1',
    });
  });

  it('echoes the clientId on a message summary when the doc carries one', () => {
    expect(toMessageSummary('c-1', { senderUid: 'a', text: 'hi', clientId: 'c-1' }, 'T1')).toEqual({
      id: 'c-1',
      senderUid: 'a',
      text: 'hi',
      createdAt: 'T1',
      clientId: 'c-1',
    });
  });

  it('surfaces replyTo on a message summary, omitting it when absent or malformed', () => {
    expect(
      toMessageSummary(
        'm1',
        {
          senderUid: 'b',
          text: 'hi',
          replyTo: {
            messageId: 'p1',
            senderUid: 'a',
            senderDisplayName: 'Alice',
            textPreview: 'original',
          },
        },
        'T1',
      ).replyTo,
    ).toEqual({
      messageId: 'p1',
      senderUid: 'a',
      senderDisplayName: 'Alice',
      textPreview: 'original',
    });
    // Ordinary / pre-reply message: field omitted.
    expect(toMessageSummary('m2', { senderUid: 'a', text: 'hi' }, 'T2')).not.toHaveProperty(
      'replyTo',
    );
    // Malformed stored snapshot (no messageId) is dropped, not surfaced as junk.
    expect(
      toMessageSummary('m3', { senderUid: 'a', text: 'hi', replyTo: { textPreview: 'x' } }, 'T3'),
    ).not.toHaveProperty('replyTo');
  });
});

describe('dm-core membership predicate', () => {
  it('checks conversation membership', () => {
    expect(isConversationMember({ members: ['a', 'b'] }, 'a')).toBe(true);
    expect(isConversationMember({ members: ['a', 'b'] }, 'c')).toBe(false);
    expect(isConversationMember(undefined, 'a')).toBe(false);
    expect(isConversationMember({}, 'a')).toBe(false);
  });
});
