import { describe, expect, it } from 'vitest';
import {
  buildBlockDocument,
  parseBlockInput,
  parseUnblockInput,
  toBlockedUserSummary,
} from './blocking-core';

describe('blocking-core parsing', () => {
  it('accepts a non-empty targetUserId and trims it', () => {
    const parsed = parseBlockInput({ targetUserId: '  user-123  ' });
    expect(parsed).toEqual({ ok: true, input: { targetUserId: 'user-123' } });
  });

  it('rejects a missing or empty targetUserId', () => {
    expect(parseBlockInput({}).ok).toBe(false);
    expect(parseBlockInput({ targetUserId: '' }).ok).toBe(false);
    expect(parseBlockInput({ targetUserId: '   ' }).ok).toBe(false);
    expect(parseUnblockInput(null).ok).toBe(false);
  });

  it('rejects unknown extra fields (strict)', () => {
    expect(parseBlockInput({ targetUserId: 'u1', extra: true }).ok).toBe(false);
  });
});

describe('blocking-core builders', () => {
  it('builds the block document with a denormalized displayName and server timestamp', () => {
    const doc = buildBlockDocument('u2', 'Bob', () => 'TS');
    expect(doc).toEqual({ blockedUserId: 'u2', displayName: 'Bob', createdAt: 'TS' });
  });

  it('null-coalesces a missing displayName', () => {
    expect(buildBlockDocument('u2', null, () => 'TS').displayName).toBeNull();
  });

  it('maps a stored block into the minimal safe summary', () => {
    expect(toBlockedUserSummary('u2', 'Bob', '2026-07-01T10:00:00.000Z')).toEqual({
      userId: 'u2',
      displayName: 'Bob',
      blockedAt: '2026-07-01T10:00:00.000Z',
    });
    expect(toBlockedUserSummary('u2', undefined, '2026-07-01T10:00:00.000Z').displayName).toBeNull();
  });
});
