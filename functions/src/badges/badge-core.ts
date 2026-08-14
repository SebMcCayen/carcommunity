/**
 * Badges domain — catalog and pure logic (Phase 9f).
 *
 * Ports services/api/src/lib/badge-catalog.ts (verbatim catalog — the single
 * source of truth for badge definitions) and the pure parts of
 * badge-service.ts to the Firestore model:
 *
 * - Awards live at `users/{uid}/badges/{badgeKey}` with the definition
 *   denormalized onto the document (backend-domain-mapping.md). The document
 *   ID equals the badge key, which makes awards naturally idempotent.
 * - Attendance for first_event / five_events uses the legacy conservative
 *   proxy: a `going` RSVP on an event that reaches `completed`. The count is
 *   maintained on the backend-only `badgeProgress/{uid}` counter document,
 *   incremented exactly once per completed event — `completed` is terminal, so
 *   whichever path ends the event (the events.complete callable or the
 *   events-autoClose sweep) makes that transition exactly once and credits
 *   exactly once.
 *
 * TIERED LADDERS (this phase). The five flat achievements above are joined by
 * six tiered ladders — Kronjägare, Vägfarare, Träffräv, Trogen, Konvojledare,
 * Samlare — each rung being its OWN badge key, so a member displays the exact
 * tier they reached and the award write stays the same create-if-absent
 * document write. Properties of the design:
 *
 *  - MONOTONIC. Earning Guld never removes Silver. Every tier a member reaches
 *    stays on their profile forever; the badge set is a display COLLECTION, not
 *    a current-rank indicator. Nothing in this domain revokes a badge.
 *  - SERVER-VERIFIED ONLY. Every ladder measures a counter the backend
 *    maintains from an authoritative source (badges/progressTriggers.ts): only
 *    `awarded` Kronjakt claims (a `risk_review` claim never counts), only
 *    server-computed drive distance, only completed-event attendance credited
 *    by the event lifecycle, only convoys the member led that COMPLETED with a
 *    second accepted participant, only the real vehicle count. No
 *    client-reported number ever reaches a threshold.
 *  - IDEMPOTENT. Thresholds are pure `>=` tests over the counters, so
 *    re-evaluation is always safe; the award write is create-if-absent.
 *  - Tier milestones also credit Kronpoäng (TIER_POINTS_REWARD) through the
 *    existing points ledger with a deterministic idempotency key.
 *
 * Design rules (legacy, still binding):
 *  - Wording stays positive and non-competitive; nothing may encourage unsafe
 *    driving, and NO badge — in wording or artwork — rewards speed. Vägfarare
 *    measures distance covered over a lifetime, which is a patience metric, and
 *    its art is explicitly barred from speed imagery.
 *  - All user-facing text is in Swedish.
 *
 * Pure module — no Firebase Admin SDK imports.
 */

import { z } from 'zod';

// ---------------------------------------------------------------------------
// Keys
// ---------------------------------------------------------------------------

/**
 * The original five flat achievements (Phase 9f). These keys are FROZEN:
 * members already hold documents at users/{uid}/badges/{key}, so a key may
 * never be renamed, remapped or removed — only described differently.
 */
export const LEGACY_BADGE_KEYS = [
  'first_event',
  'five_events',
  'helpful_member',
  'early_member',
  'garage_created',
] as const;
export type LegacyBadgeKey = (typeof LEGACY_BADGE_KEYS)[number];

/**
 * Kronjakt season PODIUM badges — one per finishing position in a single
 * season (Säsongspodium Guld / Silver / Brons for 1st / 2nd / 3rd). Standalone,
 * NOT a ladder: they are awarded by the season-rollover finalizer by RANK, not
 * by crossing a monotonic threshold, so they do not belong to the tier
 * evaluator. The one-doc-per-key award model is create-if-absent, so a member
 * who podiums in several seasons keeps ONE badge per rank ("reached the
 * podium"); which seasons is recorded in each season's stored standings.
 *
 * Rank-specific (three keys) rather than a single "top 3" badge with the rank
 * stored on the document: the award write is create-if-absent and never rewrites
 * the document, so a single badge would freeze whichever rank was earned FIRST
 * and could never upgrade when the member later finishes higher. Three keys let
 * each podium position be earned independently and permanently.
 */
export const SEASON_PODIUM_BADGE_KEYS = ['sasong_guld', 'sasong_silver', 'sasong_brons'] as const;
export type SeasonPodiumBadgeKey = (typeof SEASON_PODIUM_BADGE_KEYS)[number];

