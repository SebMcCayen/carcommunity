/**
 * Finance cost model — the ASSUMPTIONS (every guessed usage number, in one
 * place, each labelled and justified).
 *
 * WHY A SEPARATE FILE
 * -------------------
 * A cost model is only honest if you can see its guesses. Prices (pricing.ts)
 * are sourced facts; the numbers here are ESTIMATES of how much work the app
 * does — how many Firestore writes a member causes in a day, how many map loads
 * they trigger, how many incidents Trafikverket returns. The brief is explicit:
 * "never bury a guess inside a formula." So every assumption is a single named
 * constant with a short justification, surfaced on the board and tunable here
 * without touching any maths.
 *
 * COMMITTED vs VARIABLE
 * ---------------------
 * The committed (scheduled) figures below are close to MEASURED — a cron job's
 * cost is cadence × work and does not depend on how many members exist. The
 * per-member figures are the softest guesses in the whole model; they are
 * deliberately round and slightly generous, and the board scales them by the
 * REAL current member count (read from the latest metrics/{date} snapshot),
 * which is what makes it track growth.
 */

import type { CaptureDate } from './pricing';

// ---------------------------------------------------------------------------
// Trafikverket import — the dominant committed line.
// ---------------------------------------------------------------------------

/**
 * Situations written per import run. The importer upserts one Firestore
 * document per active national road Situation each run.
 *
 * MEASURED 2026-08-01: ~2,775 active Situations nationally (see
 * incidents/trafikverket-core.ts). The query cap is 3,000
 * (TRAFIKVERKET_QUERY_LIMIT) — the modelled ceiling. We cost the measured
 * figure as the expected line and expose the cap so the worst case is visible.
 */
export const TRAFIKVERKET_SITUATIONS_PER_RUN = 2_775;

/** Hard ceiling: the query limit the importer will never exceed in one run. */
export const TRAFIKVERKET_SITUATIONS_CAP = 3_000;

// ---------------------------------------------------------------------------
// Per-member daily usage (the VARIABLE model). Scaled by live member count.
// These are the softest numbers in the model — round, deliberately a little
// generous, and meant to be tuned as real telemetry arrives.
// ---------------------------------------------------------------------------
export const PER_MEMBER_PER_DAY = {
  /**
   * Firestore document writes caused by one member in a day: chat sends, RSVPs,
   * garage edits, saved drives, profile writes, convoy/live writes, incident
   * reports, notification read-marks, etc. 25 is a generous round estimate for
   * an engaged member; most days are far lighter.
   */
  firestoreWrites: 25,
  /**
   * Firestore document reads caused by one member in a day: opening feeds, lists,
   * profiles, garages, chats. Reads dwarf writes in a read-heavy mobile app;
   * 250 is a generous engaged-member day.
   */
  firestoreReads: 250,
  /** Firestore deletes (un-RSVP, delete message/drive, clear destination). Small. */
  firestoreDeletes: 5,
  /**
   * Callable invocations caused by one member in a day. Roughly the write count
   * plus reads served by callables; 60 is a round, generous figure.
   */
  functionInvocations: 60,
  /**
   * Realtime Database bytes DOWNLOADED per member per day for live location —
   * the bandwidth of watching others' markers while a live/convoy view is open.
   * 5 MB/day assumes a member with the live map open for a while most days.
   */
  rtdbDownloadBytes: 5 * 1024 * 1024,
  /**
   * Cloud Storage bytes DOWNLOADED per member per day — viewing vehicle/profile
   * images (before CDN cache). 8 MB/day is a generous browsing day.
   */
  storageDownloadBytes: 8 * 1024 * 1024,
} as const;

/**
 * Cloud Storage bytes STORED per member (cumulative), for images they upload —
 * garage photos, avatar. 15 MB/member is a generous steady-state estimate
 * (a handful of compressed photos).
 */
export const STORAGE_BYTES_PER_MEMBER = 15 * 1024 * 1024;

