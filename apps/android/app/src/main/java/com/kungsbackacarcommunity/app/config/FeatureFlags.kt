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
}

class FeatureFlags private constructor(private val values: Map<FeatureFlag, Boolean>) {

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