/**
 * Tiered ladder keys. One key per tier so a member can display the EXACT tier
 * they reached, and so the award path stays the same create-if-absent write as
 * the flat badges (document ID == badge key).
 */
export const TIER_BADGE_KEYS = [
  'kronjagare_brons',
  'kronjagare_silver',
  'kronjagare_guld',
  'kronjagare_platina',
  'vagfarare_brons',
  'vagfarare_silver',
  'vagfarare_guld',
  'vagfarare_platina',
  'traffrav_brons',
  'traffrav_silver',
  'traffrav_guld',
  'traffrav_platina',
  // Trogen intentionally stops at Guld — docs/gamification-system.md Q6 and §9:
  // a 365-day-streak rung is exactly the "reason to open an app you did not
  // want to open" the wellbeing rules forbid.
  'trogen_brons',
  'trogen_silver',
  'trogen_guld',
  'konvojledare_brons',
  'konvojledare_silver',
  'konvojledare_guld',
  'konvojledare_platina',
  'samlare_brons',
  'samlare_silver',
  'samlare_guld',
  'samlare_platina',
  // Säsongsmästare — the SCALING lifetime-championship ladder. Measures
  // `seasonsWon` (first-place season finishes), so the badge grows with the
  // number of championships; the exact count (×N) is carried in the Kronjakt
  // read contract for an at-a-glance "N-time champion". Distinct from the
  // per-season podium badges above, which mark a single season's top three.
  'sasongsmastare_brons',
  'sasongsmastare_silver',
  'sasongsmastare_guld',
  'sasongsmastare_platina',
] as const;
export type TierBadgeKey = (typeof TIER_BADGE_KEYS)[number];

export const BADGE_KEYS = [
  ...LEGACY_BADGE_KEYS,
  ...SEASON_PODIUM_BADGE_KEYS,
  ...TIER_BADGE_KEYS,
] as const;
export type BadgeKey = (typeof BADGE_KEYS)[number];

// ---------------------------------------------------------------------------
// Ladders
// ---------------------------------------------------------------------------

export const BADGE_TIERS = ['brons', 'silver', 'guld', 'platina'] as const;
export type BadgeTier = (typeof BADGE_TIERS)[number];

export const BADGE_LADDER_KEYS = [
  'kronjagare',
  'vagfarare',
  'traffrav',
  'trogen',
  'konvojledare',
  'samlare',
  'sasongsmastare',
] as const;
export type BadgeLadderKey = (typeof BADGE_LADDER_KEYS)[number];

/**
 * The server-verified counters a ladder measures. Every one is maintained by
 * the backend from an authoritative source — NEVER from a client-reported
 * number (see badges/progressTriggers.ts).
 */
export const BADGE_METRICS = [
  'crownsCollected',
  'lifetimeDistanceMeters',
  'verifiedEventsAttended',
  'bestDayStreak',
  'convoysLed',
  'vehiclesInGarage',
  'seasonsWon',
] as const;
export type BadgeMetric = (typeof BADGE_METRICS)[number];

/** Kronpoäng credited ONCE, the first time a tier is awarded. */
export const TIER_POINTS_REWARD: Readonly<Record<BadgeTier, number>> = {
  brons: 25,
  silver: 75,
  guld: 200,
  platina: 500,
};

/**
 * Swedish tier labels — the user-facing ones, and the values mirrored into
 * `badges.badgeNames.*` in contracts/localization/sv.json.
 */
export const TIER_NAME_SV: Readonly<Record<BadgeTier, string>> = {
  brons: 'Brons',
  silver: 'Silver',
  guld: 'Guld',
  platina: 'Platina',
};

/**
 * English tier labels, used for `nameEn` and mirrored into
 * `badges.badgeNames.*` in contracts/localization/en.json. NOT the Swedish
 * words: `nameEn` is a genuinely English working name, so a ladder rung reads
 * "Wayfarer Gold" rather than half-translated.
 */
export const TIER_NAME_EN: Readonly<Record<BadgeTier, string>> = {
  brons: 'Bronze',
  silver: 'Silver',
  guld: 'Gold',
  platina: 'Platinum',
};

export interface BadgeLadderTierSpec {
  tier: BadgeTier;
  key: TierBadgeKey;
  /** Inclusive threshold on the ladder's metric (>= qualifies). */
  threshold: number;
}