/**
 * Realtime Database bytes STORED at any instant for live sessions. Live nodes
 * are tiny and ephemeral (lean marker + session); this is a flat, generous
 * whole-app estimate rather than per-member because sessions are short-lived
 * and only a fraction of members share at once. 20 MB covers a busy moment.
 */
export const RTDB_STORAGE_BYTES = 20 * 1024 * 1024;

// ---------------------------------------------------------------------------
// Mapbox (separate vendor).
// ---------------------------------------------------------------------------

/**
 * Mapbox web-GL map loads per ACTIVE member per day. The app is map-first, so a
 * member typically triggers several loads a day (opening the map, navigating
 * back to it). 30/day is a generous map-first estimate; a "load" is a session
 * initialisation, not every pan.
 */
export const MAPBOX_LOADS_PER_MEMBER_PER_DAY = 30;

// ---------------------------------------------------------------------------
// Infrastructure counts (near-measured, not per-member).
// ---------------------------------------------------------------------------

/**
 * Active Secret Manager versions. Currently just the Trafikverket API key
 * (TRAFIKVERKET_API_KEY). Under the 6-version free tier, so this costs nothing
 * today — listed so it is visible, not hidden.
 */
export const SECRET_MANAGER_ACTIVE_VERSIONS = 1;

/**
 * Default member count used ONLY when no metrics/{date} snapshot exists yet to
 * read the real count from. The board says loudly when it is falling back to
 * this rather than a live figure. 16 mirrors the small real cohort seen in
 * prod (see the unknown-sign-ups note); intentionally tiny so a fallback never
 * masquerades as a large, costed community.
 */
export const FALLBACK_MEMBER_COUNT = 16;

// ---------------------------------------------------------------------------
// Fixed subscriptions / tooling (a THIRD section — neither Google Cloud usage
// nor Mapbox). Flat recurring vendor costs; never blended into any per-member
// or free-tier maths. Designed as a LIST so more tooling can be added later.
// ---------------------------------------------------------------------------

/** Billing period of a fixed subscription. */
export type SubscriptionPeriod = 'monthly' | 'annual';

/** One fixed recurring subscription. `amount === null` means "not set yet". */
export interface FixedSubscription {
  /** Stable id (for React keys / future editing). */
  id: string;
  /** Vendor + plan, e.g. "Claude (Anthropic)". */
  name: string;
  /**
   * The price Seb pays. `null` until Seb fills in his REAL figure — the board
   * shows "set your plan cost" rather than inventing a number.
   */
  amount: number | null;
  /** Currency the amount is in. Converted via USD_TO_SEK when 'USD'. */
  currency: 'SEK' | 'USD';
  /** Whether `amount` is per month or per year (annual is /12 for the board). */
  period: SubscriptionPeriod;
  /** Date the amount was last confirmed, for the "as of" stamp. */
  capturedOn: CaptureDate;
  /** Optional note shown under the line. */
  note?: string;
}

/**
 * Fixed subscriptions. ⚠️ The Claude amount is a PLACEHOLDER (null) on purpose:
 * the model does not know Seb's real plan or price, so it must not fabricate
 * one. Seb: set `amount` to your actual figure and `currency`/`period` to match
 * your invoice, then bump `capturedOn`. Add more tooling rows here as you take
 * them on (GitHub, Figma, etc.) — the board renders the list generically.
 */
export const FIXED_SUBSCRIPTIONS: FixedSubscription[] = [
  {
    id: 'claude',
    name: 'Claude (Anthropic)',
    // TODO(Seb): set your real plan cost here (e.g. amount: 200, currency: 'USD',
    // period: 'monthly' for a $200/mo Max plan) and bump capturedOn. Left null
    // so the board shows "set your plan cost" instead of a guessed number.
    amount: null,
    currency: 'USD',
    period: 'monthly',
    capturedOn: '2026-07-31',
    note: 'Set your actual Claude plan cost in functions/src/finance/assumptions.ts.',
  },
];
