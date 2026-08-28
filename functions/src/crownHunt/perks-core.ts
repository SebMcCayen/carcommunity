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
import { DAILY_POINTS_CAP } from '../points/points-economy-core';

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
/**
 * The buy would push the member's HELD count of this perk past
 * {@link MAX_PERK_HOLD_PER_PERK}, or their total banked perk VALUE past
 * {@link MAX_PERK_HOLD_VALUE_KP}. A hold-cap rejection is distinct from
 * insufficient-funds: the member could afford it, but a perk is a working stock,
 * not a war chest, so the shop refuses to let them hoard.
 */
export const PERK_PURCHASE_REASON_HOLD_CAP = 'hold_cap_reached';
/**
 * Log-only fallback reason for a `failed-precondition` the buy path did NOT
 * itself reason-code — chiefly the ledger's suspended/deleted-account guard,
 * which reaches the buyPerk catch without a `details.reason`. Distinct from
 * {@link PERK_PURCHASE_REASON_SHOP_UNAVAILABLE} so operational log analysis does
 * not mislabel an account-state rejection as the shop being off. Never surfaced
 * to the client as a typed reason (it is a diagnostic label only).
 */
export const PERK_PURCHASE_REASON_PRECONDITION_OTHER = 'precondition_other';

/**
 * `details.reason` discriminators deployPerk attaches to the NEW anti-abuse
 * rejections so the client can show a specific message. The pre-existing deploy
 * rejections (flag off, unknown perk, no inventory, trap 1-active/3-per-day/
 * 300 m-spacing) still carry NO reason and collapse to one "couldn't activate"
 * family on the client — only the limits added in this PR are discriminated.
 */
export const PERK_DEPLOY_REASON_ACTIVATION_LIMIT = 'activation_limit';

/**
 * `details.reason` discriminator deployPerk attaches when a trap is refused for
 * being too close to an active/imminent event — the anti-griefing rule that
 * stops a member spiking a gathering spot. Mirrored client-side so the app can
 * show the specific "too close to an event" message (see PerkDeployMenu.kt).
 */
export const PERK_DEPLOY_REASON_EVENT_TOO_CLOSE = 'event_too_close';

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
  /** Swedish display name (mirrored to config/perkCatalog as `name`). */
  name: string;
  /**
   * English display name (mirrored to config/perkCatalog as `nameEn`). EVERY
   * perk carries BOTH a Swedish and an English name so the shop renders in the
   * member's chosen app language; a perk with only one name is a catalog bug the
   * unit tests reject.
   */
  nameEn: string;
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
    nameEn: 'Spike strip',
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
    nameEn: 'Shield',
    iconKey: 'perk_shield',
    blurb: 'Skydda dig mot fällor under en period.',
    durationHours: 3,
  },
  boost: {
    perkId: 'boost',
    kind: 'boost',
    costKp: 120,
    name: 'Dubbla Poäng',
    nameEn: 'Double points',
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
  /** Swedish display name. */
  name: string;
  /** English display name (the client picks by app language). */
  nameEn: string;
  iconKey: string;
  costKp: number;
  blurb: string;
}

export interface PerkCatalogDoc {
  /** Bumped when the display shape changes; clients may branch on it. */
  version: number;
  perks: PerkCatalogEntry[];
}

/**
 * Bumped 1 → 2 when the mirror gained the bilingual `nameEn` field. A client on
 * an older shape simply falls back to the Swedish `name` (and its own localized
 * per-perk string resources), so the bump is informational, not a hard gate.
 */
export const PERK_CATALOG_DOC_VERSION = 2;

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
      nameEn: perk.nameEn,
      iconKey: perk.iconKey,
      costKp: perk.costKp,
      blurb: perk.blurb,
    })),
  };
}

