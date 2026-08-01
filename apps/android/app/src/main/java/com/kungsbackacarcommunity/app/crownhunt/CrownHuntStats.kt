package com.kungsbackacarcommunity.app.crownhunt

import com.kungsbackacarcommunity.app.badges.Badge
import com.kungsbackacarcommunity.app.badges.BadgeLadderId
import com.kungsbackacarcommunity.app.badges.BadgeShowcase
import com.kungsbackacarcommunity.app.badges.BadgeTier

/**
 * The member's own Kronjägare (crown-hunter) standing, for the Kronjakt page's
 * stats band — the part that makes the page worth opening even when no crown is
 * nearby.
 *
 * WHAT THIS IS, AND WHAT IT DELIBERATELY IS NOT. It carries the tiers the member
 * has EARNED and the next tier's crown threshold — both exact and reachable from
 * data the app already reads (`users/{uid}/badges`, public since the badge wall
 * shipped). It does NOT carry a crowns-collected count. That counter lives on
 * `badgeProgress/{uid}`, which firebase/firestore.rules denies to EVERY client,
 * owner included (anti-abuse + privacy — see the rule's own comment). The exact
 * "23 / 50 crowns" number therefore cannot be shown honestly on the client, and
 * this model has nowhere to put a fabricated one — exactly the same boundary the
 * profile badge wall enforces (see [BadgeShowcase] / `BadgeCounters`, where the
 * Kronjägare ladder's observable counter is null by design).
 *
 * @param highestTier the top Kronjägare rung held, or null when none is earned.
 * @param nextTier the next rung to reach, or null once every rung is held.
 * @param nextThresholdCrowns crowns the [nextTier] requires (10 / 50 / 250 /
 *   1000), or null once maxed. Read straight from the frozen [BADGE_LADDERS]
 *   catalog — a fixed goal line, not an estimate of the member's progress.
 */
data class KronjagareStanding(
    val highestTier: BadgeTier?,
    val nextTier: BadgeTier?,
    val nextThresholdCrowns: Long?,
) {
    /** True once every Kronjägare rung is held — a top-rank note, no next goal. */
    val isComplete: Boolean get() = nextTier == null

    /** True when nothing on the ladder is earned yet — an invitation, not a stat. */
    val isUnstarted: Boolean get() = highestTier == null
}

object CrownHuntStats {
    /**
     * Folds the member's own award documents into their Kronjägare standing.
     *
     * Reuses the same [BadgeShowcase] fold the profile badge wall runs, so the
     * "which rung is held / what is next" answer here can never disagree with the
     * profile's. Pure Kotlin — no Firebase, no Android — so the empty-vs-loaded
     * page decision and this derivation are both unit-tested off-device.
     */
    fun kronjagare(badges: List<Badge>): KronjagareStanding {
        val ladder =
            BadgeShowcase.from(badges).ladders.first { it.ladder.id == BadgeLadderId.KRONJAGARE }
        return KronjagareStanding(
            highestTier = ladder.highestRung?.tier,
            nextTier = ladder.nextRung?.tier,
            nextThresholdCrowns = ladder.nextRung?.threshold,
        )
    }
}
