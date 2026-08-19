package com.kungsbackacarcommunity.app.badges

/**
 * Badge walls, folded from award documents. Two models, and the split between
 * them IS the privacy boundary:
 *
 *  - [BadgeShowcase] — the SIGNED-IN member's own wall: trophies, the climb to
 *    the next rung, and the locked ladders they have not started.
 *  - [PublicBadgeWall] — ANOTHER member's wall: trophies only.
 *
 * PUBLISH THE TROPHIES, NOT THE TELEMETRY. `users/{uid}/badges` is readable by
 * any authenticated user (firebase/firestore.rules) so achievements can be shown
 * off — an award says a threshold was crossed, and when. The counters it was
 * crossed against (`badgeProgress/{uid}`: streak length, lifetime distance,
 * meets attended, crowns collected) are denied to EVERY client, owner included.
 * [PublicBadgeWall] therefore has no counter field, no next rung and no
 * fraction: it cannot carry progress even by accident, because there is nowhere
 * to put it — see `PublicBadgeWallTest`.
 *
 * WHERE THE OWN-PROFILE PROGRESS NUMBERS COME FROM. Not even the owner can READ
 * `badgeProgress` directly (the rule denies it to every client), so the seven
 * authoritative counters are handed to the OWN client by an owner-only callable,
 * `badges-getMyProgress`, which returns a read-only projection of exactly this
 * member's document (issue #799 — see functions/src/badges/getMyProgress.ts and
 * badges/FirebaseBadgeProgressRepository). Two things are additionally always
 * known and exact from data the client already holds:
 *
 *  - which rungs are held, from the award documents; and
 *  - what the next rung needs, from [BADGE_LADDERS].
 *
 * A numeric bar is drawn on every ladder whose counter [BadgeCounters] carries;
 * when the callable has not resolved (or is unavailable in a config-less build)
 * the counters are absent and a ladder falls back to showing its next rung and
 * requirement without inventing a number. Nothing here estimates or guesses.
 *
 * Pure Kotlin — no Android, no Firebase, fully unit-testable.
 */

/**
 * The signed-in member's OWN server-verified ladder counters, one per ladder.
 *
 * Sourced from the owner-only `badges-getMyProgress` callable, which projects
 * the backend-only `badgeProgress/{uid}` document (denied to every client) into
 * these seven numbers for the caller's own uid. A field is null only when the
 * value is not yet known — the callable has not resolved, failed, or is absent
 * in a config-less build — in which case that ladder simply shows no bar; it is
 * never a fabricated or estimated number.
 *
 * OWN PROFILE ONLY. These describe the signed-in member's own progress and are
 * fetched from their own owner-scoped callable. They must never be built for
 * another uid — which is why [PublicBadgeWall] accepts no counters at all and
 * another member's wall stays trophies-only.
 */
