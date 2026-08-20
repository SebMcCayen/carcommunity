/**
 * Unit tests for the Kronjakt SHOP pure logic (perks-core.ts). No emulator.
 *
 * These pin the SERVER-AUTHORITATIVE invariant: the owner-approved costs and
 * effect parameters are exactly what the buy path will debit and store, and no
 * client-supplied value can reach them. Also covers the input validation, the
 * cost arithmetic, the idempotency scoping, and the display mirror.
 */

import { describe, expect, it } from 'vitest';
import { DAILY_POINTS_CAP } from '../points/points-economy-core';
import {
  CROWN_HUNT_PERKS_FLAG_DEFAULT,
  CROWN_HUNT_PERKS_FLAG_KEY,
  MAX_CONCURRENT_ACTIVE_PERKS,
  MAX_PERK_HOLD_PER_PERK,
  MAX_PERK_HOLD_VALUE_KP,
  MAX_PERK_PURCHASE_QTY,
  PERK_BASE_COST_KP,
  PERK_CATALOG,
  PERK_CATALOG_DOC_VERSION,
  PERK_HOLD_DAYS_BUFFER,
  PERK_IDS,
  PERK_PURCHASE_COOLDOWN_SECONDS,
  activationAllowed,
  buildPerkCatalogDoc,
  evaluateHoldCap,
  isBuyable,
  isPerkId,
  isWithinPurchaseCooldown,
  parseBuyPerkInput,
  perkById,
  perkCost,
  perkPowerKp,
  perkPurchaseLedgerKey,
  purchaseCooldownBlocks,
  purchaseCooldownDocId,
  referencePerkCostKp,
  scopePerkPurchaseKey,
  type ActivePerkEffects,
  type BoostPerk,
  type PerkKind,
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

  it('mirrors BOTH the Swedish and the English name at doc version 2', () => {
    const doc = buildPerkCatalogDoc();
    expect(doc.version).toBe(PERK_CATALOG_DOC_VERSION);
    expect(PERK_CATALOG_DOC_VERSION).toBe(2);
    for (const entry of doc.perks) {
      const perk = PERK_CATALOG[entry.perkId];
      expect(entry.nameEn).toBe(perk.nameEn);
      expect(entry.name.length).toBeGreaterThan(0);
      expect(entry.nameEn.length).toBeGreaterThan(0);
    }
  });
});

describe('perks-core bilingual names (every perk has BOTH languages)', () => {
  it('gives each catalog perk a non-empty Swedish + English name that differ', () => {
    for (const id of PERK_IDS) {
      const perk = PERK_CATALOG[id];
      expect(perk.name.trim().length).toBeGreaterThan(0);
      expect(perk.nameEn.trim().length).toBeGreaterThan(0);
      // Every perk has a REAL English name, not the Swedish one copied over.
      expect(perk.nameEn).not.toBe(perk.name);
    }
  });

  it('wires the approved Spikmatta/Sköld/Dubbla Poäng ↔ Spike strip/Shield/Double points pairs', () => {
    expect([PERK_CATALOG.spike_strip.name, PERK_CATALOG.spike_strip.nameEn]).toEqual([
      'Spikmatta',
      'Spike strip',
    ]);
    expect([PERK_CATALOG.shield.name, PERK_CATALOG.shield.nameEn]).toEqual(['Sköld', 'Shield']);
    expect([PERK_CATALOG.boost.name, PERK_CATALOG.boost.nameEn]).toEqual([
      'Dubbla Poäng',
      'Double points',
    ]);
  });
});

