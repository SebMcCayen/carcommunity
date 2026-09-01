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

    /**
     * The Kronjakt SHOP — the first member-facing Kronpoäng SINK, where members
     * spend Kronpoäng to buy perks. Default OFF: the shop stays dark until it is
     * deliberately switched on, and while off the backend rejects every buy so
     * no Kronpoäng is spent.
     *
     * Registered here so the flag is operable end-to-end (contract, backend,
     * this client, admin console) — the app's shop UI is not built yet, so
     * nothing reads this flag on Android until that later release.
     */
    CROWN_HUNT_PERKS("crownHuntPerks", false),

    /**
     * The Kronjakt live-share SCORING rule. Default OFF. On, a crown collected
     * while the member is NOT sharing a live session pays only half its
     * Kronpoäng, while a crown collected during an active live session pays full
     * (so sharing while collecting doubles the points). The backend owns the
     * multiplier and is fail-open (a sharer is never wrongly penalised).
     *
     * Consumed by the Android UI, which reads this flag ONLY to describe the
     * rule: the Kronjakt instructions gain a "go live for full points" section,
     * and the crown-tap popups show a tip when the flag is on AND the member is
     * not currently live-sharing. Gated so the UI never describes a rule that is
     * off — while OFF the app shows nothing about it. Operable end-to-end
     * (contract, backend, this client, admin console).
     */
    CROWN_HUNT_LIVE_SHARE_SCORING("crownHuntLiveShareScoring", false),

    /**
     * The in-app "Open tickets" browser on the Report-a-problem page: reading
     * the backend `openTickets` mirror of open public GitHub issues and letting
     * a member +1 or comment on one (once each). Default OFF: while off the
     * backend rejects every interaction so nothing is posted to the public repo.
     *
     * Consumed by the Android UI: while OFF the "View open tickets"
     * (Visa öppna ärenden) entry is hidden on the report screen, the OpenTickets
     * route is unreachable and nothing reads the `openTickets` mirror. Operable
     * end-to-end (contract, backend, this client, admin console).
     */
    REPORT_TICKETS_BROWSER("reportTicketsBrowser", false),

    /**
     * Inline WhatsApp-style reply-to-message across the chat surfaces (community,
     * convoy, and DMs). Default OFF: while off the backend ignores any
     * replyToMessageId a client sends (no reply snapshot is stored) and the
     * reply action must stay hidden in the chat UI, so the feature is dark
     * end-to-end until it is deliberately switched on.
     *
     * Registered here so the flag is operable end-to-end (contract, backend,
     * this client, admin console). The Android reply UI lands in a later slice
     * (PR2) and gates its entry point on this flag; nothing reads it on Android
     * until then. Message reactions are a separate future feature.
     */
    CHAT_REPLIES("chatReplies", false),

    /**
     * The Slice-D subscription gate on FULL event details. Default OFF, and that
     * default is load-bearing: billing is not live yet (no member holds a paid
     * tier), so while OFF the app shows full event details AND the attendee list
     * to everyone — exactly as today. On, the exact address + long description and
     * the attendee list become a paid benefit (Plus or Supporter): a free
     * Community viewer sees the basic view (title, date/time, general area, place
     * name, short summary) plus an upgrade prompt, and no "who answered" roster.
     *
     * Read via the LIVE feature-flag listener (the same `flags` StateFlow every
     * other flag uses), combined with the stored subscription tier: while OFF the
     * detail screen is handed isPaidSubscriber=true (full view for all); while ON
     * it is handed the real paid-tier value. The attendee list is ALSO enforced
     * server-side by events-listAttendees behind the same flag. Must stay OFF
     * until Play billing goes live, then be turned on deliberately.
     */
    EVENT_DETAILS_REQUIRE_PAID("eventDetailsRequirePaid", false),
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