// ===========================================================================
// PvP — DEPLOY / USE side (Crown Hunt Shop PR3). Pure constants + math only.
//
// buyPerk (PR1) SELLS perks into perkInventory; this section is the maths and
// the anti-abuse arithmetic behind USING one: dropping a trap, raising a
// shield, arming the boost multiplier, and the trap "drain" that transfers KP
// from a rival who drives into an armed trap. Every point-bearing and every
// safety-bearing value is a SERVER CONSTANT here — no radius, drain, duration,
// cap, cooldown or immunity window is ever taken from client input. The
// Firestore-touching side (the callable, the drain transaction, the boost
// reader) lives in deployPerk.ts / pvp-drain.ts; this module stays PURE so it
// is unit-tested in perks-core.test.ts with no emulator.
// ===========================================================================

// ---------------------------------------------------------------------------
// Effect parameters — re-exported from the authoritative catalog so the deploy
// path and the tests name a constant instead of reaching into PERK_CATALOG.
// ---------------------------------------------------------------------------

// The catalog is typed as the PerkDefinition UNION, so narrow each entry to its
// concrete kind before reading a kind-specific effect parameter.
const SPIKE_STRIP = PERK_CATALOG.spike_strip as TrapPerk;
const SHIELD = PERK_CATALOG.shield as ShieldPerk;
const BOOST = PERK_CATALOG.boost as BoostPerk;

/** Trap effect radius in metres (a rival within this of an armed trap drains). */
export const TRAP_RADIUS_METERS = SPIKE_STRIP.radiusMeters; // 100
/** KP moved from victim → placer on a single successful drain. */
export const TRAP_DRAIN_KP = SPIKE_STRIP.drainKp; // 15
/** How long a deployed trap stays `armed` before it expires. */
export const TRAP_DURATION_HOURS = SPIKE_STRIP.durationHours; // 6
/** How long a raised shield protects its holder from traps. */
export const SHIELD_DURATION_HOURS = SHIELD.durationHours; // 3
/** How long an armed boost doubles the KP the holder's crown claims award. */
export const BOOST_DURATION_HOURS = BOOST.durationHours; // 1
/** Award multiplier applied to a crown claim while a boost is active. */
export const BOOST_MULTIPLIER = BOOST.multiplier; // 2

// ---------------------------------------------------------------------------
// Anti-abuse constants (owner-approved). None reach a client; changing PvP
// balance means editing here, nowhere else.
// ---------------------------------------------------------------------------

/** A member may have at most this many traps `armed` at once. */
export const MAX_ACTIVE_TRAPS_PER_USER = 1;
/** A member may DEPLOY at most this many traps per UTC day. */
export const MAX_TRAP_DEPLOYS_PER_DAY = 3;
/** Minimum spacing between a member's own armed traps, in metres. */
export const TRAP_SELF_SPACING_METERS = 300;

// --- Event exclusion (anti-griefing) -------------------------------------
// A trap must never be placed on top of a car meet: otherwise everyone
// attending an event could spike the gathering spot. A deploy is refused when
// it lands within EVENT_TRAP_EXCLUSION_RADIUS_METERS of a published/completed
// event that is currently within its blocking window. The window opens 8 h
// BEFORE the event starts — a trap lives 6 h, so a trap armed inside the
// pre-window would still be armed when people gather — and closes 3 h after
// the event's effective end (endsAt, or startsAt when no end is set).

/** A trap must be at least this far from an active event, in metres. */
export const EVENT_TRAP_EXCLUSION_RADIUS_METERS = 300;
/** Traps are blocked from this long BEFORE an event's start (covers the 6 h trap life). */
export const EVENT_TRAP_BLOCK_BEFORE_START_MS = 8 * 60 * 60 * 1000;
/** Traps stay blocked for this long AFTER an event's effective end. */
export const EVENT_TRAP_BLOCK_AFTER_END_MS = 3 * 60 * 60 * 1000;
/** A single trap may drain at most this many DISTINCT victims before it stops. */
export const MAX_VICTIMS_PER_TRAP = 10;
/** A placer may EARN at most this much KP from traps per UTC day. */
export const MAX_TRAP_EARN_KP_PER_DAY = 150;
/** A victim may LOSE at most this much KP to traps per UTC day. */
export const MAX_TRAP_LOSS_KP_PER_DAY = 45;
/** A victim cannot be drained again until this many hours have passed. */
export const VICTIM_COOLDOWN_HOURS = 2;
/** New accounts are immune as victims for their first this-many days. */
export const NEW_ACCOUNT_IMMUNITY_DAYS = 7;

