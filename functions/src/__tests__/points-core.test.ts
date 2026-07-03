/**
 * Unit tests for the Kronpoäng ledger pure logic (points-core.ts).
 * No emulators required.
 */

import { describe, expect, it } from 'vitest';
import {
  ACTIVE_POINTS_TRANSACTION_SOURCES,
  POINTS_TRANSACTION_SOURCES,
  applyDelta,
  buildLedgerEntry,
  parseAdminAdjustInput,
  parseAdminReverseInput,
  reversalDescription,
  reversalEntryId,
  toStoredBalance,
} from '../points/points-core';

describe('points-core inputs', () => {
  it('validates admin adjustments (positive integer, reason, enum type)', () => {
    const valid = { targetUid: 'u1', type: 'adjustment_credit', amount: 50, reason: 'Tävlingsvinst' };
    expect(parseAdminAdjustInput(valid).ok).toBe(true);
    expect(parseAdminAdjustInput({ ...valid, amount: 0 }).ok).toBe(false);
    expect(parseAdminAdjustInput({ ...valid, amount: -5 }).ok).toBe(false);
    expect(parseAdminAdjustInput({ ...valid, amount: 2.5 }).ok).toBe(false);
    expect(parseAdminAdjustInput({ ...valid, type: 'earn' }).ok).toBe(false);
    expect(parseAdminAdjustInput({ ...valid, reason: ' ' }).ok).toBe(false);
    expect(parseAdminAdjustInput({ ...valid, reason: 'x'.repeat(501) }).ok).toBe(false);
  });

  it('validates reversal inputs with Firestore-safe entry IDs', () => {
    expect(
      parseAdminReverseInput({ targetUid: 'u1', entryId: 'e1', reason: 'Fel belopp' }).ok,
    ).toBe(true);
    expect(
      parseAdminReverseInput({ targetUid: 'u1', entryId: 'entries/x', reason: 'r' }).ok,
    ).toBe(false);
    expect(parseAdminReverseInput({ targetUid: 'u1', entryId: 'e1', reason: '' }).ok).toBe(false);
  });

  it('keeps future_crown_hunt out of the active sources (legacy rule)', () => {
    expect(POINTS_TRANSACTION_SOURCES).toContain('future_crown_hunt');
    expect(ACTIVE_POINTS_TRANSACTION_SOURCES).not.toContain('future_crown_hunt');
  });
});

describe('points-core balance math', () => {
  it('never allows a negative balance', () => {
    expect(applyDelta(100, -100)).toEqual({ ok: true, balanceAfter: 0 });
    expect(applyDelta(100, -101).ok).toBe(false);
    expect(applyDelta(0, 25)).toEqual({ ok: true, balanceAfter: 25 });
  });

  it('reads stored balances defensively', () => {
    expect(toStoredBalance(42)).toBe(42);
    expect(toStoredBalance('42')).toBe(0);
    expect(toStoredBalance(undefined)).toBe(0);
    expect(toStoredBalance(Number.NaN)).toBe(0);
  });
});

describe('points-core entry building and reversal identity', () => {
  it('builds append-only entries with nullable linkage fields', () => {
    const entry = buildLedgerEntry(
      {
        transactionType: 'earn',
        source: 'crown_hunt',
        amount: 25,
        balanceAfter: 75,
        description: 'Kronjakt: godkänt anspråk',
        idempotencyKey: 'claim-abc',
      },
      () => 'SERVER_TS',
    );
    expect(entry.amount).toBe(25);
    expect(entry.balanceAfter).toBe(75);
    expect(entry.idempotencyKey).toBe('claim-abc');
    expect(entry.relatedEntityId).toBeNull();
    expect(entry.createdByUserId).toBeNull();
    expect(entry.createdAt).toBe('SERVER_TS');
  });

  it('derives one deterministic reversal per original entry', () => {
    expect(reversalEntryId('e1')).toBe('reversal_e1');
    expect(reversalDescription('e1', 'Fel belopp')).toBe(
      'Återföring av transaktion e1: Fel belopp',
    );
  });
});
