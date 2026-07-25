package com.kungsbackacarcommunity.app.badges

/**
 * The member's OWN badge wall, folded from the owner-readable award documents.
 *
 * OWNER-ONLY BY CONSTRUCTION. `users/{uid}/badges` is an owner-only read
 * (firebase/firestore.rules) because badges leak activity — streaks, distance
 * driven, meets attended. Everything in this file is therefore built from the
 * SIGNED-IN member's own award list; there is no code path here that takes
 * another member's uid, and none may be added without a deliberate rules change.
 *
 * WHERE THE PROGRESS NUMBERS COME FROM. The authoritative counters live on
 * `badgeProgress/{uid}`, which is denied to every client (read included) — it is
 * the anti-abuse core of the ladders. So the client cannot show "34 of 50
 * crowns" for a ladder whose counter it cannot see. Two things are always known
 * and exact:
 *
 *  - which rungs are held, from the award documents; and
 *  - what the next rung needs, from [BADGE_LADDERS].
 *
 * On top of that, a numeric bar is drawn ONLY for the ladders whose metric the
 * app can observe honestly from data it already reads for this member — see
 * [BadgeCounters]. Every other ladder shows the next rung and its requirement
 * without inventing a number. Nothing here estimates or guesses a counter.
 *
 * Pure Kotlin — no Android, no Firebase, fully unit-testable.
 */

/**
 * Client-observable stand-ins for the server's ladder counters.
 *
 * A field is non-null only where the app can derive the value from an
 * owner-scoped read it ALREADY makes on the profile route, and where that value
 * tracks the server's counter closely enough to be an honest progress bar:
 *
 *  - [savedDriveDistanceMeters] — the folded total of the member's saved drives.
 *    The server's `lifetimeDistanceMeters` is a monotonic sum that a deleted
 *    drive never decreases, so this reads LOW after a delete, never high: the
 *    bar can lag reality but never overstates progress, and the badge itself is
 *    still awarded by the server the moment the real counter crosses.
 *  - [vehiclesInGarage] — the size of the member's own garage list. The server
 *    stores a running maximum, so this too can only read low (after removing a
 *    car), never high.
 *
 * Crowns collected, meets attended, best day streak and convoys led have no
 * cheap owner-scoped source on the client — each would need a new query (and
 * index), and the streak is not client-readable at all — so they stay null and
 * their ladders render as a goal line without a bar.
 */
data class BadgeCounters(
    val savedDriveDistanceMeters: Double? = null,
    val vehiclesInGarage: Int? = null,
) {
    /** The observable value for [ladder], or null when the client cannot know it. */
    fun observedValue(ladder: BadgeLadderId): Long? =
        when (ladder) {
            BadgeLadderId.VAGFARARE ->
                savedDriveDistanceMeters?.takeIf { it.isFinite() && it >= 0 }?.toLong()
            BadgeLadderId.SAMLARE -> vehiclesInGarage?.takeIf { it >= 0 }?.toLong()
            BadgeLadderId.KRONJAGARE,
            BadgeLadderId.TRAFFRAV,
            BadgeLadderId.TROGEN,
            BadgeLadderId.KONVOJLEDARE,
            -> null
        }

    companion object {
        val NONE = BadgeCounters()
    }
}

/** One ladder as the profile renders it: what is held, what is next, how close. */
data class LadderProgress(
    val ladder: BadgeLadder,
    /** Every rung held, low → high. Ladders are monotonic; a tier is never revoked. */
    val earnedRungs: List<BadgeRung>,
    /** The next rung to reach, or null when the ladder is fully climbed. */
    val nextRung: BadgeRung?,
    /** Client-observable counter, or null when only the server knows it. */
    val observedValue: Long?,
) {
    /** Highest rung held, or null when the ladder has not been started. */
    val highestRung: BadgeRung? get() = earnedRungs.lastOrNull()

    /** The rung the medallion depicts: the highest held, else the locked first rung. */
    val displayRung: BadgeRung get() = highestRung ?: ladder.rungs.first()

    /** True when the medallion should render greyed — nothing earned on this ladder. */
    val isLocked: Boolean get() = earnedRungs.isEmpty()

    /** True when every rung is held. Platina (or Guld for Samlare) and no bar. */
    val isComplete: Boolean get() = nextRung == null

    /**
     * How far along the climb to [nextRung], in 0f..1f — null when there is no
     * next rung or no observable counter, i.e. when no bar may be drawn.
     *
     * Measured from ZERO rather than from the previous rung, so the label
     * ("34 / 50") and the fill always describe the same quantity.
     */
    val fractionToNext: Float?
        get() {
            val target = nextRung?.threshold ?: return null
            val value = observedValue ?: return null
            if (target <= 0L) return 1f
            return (value.toFloat() / target.toFloat()).coerceIn(0f, 1f)
        }
}