// ===========================================================================
// PRICING ALGORITHM — cost as a function of each perk's KP "power"
//
// A perk is priced at (roughly) the MAXIMUM KP it can swing per deployed unit —
// its "power" — with a flat defensive FLOOR. The principle keeps the shop a
// closed economy: a perk can never be a free arbitrage, because a unit costs
// about as much KP as the best case it can move, so a member profits only by
// out-PLAYING a rival (better placement, timing), never by simply buying.
//
//   costKp = max( PERK_BASE_COST_KP, perkPowerKp(perk) )
//
// perkPowerKp per kind, from the SAME server balance constants the effect uses:
//   • TRAP  — the KP one trap can earn its placer: TRAP_DRAIN_KP × the distinct-
//     victim cap, clamped by the placer's daily trap-earn cap. 15 × 10 = 150,
//     clamped to MAX_TRAP_EARN_KP_PER_DAY (150) → 150.
//   • BOOST — the extra KP a 2× window yields at a representative crown-collect
//     rate (BOOST_POWER_KP, modeled below) → 120.
//   • SHIELD — the KP loss it can AVOID over its window: the victim daily-loss
//     cap MAX_TRAP_LOSS_KP_PER_DAY (45); below the floor, so it prices at the
//     PERK_BASE_COST_KP floor → 100.
//
// The three owner-approved catalog costs (spike_strip 150, shield 100,
// boost 120) are EXACTLY this formula's output — a unit test pins
// costKp === referencePerkCostKp(perk) for every perk, so a future price or
// balance change must move together with its rationale, never drift silently.
// ===========================================================================

/** The floor every perk prices at, even a purely defensive one. */
export const PERK_BASE_COST_KP = 100;

/**
 * Modeled extra KP a single 2× BOOST yields over its {@link BOOST_DURATION_HOURS}
 * window: a dedicated player collecting ~5 crowns in the hour at the ~24 KP
 * average crown value earns ~120 KP of claims, which the 2× doubles — i.e.
 * ~120 KP of BONUS. Named here (not a magic number) because it is the boost's
 * "power" input to {@link referencePerkCostKp}. Changing the boost's real value
 * (duration/multiplier) should be reflected here so its price tracks its power.
 */
export const BOOST_POWER_KP = 120;

const MS_PER_HOUR = 60 * 60 * 1000;
const MS_PER_DAY = 24 * MS_PER_HOUR;

/** A `Date` `hours` hours after `now` (server-computed expiry). */
export function hoursFromNow(now: Date, hours: number): Date {
  return new Date(now.getTime() + hours * MS_PER_HOUR);
}

/** True while an account is inside its new-account victim-immunity window. */
export function isNewAccountImmune(createdAtMs: number | null, nowMs: number): boolean {
  if (createdAtMs === null || !Number.isFinite(createdAtMs)) return false;
  return nowMs - createdAtMs < NEW_ACCOUNT_IMMUNITY_DAYS * MS_PER_DAY;
}

/** True when a victim is close enough to an armed trap to be drained. */
export function isWithinTrapRadius(distanceMeters: number): boolean {
  return Number.isFinite(distanceMeters) && distanceMeters >= 0 && distanceMeters <= TRAP_RADIUS_METERS;
}

/** True while a victim is inside the post-drain cooldown (drain refused). */
export function isWithinVictimCooldown(lastDrainAtMs: number | null, nowMs: number): boolean {
  if (lastDrainAtMs === null || !Number.isFinite(lastDrainAtMs)) return false;
  return nowMs - lastDrainAtMs < VICTIM_COOLDOWN_HOURS * MS_PER_HOUR;
}

/** True when an epoch-ms expiry is still in the future at `nowMs`. */
export function isTimestampActive(expiresAtMs: number | null, nowMs: number): boolean {
  return expiresAtMs !== null && Number.isFinite(expiresAtMs) && expiresAtMs > nowMs;
}

/**
 * The KP a single drain actually moves — {@link TRAP_DRAIN_KP} clamped so no
 * cap can be breached: never more than the victim's balance (the ledger cannot
 * go negative), never past the victim's remaining daily loss room, never past
 * the placer's remaining daily earn room. Returns a non-negative integer; 0
 * means "do not drain" (a cap is already spent or the victim is broke).
 */
