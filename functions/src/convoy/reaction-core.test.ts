/**
 * Unit tests for the convoy REACTION pure logic (reaction-core.ts). No emulator.
 *
 * These pin the SERVER-AUTHORITATIVE anti-spam invariant off the transaction:
 * the cooldown windows, the per-kind independence, the boundary of the
 * within-cooldown predicate, the deterministic doc id / field naming, and the
 * input parser (which rejects unknown kinds and malformed ids before any read).
 */

import { describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import {
  CONVOY_REACTION_KINDS,
  REACTION_COOLDOWN_MS,
  buildReactionDocument,
  cooldownExpiry,
  isConvoyReactionKind,
  isWithinReactionCooldown,
  parseSendReactionInput,
  reactionCooldownDocId,
  reactionCooldownMs,
  reactionCooldownRemainingMs,
  reactionExpiry,
  reactionLastSentField,
} from './reaction-core';

describe('reaction kinds', () => {
  it('is exactly the three product reactions, in a stable order', () => {
    expect(CONVOY_REACTION_KINDS).toEqual(['police', 'hello', 'follow_me']);
  });

  it('recognises only the known wire values', () => {
    for (const kind of CONVOY_REACTION_KINDS) {
      expect(isConvoyReactionKind(kind)).toBe(true);
    }
    expect(isConvoyReactionKind('POLICE')).toBe(false);
    expect(isConvoyReactionKind('wave')).toBe(false);
    expect(isConvoyReactionKind(1)).toBe(false);
    expect(isConvoyReactionKind(null)).toBe(false);
  });
});

describe('cooldown windows', () => {
  it('rate-limits the police alert to once per 60s (the anti-spam requirement)', () => {
    expect(REACTION_COOLDOWN_MS.police).toBe(60_000);
    expect(reactionCooldownMs('police')).toBe(60_000);
  });

  it('gives the social taps shorter windows', () => {
    expect(reactionCooldownMs('hello')).toBe(15_000);
    expect(reactionCooldownMs('follow_me')).toBe(30_000);
  });
});

describe('isWithinReactionCooldown', () => {
  it('a never-sent kind is never on cooldown', () => {
    expect(isWithinReactionCooldown('police', null, 1_000_000)).toBe(false);
  });

  it('refuses a send strictly inside the window and admits it at/after the boundary', () => {
    const last = 1_000_000;
    // 1ms before the window closes: still on cooldown.
    expect(isWithinReactionCooldown('police', last, last + 59_999)).toBe(true);
    // Exactly at the boundary: allowed (window is half-open).
    expect(isWithinReactionCooldown('police', last, last + 60_000)).toBe(false);
    expect(isWithinReactionCooldown('police', last, last + 60_001)).toBe(false);
  });

  it('each kind uses ONLY its own window (kinds do not share a budget)', () => {
    const last = 1_000_000;
    // 20s after a send: police (60s) still blocked, hello (15s) already free.
    expect(isWithinReactionCooldown('police', last, last + 20_000)).toBe(true);
    expect(isWithinReactionCooldown('hello', last, last + 20_000)).toBe(false);
  });

  it('treats a corrupt (non-finite) stored timestamp as not-on-cooldown', () => {
    expect(isWithinReactionCooldown('police', Number.NaN, 1_000_000)).toBe(false);
    expect(isWithinReactionCooldown('police', Number.POSITIVE_INFINITY, 1_000_000)).toBe(false);
  });
});

describe('reactionCooldownRemainingMs', () => {
  it('is 0 when never sent or already past the window', () => {
    expect(reactionCooldownRemainingMs('police', null, 5_000)).toBe(0);
    expect(reactionCooldownRemainingMs('police', 1_000, 1_000 + 60_000)).toBe(0);
    expect(reactionCooldownRemainingMs('police', 1_000, 1_000 + 90_000)).toBe(0);
  });

  it('reports the exact time left mid-window (so the client greys precisely)', () => {
    expect(reactionCooldownRemainingMs('police', 1_000, 1_000 + 10_000)).toBe(50_000);
    expect(reactionCooldownRemainingMs('hello', 1_000, 1_000 + 5_000)).toBe(10_000);
  });
});

describe('deterministic ids + field names', () => {
  it('scopes the cooldown doc to (convoy, member)', () => {
    expect(reactionCooldownDocId('convoy-1', 'user-a')).toBe('convoy-1__user-a');
    // A different convoy for the same user is a different doc (no cross-convoy throttle).
    expect(reactionCooldownDocId('convoy-2', 'user-a')).not.toBe(
      reactionCooldownDocId('convoy-1', 'user-a'),
    );
  });

  it('gives each kind its own last-sent field', () => {
    expect(reactionLastSentField('police')).toBe('lastSentAt_police');
    expect(reactionLastSentField('hello')).toBe('lastSentAt_hello');
    expect(reactionLastSentField('follow_me')).toBe('lastSentAt_follow_me');
  });
});

describe('TTL expiries', () => {
  it('reaction TTL is a few minutes out; cooldown TTL outlives every window', () => {
    const now = new Date(1_700_000_000_000);
    expect(reactionExpiry(now).toMillis()).toBe(now.getTime() + 5 * 60 * 1_000);
    expect(cooldownExpiry(now).toMillis()).toBe(now.getTime() + 60 * 60 * 1_000);
    // The cooldown doc must never be swept while it is still throttling.
    expect(cooldownExpiry(now).toMillis() - now.getTime()).toBeGreaterThan(
      Math.max(...Object.values(REACTION_COOLDOWN_MS)),
    );
  });
});

describe('buildReactionDocument', () => {
  it('denormalises the sender and carries createdAt + expireAt verbatim', () => {
    const createdAt = Timestamp.fromMillis(1_700_000_000_000);
    const expireAt = Timestamp.fromMillis(1_700_000_300_000);
    const doc = buildReactionDocument({
      kind: 'police',
      senderUid: 'user-a',
      senderProfile: { displayName: 'Anna', avatarPath: 'profileImages/user-a/a.jpg' },
      createdAt,
      expireAt,
    });
    expect(doc).toEqual({
      kind: 'police',
      senderUid: 'user-a',
      senderDisplayName: 'Anna',
      senderAvatarPath: 'profileImages/user-a/a.jpg',
      createdAt,
      expireAt,
    });
  });

  it('tolerates a missing sender profile (reactions are cosmetic)', () => {
    const t = Timestamp.fromMillis(0);
    const doc = buildReactionDocument({
      kind: 'hello',
      senderUid: 'user-b',
      senderProfile: { displayName: null, avatarPath: null },
      createdAt: t,
      expireAt: t,
    });
    expect(doc.senderDisplayName).toBeNull();
    expect(doc.senderAvatarPath).toBeNull();
  });
});

describe('parseSendReactionInput', () => {
  it('accepts a well-formed send', () => {
    const result = parseSendReactionInput({ convoyId: 'convoy-1', kind: 'police' });
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.input).toEqual({ convoyId: 'convoy-1', kind: 'police' });
    }
  });

  it('accepts an optional idempotency clientId', () => {
    const result = parseSendReactionInput({
      convoyId: 'convoy-1',
      kind: 'follow_me',
      clientId: 'abc_123-XYZ',
    });
    expect(result.ok).toBe(true);
  });

  it('rejects an unknown kind', () => {
    expect(parseSendReactionInput({ convoyId: 'convoy-1', kind: 'wave' }).ok).toBe(false);
    expect(parseSendReactionInput({ convoyId: 'convoy-1', kind: 'POLICE' }).ok).toBe(false);
  });

  it('rejects a missing convoyId and a malformed clientId', () => {
    expect(parseSendReactionInput({ kind: 'police' }).ok).toBe(false);
    expect(
      parseSendReactionInput({ convoyId: 'convoy-1', kind: 'police', clientId: 'has space' }).ok,
    ).toBe(false);
  });

  it('rejects unknown extra keys (strict)', () => {
    expect(
      parseSendReactionInput({ convoyId: 'convoy-1', kind: 'police', text: 'sneaky' }).ok,
    ).toBe(false);
  });

  it('rejects a non-object', () => {
    expect(parseSendReactionInput(null).ok).toBe(false);
    expect(parseSendReactionInput('police').ok).toBe(false);
  });
});
