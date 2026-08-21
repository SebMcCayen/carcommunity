package com.kungsbackacarcommunity.app.crownhunt

/**
 * PURE (Firebase-free, Compose-free) decisions behind the Kronjakt perk map
 * layer, so the "what to draw" logic is JVM-unit-testable independently of the
 * projection/animation (PerkMapVisualsTest). The Compose rendering lives in
 * PerkMapOverlays.kt.
 *
 * Two indicators, all hung on the member's OWN map presence:
 *  - the PLACER-ONLY spike-strip markers (own armed traps — a Spikmatta is
 *    invisible to everyone else, so this only ever draws the caller's own);
 *  - the own-dot SHIELD aura and DOUBLE-POINTS effect while each is active.
 */
object PerkMapVisuals {

    /**
     * The still-live subset of the caller's own traps at [nowMillis]. The query's
     * lower bound is set generously into the past (a margin, so a just-expired trap
     * still arrives), so the precise live/expired cut is done HERE against a moving
     * now — a trap that expires while the map stays open then drops off without a
     * Firestore re-emit, mirroring PerkDeploy.liveTrapCount.
     */
    fun liveTraps(traps: List<OwnTrapMarker>, nowMillis: Long): List<OwnTrapMarker> =
        traps.filter { it.expiresAtMillis > nowMillis }

    /** True while [expiresAtMillis] is strictly in the future at [nowMillis]. */
    fun isEffectActive(expiresAtMillis: Long?, nowMillis: Long): Boolean =
        expiresAtMillis != null && expiresAtMillis > nowMillis

    /**
     * The FULL lifetime of a deployed Spikmatta, in ms — the fallback span for the
     * remaining-life bar when a trap has no usable `createdAt`. Mirrors the server's
     * `TRAP_DURATION_HOURS` (functions `crownHunt/deployPerk.ts`, 6 h): the effect
     * parameters are deliberately NOT mirrored to the client, so this is the client
     * side's copy of the one value the bar needs. Only used as a fallback — when the
     * trap carries its own `createdAt` the bar measures the real `expiresAt −
     * createdAt` span instead, so a server retune stays honest without an app
     * release.
     */
    const val TRAP_FULL_LIFETIME_MS: Long = 6L * 60L * 60L * 1_000L

    /**
     * The 0..1 fraction of a deployed trap's lifetime STILL REMAINING at
     * [nowMillis] — drives the depleting bar under the trap glyph (full = 1 at
     * deploy, empty = 0 at expiry). Pure, so the full/half/near-zero/expired/clamped
     * behaviour is unit-tested without Compose or a clock.
     *
     * The total span is the trap's real `expiresAt − deployedAt` when a
     * [deployedAtMillis] is known (honest across a server-side TTL retune), else the
     * known [fullLifetimeMs] fallback. The remaining time is clamped into
     * `[0, total]` so a clock skew that puts `now` before the deploy cannot exceed 1
     * and an expired trap resolves to exactly 0.
     */
    fun remainingLifeFraction(
        expiresAtMillis: Long,
        deployedAtMillis: Long?,
        nowMillis: Long,
        fullLifetimeMs: Long = TRAP_FULL_LIFETIME_MS,
    ): Float {
        val totalMs =
            deployedAtMillis
                ?.let { expiresAtMillis - it }
                ?.takeIf { it > 0L }
                ?: fullLifetimeMs
        if (totalMs <= 0L) return 0f
        val remainingMs = (expiresAtMillis - nowMillis).coerceIn(0L, totalMs)
        return (remainingMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * The remaining time until [expiresAtMillis] broken into whole hours / minutes /
     * seconds, for the tapped-trap detail popup's live "2 min 30 s kvar" countdown.
     * Pure so the h/m/s carve-up (and the expired → all-zero case) is unit-testable.
     *
     * Seconds are rounded UP within the remaining minute so a trap with 90 s left
     * reads "1 min 30 s", and the readout only hits "0 s" exactly at expiry rather
     * than a second early. Past expiry every field is 0 ([RemainingClock.isExpired]).
     */
    fun remainingClock(expiresAtMillis: Long, nowMillis: Long): RemainingClock {
        val remainingMs = expiresAtMillis - nowMillis
        if (remainingMs <= 0L) return RemainingClock(0, 0, 0)
        val totalSeconds = (remainingMs + 999L) / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return RemainingClock(hours.toInt(), minutes.toInt(), seconds.toInt())
    }

    /** A remaining-time countdown split into whole hours / minutes / seconds. */
    data class RemainingClock(val hours: Int, val minutes: Int, val seconds: Int) {
        /** True when nothing is left — the trap has expired. */
        val isExpired: Boolean = hours == 0 && minutes == 0 && seconds == 0
    }

    /**
     * The 0..1 "appear" fraction of the double-points "+" glyph at [index] of
     * [total], given a global 0..1 [progress] sweep. Each glyph opens over its own
     * slice of the sweep so the plus signs fade / scale in one after another (a
     * staggered bloom) rather than all at once, and the whole ring is fully open at
     * progress = 1. Pure, so the stagger curve is unit-tested without Compose or a
     * running animation clock.
     */
    fun staggeredAppearAlpha(index: Int, total: Int, progress: Float): Float {
        if (total <= 0 || index < 0 || index >= total) return 0f
        val p = progress.coerceIn(0f, 1f)
        val start = index.toFloat() / total
        val span = 1f / total
        return ((p - start) / span).coerceIn(0f, 1f)
    }

    /**
     * Whether the own-dot overlay has anything to draw: a live shield, a live
     * boost, or at least one live own trap. When false the overlay composes
     * nothing at all, so a member with no active perk adds no map layer.
     */
    fun hasAnything(
        traps: List<OwnTrapMarker>,
        shieldActiveUntilMillis: Long?,
        boostActiveUntilMillis: Long?,
        nowMillis: Long,
    ): Boolean =
        liveTraps(traps, nowMillis).isNotEmpty() ||
            isEffectActive(shieldActiveUntilMillis, nowMillis) ||
            isEffectActive(boostActiveUntilMillis, nowMillis)
}
