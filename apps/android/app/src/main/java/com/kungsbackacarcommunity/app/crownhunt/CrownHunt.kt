package com.kungsbackacarcommunity.app.crownhunt

/**
 * Kronjakt (crown hunt) domain model + pure logic (Phase 12 slice 16).
 *
 * Mirrors the backend crownhunt-core contract: the point status vocabulary,
 * the 11 claim-result codes, and the claim input coordinate. Pure Kotlin —
 * JVM-testable. Result → user message mapping lives in the screen (localized).
 */

/** Point lifecycle status (crownHuntPoints/{id}.status). */
enum class CrownHuntPointStatus(val wire: String) {
    DRAFT("draft"),
    ACTIVE("active"),
    PAUSED("paused"),
    ENDED("ended"),
    ;

    companion object {
        fun fromWire(value: String?): CrownHuntPointStatus? = values().firstOrNull { it.wire == value }
    }
}

/** submitClaim result codes — mirror CROWN_HUNT_CLAIM_RESULTS exactly. */
enum class CrownHuntClaimResult(val wire: String) {
    AWARDED("awarded"),
    ALREADY_CLAIMED("already_claimed"),
    OUTSIDE_GEOFENCE("outside_geofence"),
    MOVING_TOO_FAST("moving_too_fast"),
    POSITION_TOO_OLD("position_too_old"),
    POINT_INACTIVE("point_inactive"),
    COOLDOWN_ACTIVE("cooldown_active"),
    DAILY_LIMIT_REACHED("daily_limit_reached"),
    RISK_REVIEW("risk_review"),
    FEATURE_DISABLED("feature_disabled"),
    NOT_ELIGIBLE("not_eligible"),
    ;

    companion object {
        fun fromWire(value: String?): CrownHuntClaimResult? = values().firstOrNull { it.wire == value }
    }
}

/** An active reward point (crownHuntPoints/{id}), teaser fields for the list. */
data class CrownHuntPoint(
    val id: String,
    val title: String,
    val description: String?,
    val rewardPoints: Int,
    val latitude: Double?,
    val longitude: Double?,
    val geofenceRadiusMeters: Double?,
)

/** The device position submitted with a claim. */
data class ClaimCoordinate(
    val latitude: Double,
    val longitude: Double,
    val recordedAtIso: String,
    val speedMetersPerSecond: Double? = null,
    val accuracyMeters: Double? = null,
)

/** The parsed submitClaim response. */
data class ClaimOutcome(
    val result: CrownHuntClaimResult,
    val pointsAwarded: Int?,
    val newBalance: Int?,
)

object CrownHunt {
    /**
     * Maximum active points the Firestore listener subscribes to (newest
     * first by createdAt). Active Kronjakt points are already expected to be
     * a small, admin-curated set, but the listener is capped regardless so
     * `crownHuntPoints` staying unbounded can never turn into an unbounded
     * snapshot as the game runs over the app's lifetime.
     */
    const val ACTIVE_POINTS_QUERY_LIMIT = 200L
}