/** A held standalone badge (the five non-tiered milestones). */
data class MilestoneBadge(
    val key: String,
    val fallbackName: String?,
    val awardedAtMillis: Long?,
)

/** The whole own-profile badge wall. */
data class BadgeShowcase(
    /** All six ladders, always — an unstarted ladder renders locked, not hidden. */
    val ladders: List<LadderProgress>,
    /** Standalone milestones the member holds; empty until one is awarded. */
    val milestones: List<MilestoneBadge>,
    /** Distinct catalog badges held (unknown keys excluded). */
    val earnedCount: Int,
    /** Every badge in the catalog — the denominator of "x of y unlocked". */
    val totalCount: Int = BADGE_TOTAL_COUNT,
    /** Award timestamps by badge key, for the detail sheet. */
    val awardedAtByKey: Map<String, Long>,
) {
    /** False → the profile shows the inviting "here is what you can earn" wall. */
    val hasAnyBadge: Boolean get() = earnedCount > 0

    /** Ladders with a rung still to climb, closest-to-done first — the hook. */
    val laddersInProgress: List<LadderProgress>
        get() =
            ladders
                .filterNot { it.isComplete }
                .sortedWith(
                    // Ladders with a real bar lead, most-complete first; the
                    // rest keep catalog order behind them so the list is stable.
                    compareByDescending<LadderProgress> { it.fractionToNext ?: -1f }
                        .thenBy { it.ladder.id.ordinal },
                )

    companion object {
        /**
         * Folds the owner's award documents into the wall.
         *
         * Robust to anything Firestore can hand back: unknown/retired badge keys
         * are ignored, duplicates collapse, and a ladder holding a HIGH rung but
         * missing a lower one (which the monotonic backend never produces) still
         * reports the highest held rung and the next unheld one above it.
         */
        fun from(badges: List<Badge>, counters: BadgeCounters = BadgeCounters.NONE): BadgeShowcase {
            val heldKeys = badges.map { it.key }.toSet()
            val awardedAt =
                badges.mapNotNull { badge -> badge.awardedAtMillis?.let { badge.key to it } }.toMap()

            val ladders =
                BADGE_LADDERS.map { ladder ->
                    val earned = ladder.rungs.filter { it.badgeKey in heldKeys }
                    // The next rung is the lowest UNHELD one, so a gap left by a
                    // partial write is offered again rather than skipped.
                    val next = ladder.rungs.firstOrNull { it.badgeKey !in heldKeys }
                    LadderProgress(
                        ladder = ladder,
                        earnedRungs = earned,
                        nextRung = next,
                        observedValue = counters.observedValue(ladder.id),
                    )
                }

            val milestones =
                BADGE_MILESTONE_KEYS.mapNotNull { key ->
                    badges.firstOrNull { it.key == key }?.let {
                        MilestoneBadge(key = key, fallbackName = it.fallbackName, awardedAtMillis = it.awardedAtMillis)
                    }
                }

            val catalogKeys = BADGE_MILESTONE_KEYS.toSet() + BADGE_LADDERS.flatMap { it.badgeKeys }
            return BadgeShowcase(
                ladders = ladders,
                milestones = milestones,
                earnedCount = heldKeys.count { it in catalogKeys },
                awardedAtByKey = awardedAt,
            )
        }
    }
}