data class BadgeCounters(
    val crownsCollected: Long? = null,
    val lifetimeDistanceMeters: Long? = null,
    val verifiedEventsAttended: Long? = null,
    val bestDayStreak: Long? = null,
    val convoysLed: Long? = null,
    val vehiclesInGarage: Long? = null,
    val seasonsWon: Long? = null,
    val wavesSent: Long? = null,
) {
    /**
     * The observable value for [ladder], or null when the client does not yet
     * hold that counter. Every ladder now maps to a counter — the server hands
     * over all eight — so a bar is drawn for each ladder whose number is known.
     * The counters arrive already sanitised (finite, non-negative, floored) from
     * the callable; a stray negative is still floored out here as defence.
     */
    fun observedValue(ladder: BadgeLadderId): Long? =
        when (ladder) {
            BadgeLadderId.KRONJAGARE -> crownsCollected
            BadgeLadderId.VAGFARARE -> lifetimeDistanceMeters
            BadgeLadderId.TRAFFRAV -> verifiedEventsAttended
            BadgeLadderId.TROGEN -> bestDayStreak
            BadgeLadderId.KONVOJLEDARE -> convoysLed
            BadgeLadderId.SAMLARE -> vehiclesInGarage
            BadgeLadderId.SASONGSMASTARE -> seasonsWon
            BadgeLadderId.VINKARE -> wavesSent
        }?.takeIf { it >= 0 }

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

/**
 * One earned award as the always-visible SUMMARY strip renders it: a single held
 * ladder tier OR a standalone milestone, each its own item.
 *
 * This is the granularity the summary was changed to — a flat recency list, not
 * the old per-ladder medallion. Two tiers of the same ladder are TWO entries
 * here, and a milestone ([isMilestone]) is an entry too, so the strip shows
 * exactly what the member has unlocked. For a member holding [RECENT_AWARDS_LIMIT]
 * or fewer awards the summary therefore shows all of them, and its item count
 * equals [BadgeShowcase.earnedCount] — which is what makes the headline
 * "x of y unlocked" agree with the strip below it.
 */
data class EarnedAward(
    val badgeKey: String,
    /** The ladder this tier belongs to, or null for a standalone milestone. */
    val ladderId: BadgeLadderId?,
    /** The tier's rank, or null for a milestone (which has none). */
    val tier: BadgeTier?,
    /** The award doc's denormalized name — the fallback for a milestone with no catalog string. */
    val fallbackName: String?,
    /** When it was acquired; null-dated awards sort last, never first. */
    val awardedAtMillis: Long?,
) {
    /** True for a standalone milestone, false for a ladder tier. */
    val isMilestone: Boolean get() = ladderId == null
}

/** The whole own-profile badge wall. */
data class BadgeShowcase(
    /** All six ladders, always — an unstarted ladder renders locked, not hidden. */
    val ladders: List<LadderProgress>,
    /** Standalone milestones the member holds; empty until one is awarded. */
    val milestones: List<MilestoneBadge>,
    /**
     * The member's earned awards, newest-acquired first, capped at
     * [RECENT_AWARDS_LIMIT] — the source of the always-visible summary strip.
     * Each held ladder tier and each milestone is its own entry (unknown/retired
     * keys excluded, duplicates collapsed), so when the member holds
     * [RECENT_AWARDS_LIMIT] or fewer awards this holds all of them and
     * `recentAwards.size == earnedCount`.
     */
    val recentAwards: List<EarnedAward>,
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
        /** The always-visible summary strip shows at most this many awards, newest first. */
        const val RECENT_AWARDS_LIMIT = 6

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
            // Newest timestamp per key: were the same key ever to arrive on more
            // than one doc, the detail sheet shows its most recent award, and that
            // matches how the recency strip collapses duplicates below.
            val awardedAt =
                badges
                    .mapNotNull { badge -> badge.awardedAtMillis?.let { badge.key to it } }
                    .groupingBy { it.first }
                    .fold(Long.MIN_VALUE) { acc, (_, millis) -> maxOf(acc, millis) }

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

            val milestoneKeys = BADGE_MILESTONE_KEYS.toSet()
            val catalogKeys = milestoneKeys + BADGE_LADDERS.flatMap { it.badgeKeys }

            // The summary strip source: one flat entry per HELD catalog badge —
            // every ladder tier and every milestone — newest acquired first.
            // Unknown/retired keys are dropped and duplicate award docs collapse,
            // so the uncapped list has exactly `earnedCount` entries and the cap
            // is the only reason it can ever hold fewer than the count.
            val recentAwards =
                badges
                    .mapNotNull { badge ->
                        val rung = rungForBadgeKey(badge.key)
                        when {
                            rung != null ->
                                EarnedAward(
                                    badgeKey = badge.key,
                                    ladderId = rung.first.id,
                                    tier = rung.second.tier,
                                    fallbackName = null,
                                    awardedAtMillis = badge.awardedAtMillis,
                                )
                            badge.key in milestoneKeys ->
                                EarnedAward(
                                    badgeKey = badge.key,
                                    ladderId = null,
                                    tier = null,
                                    fallbackName = badge.fallbackName,
                                    awardedAtMillis = badge.awardedAtMillis,
                                )
                            else -> null
                        }
                    }
                    // Newest first; an undated award sorts last, and ties break on
                    // the (frozen) key so the order is fully deterministic.
                    .sortedWith(
                        compareByDescending<EarnedAward> { it.awardedAtMillis ?: Long.MIN_VALUE }
                            .thenBy { it.badgeKey },
                    )
                    // Collapse duplicate docs for the same key AFTER sorting, so the
                    // survivor is the NEWEST one — never an older/undated doc. This
                    // keeps recency consistent with `awardedAtByKey`, which likewise
                    // carries the newest timestamp per key.
                    .distinctBy { it.badgeKey }
                    .take(RECENT_AWARDS_LIMIT)

            return BadgeShowcase(
                ladders = ladders,
                milestones = milestones,
                recentAwards = recentAwards,
                earnedCount = heldKeys.count { it in catalogKeys },
                awardedAtByKey = awardedAt,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Another member's wall — trophies only
// ---------------------------------------------------------------------------

/** The top rung another member has reached on one ladder. */
data class PublicLadderStanding(
    val ladder: BadgeLadder,
    /** Highest rung held on this ladder. Never null: unstarted ladders are omitted. */
    val highestRung: BadgeRung,
    /** When that rung was awarded, when the document carries a date. */
    val awardedAtMillis: Long?,
)

/**
 * ANOTHER member's badge wall, as the read-only member-profile screen shows it.
 *
 * DELIBERATELY POORER THAN [BadgeShowcase], in two ways:
 *
 *  1. NO PROGRESS, EVER. There is no counter, no next rung and no fraction on
 *     this type or on [PublicLadderStanding], and [from] takes nothing but the
 *     award list — so a progress number cannot leak onto another member's
 *     profile even by a wiring mistake. The climb is yours alone; the trophies
 *     are the part meant to be shown off. (`badgeProgress/{uid}` is denied to
 *     every client anyway, but the boundary is enforced here in the type as
 *     well, not only in the rules.)
 *  2. ONLY WHAT THEY HOLD. Ladders they have not started are omitted rather
 *     than rendered as greyed goals: an unstarted ladder is a to-do list, which
 *     belongs on your own profile as motivation and on nobody else's as a
 *     scoreboard of what they have not done.
 *
 * Unknown/retired badge keys are ignored, exactly as on the own-profile wall, so
 * a future catalog key cannot break an older client's rendering.
 */
data class PublicBadgeWall(
    /** Started ladders only, in catalog order. Empty when nothing is held. */
    val ladders: List<PublicLadderStanding>,
    /** Standalone milestones held, in catalog order. */
    val milestones: List<MilestoneBadge>,
    /** Distinct catalog badges held (unknown keys excluded). */
    val earnedCount: Int,
    /** Every badge in the catalog — the denominator of "x of y unlocked". */
    val totalCount: Int = BADGE_TOTAL_COUNT,
) {
    /** False → the profile shows a neutral "no awards yet" line, not an empty grid. */
    val hasAnyBadge: Boolean get() = earnedCount > 0

    companion object {
        fun from(badges: List<Badge>): PublicBadgeWall {
            val heldKeys = badges.map { it.key }.toSet()
            val awardedAt =
                badges.mapNotNull { badge -> badge.awardedAtMillis?.let { badge.key to it } }.toMap()

            val ladders =
                BADGE_LADDERS.mapNotNull { ladder ->
                    val highest = ladder.rungs.lastOrNull { it.badgeKey in heldKeys } ?: return@mapNotNull null
                    PublicLadderStanding(
                        ladder = ladder,
                        highestRung = highest,
                        awardedAtMillis = awardedAt[highest.badgeKey],
                    )
                }

            val milestones =
                BADGE_MILESTONE_KEYS.mapNotNull { key ->
                    badges.firstOrNull { it.key == key }?.let {
                        MilestoneBadge(key = key, fallbackName = it.fallbackName, awardedAtMillis = it.awardedAtMillis)
                    }
                }

            val catalogKeys = BADGE_MILESTONE_KEYS.toSet() + BADGE_LADDERS.flatMap { it.badgeKeys }
            return PublicBadgeWall(
                ladders = ladders,
                milestones = milestones,
                earnedCount = heldKeys.count { it in catalogKeys },
            )
        }
    }
}