export function resolveDrainAmount(params: {
  victimBalance: number;
  victimLossToday: number;
  placerEarnToday: number;
}): number {
  const lossRoom = Math.max(0, MAX_TRAP_LOSS_KP_PER_DAY - Math.max(0, params.victimLossToday));
  const earnRoom = Math.max(0, MAX_TRAP_EARN_KP_PER_DAY - Math.max(0, params.placerEarnToday));
  const balance = Math.max(0, Math.floor(params.victimBalance));
  const drain = Math.min(TRAP_DRAIN_KP, balance, lossRoom, earnRoom);
  return Number.isFinite(drain) && drain > 0 ? Math.floor(drain) : 0;
}

/** True when a trap has room for another distinct victim. */
export function trapHasVictimRoom(victimCount: number): boolean {
  const n = Number.isFinite(victimCount) ? victimCount : 0;
  return n < MAX_VICTIMS_PER_TRAP;
}

// ---------------------------------------------------------------------------
// Pricing — the perk-power → cost curve (see the PRICING ALGORITHM block above)
// ---------------------------------------------------------------------------

/**
 * The maximum KP a single deployed unit of `perk` can SWING — its "power". Used
 * only to derive the reference price; the actual debit is always the catalog
 * `costKp`. Defensive perks report the KP they can AVOID losing.
 */
export function perkPowerKp(perk: PerkDefinition): number {
  switch (perk.kind) {
    case 'trap':
      // KP one trap can earn its placer, clamped by the daily trap-earn cap.
      return Math.min(perk.drainKp * MAX_VICTIMS_PER_TRAP, MAX_TRAP_EARN_KP_PER_DAY);
    case 'boost':
      return BOOST_POWER_KP;
    case 'shield':
      // KP loss the shield can avoid over its window (the victim daily-loss cap).
      return MAX_TRAP_LOSS_KP_PER_DAY;
  }
  // Exhaustiveness guard (NO `default`): adding a PerkKind narrows `perk` to that
  // new member here instead of `never` and fails to compile, forcing a deliberate
  // power definition rather than silently pricing the new perk at the floor.
  const exhaustive: never = perk;
  return exhaustive;
}

/**
 * The algorithmic reference price for `perk`: `max(floor, power)`. The catalog
 * `costKp` MUST equal this for every perk (pinned by perks-core.test.ts), so the
 * owner-approved prices and their economic rationale can never silently diverge.
 */
export function referencePerkCostKp(perk: PerkDefinition): number {
  return Math.max(PERK_BASE_COST_KP, perkPowerKp(perk));
}

// ---------------------------------------------------------------------------
// HOLD CAP — how many perks a member may BUY / HOLD at once
//
// A perk is a working stock, not a war chest. Two independent ceilings bound the
// inventory so a member can never bank more perk-power than a healthy working
// stock; buyPerk enforces BOTH atomically and FAILS CLOSED
// (PERK_PURCHASE_REASON_HOLD_CAP) when either would be breached. There is NO
// purchase cooldown — buys are back-to-back — so this per-perk cap is the sole
// brake on stockpiling one perk.
//
//   1. PER-PERK COUNT — MAX_PERK_HOLD_PER_PERK. A flat "max 3 of each perk" working
//      stock: a member may hold up to three of any single perk at once and buy
//      them back-to-back, but not hoard a fourth until they use one. Kept flat
//      (not derived from deploy throughput) so the shop rule is simple to state
//      in the UI: "at most 3 of each perk".
//   2. TOTAL VALUE — MAX_PERK_HOLD_VALUE_KP. The summed (count × costKp) a member
//      may hold is one capped DAY of earnings (DAILY_POINTS_CAP), so the
//      aggregate banked perk-power tracks the economy regardless of how cheap any
//      single perk is. Anchoring to the same DAILY_POINTS_CAP the crown economy
//      uses keeps the shop balanced against everything else that spends KP. At
//      3-of-each this value cap never binds — it stays a harmless backstop.
// ---------------------------------------------------------------------------

