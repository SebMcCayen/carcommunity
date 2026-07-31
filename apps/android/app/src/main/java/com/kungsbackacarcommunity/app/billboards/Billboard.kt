package com.kungsbackacarcommunity.app.billboards

/**
 * Digital billboards domain (Phase 12 slice 20). Read active billboards +
 * record interactions. Pure Kotlin.
 */
enum class BillboardInteractionType(val wire: String) {
    IMPRESSION("impression"),
    OPEN("open"),
    NAVIGATE("navigate"),
    PHONE("phone"),
    WEBSITE("website"),
    OFFER_VIEW("offer_view"),
}

/**
 * The kinds of call-to-action a billboard may carry
 * (`packages/shared/src/digital-billboards.ts`).
 *
 * Only the two that the app can actually ACT on carry a value the detail sheet
 * offers as a link — `phone` and `website` — which mirrors the admin form,
 * where the value field only appears for those two. The rest are recognised so
 * an unknown wire string is distinguishable from a legitimately absent CTA.
 */
enum class BillboardCtaType(val wire: String) {
    NAVIGATE("navigate"),
    PHONE("phone"),
    WEBSITE("website"),
    OFFER_VIEW("offer_view"),
    PARTNER_PROFILE("partner_profile"),
    ;

    companion object {
        fun fromWire(wire: String?): BillboardCtaType? = entries.find { it.wire == wire }
    }
}

/**
 * A sponsored billboard the member map may draw (`billboards/{id}`).
 *
 * [latitude]/[longitude] are non-null because a billboard WITHOUT a position is
 * not something this app has anywhere to put: the contract requires both (the
 * admin form marks them mandatory and the create callable validates the
 * ranges), and a document that somehow lacks them is dropped at parse rather
 * than carried around as a marker that cannot be placed.
 *
 * [availableFromMillis]/[availableUntilMillis] are carried purely so the client
 * can re-check the schedule locally — see [BillboardVisibility]. They are NOT
 * the enforcement point; the server is.
 */
data class Billboard(
    val id: String,
    val headline: String,
    val message: String?,
    val companyId: String?,
    val latitude: Double,
    val longitude: Double,
    val callToActionType: BillboardCtaType? = null,
    val callToActionValue: String? = null,
    val availableFromMillis: Long? = null,
    val availableUntilMillis: Long? = null,
)

/**
 * The client half of "an inactive, expired or unscheduled billboard must not
 * render".
 *
 * **This is not the enforcement.** The enforcement is server-side: the read
 * rule on `billboards/{id}` requires `status == 'active' && mapVisible == true`,
 * `mapVisible` is written only by the Admin SDK (the lifecycle callables
 * transactionally, the scheduled sweep for clock-driven transitions), and the
 * repository queries on exactly that field — so a document outside its window
 * is not merely undrawn, it is unreadable. A member who bypasses this app
 * entirely still cannot fetch one.
 *
 * What this adds is LATENCY COVER, and only that. The sweep runs every ten
 * minutes, so there is a bounded interval in which a billboard whose
 * `availableUntil` has just passed is still flagged visible and still sitting
 * in an open snapshot listener on somebody's phone. Re-checking the window the
 * document carries closes that gap on the device without waiting for the sweep,
 * and it costs nothing — the fields are already in hand.
 *
 * Pure and clock-injected, so the boundary behaviour is asserted in a unit test
 * rather than inferred from whichever second the sweep happened to run.
 */
object BillboardVisibility {
    /**
     * Whether [billboard] may be drawn at [nowMillis].
     *
     * The window is half-open, `[from, until)`, matching
     * `isBillboardMapVisible` in `functions/src/billboards/billboards-core.ts`:
     * a billboard whose end instant is exactly now has finished. A null bound
     * means unbounded on that side, which is how an admin says "from
     * activation" or "until I stop it".
     */
    fun isVisible(billboard: Billboard, nowMillis: Long): Boolean {
        val from = billboard.availableFromMillis
        if (from != null && nowMillis < from) return false
        val until = billboard.availableUntilMillis
        if (until != null && nowMillis >= until) return false
        return true
    }

    /** [billboards] filtered to those drawable at [nowMillis], order preserved. */
    fun visibleAt(billboards: List<Billboard>, nowMillis: Long): List<Billboard> =
        billboards.filter { isVisible(it, nowMillis) }

    /**
     * The soonest instant at which [billboards] would produce a DIFFERENT
     * visible set, or null when nothing on the map is time-limited.
     *
     * Lets the map layer sleep exactly until the next boundary instead of
     * re-filtering on a timer — the same shape the event-pin layer uses for its
     * "not past" cutoff. Both bounds count: a billboard scheduled to start in
     * an hour needs a wake-up just as much as one expiring in an hour.
     */
    fun nextBoundaryMillis(billboards: List<Billboard>, nowMillis: Long): Long? =
        billboards
            .flatMap { listOfNotNull(it.availableFromMillis, it.availableUntilMillis) }
            .filter { it > nowMillis }
            .minOrNull()
}

object Billboards {
    /**
     * Maximum billboards the Firestore listener subscribes to (createdAt
     * ascending — the direction of the `billboards` composite index, so the
     * `mapVisible ASC, createdAt ASC` index this query needs matches the one
     * already used for `status ASC, createdAt ASC`). Keeps the snapshot bounded
     * as the collection grows without bound. Trade-off: the oldest visible
     * billboards are kept and the newest fall off past the cap, the reverse of
     * typical "recent first" bounding. Acceptable because billboard activation
     * is an audited admin action (a slow-moving, human-curated set), but if a
     * newest-first cap is wanted later, add a `mapVisible ASC, createdAt DESC`
     * composite index and flip the query direction.
     */
    const val ACTIVE_BILLBOARDS_QUERY_LIMIT = 150L
}
