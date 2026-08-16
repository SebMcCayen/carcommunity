/**
 * Kronjakt SHOP — perk catalog + pure logic (Crown Hunt Shop PR1, backend core).
 *
 * This is the FIRST member-facing SINK for Kronpoäng (KP): every other points
 * surface EARNS (crown claims, the economy rules); this one SPENDS. A member
 * buys a perk with KP and it lands in their backend-only `perkInventory`. The
 * perks are NOT deployed or used yet — traps, shields and the boost multiplier
 * are activated in later PRs (PvP/deployPerk). This PR only sells them.
 *
 * PURE module — no Firebase Admin SDK, mirroring points-economy-core.ts:
 *
 *  1. SERVER-AUTHORITATIVE. Every point-bearing value (costKp, and the effect
 *     parameters that later PRs will read) is a SERVER CONSTANT in this table.
 *     No caller may pass a cost, a radius, a drain, a duration or a multiplier
 *     that reaches the ledger or the inventory — the buyPerk callable derives
 *     the amount to debit from THIS table and the client-supplied quantity
 *     only, never from a client-supplied price.
 *  2. IDEMPOTENT. A purchase derives a deterministic idempotency key from
 *     (uid, client key); the key IS the ledger entry document ID, so a retried
 *     or double-tapped buy debits once and increments the inventory once.
 *  3. DISPLAY IS A MIRROR. `buildPerkCatalogDoc` produces the member-readable
 *     `config/perkCatalog` document (name, icon key, cost, blurb) — a DISPLAY
 *     copy of these constants. The authoritative values live HERE; the mirror
 *     is seeded from them and the client never trusts a price it reads.
 *
 * Swedish is the member-facing language (parity with the rest of Kronjakt).
 */

import { z } from 'zod';
import { createHash } from 'node:crypto';
import { isFirestoreSafeId } from '../points/points-core';

// ---------------------------------------------------------------------------
// Feature flag (contract default OFF — the whole shop is dark until enabled)
// ---------------------------------------------------------------------------

export const CROWN_HUNT_PERKS_FLAG_KEY = 'crownHuntPerks';
export const CROWN_HUNT_PERKS_FLAG_DEFAULT = false;

// ---------------------------------------------------------------------------
// Structured rejection reasons (HttpsError `details.reason`)
// ---------------------------------------------------------------------------

/**
 * `details.reason` discriminators buyPerk attaches to its `failed-precondition`
 * rejections so the client can tell "not enough Kronpoäng" apart from "the shop
 * refused the buy" WITHOUT substring-matching a (localizable) message. The
 * Android client mirrors these string values in PerkShopRepository.kt.
 */
export const PERK_PURCHASE_REASON_INSUFFICIENT_FUNDS = 'insufficient_funds';
export const PERK_PURCHASE_REASON_SHOP_UNAVAILABLE = 'shop_unavailable';

// ---------------------------------------------------------------------------
// The catalog
// ---------------------------------------------------------------------------

export const PERK_IDS = ['spike_strip', 'shield', 'boost'] as const;
export type PerkId = (typeof PERK_IDS)[number];

/**
 * `trap` perks are DEPLOYED to the map to drain a rival's KP (PvP, later PR);
 * `shield` protects the holder from traps for a window; `boost` doubles the KP
 * a claim awards for a window. The kind drives which activation path a later
 * PR runs — nothing in THIS PR reads it beyond the display mirror.
 */
export type PerkKind = 'trap' | 'shield' | 'boost';

interface PerkBase {
  perkId: PerkId;
  kind: PerkKind;
  /** Server-authoritative KP price for ONE unit. */
  costKp: number;
  /** Swedish display name (mirrored to config/perkCatalog). */
  name: string;
  /** Stable icon key the client maps to a drawable (mirrored). */
  iconKey: string;
  /** Swedish one-line blurb (mirrored). */
  blurb: string;
  /**
   * OPTIONAL per-member per-day purchase cap. Reserved for later PRs — no perk
   * sets it today, so it is defined-but-unenforced in this shop-core PR (the
   * buy path takes no daily-counter read guard). Add enforcement WITH the first
   * perk that needs a cap.
   */
  dailyPurchaseCap?: number;
}

/** Spikmatta — drains KP from a rival who drives into its radius. */
export interface TrapPerk extends PerkBase {
  kind: 'trap';
  /** Effect radius in metres (stored now, activated in the PvP PR). */
  radiusMeters: number;
  /** KP drained from a caught rival (stored now, used in the PvP PR). */
  drainKp: number;
  /** How long a deployed trap stays live, in hours. */
  durationHours: number;
}

/** Sköld — protects the holder from traps for a window. */
export interface ShieldPerk extends PerkBase {
  kind: 'shield';
  /** How long the shield lasts once activated, in hours. */
  durationHours: number;
}

/** Dubbla Poäng — multiplies claim KP for a window. */
export interface BoostPerk extends PerkBase {
  kind: 'boost';
  /** Award multiplier while active (stored now, applied in a later PR). */
  multiplier: number;
  /** How long the boost lasts once activated, in hours. */
  durationHours: number;
}

export type PerkDefinition = TrapPerk | ShieldPerk | BoostPerk;

/**
 * Owner-approved constants (Crown Hunt Shop, 2026-08). Costs and effect
 * parameters are FINAL server values — do not let any of these be reached by a
 * client-supplied field.
 */
