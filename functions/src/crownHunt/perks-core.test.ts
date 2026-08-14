/**
 * Unit tests for the Kronjakt SHOP pure logic (perks-core.ts). No emulator.
 *
 * These pin the SERVER-AUTHORITATIVE invariant: the owner-approved costs and
 * effect parameters are exactly what the buy path will debit and store, and no
 * client-supplied value can reach them. Also covers the input validation, the
 * cost arithmetic, the idempotency scoping, and the display mirror.
 */

import { describe, expect, it } from 'vitest';
import {
  CROWN_HUNT_PERKS_FLAG_DEFAULT,
  CROWN_HUNT_PERKS_FLAG_KEY,
  MAX_PERK_PURCHASE_QTY,
  PERK_CATALOG,
  PERK_IDS,
  buildPerkCatalogDoc,
  isBuyable,
  isPerkId,
  parseBuyPerkInput,
  perkById,
  perkCost,
  perkPurchaseLedgerKey,
  scopePerkPurchaseKey,
  type BoostPerk,
  type ShieldPerk,
  type TrapPerk,
} from './perks-core';

describe('perks-core flag', () => {
  it('is crownHuntPerks, default OFF', () => {
    expect(CROWN_HUNT_PERKS_FLAG_KEY).toBe('crownHuntPerks');
    expect(CROWN_HUNT_PERKS_FLAG_DEFAULT).toBe(false);
  });
});

describe('perks-core catalog constants (owner-approved, server-authoritative)', () => {
  it('holds exactly the three approved perks', () => {
    expect([...PERK_IDS]).toEqual(['spike_strip', 'shield', 'boost']);
  });

  it('spike_strip is a 150 KP trap with the approved effect params', () => {
    const perk = PERK_CATALOG.spike_strip as TrapPerk;
    expect(perk.kind).toBe('trap');
    expect(perk.costKp).toBe(150);
    expect(perk.radiusMeters).toBe(100);
    expect(perk.drainKp).toBe(15);
    expect(perk.durationHours).toBe(6);
  });

  it('shield is a 100 KP shield lasting 3 h', () => {
    const perk = PERK_CATALOG.shield as ShieldPerk;
    expect(perk.kind).toBe('shield');
    expect(perk.costKp).toBe(100);
    expect(perk.durationHours).toBe(3);
  });

  it('boost is a 120 KP 2x boost lasting 1 h', () => {
    const perk = PERK_CATALOG.boost as BoostPerk;
    expect(perk.kind).toBe('boost');
    expect(perk.costKp).toBe(120);
    expect(perk.multiplier).toBe(2);
    expect(perk.durationHours).toBe(1);
  });

  it('every perk id maps to a definition whose perkId matches its key', () => {
    for (const id of PERK_IDS) {
      expect(PERK_CATALOG[id].perkId).toBe(id);
    }
  });
});

describe('perks-core lookups', () => {
  it('isPerkId / isBuyable accept only the catalog ids', () => {
    expect(isPerkId('shield')).toBe(true);
    expect(isPerkId('nope')).toBe(false);
    expect(isPerkId(42)).toBe(false);
    expect(isBuyable('boost')).toBe(true);
    expect(isBuyable('__proto__')).toBe(false);
  });

  it('perkById returns undefined for unknown ids', () => {
    expect(perkById('spike_strip')?.perkId).toBe('spike_strip');
    expect(perkById('unknown')).toBeUndefined();
  });
});

describe('perks-core cost arithmetic (never from client input)', () => {
  it('multiplies the server cost by a valid quantity', () => {
    expect(perkCost('spike_strip', 1)).toBe(150);
    expect(perkCost('spike_strip', 3)).toBe(450);
    expect(perkCost('shield', 2)).toBe(200);
  });

  it('rejects an unknown perk or a non-positive-integer quantity', () => {
    expect(perkCost('unknown', 1)).toBeNull();
    expect(perkCost('shield', 0)).toBeNull();
    expect(perkCost('shield', -1)).toBeNull();
    expect(perkCost('shield', 1.5)).toBeNull();
    expect(perkCost('shield', Number.NaN)).toBeNull();
  });
});

describe('perks-core buyPerk input validation', () => {
  it('accepts a well-formed input and defaults qty to 1', () => {
    const parsed = parseBuyPerkInput({ perkId: 'shield', idempotencyKey: 'buy-abc_1' });
    expect(parsed.ok).toBe(true);
    if (parsed.ok) {
      expect(parsed.input.qty).toBe(1);
      expect(parsed.input.perkId).toBe('shield');
    }
  });

  it('accepts an explicit qty within bounds', () => {
    const parsed = parseBuyPerkInput({
      perkId: 'boost',
      qty: MAX_PERK_PURCHASE_QTY,
      idempotencyKey: 'k1',
    });
    expect(parsed.ok).toBe(true);
  });

  it('rejects a missing perkId, a missing key, a bad qty, and an unsafe key', () => {
    expect(parseBuyPerkInput({ idempotencyKey: 'k1' }).ok).toBe(false);
    expect(parseBuyPerkInput({ perkId: 'shield' }).ok).toBe(false);
    expect(parseBuyPerkInput({ perkId: 'shield', qty: 0, idempotencyKey: 'k1' }).ok).toBe(false);
    expect(parseBuyPerkInput({ perkId: 'shield', qty: 1.5, idempotencyKey: 'k1' }).ok).toBe(false);
    expect(
      parseBuyPerkInput({ perkId: 'shield', qty: MAX_PERK_PURCHASE_QTY + 1, idempotencyKey: 'k1' })
        .ok,
    ).toBe(false);
    expect(parseBuyPerkInput({ perkId: 'shield', idempotencyKey: 'bad/key' }).ok).toBe(false);
    // Unknown extra fields are rejected (strict schema).
    expect(parseBuyPerkInput({ perkId: 'shield', idempotencyKey: 'k1', extra: 1 }).ok).toBe(false);
  });
});

describe('perks-core idempotency scoping', () => {
  it('is deterministic, user-scoped, and Firestore-safe', () => {
    const a = scopePerkPurchaseKey('user-1', 'buy-1');
    const b = scopePerkPurchaseKey('user-1', 'buy-1');
    const c = scopePerkPurchaseKey('user-2', 'buy-1');
    expect(a).toBe(b);
    expect(a).not.toBe(c);
    expect(a).toMatch(/^[a-f0-9]{64}$/);
    expect(perkPurchaseLedgerKey(a)).toBe(`perk-purchase_${a}`);
    expect(perkPurchaseLedgerKey(a)).toMatch(/^[A-Za-z0-9._-]+$/);
  });
});

describe('perks-core display mirror', () => {
  it('mirrors every perk with display fields only (no effect params)', () => {
    const doc = buildPerkCatalogDoc();
    expect(doc.perks).toHaveLength(PERK_IDS.length);
    for (const entry of doc.perks) {
      const perk = PERK_CATALOG[entry.perkId];
      expect(entry.costKp).toBe(perk.costKp);
      expect(entry.name).toBe(perk.name);
      expect(entry.iconKey).toBe(perk.iconKey);
      expect(entry.kind).toBe(perk.kind);
      // Effect parameters are deliberately NOT exposed to the client.
      expect(entry).not.toHaveProperty('radiusMeters');
      expect(entry).not.toHaveProperty('drainKp');
      expect(entry).not.toHaveProperty('multiplier');
      expect(entry).not.toHaveProperty('durationHours');
    }
  });
});