describe('perks-core pricing algorithm (cost === reference price)', () => {
  it('prices every perk at exactly max(floor, power) — no silent drift', () => {
    for (const id of PERK_IDS) {
      const perk = PERK_CATALOG[id];
      expect(perk.costKp).toBe(referencePerkCostKp(perk));
    }
  });

  it('derives the trap price from its earn ceiling and the boost from its power', () => {
    expect(perkPowerKp(PERK_CATALOG.spike_strip)).toBe(150);
    expect(referencePerkCostKp(PERK_CATALOG.spike_strip)).toBe(150);
    expect(referencePerkCostKp(PERK_CATALOG.boost)).toBe(120);
  });

  it('floors a purely defensive perk at the base cost', () => {
    // The shield can avoid at most 45 KP/day of loss — below the floor — so it
    // prices at PERK_BASE_COST_KP, not its raw power.
    expect(perkPowerKp(PERK_CATALOG.shield)).toBeLessThan(PERK_BASE_COST_KP);
    expect(referencePerkCostKp(PERK_CATALOG.shield)).toBe(PERK_BASE_COST_KP);
  });
});

describe('perks-core hold cap (buy/hold ceiling)', () => {
  it('derives the per-perk cap from trap throughput × the day buffer', () => {
    expect(MAX_PERK_HOLD_PER_PERK).toBe(3 * PERK_HOLD_DAYS_BUFFER);
    expect(MAX_PERK_HOLD_PER_PERK).toBe(6);
  });

  it('allows a buy that fits under both ceilings', () => {
    expect(evaluateHoldCap({}, 'shield', 1, 100)).toBeNull();
    expect(evaluateHoldCap({ shield: 5 }, 'shield', 1, 100)).toBeNull();
  });

  it('refuses a buy that would exceed the per-perk count cap', () => {
    expect(evaluateHoldCap({ shield: MAX_PERK_HOLD_PER_PERK }, 'shield', 1, 100)).toBe('per_perk');
    expect(evaluateHoldCap({ shield: 5 }, 'shield', 2, 100)).toBe('per_perk');
  });

  it('refuses a buy that would exceed the total held VALUE cap', () => {
    // Derive the scenario from the constants rather than hardcoding a cap value
    // — a stale hardcoded cap is exactly what broke this test last time. Max out
    // the two OTHER perks, then buy the smallest shield quantity whose added
    // value tips the summed hold over the ceiling, while staying under the
    // per-perk COUNT cap so it is provably the VALUE ceiling that bites.
    const boughtPerk = 'shield';
    const otherPerkIds = PERK_IDS.filter((id) => id !== boughtPerk);
    const inventory = Object.fromEntries(
      otherPerkIds.map((id) => [id, MAX_PERK_HOLD_PER_PERK]),
    );
    const heldValue = otherPerkIds.reduce(
      (sum, id) => sum + PERK_CATALOG[id].costKp * MAX_PERK_HOLD_PER_PERK,
      0,
    );
    const unitCost = PERK_CATALOG[boughtPerk].costKp;
    const qty = Math.floor((MAX_PERK_HOLD_VALUE_KP - heldValue) / unitCost) + 1;

    // The scenario only tests the VALUE ceiling if the tipping buy still fits
    // under the per-perk COUNT cap; assert that so a future constant change that
    // makes it infeasible fails loudly here instead of silently passing.
    expect(qty).toBeGreaterThan(0);
    expect(qty).toBeLessThanOrEqual(MAX_PERK_HOLD_PER_PERK);
    expect(evaluateHoldCap(inventory, boughtPerk, qty, unitCost)).toBe('total_value');
    // The value ceiling is anchored to the daily points cap by design.
    expect(MAX_PERK_HOLD_VALUE_KP).toBe(DAILY_POINTS_CAP);
  });

  it('treats a corrupt/negative stored count as zero (never bypasses the cap)', () => {
    expect(evaluateHoldCap({ shield: -5 as unknown as number }, 'shield', 6, 100)).toBeNull();
    expect(evaluateHoldCap({ shield: Number.NaN }, 'shield', 7, 100)).toBe('per_perk');
  });
});