/**
 * Max units of ONE perk a member may hold at once (flat "3 of each perk").
 *
 * The buyPerk SERVER hold-cap message derives its number from this constant, so
 * changing it keeps the authoritative cap and the server message in sync. The
 * ANDROID client's localized `shopErrorHoldCap` string (contracts/localization
 * sv.json + en.json) hardcodes the number "3" as a static display resource — if
 * this value changes, UPDATE THOSE SV/EN STRINGS too so the client copy matches.
 */
export const MAX_PERK_HOLD_PER_PERK = 3;

/** Max summed KP VALUE of all held perks — one capped day of earnings. */
export const MAX_PERK_HOLD_VALUE_KP = DAILY_POINTS_CAP;

/**
 * The Swedish member-facing message buyPerk throws when a buy hits the hold cap.
 * The cap number is DERIVED from {@link MAX_PERK_HOLD_PER_PERK} (not hardcoded)
 * so the authoritative cap and its message can never silently drift apart — a
 * unit test pins that the message contains the constant.
 */
export function perkHoldCapRejectionMessage(): string {
  return `Du kan ha max ${MAX_PERK_HOLD_PER_PERK} av varje perk just nu. Använd några först.`;
}

/** A buy's held-inventory snapshot, as the `{ perkId: count }` map buyPerk reads. */
export type PerkInventoryCounts = Readonly<Record<string, number>>;

export type HoldCapRejection = 'per_perk' | 'total_value';

/**
 * PURE hold-cap check, run inside the buy transaction against the member's
 * current inventory. Returns the reason a buy of `qty` × `perkId` must be
 * refused, or null when it fits under BOTH ceilings. `costPerUnit` is the
 * server-authoritative unit cost (never client-supplied).
 *
 * Non-numeric / negative stored counts are treated as 0 (defensive — a corrupt
 * inventory value never lets a member bypass the cap or wrongly get blocked).
 */
export function evaluateHoldCap(
  inventory: PerkInventoryCounts,
  perkId: string,
  qty: number,
  costPerUnit: number,
): HoldCapRejection | null {
  const safeCount = (value: unknown): number => {
    const n = typeof value === 'number' && Number.isFinite(value) ? Math.floor(value) : 0;
    return n > 0 ? n : 0;
  };

  const currentOfPerk = safeCount(inventory[perkId]);
  if (currentOfPerk + qty > MAX_PERK_HOLD_PER_PERK) {
    return 'per_perk';
  }

  // Total held VALUE after this buy. The value of OTHER perks uses their own
  // catalog cost; the bought perk uses the server unit cost passed in.
  let heldValueAfter = costPerUnit * (currentOfPerk + qty);
  for (const id of PERK_IDS) {
    if (id === perkId) continue;
    const perk = PERK_CATALOG[id];
    heldValueAfter += perk.costKp * safeCount(inventory[id]);
  }
  if (heldValueAfter > MAX_PERK_HOLD_VALUE_KP) {
    return 'total_value';
  }
  return null;
}

// ---------------------------------------------------------------------------
// CONCURRENT ACTIVATION LIMIT — how many effects may be LIVE at once
//
// Independent of the hold cap (how many you OWN): this bounds how many DISTINCT
// perk effects a member may have simultaneously ACTIVE — an armed trap, a raised
// shield, an armed boost. Being fully loaded (all three at once) is denied so a
// member must choose offense vs defense rather than stack everything.
// deployPerk enforces it in the deploy transaction and FAILS CLOSED
// (PERK_DEPLOY_REASON_ACTIVATION_LIMIT). Re-raising an already-active kind does
// NOT count as a new activation (it replaces, not adds), so it is never blocked
// by this limit.
// ---------------------------------------------------------------------------

/** Max DISTINCT perk effects (trap / shield / boost) live at the same time. */
export const MAX_CONCURRENT_ACTIVE_PERKS = 2;

/** Which of a member's perk effects are currently live. */
export interface ActivePerkEffects {
  trap: boolean;
  shield: boolean;
  boost: boolean;
}