export const PERK_CATALOG: Readonly<Record<PerkId, PerkDefinition>> = {
  spike_strip: {
    perkId: 'spike_strip',
    kind: 'trap',
    costKp: 150,
    name: 'Spikmatta',
    iconKey: 'perk_spike_strip',
    blurb: 'Placera en fälla som tömmer kronpoäng från rivaler som kör in i den.',
    radiusMeters: 100,
    drainKp: 15,
    durationHours: 6,
  },
  shield: {
    perkId: 'shield',
    kind: 'shield',
    costKp: 100,
    name: 'Sköld',
    iconKey: 'perk_shield',
    blurb: 'Skydda dig mot fällor under en period.',
    durationHours: 3,
  },
  boost: {
    perkId: 'boost',
    kind: 'boost',
    costKp: 120,
    name: 'Dubbla Poäng',
    iconKey: 'perk_boost',
    blurb: 'Dubbla kronpoängen på dina fångster under en period.',
    multiplier: 2,
    durationHours: 1,
  },
} as const;

export const PERK_DEFINITIONS: readonly PerkDefinition[] = PERK_IDS.map((id) => PERK_CATALOG[id]);

// ---------------------------------------------------------------------------
// Pure lookups / validation
// ---------------------------------------------------------------------------

export function isPerkId(value: unknown): value is PerkId {
  return typeof value === 'string' && Object.hasOwn(PERK_CATALOG, value);
}

/** The perk definition, or undefined for an unknown id. */
export function perkById(perkId: string): PerkDefinition | undefined {
  return isPerkId(perkId) ? PERK_CATALOG[perkId] : undefined;
}

/** A known perk that may currently be bought. Today every catalog perk is. */
export function isBuyable(perkId: string): boolean {
  return isPerkId(perkId);
}

/**
 * Total KP cost for `qty` units of a perk, from the SERVER constant only.
 * Returns null for an unknown perk or a non-positive-integer quantity, so the
 * caller can never derive a price from client input. A safe-integer overflow
 * guard keeps the product a Firestore-safe integer.
 */
export function perkCost(perkId: string, qty: number): number | null {
  const perk = perkById(perkId);
  if (!perk) return null;
  if (!Number.isInteger(qty) || qty < 1) return null;
  const total = perk.costKp * qty;
  return Number.isSafeInteger(total) ? total : null;
}

// ---------------------------------------------------------------------------
// Idempotency scoping (mirrors crownhunt-core.scopeClaimIdempotencyKey)
// ---------------------------------------------------------------------------

/**
 * Scopes a client idempotency key to the buyer via SHA-256 — the hex digest is
 * Firestore-safe by construction and namespaced (`perk`) so a key reused
 * across the claim flows and the shop cannot replay the other's result.
 */
export function scopePerkPurchaseKey(userId: string, idempotencyKey: string): string {
  return createHash('sha256')
    .update('perk')
    .update(':')
    .update(userId)
    .update(':')
    .update(idempotencyKey)
    .digest('hex');
}

/** Ledger idempotency key for the purchase's KP debit (Firestore-safe). */
export function perkPurchaseLedgerKey(scopedKey: string): string {
  return `perk-purchase_${scopedKey}`;
}

// ---------------------------------------------------------------------------
// buyPerk input
// ---------------------------------------------------------------------------

/** A single purchase is bounded so one call cannot debit an unbounded amount. */
export const MAX_PERK_PURCHASE_QTY = 10;

const buyPerkInputSchema = z
  .object({
    perkId: z.string().trim().min(1).max(64),
    qty: z.number().int().min(1).max(MAX_PERK_PURCHASE_QTY).optional().default(1),
    idempotencyKey: z
      .string()
      .trim()
      .min(1)
      .max(300)
      .refine(isFirestoreSafeId, { message: 'idempotencyKey is not a valid document ID.' }),
  })
  .strict();

export interface BuyPerkInput {
  perkId: string;
  qty: number;
  idempotencyKey: string;
}

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

export function parseBuyPerkInput(data: unknown): ParseResult<BuyPerkInput> {
  const result = buyPerkInputSchema.safeParse(data ?? {});
  if (!result.success) {
    return {
      ok: false,
      message: `Expected { perkId: string, qty?: 1..${MAX_PERK_PURCHASE_QTY}, idempotencyKey: string }.`,
    };
  }
  return { ok: true, input: result.data };
}

// ---------------------------------------------------------------------------
// Display mirror (config/perkCatalog) — member-readable, DISPLAY ONLY
// ---------------------------------------------------------------------------

export interface PerkCatalogEntry {
  perkId: PerkId;
  kind: PerkKind;
  name: string;
  iconKey: string;
  costKp: number;
  blurb: string;
}

export interface PerkCatalogDoc {
  /** Bumped when the display shape changes; clients may branch on it. */
  version: number;
  perks: PerkCatalogEntry[];
}

export const PERK_CATALOG_DOC_VERSION = 1;

/**
 * The member-readable `config/perkCatalog` document — a DISPLAY MIRROR of the
 * constants above. Effect parameters (radius/drain/duration/multiplier) are
 * deliberately NOT mirrored: the client only needs to render the shop, and the
 * authoritative values stay server-side. The seeder writes exactly this.
 */
export function buildPerkCatalogDoc(): PerkCatalogDoc {
  return {
    version: PERK_CATALOG_DOC_VERSION,
    perks: PERK_DEFINITIONS.map((perk) => ({
      perkId: perk.perkId,
      kind: perk.kind,
      name: perk.name,
      iconKey: perk.iconKey,
      costKp: perk.costKp,
      blurb: perk.blurb,
    })),
  };
}
