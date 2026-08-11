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
// Mapbox (separate vendor) — MOBILE SDK usage, billed by Monthly Active Users.
//
// ⚠️ The old MAPBOX_LOADS_PER_MEMBER_PER_DAY (30 web-GL loads/day) was DELETED
// on 2026-08-05: the member app is a mobile app (Maps SDK + Navigation SDK), so
// it is billed by MAU + trips, not per web load. The two constants below are
// the only Mapbox usage guesses now, both surfaced on the board.
// ---------------------------------------------------------------------------

/**
 * Fraction of active members who use turn-by-turn NAVIGATION in a given month
 * (and so count as a Navigation-SDK MAU + generate trips). The basemap is used
 * by everyone who opens the map; navigation is a smaller, opt-in subset — you
 * only pay Nav rates for members who actually route somewhere.
 *
 * 0.5 (half) is a deliberately CAUTIOUS (cost-erring-high, not savings-erring)
 * default for a driving-focused community: it does not pretend navigation is
 * rare, so the model does NOT understate the one Mapbox line that actually
 * grows. Tune down as real telemetry shows the true share. Justification dated
 * 2026-08-05.
 */
export const NAV_USING_FRACTION = 0.5;

/**
 * Trips per NAVIGATING member per month. A "trip" is one turn-by-turn session
 * (start → arrive). A member who navigates does so a couple of times a week;
 * 8/month is a round, slightly-generous figure so the trip line is not
 * understated. Only members counted by NAV_USING_FRACTION generate these.
 * Justification dated 2026-08-05.
 */
export const NAV_TRIPS_PER_NAVIGATING_MEMBER_PER_MONTH = 8;

/**
 * Admin web map-picker loads per month (Mapbox GL JS, #673). This is the ONLY
 * genuinely per-load Mapbox surface, and it is admin-only — Seb placing event
 * and contract-area pins. ~50 loads/month is a generous estimate for one
 * operator; it sits far under the 50,000/month free web tier, so it costs
 * nothing and must never dominate the member-app MAU lines. Dated 2026-08-05.
 */
export const ADMIN_WEB_MAP_LOADS_PER_MONTH = 50;

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
// Recurring costs (a THIRD section — neither Google Cloud usage nor Mapbox).
//
// These used to live here as a HARDCODED list (a single Claude placeholder).
// They are now DATA-BACKED and admin-editable: real recurring costs live in the
// `financeRecurringCosts` Firestore collection, are managed from the Finance &
// Cost board's "Recurring costs" section (finance.addRecurringCost /
// updateRecurringCost / deleteRecurringCost), read by finance/estimate.ts, and
// folded into the grand total by finance/model.ts (see recurringCosts-core.ts).
//
// The Claude placeholder that lived here was REMOVED deliberately so nothing is
// double-counted — Seb re-adds Claude as a normal entry via the new UI. There
// is intentionally no constant to replace it: an empty collection contributes 0
// and the board shows an empty-state rather than a fabricated number.
// ---------------------------------------------------------------------------