describe('perks-core purchase cooldown', () => {
  it('scopes the cooldown doc to the member', () => {
    expect(purchaseCooldownDocId('user-1')).toBe('user-1');
  });

  it('blocks a buy inside the window and allows it after', () => {
    const now = 1_000_000;
    const windowMs = PERK_PURCHASE_COOLDOWN_SECONDS * 1000;
    expect(isWithinPurchaseCooldown(now - 1, now)).toBe(true);
    expect(isWithinPurchaseCooldown(now - (windowMs - 1), now)).toBe(true);
    expect(isWithinPurchaseCooldown(now - windowMs, now)).toBe(false);
    expect(isWithinPurchaseCooldown(null, now)).toBe(false);
  });

  it('purchaseCooldownBlocks fails CLOSED on a corrupt cooldown doc', () => {
    const now = 1_000_000;
    const windowMs = PERK_PURCHASE_COOLDOWN_SECONDS * 1000;
    // No doc = genuine first purchase = allowed.
    expect(purchaseCooldownBlocks(false, null, now)).toBe(false);
    // Doc exists with a valid, in-window timestamp = blocked; past the window = allowed.
    expect(purchaseCooldownBlocks(true, now - 1, now)).toBe(true);
    expect(purchaseCooldownBlocks(true, now - windowMs, now)).toBe(false);
    // Doc EXISTS but the timestamp is missing/invalid = fail closed (blocked), so
    // corrupt data cannot disable the anti-burst control.
    expect(purchaseCooldownBlocks(true, null, now)).toBe(true);
    expect(purchaseCooldownBlocks(true, Number.NaN, now)).toBe(true);
  });
});

describe('perks-core concurrent activation limit', () => {
  const none: ActivePerkEffects = { trap: false, shield: false, boost: false };
  const deploy = (active: ActivePerkEffects, kind: PerkKind) => activationAllowed(active, kind);

  it('caps distinct live effects at MAX_CONCURRENT_ACTIVE_PERKS (2)', () => {
    expect(MAX_CONCURRENT_ACTIVE_PERKS).toBe(2);
  });

  it('allows a deploy while under the limit', () => {
    expect(deploy(none, 'trap')).toBe(true);
    expect(deploy({ trap: true, shield: false, boost: false }, 'shield')).toBe(true);
  });

  it('refuses a NEW third distinct effect', () => {
    expect(deploy({ trap: true, shield: true, boost: false }, 'boost')).toBe(false);
    expect(deploy({ trap: true, shield: false, boost: true }, 'shield')).toBe(false);
  });

  it('always allows re-raising an already-active kind (replaces, adds nothing)', () => {
    expect(deploy({ trap: true, shield: true, boost: false }, 'shield')).toBe(true);
    expect(deploy({ trap: true, shield: true, boost: false }, 'trap')).toBe(true);
  });
});

// ===========================================================================
// PvP — DEPLOY/USE pure logic (Crown Hunt Shop PR3)
// ===========================================================================

import {
  BOOST_MULTIPLIER,
  MAX_ACTIVE_TRAPS_PER_USER,
  MAX_TRAP_DEPLOYS_PER_DAY,
  MAX_TRAP_EARN_KP_PER_DAY,
  MAX_TRAP_LOSS_KP_PER_DAY,
  MAX_VICTIMS_PER_TRAP,
  NEW_ACCOUNT_IMMUNITY_DAYS,
  TRAP_DRAIN_KP,
  TRAP_DURATION_HOURS,
  TRAP_RADIUS_METERS,
  TRAP_SELF_SPACING_METERS,
  VICTIM_COOLDOWN_HOURS,
  deployRecordDocId,
  hoursFromNow,
  isNewAccountImmune,
  isTimestampActive,
  isWithinTrapRadius,
  isWithinVictimCooldown,
  parseDeployPerkInput,
  resolveDrainAmount,
  scopeDeployKey,
  trapDeployCounterDocId,
  trapDocId,
  trapHasVictimRoom,
  trapVictimMarkerId,
} from './perks-core';

const HOUR = 60 * 60 * 1000;
const DAY = 24 * HOUR;

