import { describe, expect, it } from 'vitest';
import { allowedEmojiIds, messagePath, mineSchema, nextCounts, parse, setSchema } from './core';

const input = {
  scope: 'community',
  roomId: 'global',
  messageId: 'message',
  emojiId: '1f44d',
  expectedVersion: 0,
  operationId: 'valid-operation-01',
};
describe('message reaction contract', () => {
  it('accepts the complete pinned catalog, including tones and composed emoji, within the payload budget', () => {
    expect(allowedEmojiIds.size).toBe(3963);
    expect(allowedEmojiIds.has('1f44d-1f3fd')).toBe(true);
    expect(allowedEmojiIds.has('1f468-200d-1f469-200d-1f467')).toBe(true);
    const worst = Object.fromEntries(
      [...allowedEmojiIds].map((key) => [key, Number.MAX_SAFE_INTEGER]),
    );
    expect(Buffer.byteLength(JSON.stringify(worst))).toBeLessThan(256 * 1024);
  });
  it('rejects forged authority, arbitrary paths and multi-emoji text', () => {
    for (const data of [
      { ...input, uid: 'victim' },
      { ...input, messageId: '../private' },
      { ...input, emojiId: '👍👍' },
      { ...input, roomId: 'a/b' },
      { ...input, expectedVersion: -1 },
      { ...input, operationId: 'x' },
    ])
      expect(() => parse(setSchema, data)).toThrow();
  });
  it('bounds own-state batches and selects only canonical target roots', () => {
    expect(() =>
      parse(mineSchema, { scope: 'community', roomId: 'global', messageIds: Array(51).fill('x') }),
    ).toThrow();
    expect(() => messagePath({ scope: 'community', roomId: 'other' }, 'm')).toThrow();
    expect(messagePath({ scope: 'club', roomId: 'club' }, 'm')).toBe('driverClubs/club/messages/m');
  });
  it('preserves the one-person delta and removes zero buckets on replace/removal', () => {
    expect(nextCounts({ '1f44d': 3, '2764-fe0f': 1 }, '1f44d', '2764-fe0f')).toEqual({
      '1f44d': 2,
      '2764-fe0f': 2,
    });
    expect(nextCounts({ '1f44d': 1 }, '1f44d', null)).toEqual({});
    expect(() => nextCounts({}, '1f44d', null)).toThrow();
    expect(() => nextCounts({ bad: 1 }, null, '1f44d')).toThrow();
    expect(() => nextCounts({ '1f44d': Number.MAX_SAFE_INTEGER }, null, '1f44d')).toThrow();
  });
});