export interface BadgeLadderDefinition {
  ladder: BadgeLadderKey;
  /** Swedish ladder name (the user-facing one). */
  name: string;
  /** English working name, for design/product docs only — never shown. */
  nameEn: string;
  metric: BadgeMetric;
  /** Swedish sentence template; `{n}` is replaced by the formatted threshold. */
  descriptionTemplate: string;
  /**
   * Swedish template used when the threshold is exactly 1. Swedish inflects the
   * noun, so the plural template would render "Deltog i 1 träffar" — wrong, and
   * on the Brons rung of two ladders. Omitted where the noun does not inflect
   * (`fordon` is identical in both numbers).
   */
  descriptionTemplateOne?: string;
  /** Formats a raw threshold for the description (e.g. metres → "100 km"). */
  formatThreshold: (threshold: number) => string;
  /** The ladder's shared silhouette glyph — see BADGE_ICON_SYSTEM. */
  glyphBrief: string;
  tiers: readonly BadgeLadderTierSpec[];
}

const METRES_PER_KM = 1_000;

export const BADGE_LADDERS: readonly BadgeLadderDefinition[] = [
  {
    ladder: 'kronjagare',
    name: 'Kronjägare',
    nameEn: 'Crown Hunter',
    metric: 'crownsCollected',
    descriptionTemplate: 'Samlat {n} kronor i Kronjakten.',
    formatThreshold: (n) => String(n),
    glyphBrief:
      'A five-point crown seen straight on: a solid trapezoid base with three ' +
      'triangular spikes, a single round dot centred beneath it as a map marker. ' +
      'Wide, flat-bottomed silhouette — unmistakable against the pin and arch glyphs.',
    tiers: [
      { tier: 'brons', key: 'kronjagare_brons', threshold: 10 },
      { tier: 'silver', key: 'kronjagare_silver', threshold: 50 },
      { tier: 'guld', key: 'kronjagare_guld', threshold: 250 },
      { tier: 'platina', key: 'kronjagare_platina', threshold: 1_000 },
    ],
  },
  {
    ladder: 'vagfarare',
    name: 'Vägfarare',
    nameEn: 'Wayfarer',
    metric: 'lifetimeDistanceMeters',
    descriptionTemplate: 'Kört {n} totalt.',
    formatThreshold: (n) => `${n / METRES_PER_KM} km`,
    // NO SPEED IMAGERY. Standing product stance: nothing in the app may
    // gamify speed. No speedometer, no motion/whoosh lines, no car, no
    // needle, no chequered flag — the badge is about DISTANCE COVERED.
    glyphBrief:
      'A road ribbon receding to a horizon: a straight horizon bar across the ' +
      'upper third, a trapezoid road narrowing up toward it with two centre-line ' +
      'dashes, and a small rounded milestone stone standing at the lower left. ' +
      'Deliberately depicts distance and journey, never speed — no speedometer, ' +
      'no motion lines, no vehicle, no needle.',
    tiers: [
      { tier: 'brons', key: 'vagfarare_brons', threshold: 100 * METRES_PER_KM },
      { tier: 'silver', key: 'vagfarare_silver', threshold: 500 * METRES_PER_KM },
      { tier: 'guld', key: 'vagfarare_guld', threshold: 2_000 * METRES_PER_KM },
      { tier: 'platina', key: 'vagfarare_platina', threshold: 10_000 * METRES_PER_KM },
    ],
  },
  {
    ladder: 'traffrav',
    name: 'Träffräv',
    nameEn: 'Meet Fox',
    metric: 'verifiedEventsAttended',
    descriptionTemplate: 'Deltog i {n} träffar.',
    descriptionTemplateOne: 'Deltog i {n} träff.',
    formatThreshold: (n) => String(n),
    glyphBrief:
      'A fox head front-on: two tall triangular ears on a broad skull that ' +
      'tapers to a narrow muzzle, with two notched eye cut-outs. A pointed, ' +
      'top-heavy triangle silhouette that no other glyph in the set shares.',
    tiers: [
      // Brons/Silver deliberately mirror the existing first_event (1) and
      // five_events (5) badges — docs/gamification-system.md §7.2.
      { tier: 'brons', key: 'traffrav_brons', threshold: 1 },
      { tier: 'silver', key: 'traffrav_silver', threshold: 5 },
      { tier: 'guld', key: 'traffrav_guld', threshold: 25 },
      { tier: 'platina', key: 'traffrav_platina', threshold: 100 },
    ],
  },
  {
    ladder: 'trogen',
    name: 'Trogen',
    nameEn: 'Faithful',
    metric: 'bestDayStreak',
    descriptionTemplate: 'Öppnade appen {n} dagar i rad.',
    formatThreshold: (n) => String(n),
    glyphBrief:
      'A flame rising from a solid horizontal base bar: a teardrop outer flame ' +
      'with one inner notch, sitting on a short plinth. The plinth is what ' +
      'separates it from every other rounded glyph at small sizes.',
    tiers: [
      // Three rungs only, by product decision (Q6): a 365-day Platina streak is
      // the loss-aversion hook §9 rules out. Guld at 100 is the top.
      { tier: 'brons', key: 'trogen_brons', threshold: 7 },
      { tier: 'silver', key: 'trogen_silver', threshold: 30 },
      { tier: 'guld', key: 'trogen_guld', threshold: 100 },
    ],
  },
  {
    ladder: 'konvojledare',
    name: 'Konvojledare',
    nameEn: 'Convoy Leader',
    metric: 'convoysLed',
    descriptionTemplate: 'Ledde {n} konvojer.',
    descriptionTemplateOne: 'Ledde {n} konvoj.',
    formatThreshold: (n) => String(n),
    glyphBrief:
      'Three chevrons in V-formation seen from above: one large leading chevron ' +
      'with two smaller ones trailing behind and outward. Pure angular ' +
      'repetition — no rounded parts at all, so the silhouette reads as "formation".',
    tiers: [
      // Silver/Guld/Platina are the spec's 5/25/100 (§7.2); Brons is the
      // first-convoy rung that gives the ladder its fourth rung per §7.1.
      { tier: 'brons', key: 'konvojledare_brons', threshold: 1 },
      { tier: 'silver', key: 'konvojledare_silver', threshold: 5 },
      { tier: 'guld', key: 'konvojledare_guld', threshold: 25 },
      { tier: 'platina', key: 'konvojledare_platina', threshold: 100 },
    ],
  },
  {
    ladder: 'samlare',
    name: 'Samlare',
    nameEn: 'Collector',
    metric: 'vehiclesInGarage',
    descriptionTemplate: 'Har {n} fordon i sitt garage.',
    formatThreshold: (n) => String(n),
    // Full four-rung ladder: the garage cap is MAX_VEHICLES_PER_USER (10, raised
    // from 5 in 2026-08), so a Platina tier at the cap is now reachable. Tuned to
    // reality (most members own 1–2 cars): Brons at the first car keeps the entry
    // rung inclusive, while Guld (6) and Platina (10) stay aspirational.
    glyphBrief:
      'A garage arch — a wide semicircular roofline on two short legs — with ' +
      'three round dots in a row underneath, one per collected car. Arch-over-pips ' +
      'is the only glyph in the set built from a half-disc plus dots.',
    tiers: [
      { tier: 'brons', key: 'samlare_brons', threshold: 1 },
      { tier: 'silver', key: 'samlare_silver', threshold: 3 },
      { tier: 'guld', key: 'samlare_guld', threshold: 6 },
      { tier: 'platina', key: 'samlare_platina', threshold: 10 },
    ],
  },
  {
    ladder: 'sasongsmastare',
    name: 'Säsongsmästare',
    nameEn: 'Season Champion',
    metric: 'seasonsWon',
    descriptionTemplate: 'Vann {n} säsonger av Kronjakten.',
    descriptionTemplateOne: 'Vann {n} säsong av Kronjakten.',
    formatThreshold: (n) => String(n),
    // NO SPEED IMAGERY (standing rule): a laurel/crown of victory, never a
    // podium-with-motion or a finish line.
    glyphBrief:
      'A five-point crown resting inside an open laurel wreath: the same wide, ' +
      'flat-bottomed crown silhouette as Kronjägare, but cradled by two curved ' +
      'laurel branches meeting at the base. The wreath is what marks it as a ' +
      'championship rather than a collection count.',
    tiers: [
      { tier: 'brons', key: 'sasongsmastare_brons', threshold: 1 },
      { tier: 'silver', key: 'sasongsmastare_silver', threshold: 3 },
      { tier: 'guld', key: 'sasongsmastare_guld', threshold: 5 },
      { tier: 'platina', key: 'sasongsmastare_platina', threshold: 10 },
    ],
  },
];

