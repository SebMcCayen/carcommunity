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
 * WHAT THIS IS. It carries the tiers the member has EARNED and the next tier's
 * crown threshold — both exact and reachable from data the app already reads
 * (`users/{uid}/badges`, public since the badge wall shipped) — plus, when known,
 * the member's lifetime crowns for an honest "9 / 10 crowns" progress line.
 *
 * WHERE THE COUNT COMES FROM (and where it does NOT). The progress figure is the
 * client-readable all-time leaderboard mirror
 * (`crownHuntLeaderboardEntries/alltime__{uid}.crownsCollected`), which the hub
 * already reads for the personal-stats card. It is NOT read from
 * `badgeProgress/{uid}` — that counter is denied to EVERY client, owner included
 * (anti-abuse + privacy, see the rule's own comment). The two are reconciled to
 * the same number (running-max, see the badges summary), so the leaderboard figure
 * is honest, not a fabricated estimate. When it has not loaded the count is null
 * and the card falls back to the fixed goal line — the profile badge wall's
 * Kronjägare `BadgeCounters` value stays null there because that surface does not
 * read the leaderboard mirror.
 *
 * @param highestTier the top Kronjägare rung held, or null when none is earned.
 * @param nextTier the next rung to reach, or null once every rung is held.
 * @param nextThresholdCrowns crowns the [nextTier] requires (10 / 50 / 250 /
 *   1000), or null once maxed. Read straight from the frozen [BADGE_LADDERS]
 *   catalog — a fixed goal line, not an estimate of the member's progress.
 * @param crownsCollected the member's lifetime crowns collected, when known — the
 *   client-readable all-time leaderboard mirror (`crownHuntLeaderboardEntries/
 *   alltime__{uid}.crownsCollected`), NOT the rules-denied `badgeProgress/{uid}`.
 *   The leaderboard counter is reconciled up to the same number the ladder is
 *   derived from (running-max, see the badges summary), so showing "9 / 10 crowns
 *   to Bronze" here is honest, not a fabricated estimate. Null until the stats
 *   read resolves (or when it fails) — the card then falls back to the fixed goal
 *   line with no progress figure.
 */
data class KronjagareStanding(
    val highestTier: BadgeTier?,
    val nextTier: BadgeTier?,
    val nextThresholdCrowns: Long?,
    val crownsCollected: Long? = null,
) {
    /** True once every Kronjägare rung is held — a top-rank note, no next goal. */
    val isComplete: Boolean get() = nextTier == null

    /** True when nothing on the ladder is earned yet — an invitation, not a stat. */
    val isUnstarted: Boolean get() = highestTier == null

    /**
     * Crowns to show against the [nextThresholdCrowns] goal line, or null when the
     * count is unknown or every rung is held. Clamped to the goal so a member who
     * has already crossed the threshold (the badge award trails the counter by a
     * trigger/sweep tick) never renders "11 / 10" — it reads "10 / 10" until the
     * award lands and the next rung takes over.
     */
    val crownsTowardNext: Long?
        get() {
            val threshold = nextThresholdCrowns ?: return null
            val collected = crownsCollected ?: return null
            return collected.coerceIn(0L, threshold)
        }
}

object CrownHuntStats {
    /**
     * Folds the member's own award documents into their Kronjägare standing.
     *
     * Reuses the same [BadgeShowcase] fold the profile badge wall runs, so the
     * "which rung is held / what is next" answer here can never disagree with the
     * profile's. Pure Kotlin — no Firebase, no Android — so the empty-vs-loaded
     * page decision and this derivation are both unit-tested off-device.
     *
     * @param crownsCollected the member's lifetime crowns from the client-readable
     *   all-time leaderboard mirror, or null when the stats read has not resolved.
     *   Passed straight through to [KronjagareStanding.crownsCollected] so the card
     *   can draw honest progress toward the next rung — see that field's contract.
     */
    fun kronjagare(badges: List<Badge>, crownsCollected: Long? = null): KronjagareStanding {
        val ladder =
            BadgeShowcase.from(badges).ladders.first { it.ladder.id == BadgeLadderId.KRONJAGARE }
        return KronjagareStanding(
            highestTier = ladder.highestRung?.tier,
            nextTier = ladder.nextRung?.tier,
            nextThresholdCrowns = ladder.nextRung?.threshold,
            crownsCollected = crownsCollected,
        )
    }
}
