package com.kungsbackacarcommunity.app.profile

import com.kungsbackacarcommunity.app.drives.DriveStats

/**
 * At-a-glance activity summary shown on the member's OWN profile.
 *
 * Every figure is aggregated from data the profile route ALREADY reads — there
 * is no dedicated backend aggregate and no new query beyond the owner-scoped
 * listeners the app opens elsewhere:
 * - drive totals come from the same owner drives list the History route folds
 *   ([DriveStats] via `DriveStatsCalculator`); nothing is recomputed here;
 * - [badgeCount] is the size of the owner's `users/{uid}/badges` list;
 * - [pointsBalance] is the single `pointsLedger/{uid}.balance` doc;
 * - [memberSinceMillis] is `users/{uid}.createdAt`, carried on the profile
 *   snapshot itself (zero extra reads).
 *
 * Convoys-joined / events-attended are deliberately NOT here: they would each
 * need a new membership query (and composite index), i.e. backend work, so they
 * are out of scope for this reads-only summary.
 */
data class ProfileStatsSummary(
    /** Lifetime saved-drive count (0 when the member has no drives). */
    val totalDrives: Int,
    /** Lifetime distance across all saved drives, in metres. */
    val totalDistanceMeters: Double,
    /** Lifetime driving time across all saved drives, in seconds. */
    val totalDurationSeconds: Long,
    /**
     * Highest single-drive max speed (m/s) on record, or null when no saved drive
     * stored one. Rendered as a neutral fact at the same weight as the other
     * totals — see [DriveStats.highestMaxSpeedMps].
     */
    val highestMaxSpeedMps: Double? = null,
    /** Number of earned awards (badges). */
    val badgeCount: Int,
    /** Kronpoäng balance, or null when the wallet has not been read yet. */
    val pointsBalance: Long?,
    /** Account creation instant (epoch-millis), or null when unknown. */
    val memberSinceMillis: Long?,
) {
    /**
     * True once the member has done ANYTHING worth summarising — at least one
     * saved drive or one earned award. Points and "member since" alone do not
     * count: a brand-new member has a balance of 0 and a creation date but no
     * activity, and should see the encouraging empty state rather than a wall of
     * zeroes.
     */
    val hasActivity: Boolean
        get() = totalDrives > 0 || badgeCount > 0

    companion object {
        /**
         * Assembles the summary from the already-loaded pieces. Pure and
         * deterministic so it is unit-testable without Firebase.
         *
         * @param driveStats the folded drive totals, or null when the drives
         *   list is empty or still loading — either way the drive figures read
         *   as zero here.
         */
        fun from(
            driveStats: DriveStats?,
            badgeCount: Int,
            pointsBalance: Long?,
            memberSinceMillis: Long?,
        ): ProfileStatsSummary =
            ProfileStatsSummary(
                totalDrives = driveStats?.totalDrives ?: 0,
                totalDistanceMeters = driveStats?.totalDistanceMeters ?: 0.0,
                totalDurationSeconds = driveStats?.totalDurationSeconds ?: 0L,
                highestMaxSpeedMps = driveStats?.highestMaxSpeedMps,
                badgeCount = badgeCount.coerceAtLeast(0),
                pointsBalance = pointsBalance,
                memberSinceMillis = memberSinceMillis,
            )
    }
}