describe('PvP constants match the owner-approved spec', () => {
  it('pins every anti-abuse constant', () => {
    expect(TRAP_RADIUS_METERS).toBe(100);
    expect(TRAP_DRAIN_KP).toBe(15);
    expect(TRAP_DURATION_HOURS).toBe(6);
    expect(BOOST_MULTIPLIER).toBe(2);
    expect(MAX_ACTIVE_TRAPS_PER_USER).toBe(1);
    expect(MAX_TRAP_DEPLOYS_PER_DAY).toBe(3);
    expect(TRAP_SELF_SPACING_METERS).toBe(300);
    expect(MAX_VICTIMS_PER_TRAP).toBe(10);
    expect(MAX_TRAP_EARN_KP_PER_DAY).toBe(150);
    expect(MAX_TRAP_LOSS_KP_PER_DAY).toBe(45);
    expect(VICTIM_COOLDOWN_HOURS).toBe(2);
    expect(NEW_ACCOUNT_IMMUNITY_DAYS).toBe(7);
  });
});

describe('hoursFromNow', () => {
  it('adds the hours to now', () => {
    const now = new Date('2026-08-16T00:00:00Z');
    expect(hoursFromNow(now, 6).toISOString()).toBe('2026-08-16T06:00:00.000Z');
  });
});

describe('isNewAccountImmune', () => {
  const now = 10 * DAY;
  it('is immune inside the 7-day window', () => {
    expect(isNewAccountImmune(now - 6 * DAY, now)).toBe(true);
    expect(isNewAccountImmune(now - 1, now)).toBe(true);
  });
  it('is not immune at or past 7 days', () => {
    expect(isNewAccountImmune(now - 7 * DAY, now)).toBe(false);
    expect(isNewAccountImmune(now - 8 * DAY, now)).toBe(false);
  });
  it('is not immune when the creation time is unknown', () => {
    expect(isNewAccountImmune(null, now)).toBe(false);
    expect(isNewAccountImmune(Number.NaN, now)).toBe(false);
  });
});

describe('isWithinTrapRadius', () => {
  it('accepts at or inside 100 m', () => {
    expect(isWithinTrapRadius(0)).toBe(true);
    expect(isWithinTrapRadius(99.9)).toBe(true);
    expect(isWithinTrapRadius(100)).toBe(true);
  });
  it('rejects beyond 100 m and non-finite', () => {
    expect(isWithinTrapRadius(100.1)).toBe(false);
    expect(isWithinTrapRadius(Number.NaN)).toBe(false);
    expect(isWithinTrapRadius(-1)).toBe(false);
  });
});

describe('isWithinVictimCooldown', () => {
  const now = 100 * HOUR;
  it('is on cooldown inside 2h', () => {
    expect(isWithinVictimCooldown(now - HOUR, now)).toBe(true);
    expect(isWithinVictimCooldown(now - 1, now)).toBe(true);
  });
  it('is clear at or past 2h, or with no prior drain', () => {
    expect(isWithinVictimCooldown(now - 2 * HOUR, now)).toBe(false);
    expect(isWithinVictimCooldown(now - 3 * HOUR, now)).toBe(false);
    expect(isWithinVictimCooldown(null, now)).toBe(false);
  });
});

describe('isTimestampActive', () => {
  const now = 1000;
  it('is active only for a future, finite expiry', () => {
    expect(isTimestampActive(1001, now)).toBe(true);
    expect(isTimestampActive(1000, now)).toBe(false);
    expect(isTimestampActive(999, now)).toBe(false);
    expect(isTimestampActive(null, now)).toBe(false);
    expect(isTimestampActive(Number.POSITIVE_INFINITY, now)).toBe(false);
  });
});

