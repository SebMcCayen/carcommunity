package com.kungsbackacarcommunity.app.crownhunt

/** Local evidence survives closing a card, but never an identity/map lifecycle change. */
class CrownReadinessSession {
    private val tracker = CrownFixTracker()

    fun record(fix: CrownFix) = tracker.record(fix)
    fun clear() = tracker.clear()

    fun snapshot(
        nowMillis: Long,
        crown: CrownSpawn?,
        requireLiveFix: Boolean = crown != null,
    ): CrownReadinessSnapshot {
        val (current, previous) = CrownProofSelection.selectFixes(tracker, nowMillis, crown)
        val latest = tracker.latest?.takeIf {
            nowMillis - it.recordedAtMillis in 0..CrownSpawnLimits.MAX_POSITION_AGE_SECONDS * 1000
        }
        if (requireLiveFix && (latest == null || nowMillis - latest.recordedAtMillis > MAX_INTERACTION_FIX_AGE_MS)) {
            return CrownReadinessSnapshot()
        }
        return CrownReadinessSnapshot(
            current, previous, latest,
            if (previous == null) tracker.secondsUntilProofReady(nowMillis, CrownProofSelection.inRangePredicate(crown)) else null,
        )
    }

    fun proofForSubmission(nowMillis: Long, crown: CrownSpawn): CrownProofPair? =
        eligibleProof(nowMillis, crown, requireLiveFix = true)

    private fun eligibleProof(nowMillis: Long, crown: CrownSpawn, requireLiveFix: Boolean): CrownProofPair? {
        val snapshot = snapshot(nowMillis, crown, requireLiveFix)
        val current = snapshot.current ?: return null
        val previous = snapshot.previous ?: return null
        val latest = snapshot.latest ?: return null
        val distance = CrownSpawnQuery.distanceMeters(latest.latitude, latest.longitude, crown.latitude, crown.longitude)
        val state = CrownCollectGate.evaluate(
            featureEnabled = true,
            distanceMeters = distance,
            speedMetersPerSecond = CrownCollectGate.liveMovementSpeedMps(current, previous, latest),
            collectRadiusMeters = crown.collectRadiusMeters,
            dwellProofReady = CrownCollectGate.isDwellProofUsable(previous, current),
            accuracyMeters = latest.accuracyMeters,
        )
        return if (state == CrownCollectState.Ready) CrownProofPair(current, previous) else null
    }

    /** Renew before evidence expires; a warm parked map does not hold a 1 Hz request. */
    fun prewarmPauseMillis(nowMillis: Long, crowns: List<CrownSpawn>): Long {
        val current = crowns.firstNotNullOfOrNull { eligibleProof(nowMillis, it, requireLiveFix = false)?.current } ?: return 0
        return (current.recordedAtMillis + PREWARM_RENEW_AGE_MS - nowMillis).coerceIn(0, PREWARM_RENEW_AGE_MS)
    }

    companion object {
        const val MAX_INTERACTION_FIX_AGE_MS = 2_000L
        const val UPDATE_INTERVAL_MS = 1_000L
        const val READINESS_TICK_MS = 250L
        const val PREWARM_BURST_MS = 8_000L
        const val PREWARM_RETRY_MS = 3_000L
        const val PREWARM_RENEW_AGE_MS = 8_000L
    }
}

data class CrownReadinessSnapshot(
    val current: CrownFix? = null,
    val previous: CrownFix? = null,
    val latest: CrownFix? = null,
    val secondsRemaining: Int? = null,
)
