package com.kungsbackacarcommunity.app.badges

import kotlin.math.roundToLong

/**
 * Client-side MIRROR of the tiered badge catalog (functions/src/badges/badge-core.ts).
 *
 * WHY A MIRROR EXISTS. An awarded badge document carries its own denormalized
 * name/ladder/tier, so rendering what a member ALREADY holds needs no catalog.
 * The showcase also has to render what they have NOT earned yet — the next rung
 * and its threshold, and the locked first rung of every ladder for a member with
 * nothing at all — and those documents do not exist. The thresholds therefore
 * have to be known client-side.
 *
 * DISPLAY ONLY. Nothing here decides anything: qualification is a pure `>=` test
 * run on the backend against the `badgeProgress/{uid}` counters, which no client
 * can read or write ([firebase/firestore.rules] denies both). If this table ever
 * drifted from the server's, the only consequence is a wrong *goal line* on the
 * profile — never a wrong badge. The server stays the single source of truth.
 *
 * Keys, tiers and thresholds are copied verbatim from `BADGE_LADDERS`; the
 * `badgeKey` strings must keep matching `users/{uid}/badges/{badgeKey}`
 * document IDs, which are frozen.
 *
 * Pure Kotlin — no Android, no Firebase. User-facing names live in the
 * localization contracts and are resolved in [BadgeStrings].
 */

/** A rung's rank. Ordinal order is the ladder order (low → high). */
enum class BadgeTier(val key: String) {
    BRONS("brons"),
    SILVER("silver"),
    GULD("guld"),
    PLATINA("platina"),
    ;

    companion object {
        fun fromKey(key: String?): BadgeTier? = entries.firstOrNull { it.key == key }
    }
}

/** How a ladder's threshold and counter are rendered. */
enum class BadgeLadderUnit {
    /** A plain count (crowns, meets, days, convoys, vehicles). */
    COUNT,

    /** Metres, shown as whole kilometres — the Vägfarare unit. */
    DISTANCE_METERS,
}

/** One rung of a ladder: the badge key it awards and the counter it needs. */
data class BadgeRung(
    val tier: BadgeTier,
    val badgeKey: String,
    /** Inclusive threshold on the ladder's metric (>= qualifies). */
    val threshold: Long,
)

/** Stable identity of a ladder. `key` matches the backend `ladder` field. */
enum class BadgeLadderId(val key: String) {
    KRONJAGARE("kronjagare"),
    VAGFARARE("vagfarare"),
    TRAFFRAV("traffrav"),
    TROGEN("trogen"),
    KONVOJLEDARE("konvojledare"),
    SAMLARE("samlare"),
    SASONGSMASTARE("sasongsmastare"),
    ;

    companion object {
        fun fromKey(key: String?): BadgeLadderId? = entries.firstOrNull { it.key == key }
    }
}

data class BadgeLadder(
    val id: BadgeLadderId,
    val unit: BadgeLadderUnit,
    /** Bottom-to-top. Trogen and Samlare have three rungs; the rest have four. */
    val rungs: List<BadgeRung>,
) {
    val badgeKeys: List<String> get() = rungs.map { it.badgeKey }

    fun rungFor(tier: BadgeTier): BadgeRung? = rungs.firstOrNull { it.tier == tier }
}

private const val METRES_PER_KM = 1_000L