// ---------------------------------------------------------------------------
// Icon design system
// ---------------------------------------------------------------------------

/**
 * The shared visual system every badge icon follows, so 27 badges read as ONE
 * family. Descriptive only — this repo ships no image assets; the art is
 * produced against these briefs and shipped as `iconIdentifier` drawables.
 *
 * ACCESSIBILITY RULE: tier is encoded by BOTH ring colour AND a counted pip
 * pattern. A colour-blind member can always tell Brons from Guld by counting
 * pips, never by hue alone. Ladders are told apart by SILHOUETTE, never colour.
 */
export const BADGE_ICON_SYSTEM = [
  'FORM: a circular medallion. Outer ring (4 dp at 48 dp) = tier. Inner field is',
  'a constant dark slate disc across the entire set. A single flat glyph sits',
  'centred on the field in one light ink colour. Flat vector only: no gradients,',
  'no bevels, no photographic detail, no text, no numerals inside the icon.',
  '',
  'READABILITY: designed at 48 dp and checked at 24 dp. Every glyph is a solid',
  'filled shape; no stroke thinner than 2 dp at 48 dp; minimum 3 dp of clear',
  'field between glyph and ring. Ladders are distinguished by SILHOUETTE ALONE —',
  'the set must survive being rendered as a pure black-on-white stencil.',
  '',
  'TIER TREATMENT (colour AND countable pips, never colour alone):',
  '  brons   — warm bronze ring (#A9683A), 1 pip notched into the ring at 6 o\'clock.',
  '  silver  — cool silver ring (#B9C3CB), 2 pips flanking 6 o\'clock.',
  '  guld    — gold ring (#E0A83A), 3 pips across the lower ring.',
  '  platina — pale platinum ring (#CFE3EA) PLUS a second concentric hairline',
  '            ring inside it, and 4 pips. The doubled ring makes Platina',
  '            distinct in silhouette, not just in hue.',
  '',
  'NON-TIERED (the five original badges): a plain unnotched pewter ring (#7C8792)',
  'with zero pips, which marks them at a glance as one-off milestones rather',
  'than rungs on a ladder.',
  '',
  'CONTENT RULE: no badge art may depict or imply speed anywhere in the set —',
  'no speedometer, no needle, no motion line, no chequered flag, no moving vehicle.',
].join('\n');

