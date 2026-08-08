package com.kungsbackacarcommunity.app.config

/**
 * Feature flags (Phase 12 slice 3). Keys and contract defaults mirror
 * contracts/features/feature-flags.json exactly — the canonical registry.
 * Pure Kotlin so the merge/lookup logic is JVM-unit-testable.
 *
 * The backend (config/featureFlags) is authoritative; clients read it on
 * launch/resume and fall back to these defaults when the document, or an
 * individual field, is absent or malformed — flags degrade to their
 * documented default, never to "off".
 */
enum class FeatureFlag(val key: String, val default: Boolean) {
    LIVE_LOCATION("liveLocation", true),
    CHAT("chat", true),
    CROWN_HUNT("crownHunt", true),
    PARTNERS("partners", true),
    PARTNER_STATS("partnerStats", true),
    PUSH_NOTIFICATIONS("pushNotifications", true),
    SOCIAL_SHARING("socialSharing", true),
    EXTERNAL_DATA_SOURCES("externalDataSources", true),
    DIGITAL_BILLBOARDS("digitalBillboards", true),

    /** Phase 9j privacy gate: default OFF; also requires explicit opt-in. */
    PARTNER_INSIGHTS_PASS_BY("partnerInsightsPassBy", false),

    /**
     * The AUTO-SPAWNED half of Kronjakt — crowns that appear by themselves and
     * are collected while parked. Default OFF, and unusually for this enum that
     * default is load-bearing rather than cautious.
     *
     * A hand-placed Kronjakt point carries a named admin's confirmation that
     * that exact spot is safe to stop at; an automatically-placed crown cannot.
     * Until the allow-list of approved areas is populated and the operator
     * deliberately switches this on, the client issues NO `crownSpawns` queries
     * and draws no crown layer at all — see `CrownSpawnController`.
     *
     * [CROWN_HUNT] gates the feature as a whole; this gates only the automatic
     * half, so the hand-placed points keep working with this off.
     */
    CROWN_HUNT_SPAWN("crownHuntSpawn", false),
}

/**
 * A `data class` over the fully-populated backing map so equality is
 * structural: two flag sets with the same values compare equal, which lets the
 * store's `StateFlow` dedupe identical realtime-listener emissions instead of
 * recomposing every `collectAsState()` reader on each snapshot. The map always
 * carries every [FeatureFlag] key (see [DEFAULTS]/[fromStored]), so `Map`'s own
 * structural equality is exact here.
 *
 * [ConsistentCopyVisibility] keeps the generated `copy()` as private as the
 * constructor, so callers still can only build a set through [DEFAULTS] or
 * [fromStored] (which guarantee every key is present) — copy can't be used to
 * smuggle in a partially-populated map.
 */
@ConsistentCopyVisibility
data class FeatureFlags private constructor(private val values: Map<FeatureFlag, Boolean>) {

    fun isEnabled(flag: FeatureFlag): Boolean = values[flag] ?: flag.default

    companion object {
        /** All flags at their contract defaults (the offline/fallback view). */
        val DEFAULTS: FeatureFlags = FeatureFlags(FeatureFlag.values().associateWith { it.default })

        /**
         * Overlays a stored config/featureFlags document (camelCase boolean
         * fields) onto the defaults. A missing or non-boolean value keeps the
         * contract default.
         */
        fun fromStored(stored: Map<String, Any?>): FeatureFlags =
            FeatureFlags(
                FeatureFlag.values().associateWith { flag ->
                    (stored[flag.key] as? Boolean) ?: flag.default
                },
            )
    }
}
