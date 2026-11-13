import { describe, expect, it } from 'vitest';
import {
  chatModerationContextInput,
  chatModerationDecision,
  chatPause,
  chatPauseDuration,
  isChatPauseActive,
  MAX_CHAT_PAUSE_MS,
} from './chatModeration-core';

const decision = {
  target: { source: 'community', messageId: 'message' },
  expectedActorUid: 'admin',
  expectedVersion: 'a'.repeat(64),
  action: 'remove',
  reason: 'Reviewed reason',
  warn: false,
  pause: null,
  idempotencyKey: '1234567890123456',
};
describe('public chat moderation input boundary', () => {
  it.each([null, { amount: 5, unit: 'minutes' }, { amount: 2, unit: 'days' }])(
    'accepts removal with optional pause %j',
    (pause) => {
      expect(chatModerationDecision.safeParse({ ...decision, pause }).success).toBe(true);
    },
  );
  it('requires a pause for standalone pause, and explicit warning choice', () => {
    expect(chatModerationDecision.safeParse({ ...decision, action: 'pause' }).success).toBe(false);
    expect(chatModerationDecision.safeParse({ ...decision, warn: undefined }).success).toBe(false);
    expect(
      chatModerationDecision.safeParse({
        ...decision,
        action: 'pause',
        pause: { amount: 2, unit: 'days' },
      }).success,
    ).toBe(true);
  });
  it.each([0, -1, 0.5, 1.5, '5', NaN, Infinity, Number.MAX_SAFE_INTEGER])(
    'rejects invalid amount %s',
    (amount) => {
      expect(chatPause.safeParse({ amount, unit: 'minutes' }).success).toBe(false);
    },
  );
  it('enforces the same exact 30-day cap in both units', () => {
    expect(chatPause.safeParse({ amount: 30, unit: 'days' }).success).toBe(true);
    expect(chatPause.safeParse({ amount: 43200, unit: 'minutes' }).success).toBe(true);
    expect(chatPause.safeParse({ amount: 31, unit: 'days' }).success).toBe(false);
    expect(chatPause.safeParse({ amount: 43201, unit: 'minutes' }).success).toBe(false);
    expect(chatPauseDuration({ amount: 30, unit: 'days' })).toBe(MAX_CHAT_PAUSE_MS);
  });
  it.each(['hours', 'day', '', undefined])('rejects unknown unit %s', (unit) => {
    expect(chatPause.safeParse({ amount: 2, unit }).success).toBe(false);
  });
  it.each(['dm', 'convoy', 'club', '../community'])('rejects non-public scope %s', (source) => {
    expect(
      chatModerationContextInput.safeParse({ target: { source, messageId: 'm' } }).success,
    ).toBe(false);
  });
  it('rejects forged fields and event/path confusion', () => {
    for (const patch of [
      { role: 'admin' },
      { targetUid: 'victim' },
      { pauseUntilMillis: Date.now() },
      { path: 'conversations/private' },
    ])
      expect(chatModerationDecision.safeParse({ ...decision, ...patch }).success).toBe(false);
    for (const target of [
      { source: 'community', messageId: '../m' },
      { source: 'community', messageId: 'm', eventId: 'e' },
      { source: 'event', messageId: 'm' },
      { source: 'event', eventId: '../e', messageId: 'm' },
      { source: 'event', eventId: 'e', messageId: '.' },
    ])
      expect(chatModerationContextInput.safeParse({ target }).success).toBe(false);
  });
  it('ends at the precise server-time boundary, independent of timezone', () => {
    expect(isChatPauseActive(1000, 999)).toBe(true);
    expect(isChatPauseActive(1000, 1000)).toBe(false);
    expect(isChatPauseActive(1000, 1001)).toBe(false);
    expect(chatPauseDuration({ amount: 1, unit: 'days' })).toBe(86_400_000);
  });
});