const TIER_RING_TREATMENT: Readonly<Record<BadgeTier, string>> = {
  brons: 'Bronze ring (#A9683A) with 1 pip notched at 6 o\'clock.',
  silver: 'Silver ring (#B9C3CB) with 2 pips flanking 6 o\'clock.',
  guld: 'Gold ring (#E0A83A) with 3 pips across the lower ring.',
  platina:
    'Platinum ring (#CFE3EA) with 4 pips plus a second concentric hairline ring — ' +
    'the doubled ring makes the top tier distinct in silhouette, not only in hue.',
};

const NON_TIERED_RING_TREATMENT =
  'Plain unnotched pewter ring (#7C8792), no pips — marks a one-off milestone ' +
  'rather than a rung on a ladder.';

// ---------------------------------------------------------------------------
// Catalog
// ---------------------------------------------------------------------------

export interface BadgeDefinition {
  key: BadgeKey;
  name: string;
  /** English working name; product/design only, never rendered to members. */
  nameEn: string;
  description: string;
  iconIdentifier: string;
  /** True if the backend awards this badge automatically. False = admin-only. */
  isAutomatic: boolean;
  /** Ladder this badge is a rung of; null for the five standalone badges. */
  ladder: BadgeLadderKey | null;
  tier: BadgeTier | null;
  /** Server-verified counter the ladder measures; null for standalone badges. */
  metric: BadgeMetric | null;
  /** Inclusive threshold on `metric` (>= qualifies); null for standalone. */
  threshold: number | null;
  /** Kronpoäng credited once on first award. 0 for the standalone badges. */
  pointsReward: number;
  /** Art brief: glyph + tier ring treatment. See BADGE_ICON_SYSTEM. */
  iconDesign: string;
  /**
   * True for a badge retained only so existing holders keep it. Still awarded
   * (its source path is unchanged) but superseded by a ladder for new design
   * work — see `supersededBy`.
   */
  isLegacy: boolean;
  supersededBy: BadgeKey | null;
}