/**
 * PURE concurrent-activation check. Given which effects are already live and the
 * `kind` about to deploy, returns true when the deploy is allowed. A deploy of a
 * kind that is ALREADY active is always allowed (it re-raises, adding no new
 * distinct effect); a deploy of a NEW kind is refused once the count of live
 * distinct effects has reached {@link MAX_CONCURRENT_ACTIVE_PERKS}.
 */
export function activationAllowed(active: ActivePerkEffects, kind: PerkKind): boolean {
  const liveCount = (active.trap ? 1 : 0) + (active.shield ? 1 : 0) + (active.boost ? 1 : 0);
  const kindAlreadyActive =
    (kind === 'trap' && active.trap) ||
    (kind === 'shield' && active.shield) ||
    (kind === 'boost' && active.boost);
  if (kindAlreadyActive) return true;
  return liveCount < MAX_CONCURRENT_ACTIVE_PERKS;
}

// ---------------------------------------------------------------------------
// Deterministic document IDs (Firestore-safe by construction). All backend-only
// collections — a client can neither read nor forge these.
// ---------------------------------------------------------------------------

function sha256(...parts: string[]): string {
  const hash = createHash('sha256');
  for (const part of parts) {
    hash.update(part);
    hash.update('\0');
  }
  return hash.digest('hex');
}

/**
 * Scopes a client deploy idempotency key to the actor — the deploy record doc
 * ID, namespaced `deploy` so a key reused across buy/deploy cannot replay the
 * other's outcome. A retried deploy hits the same record and is a no-op.
 */
export function scopeDeployKey(userId: string, idempotencyKey: string): string {
  return sha256('deploy', userId, idempotencyKey);
}

/** activePerks (trap) document ID for an idempotent deploy. */
export function trapDocId(scopedDeployKey: string): string {
  return `trap_${scopedDeployKey}`;
}

/** perkDeploys guard/audit doc ID for an idempotent deploy of any perk kind. */
export function deployRecordDocId(scopedDeployKey: string): string {
  return `deploy_${scopedDeployKey}`;
}

/** perkTrapVictims marker — one per (trap, victim); create-if-absent = once-per-trap. */
export function trapVictimMarkerId(trapId: string, victimUid: string): string {
  return sha256('trapvictim', trapId, victimUid);
}

/** perkDrainCooldowns doc ID — one per victim (global 2h cooldown). */
export function victimCooldownDocId(victimUid: string): string {
  return victimUid;
}

/** perkTrapDeploys counter doc ID — trap deploys by a member on a UTC day. */
export function trapDeployCounterDocId(uid: string, dayKey: string): string {
  return `${uid}__${dayKey}`;
}

/** perkTrapEarn counter doc ID — KP a placer earned from traps on a UTC day. */
export function trapEarnCounterDocId(uid: string, dayKey: string): string {
  return `${uid}__${dayKey}`;
}

/** perkTrapLoss counter doc ID — KP a victim lost to traps on a UTC day. */
export function trapLossCounterDocId(uid: string, dayKey: string): string {
  return `${uid}__${dayKey}`;
}

// ---------------------------------------------------------------------------
// deployPerk input
// ---------------------------------------------------------------------------

const deployPerkInputSchema = z
  .object({
    perkId: z.string().trim().min(1).max(64),
    // Required for a trap (dropped at the caller's GPS); ignored for shield/boost.
    latitude: z.number().finite().gte(-90).lte(90).optional(),
    longitude: z.number().finite().gte(-180).lte(180).optional(),
    idempotencyKey: z
      .string()
      .trim()
      .min(1)
      .max(300)
      .refine(isFirestoreSafeId, { message: 'idempotencyKey is not a valid document ID.' }),
  })
  .strict();

export interface DeployPerkInput {
  perkId: string;
  latitude?: number;
  longitude?: number;
  idempotencyKey: string;
}

export function parseDeployPerkInput(data: unknown): ParseResult<DeployPerkInput> {
  const result = deployPerkInputSchema.safeParse(data ?? {});
  if (!result.success) {
    return {
      ok: false,
      message: 'Expected { perkId: string, latitude?, longitude?, idempotencyKey: string }.',
    };
  }
  return { ok: true, input: result.data };
}
