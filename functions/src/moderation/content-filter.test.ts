import { describe, expect, it } from 'vitest';
import { containsObjectionableText } from './content-filter';
import { parsePostCommunityInput, parsePostConvoyInput } from '../chatchannels/chat-core';
import { parseSendMessageInput } from '../dm/dm-core';
import { parsePostChatMessageInput } from '../events/chat-core';

describe('baseline user-content filtering', () => {
  it('rejects severe text through the actual publishing validators', () => {
    const text = 'KILL YOURSELF';
    expect(parsePostCommunityInput({ text }).ok).toBe(false);
    expect(parsePostConvoyInput({ convoyId: 'convoy_123', text }).ok).toBe(false);
    expect(parseSendMessageInput({ toUid: 'other-user', text }).ok).toBe(false);
    expect(parsePostChatMessageInput({ eventId: 'event_123', message: text }).ok).toBe(false);
  });
  it('handles case/zero-width variants without rewriting valid automotive text', () => {
    expect(containsObjectionableText('ki\u200bll yourself')).toBe(true);
    expect(containsObjectionableText('JAG SKA DÖDA DIG')).toBe(true);
    expect(containsObjectionableText('Cummins diesel – ses på bilträffen! 🚗')).toBe(false);
    expect(parsePostCommunityInput({ text: 'Cummins diesel – ses på bilträffen! 🚗' }).ok).toBe(
      true,
    );
  });
});

it('permits valid requests on every filtered publishing surface', () => {
  const text = 'Ses på bilträffen 🚗';
  expect(parsePostCommunityInput({ text }).ok).toBe(true);
  expect(parsePostConvoyInput({ convoyId: 'convoy_123', text }).ok).toBe(true);
  expect(parseSendMessageInput({ toUid: 'other-user', text }).ok).toBe(true);
  expect(parsePostChatMessageInput({ eventId: 'event_123', message: text }).ok).toBe(true);
});