const LEGACY_CATALOG: Readonly<Record<LegacyBadgeKey, BadgeDefinition>> = {
  first_event: {
    key: 'first_event',
    name: 'Första träffen',
    nameEn: 'First Meet',
    description: 'Deltog i sitt första community-event.',
    iconIdentifier: 'badge_first_event',
    isAutomatic: true,
    ladder: null,
    tier: null,
    metric: null,
    threshold: null,
    pointsReward: 0,
    iconDesign:
      'A single map pin — teardrop body, round hole punched through its centre — ' +
      `standing upright on the field. ${NON_TIERED_RING_TREATMENT}`,
    isLegacy: false,
    supersededBy: null,
  },
  five_events: {
    key: 'five_events',
    name: '5 träffar',
    nameEn: 'Five Meets',
    description: 'Deltog i fem community-event.',
    iconIdentifier: 'badge_five_events',
    isAutomatic: true,
    ladder: null,
    tier: null,
    metric: null,
    threshold: null,
    pointsReward: 0,
    iconDesign:
      'Five map pins fanned along a shallow arc, the centre pin tallest. The arc ' +
      'is what separates it from first_event in pure silhouette — no numerals. ' +
      NON_TIERED_RING_TREATMENT,
    isLegacy: false,
    supersededBy: null,
  },
  helpful_member: {
    key: 'helpful_member',
    name: 'Hjälpsam medlem',
    nameEn: 'Helpful Member',
    description: 'Har bidragit positivt och hjälpsamt i communityn.',
    iconIdentifier: 'badge_helpful_member',
    isAutomatic: false,
    ladder: null,
    tier: null,
    metric: null,
    threshold: null,
    pointsReward: 0,
    iconDesign:
      'Two open hands cupped into a bowl, palms up, with a small rounded shape ' +
      `resting in them. A wide, low, symmetric silhouette. ${NON_TIERED_RING_TREATMENT}`,
    isLegacy: false,
    supersededBy: null,
  },
  early_member: {
    key: 'early_member',
    name: 'Tidig medlem',
    nameEn: 'Early Member',
    description: 'Var med tidigt i communityn.',
    iconIdentifier: 'badge_early_member',
    isAutomatic: true,
    ladder: null,
    tier: null,
    metric: null,
    threshold: null,
    pointsReward: 0,
    iconDesign:
      'A sunrise: a half-disc sitting flat on a horizon bar, with three short ' +
      'rays above it. Flat-bottomed and horizontally symmetric. ' +
      NON_TIERED_RING_TREATMENT,
    isLegacy: false,
    supersededBy: null,
  },
  garage_created: {
    key: 'garage_created',
    name: 'Garageprofil skapad',
    nameEn: 'Garage Created',
    description: 'Skapade sin första fordonsprofil i Mitt garage.',
    iconIdentifier: 'badge_garage_created',
    isAutomatic: true,
    ladder: null,
    tier: null,
    metric: null,
    threshold: null,
    pointsReward: 0,
    iconDesign:
      'A garage door: a squared-off frame with three horizontal panel slats, the ' +
      'top slat raised to show the door part-open. Rectangular silhouette — the ' +
      'square frame is what separates it from the Samlare arch. ' +
      NON_TIERED_RING_TREATMENT,
    // Historic. Kept live and unchanged (garage.addVehicle still awards it) so
    // the many members already holding it are untouched; `samlare_brons` is the
    // ladder rung that measures the same moment going forward. Remapping the
    // key would either orphan the documents members already hold at this ID or
    // require a migration write to every holder, for no user benefit — and
    // because garage_created carries 0 KP, holding both is not a double
    // payout. An existing garage_created holder picks up the Samlare rungs
    // their garage already justifies from the badges-evaluateBacklog sweep,
    // which re-derives the vehicle count (badges/scheduled.ts) — no migration
    // write to existing badge documents is needed or performed.
    isLegacy: true,
    supersededBy: 'samlare_brons',
  },
};

/** Podium glyph, shared by the three rank badges; the ring conveys the rank. */
const SEASON_PODIUM_GLYPH =
  'A three-step winner\'s podium seen head-on — a tall centre block flanked by ' +
  'a slightly lower left and lower-still right block — with a small five-point ' +
  'crown centred above the tall block. Blocky, symmetric silhouette shared by ' +
  'all three podium badges; the tier ring is what says which step was reached.';

const SEASON_PODIUM_CATALOG: Readonly<Record<SeasonPodiumBadgeKey, BadgeDefinition>> = {
  sasong_guld: {
    key: 'sasong_guld',
    name: 'Säsongspodium Guld',
    nameEn: 'Season Podium Gold',
    description: 'Slutade etta i en säsong av Kronjakten.',
    iconIdentifier: 'badge_sasong_guld',
    isAutomatic: true,
    ladder: null,
    tier: 'guld',
    metric: null,
    threshold: null,
    pointsReward: 0,
    iconDesign: `${SEASON_PODIUM_GLYPH} ${TIER_RING_TREATMENT.guld}`,
    isLegacy: false,
    supersededBy: null,
  },
  sasong_silver: {
    key: 'sasong_silver',
    name: 'Säsongspodium Silver',
    nameEn: 'Season Podium Silver',
    description: 'Slutade tvåa i en säsong av Kronjakten.',
    iconIdentifier: 'badge_sasong_silver',
    isAutomatic: true,
    ladder: null,
    tier: 'silver',
    metric: null,
    threshold: null,
    pointsReward: 0,
    iconDesign: `${SEASON_PODIUM_GLYPH} ${TIER_RING_TREATMENT.silver}`,
    isLegacy: false,
    supersededBy: null,
  },
  sasong_brons: {
    key: 'sasong_brons',
    name: 'Säsongspodium Brons',
    nameEn: 'Season Podium Bronze',
    description: 'Slutade trea i en säsong av Kronjakten.',
    iconIdentifier: 'badge_sasong_brons',
    isAutomatic: true,
    ladder: null,
    tier: 'brons',
    metric: null,
    threshold: null,
    pointsReward: 0,
    iconDesign: `${SEASON_PODIUM_GLYPH} ${TIER_RING_TREATMENT.brons}`,
    isLegacy: false,
    supersededBy: null,
  },
};