/** The seven ladders, in the catalog's display order. */
val BADGE_LADDERS: List<BadgeLadder> =
    listOf(
        BadgeLadder(
            id = BadgeLadderId.KRONJAGARE,
            unit = BadgeLadderUnit.COUNT,
            rungs =
                listOf(
                    BadgeRung(BadgeTier.BRONS, "kronjagare_brons", 10),
                    BadgeRung(BadgeTier.SILVER, "kronjagare_silver", 50),
                    BadgeRung(BadgeTier.GULD, "kronjagare_guld", 250),
                    BadgeRung(BadgeTier.PLATINA, "kronjagare_platina", 1_000),
                ),
        ),
        BadgeLadder(
            id = BadgeLadderId.VAGFARARE,
            unit = BadgeLadderUnit.DISTANCE_METERS,
            rungs =
                listOf(
                    BadgeRung(BadgeTier.BRONS, "vagfarare_brons", 100 * METRES_PER_KM),
                    BadgeRung(BadgeTier.SILVER, "vagfarare_silver", 500 * METRES_PER_KM),
                    BadgeRung(BadgeTier.GULD, "vagfarare_guld", 2_000 * METRES_PER_KM),
                    BadgeRung(BadgeTier.PLATINA, "vagfarare_platina", 10_000 * METRES_PER_KM),
                ),
        ),
        BadgeLadder(
            id = BadgeLadderId.TRAFFRAV,
            unit = BadgeLadderUnit.COUNT,
            rungs =
                listOf(
                    // Brons/Silver deliberately mirror the existing first_event (1)
                    // and five_events (5) badges — docs/gamification-system.md §7.2.
                    BadgeRung(BadgeTier.BRONS, "traffrav_brons", 1),
                    BadgeRung(BadgeTier.SILVER, "traffrav_silver", 5),
                    BadgeRung(BadgeTier.GULD, "traffrav_guld", 25),
                    BadgeRung(BadgeTier.PLATINA, "traffrav_platina", 100),
                ),
        ),
        // Three rungs only, by product decision (Q6): a 365-day Platina streak is
        // the loss-aversion hook the design doc rules out, so `trogen_platina` is
        // not a badge key at all. Guld at 100 is the top (matches the backend).
        BadgeLadder(
            id = BadgeLadderId.TROGEN,
            unit = BadgeLadderUnit.COUNT,
            rungs =
                listOf(
                    BadgeRung(BadgeTier.BRONS, "trogen_brons", 7),
                    BadgeRung(BadgeTier.SILVER, "trogen_silver", 30),
                    BadgeRung(BadgeTier.GULD, "trogen_guld", 100),
                ),
        ),
        BadgeLadder(
            id = BadgeLadderId.KONVOJLEDARE,
            unit = BadgeLadderUnit.COUNT,
            rungs =
                listOf(
                    BadgeRung(BadgeTier.BRONS, "konvojledare_brons", 1),
                    BadgeRung(BadgeTier.SILVER, "konvojledare_silver", 5),
                    BadgeRung(BadgeTier.GULD, "konvojledare_guld", 25),
                    BadgeRung(BadgeTier.PLATINA, "konvojledare_platina", 100),
                ),
        ),
        // Three rungs only: the garage caps at five vehicles, so a Platina rung
        // would be unreachable (matches the backend catalog).
        BadgeLadder(
            id = BadgeLadderId.SAMLARE,
            unit = BadgeLadderUnit.COUNT,
            rungs =
                listOf(
                    BadgeRung(BadgeTier.BRONS, "samlare_brons", 1),
                    BadgeRung(BadgeTier.SILVER, "samlare_silver", 3),
                    BadgeRung(BadgeTier.GULD, "samlare_guld", 5),
                ),
        ),
        // Säsongsmästare — the SCALING lifetime-championship ladder (metric
        // seasonsWon, first-place season finishes). Four rungs 1/3/5/10, matching
        // the backend catalog. Distinct from the per-season podium badges
        // (sasong_guld/silver/brons), which are standalone milestones below.
        BadgeLadder(
            id = BadgeLadderId.SASONGSMASTARE,
            unit = BadgeLadderUnit.COUNT,
            rungs =
                listOf(
                    BadgeRung(BadgeTier.BRONS, "sasongsmastare_brons", 1),
                    BadgeRung(BadgeTier.SILVER, "sasongsmastare_silver", 3),
                    BadgeRung(BadgeTier.GULD, "sasongsmastare_guld", 5),
                    BadgeRung(BadgeTier.PLATINA, "sasongsmastare_platina", 10),
                ),
        ),
    )

/**
 * The standalone (non-tiered) badges, in catalog order: the five original
 * milestones plus the three season PODIUM badges (a single season's top three,
 * awarded by rank — not a ladder). Matches the backend catalog's standalone set.
 */
val BADGE_MILESTONE_KEYS: List<String> =
    listOf(
        "first_event",
        "five_events",
        "helpful_member",
        "early_member",
        "garage_created",
        "sasong_guld",
        "sasong_silver",
        "sasong_brons",
    )

/** Kronpoäng credited once, the first time a rung is reached (TIER_POINTS_REWARD). */
val BADGE_TIER_POINTS: Map<BadgeTier, Int> =
    mapOf(
        BadgeTier.BRONS to 25,
        BadgeTier.SILVER to 75,
        BadgeTier.GULD to 200,
        BadgeTier.PLATINA to 500,
    )

/** Every badge key in the catalog — the denominator of "x of y unlocked". */
val BADGE_TOTAL_COUNT: Int = BADGE_MILESTONE_KEYS.size + BADGE_LADDERS.sumOf { it.rungs.size }

private val LADDER_BY_BADGE_KEY: Map<String, Pair<BadgeLadder, BadgeRung>> =
    BADGE_LADDERS.flatMap { ladder -> ladder.rungs.map { rung -> rung.badgeKey to (ladder to rung) } }
        .toMap()

/** The ladder + rung a badge key belongs to, or null for a standalone key. */
fun rungForBadgeKey(badgeKey: String): Pair<BadgeLadder, BadgeRung>? = LADDER_BY_BADGE_KEY[badgeKey]

fun ladderById(id: BadgeLadderId): BadgeLadder = BADGE_LADDERS.first { it.id == id }

/**
 * Renders a threshold or a counter for display, in the ladder's own unit.
 *
 * Distances are stored in metres and shown as WHOLE kilometres — every
 * Vägfarare threshold is a round number of km, so "100 km" and "34 km" read as
 * the same quantity on both sides of a "34 km / 100 km" progress line. Locale
 * independent by design (no grouping separators to disagree with the ladder's
 * Swedish copy at 1 000 / 10 000).
 */
fun formatLadderValue(unit: BadgeLadderUnit, value: Long): String =
    when (unit) {
        BadgeLadderUnit.COUNT -> value.toString()
        BadgeLadderUnit.DISTANCE_METERS -> "${(value.toDouble() / METRES_PER_KM).roundToLong()} km"
    }