describe('resolveDrainAmount', () => {
  it('drains the full 15 KP when nothing is capped', () => {
    expect(resolveDrainAmount({ victimBalance: 100, victimLossToday: 0, placerEarnToday: 0 })).toBe(15);
  });
  it('never exceeds the victim balance (ledger cannot go negative)', () => {
    expect(resolveDrainAmount({ victimBalance: 7, victimLossToday: 0, placerEarnToday: 0 })).toBe(7);
    expect(resolveDrainAmount({ victimBalance: 0, victimLossToday: 0, placerEarnToday: 0 })).toBe(0);
  });
  it('clamps to the victim daily loss room', () => {
    // 45 - 40 = 5 left today.
    expect(resolveDrainAmount({ victimBalance: 100, victimLossToday: 40, placerEarnToday: 0 })).toBe(5);
    expect(resolveDrainAmount({ victimBalance: 100, victimLossToday: 45, placerEarnToday: 0 })).toBe(0);
  });
  it('clamps to the placer daily earn room', () => {
    // 150 - 145 = 5 left today.
    expect(resolveDrainAmount({ victimBalance: 100, victimLossToday: 0, placerEarnToday: 145 })).toBe(5);
    expect(resolveDrainAmount({ victimBalance: 100, victimLossToday: 0, placerEarnToday: 150 })).toBe(0);
  });
  it('takes the tightest of every cap', () => {
    expect(resolveDrainAmount({ victimBalance: 3, victimLossToday: 43, placerEarnToday: 148 })).toBe(2);
  });
});

describe('trapHasVictimRoom', () => {
  it('has room below 10 distinct victims and none at/over', () => {
    expect(trapHasVictimRoom(0)).toBe(true);
    expect(trapHasVictimRoom(9)).toBe(true);
    expect(trapHasVictimRoom(10)).toBe(false);
    expect(trapHasVictimRoom(11)).toBe(false);
  });
});

describe('deploy doc-id builders', () => {
  it('scopes the deploy key deterministically and per-user', () => {
    const a = scopeDeployKey('u1', 'k1');
    expect(a).toBe(scopeDeployKey('u1', 'k1'));
    expect(a).not.toBe(scopeDeployKey('u2', 'k1'));
    expect(a).not.toBe(scopeDeployKey('u1', 'k2'));
    expect(a).toMatch(/^[0-9a-f]{64}$/);
  });
  it('namespaces the trap doc, deploy record, and per-(trap,victim) marker', () => {
    const scoped = scopeDeployKey('u1', 'k1');
    expect(trapDocId(scoped)).toMatch(/^trap_[0-9a-f]{64}$/);
    expect(deployRecordDocId(scoped)).toMatch(/^deploy_[0-9a-f]{64}$/);
    const m = trapVictimMarkerId('trap1', 'victim1');
    expect(m).toBe(trapVictimMarkerId('trap1', 'victim1'));
    expect(m).not.toBe(trapVictimMarkerId('trap1', 'victim2'));
    expect(m).not.toBe(trapVictimMarkerId('trap2', 'victim1'));
  });
  it('builds day-scoped counter ids', () => {
    expect(trapDeployCounterDocId('u1', '2026-08-16')).toBe('u1__2026-08-16');
  });
});

describe('parseDeployPerkInput', () => {
  it('accepts a trap with coordinates', () => {
    const r = parseDeployPerkInput({ perkId: 'spike_strip', latitude: 59.3, longitude: 18.1, idempotencyKey: 'k1' });
    expect(r.ok).toBe(true);
  });
  it('accepts shield/boost without coordinates', () => {
    expect(parseDeployPerkInput({ perkId: 'shield', idempotencyKey: 'k1' }).ok).toBe(true);
    expect(parseDeployPerkInput({ perkId: 'boost', idempotencyKey: 'k1' }).ok).toBe(true);
  });
  it('rejects a missing perkId or idempotency key', () => {
    expect(parseDeployPerkInput({ idempotencyKey: 'k1' }).ok).toBe(false);
    expect(parseDeployPerkInput({ perkId: 'shield' }).ok).toBe(false);
  });
  it('rejects out-of-range coordinates and unknown extra fields', () => {
    expect(parseDeployPerkInput({ perkId: 'spike_strip', latitude: 999, longitude: 0, idempotencyKey: 'k1' }).ok).toBe(false);
    expect(parseDeployPerkInput({ perkId: 'shield', idempotencyKey: 'k1', costKp: 0 }).ok).toBe(false);
  });
});
