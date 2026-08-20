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