function buildTierDefinition(
  ladder: BadgeLadderDefinition,
  spec: BadgeLadderTierSpec,
): BadgeDefinition {
  return {
    key: spec.key,
    name: `${ladder.name} ${TIER_NAME_SV[spec.tier]}`,
    nameEn: `${ladder.nameEn} ${TIER_NAME_EN[spec.tier]}`,
    description: (spec.threshold === 1 && ladder.descriptionTemplateOne
      ? ladder.descriptionTemplateOne
      : ladder.descriptionTemplate
    ).replace('{n}', ladder.formatThreshold(spec.threshold)),
    iconIdentifier: `badge_${spec.key}`,
    isAutomatic: true,
    ladder: ladder.ladder,
    tier: spec.tier,
    metric: ladder.metric,
    threshold: spec.threshold,
    pointsReward: TIER_POINTS_REWARD[spec.tier],
    iconDesign: `${ladder.glyphBrief} ${TIER_RING_TREATMENT[spec.tier]}`,
    isLegacy: false,
    supersededBy: null,
  };
}

const TIER_CATALOG = Object.fromEntries(
  BADGE_LADDERS.flatMap((ladder) =>
    ladder.tiers.map((spec) => [spec.key, buildTierDefinition(ladder, spec)] as const),
  ),
) as Record<TierBadgeKey, BadgeDefinition>;

export const BADGE_CATALOG: Readonly<Record<BadgeKey, BadgeDefinition>> = {
  ...LEGACY_CATALOG,
  ...SEASON_PODIUM_CATALOG,
  ...TIER_CATALOG,
};

/**
 * Product-defined display order: the five historic milestones first (they are
 * the ones existing members already hold), then each ladder bottom-to-top so a
 * profile reads as a progression.
 */
export const BADGE_CATALOG_ORDER: readonly BadgeKey[] = [
  ...LEGACY_BADGE_KEYS,
  ...SEASON_PODIUM_BADGE_KEYS,
  ...BADGE_LADDERS.flatMap((ladder) => ladder.tiers.map((spec) => spec.key)),
];

/** The ladder definition a tier key belongs to, or null for a standalone key. */
export function ladderForBadgeKey(key: BadgeKey): BadgeLadderDefinition | null {
  const ladderKey = BADGE_CATALOG[key].ladder;
  return ladderKey ? (BADGE_LADDERS.find((l) => l.ladder === ladderKey) ?? null) : null;
}

/** Type guard for values arriving from Firestore documents. */
export function isBadgeKey(value: unknown): value is BadgeKey {
  return typeof value === 'string' && value in BADGE_CATALOG;
}

/** Attendance thresholds for the event badges (legacy evaluateEventBadges). */
export const FIRST_EVENT_THRESHOLD = 1;
export const FIVE_EVENTS_THRESHOLD = 5;

// ---------------------------------------------------------------------------
// Inputs
// ---------------------------------------------------------------------------

const awardHelpfulMemberInputSchema = z
  .object({
    targetUid: z.string().trim().min(1).max(128),
    reason: z.string().trim().min(1).max(2000),
  })
  .strict();

export type AwardHelpfulMemberInput = z.infer<typeof awardHelpfulMemberInputSchema>;

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

export function parseAwardHelpfulMemberInput(
  data: unknown,
): ParseResult<AwardHelpfulMemberInput> {
  const result = awardHelpfulMemberInputSchema.safeParse(data ?? {});
  if (!result.success) {
    return {
      ok: false,
      message: 'Expected { targetUid, reason } — reason is required for manual badge awards.',
    };
  }
  return { ok: true, input: result.data };
}

// ---------------------------------------------------------------------------
// Document builder
// ---------------------------------------------------------------------------

/**
 * users/{uid}/badges/{badgeKey} document. The catalog definition is
 * denormalized so clients render without a catalog lookup.
 *
 * `ladder` / `tier` are ADDITIVE and always written (null for the standalone
 * badges) so a client can group a profile into ladders without shipping the
 * catalog. Badge documents written before this phase simply lack the two
 * fields — the contract keeps them optional for exactly that reason, and
 * nothing back-fills them (a rewrite would move `awardedAt`).
 */
export function buildBadgeDocument(
  badgeKey: BadgeKey,
  context: { source: 'automatic' | 'admin_manual' },
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  const definition = BADGE_CATALOG[badgeKey];
  return {
    badgeKey,
    name: definition.name,
    description: definition.description,
    iconIdentifier: definition.iconIdentifier,
    ladder: definition.ladder,
    tier: definition.tier,
    source: context.source,
    // NOT the awarding admin's UID. This document is publicly readable by any
    // signed-in member (the badge wall), and Firestore has no field-level read
    // security, so anything written here is public. The awarder identity lives
    // in adminAuditEvents, which is admin-only and written in the same
    // transaction.
    awardedAt: serverTimestamp(),
  };
}

// ---------------------------------------------------------------------------
// early_member (legacy evaluateEarlyMember)
// ---------------------------------------------------------------------------

/**
 * Parses the EARLY_MEMBER_CUTOFF_DATE configuration value. Returns null when
 * unset or invalid — the legacy safe default: no cutoff configured means the
 * badge is never awarded.
 */
export function parseEarlyMemberCutoff(raw: string | undefined): Date | null {
  if (!raw || !raw.trim()) {
    return null;
  }
  const parsed = new Date(raw.trim());
  return Number.isNaN(parsed.getTime()) ? null : parsed;
}

/** Accounts created strictly before the cutoff qualify (legacy rule). */
export function qualifiesAsEarlyMember(createdAt: Date, cutoff: Date): boolean {
  return createdAt.getTime() < cutoff.getTime();
}

/** Which event badges an attendance count qualifies for. */
export function qualifiedEventBadges(attendanceCount: number): BadgeKey[] {
  const keys: BadgeKey[] = [];
  if (attendanceCount >= FIRST_EVENT_THRESHOLD) {
    keys.push('first_event');
  }
  if (attendanceCount >= FIVE_EVENTS_THRESHOLD) {
    keys.push('five_events');
  }
  return keys;
}

// ---------------------------------------------------------------------------
// Admin aggregate summary (Phase 18b)
// ---------------------------------------------------------------------------

/** Recent-window for the admin badge summary (legacy RECENT_BADGE_WINDOW_DAYS). */
export const RECENT_BADGE_WINDOW_DAYS = 30;

/** One row of the admin badge summary — aggregate counts only, no user data. */
export interface AdminBadgeAggregateItem {
  key: BadgeKey;
  name: string;
  totalCount: number;
  recentCount: number;
}

/**
 * Pure aggregation of badge awards into per-key totals + recent (last 30 days)
 * counts, ordered by the catalog. Mirrors the legacy SQL groupBy
 * (badge-service.getAdminBadgeSummary) over the Firestore collectionGroup.
 * Unknown/legacy keys are ignored (only catalog keys are reported).
 */
export function buildAdminBadgeSummary(
  awards: ReadonlyArray<{ badgeKey: string; awardedAtMillis: number | null }>,
  nowMillis: number,
): AdminBadgeAggregateItem[] {
  const cutoff = nowMillis - RECENT_BADGE_WINDOW_DAYS * 24 * 60 * 60 * 1000;
  const catalogKeys = new Set<string>(BADGE_CATALOG_ORDER);
  const total = new Map<string, number>();
  const recent = new Map<string, number>();
  for (const award of awards) {
    // Ignore unknown/legacy keys (matches the docstring; keeps the maps small).
    if (!catalogKeys.has(award.badgeKey)) continue;
    total.set(award.badgeKey, (total.get(award.badgeKey) ?? 0) + 1);
    if (award.awardedAtMillis != null && award.awardedAtMillis >= cutoff) {
      recent.set(award.badgeKey, (recent.get(award.badgeKey) ?? 0) + 1);
    }
  }
  return BADGE_CATALOG_ORDER.map((key) => ({
    key,
    name: BADGE_CATALOG[key].name,
    totalCount: total.get(key) ?? 0,
    recentCount: recent.get(key) ?? 0,
  }));
}
