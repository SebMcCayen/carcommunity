package com.kungsbackacarcommunity.app.map

import com.kungsbackacarcommunity.app.live.LiveMarker
import com.kungsbackacarcommunity.app.map.ConvoyEdgeGeometry.ProjectedPoint
import java.time.Instant

/**
 * One convoy member's known live position, as the map awareness layer sees it.
 *
 * [imagePath] is the Storage path of the member's garage MAIN-CAR photo — the
 * same field the live marker already carries ([com.kungsbackacarcommunity.app.live.LiveMainCar.imagePath])
 * and the same identity the map uses for a member elsewhere. The arrow does not
 * invent a second way of saying who someone is; it renders the same car photo
 * the on-screen marker does, so an arrow turning into a marker is continuous.
 *
 * [updatedAtMillis] is when the position was recorded, or null when the producer
 * did not say. Null is NOT treated as stale — a member we can see but cannot
 * date is better shown than silently dropped.
 */
data class ConvoyMemberPosition(
    val uid: String,
    val latitude: Double,
    val longitude: Double,
    val displayName: String? = null,
    val imagePath: String? = null,
    val updatedAtMillis: Long? = null,
    /**
     * The publishing fix's own reported accuracy in metres, or null when the
     * publisher did not send one.
     *
     * Carried purely so [LiveMarkerSmoother] can tell a GPS fix from a
     * cell-derived one BEFORE it moves a marker. An implied-speed test cannot:
     * a parked publisher's samples are three minutes apart, and a 1-2 km error
     * across three minutes looks exactly like ordinary town driving. Null means
     * UNKNOWN, never "bad" — see
     * [com.kungsbackacarcommunity.app.location.LivePositionQuality].
     */
    val accuracyMeters: Double? = null,
)

/**
 * Adapts a live marker into the awareness layer's view of a convoy member.
 *
 * The main-car photo path is carried straight through, so the arrow and the
 * marker show the SAME identity the live-share already publishes rather than a
 * second, parallel one.
 *
 * A `recordedAt` that will not parse is treated as unknown (null) rather than as
 * "now" or as "ancient": an unparseable timestamp says nothing about the
 * position's age in either direction, and guessing in either direction is a way
 * to either hide a live member or point at a ghost.
 */
fun LiveMarker.toConvoyMemberPosition(): ConvoyMemberPosition =
    ConvoyMemberPosition(
        uid = uid,
        latitude = latitude,
        longitude = longitude,
        displayName = displayName,
        imagePath = mainCar?.imagePath,
        updatedAtMillis =
            recordedAtIso?.let { iso -> runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull() },
        accuracyMeters = accuracyMeters,
    )

/**
 * How one convoy member should be drawn this frame.
 *
 * The two states are mutually exclusive by construction — they come out of a
 * single pass over a single list — so a member can never be both an on-screen
 * marker and an edge arrow at the same time.
 */
sealed interface ConvoyMemberPlacement {
    val member: ConvoyMemberPosition

    /** The member is inside the viewport: draw the normal marker at [point]. */
    data class OnScreen(
        override val member: ConvoyMemberPosition,
        val point: ProjectedPoint,
    ) : ConvoyMemberPlacement

    /**
     * The member is outside the viewport: draw an arrow pinned at [point] on the
     * viewport edge, rotated [angleDegrees] clockwise from screen-up.
     *
     * [extraCount] is how many FURTHER off-screen members this arrow stands for
     * (0 when it represents exactly one person) — see
     * [ConvoyArrowPlanner.plan] for how members are merged. [distanceMeters] is
     * the represented member's distance from the camera centre.
     */
    data class OffScreen(
        override val member: ConvoyMemberPosition,
        val point: ProjectedPoint,
        val angleDegrees: Double,
        val distanceMeters: Double,
        val extraCount: Int,
    ) : ConvoyMemberPlacement
}

/** Everything the overlay needs to draw for one camera frame. */
data class ConvoyPlacements(
    val onScreen: List<ConvoyMemberPlacement.OnScreen> = emptyList(),
    val offScreen: List<ConvoyMemberPlacement.OffScreen> = emptyList(),
)

/**
 * Decides, for one camera frame, which convoy members are drawn as normal
 * markers and which as edge arrows.
 *
 * ## Handling many members off screen
 * Ringing the viewport in arrows is worse than useless: at a glance it says
 * nothing, and it covers the map you are trying to drive by. Two rules
 * therefore reduce the set, in this order.
 *
 * 1. **Merge by direction.** Members whose arrows would point within the same
 *    [SECTOR_DEGREES] sector are one arrow — they are, from the driver's seat,
 *    the same answer to "which way?". The arrow takes the NEAREST member's
 *    identity (the one you will meet first) and carries a `+N` badge for the
 *    rest.
 * 2. **Cap.** At most [MAX_ARROWS] arrows survive, the nearest first. Members
 *    in a dropped sector are not lost from the count: they are folded into the
 *    `+N` of the nearest surviving arrow, so the badges across the screen always
 *    add up to every off-screen member.
 *
 * With a 12-sector wheel and a cap of four, a convoy scattered in every
 * direction shows four honest arrows plus counts, not twelve chips fighting for
 * the same corner.
 *
 * ## Degenerate inputs
 * - **Stale position.** Older than [STALE_AFTER_MS] and the member is dropped
 *   entirely — no arrow, no marker. A stale arrow is worse than no arrow: it
 *   points confidently at where somebody used to be.
 * - **Unknown position.** Members without a live position never reach here.
 * - **A member at the camera centre.** Their bearing is undefined. They are also
 *   trivially on screen, so they are classified as [ConvoyMemberPlacement.OnScreen]
 *   without consulting the bearing at all — the projection is authoritative for
 *   anything inside the viewport, and [MIN_ARROW_DISTANCE_METERS] is a second
 *   guard for the pathological case of a projection that claims otherwise.
 * - **The viewer themselves** is filtered by the caller (they are the puck).
 */
object ConvoyArrowPlanner {

    /** Width of one direction bucket. 360 / 30 = 12 sectors around the screen. */
    const val SECTOR_DEGREES: Double = 30.0

    /** Most edge arrows drawn at once, however many members are off screen. */
    const val MAX_ARROWS: Int = 4

    /**
     * Positions older than this are dropped rather than pointed at.
     *
     * MUST stay strictly greater than the stationary publish heartbeat
     * ([com.kungsbackacarcommunity.app.location.BackgroundLocation.STATIONARY_HEARTBEAT_MS],
     * 3 min): a parked but still-alive member republishes only once per heartbeat,
     * so a window at or below the heartbeat would drop them as "stale" in the gap
     * between beats even though they are present — the convoy arrow would vanish
     * and reappear every few minutes. At 4 min there is a full minute of margin
     * over the 3-min heartbeat for publish jitter. The ordering is asserted by a
     * unit test (ConvoyArrowPlannerTest) so the two constants cannot drift.
     *
     * The trade-off runs the other way for a MOVING member who loses signal: their
     * last position now lingers up to 4 min (was 2) before the arrow drops. That is
     * the accepted cost of the 3-min stationary heartbeat's data saving; a stale
     * arrow is still dropped well before it becomes dangerously wrong at road speed.
     */
    const val STALE_AFTER_MS: Long = 4 * 60 * 1000L

    /**
     * A member closer than this to the camera centre never gets an arrow: at
     * that separation the bearing is numerical noise and they are on screen
     * anyway.
     */
    const val MIN_ARROW_DISTANCE_METERS: Double = 5.0

    /**
     * Fallback viewport slack for the inside/outside decision, in device px.
     *
     * Callers that know the display density should pass their own
     * `viewportMarginPx` to [plan] instead — the margin needs to be about the
     * chip's RADIUS so a member is reclassified as off-screen just before their
     * chip starts being clipped, and the chip is sized in dp. A fixed px value
     * is only correct at mdpi: at 3x the chip radius is ~72px while this is 24,
     * so a member would stay a marker while already half off the screen, and
     * flicker between marker and arrow along the edge.
     */
    const val VIEWPORT_MARGIN_PX: Float = 24f

    /**
     * Plan one frame.
     *
     * @param members every convoy member with a known live position, excluding
     *   the viewer.
     * @param project the map SDK's own coordinate→pixel projection. Returning
     *   null covers every case with no honest pixel to place a marker at: no
     *   map/style yet (the stub), OR no TRUSTWORTHY on-screen position (behind a
     *   tilted camera / folded / clamped, which a call site may surface by dropping
     *   a `MapScreenPoint.trustworthy == false` result) — see
     *   MapProjection.screenPositionFor. In all of them the member is treated as
     *   OFF-SCREEN and gets an edge arrow from their bearing, unless they are within
     *   [MIN_ARROW_DISTANCE_METERS] of the camera centre (under the puck), in which
     *   case they get neither. (A genuinely absent map is filtered upstream — the
     *   overlay bails on a null camera — so in practice a null here means
     *   off-screen, not "no map".)
     * @param cameraLatitude / [cameraLongitude] the camera centre — the origin
     *   every bearing is measured from, which is what makes the arrows agree
     *   with what is actually framed rather than with where the user's GPS is.
     * @param cameraBearing the camera's own bearing; see
     *   [ConvoyEdgeGeometry.screenAngleDegrees].
     * @param edgeInsetPx how far in from the viewport edge to pin the arrows.
     * @param nowMillis wall clock, injected so staleness is testable.
     */
    fun plan(
        members: List<ConvoyMemberPosition>,
        cameraLatitude: Double,
        cameraLongitude: Double,
        cameraBearing: Double,
        viewportWidth: Float,
        viewportHeight: Float,
        edgeInsetPx: Float,
        nowMillis: Long,
        viewportMarginPx: Float = VIEWPORT_MARGIN_PX,
        project: (ConvoyMemberPosition) -> ProjectedPoint?,
    ): ConvoyPlacements {
        if (viewportWidth <= 0f || viewportHeight <= 0f) return ConvoyPlacements()

        val onScreen = mutableListOf<ConvoyMemberPlacement.OnScreen>()
        // Off-screen candidates, one per member, before merging and capping.
        val candidates = mutableListOf<Candidate>()

        for (member in members) {
            if (isStale(member.updatedAtMillis, nowMillis)) continue

            val geographicBearing =
                ConvoyEdgeGeometry.initialBearingDegrees(
                    fromLatitude = cameraLatitude,
                    fromLongitude = cameraLongitude,
                    toLatitude = member.latitude,
                    toLongitude = member.longitude,
                )
            val screenAngle =
                ConvoyEdgeGeometry.screenAngleDegrees(geographicBearing, cameraBearing)
            val distance =
                ConvoyEdgeGeometry.distanceMeters(
                    fromLatitude = cameraLatitude,
                    fromLongitude = cameraLongitude,
                    toLatitude = member.latitude,
                    toLongitude = member.longitude,
                )

            val projected = project(member)

            // A NULL projection now means the renderer has no honest on-screen
            // position for this member: they are behind the tilted camera / beyond
            // the horizon / off the projectable globe, and the seam refused to hand
            // back the folded-or-clamped pixel (see MapProjection.screenPositionFor
            // and ConvoyEdgeGeometry.projectionRoundTrips). That is OFF-SCREEN, not
            // absent — so they still get an edge arrow, drawn from their bearing
            // (edgePoint never uses the pixel). The only exception is a member
            // essentially on top of the puck: no honest pixel AND within
            // MIN_ARROW_DISTANCE_METERS means they are under us, so no arrow.
            //
            // (Before the round-trip seam fix, a fold came back as a NON-null
            // folded pixel and was reclassified off-screen by isProjectionTrustworthy
            // below; that path still works for any renderer that returns a raw
            // pixel, so both are handled.)
            if (projected == null) {
                if (distance >= MIN_ARROW_DISTANCE_METERS) {
                    candidates += Candidate(member, screenAngle, distance)
                }
                continue
            }

            // A projection we cannot trust is a point behind a tilted camera
            // folded back into view (see isProjectionTrustworthy): treat it as
            // off screen, because that is where it is.
            val trustworthy =
                ConvoyEdgeGeometry.isProjectionTrustworthy(
                    point = projected,
                    viewportWidth = viewportWidth,
                    viewportHeight = viewportHeight,
                    expectedScreenAngle = screenAngle,
                )

            val inside =
                trustworthy &&
                    ConvoyEdgeGeometry.isInsideViewport(
                        point = projected,
                        viewportWidth = viewportWidth,
                        viewportHeight = viewportHeight,
                        marginPx = viewportMarginPx,
                    )

            if (inside || distance < MIN_ARROW_DISTANCE_METERS) {
                onScreen += ConvoyMemberPlacement.OnScreen(member = member, point = projected)
            } else {
                candidates += Candidate(member, screenAngle, distance)
            }
        }

        return ConvoyPlacements(
            onScreen = onScreen,
            offScreen =
                mergeAndCap(
                    candidates = candidates,
                    viewportWidth = viewportWidth,
                    viewportHeight = viewportHeight,
                    edgeInsetPx = edgeInsetPx,
                ),
        )
    }

    /** Whether a recorded-at stamp is old enough to stop trusting. */
    fun isStale(updatedAtMillis: Long?, nowMillis: Long): Boolean {
        if (updatedAtMillis == null) return false
        return nowMillis - updatedAtMillis > STALE_AFTER_MS
    }

    private fun mergeAndCap(
        candidates: List<Candidate>,
        viewportWidth: Float,
        viewportHeight: Float,
        edgeInsetPx: Float,
    ): List<ConvoyMemberPlacement.OffScreen> {
        if (candidates.isEmpty()) return emptyList()

        // 1. Merge by direction sector; the nearest member in a sector speaks
        //    for it. Ties broken by uid so the arrow does not swap identity
        //    between frames when two members are equidistant.
        val bySector =
            candidates.groupBy { sectorOf(it.screenAngle) }
                .map { (_, inSector) ->
                    val representative =
                        inSector.minWith(
                            compareBy({ it.distanceMeters }, { it.member.uid }),
                        )
                    Merged(representative, extraCount = inSector.size - 1)
                }
                .sortedWith(
                    compareBy(
                        { it.representative.distanceMeters },
                        { it.representative.member.uid },
                    ),
                )

        // 2. Cap. Everyone in a dropped sector is folded into the nearest
        //    surviving arrow's badge, so the counts on screen still account for
        //    every off-screen member.
        val kept = bySector.take(MAX_ARROWS)
        val foldedIn = bySector.drop(MAX_ARROWS).sumOf { it.extraCount + 1 }

        return kept.mapIndexed { index, merged ->
            val angle = merged.representative.screenAngle
            ConvoyMemberPlacement.OffScreen(
                member = merged.representative.member,
                point =
                    ConvoyEdgeGeometry.edgePoint(
                        angleDegrees = angle,
                        viewportWidth = viewportWidth,
                        viewportHeight = viewportHeight,
                        insetPx = edgeInsetPx,
                    ),
                angleDegrees = angle,
                distanceMeters = merged.representative.distanceMeters,
                extraCount = merged.extraCount + if (index == 0) foldedIn else 0,
            )
        }
    }

    /** Which of the 360/[SECTOR_DEGREES] direction buckets an angle falls in. */
    fun sectorOf(screenAngle: Double): Int =
        (ConvoyEdgeGeometry.normalizeDegrees(screenAngle) / SECTOR_DEGREES).toInt()

    private data class Candidate(
        val member: ConvoyMemberPosition,
        val screenAngle: Double,
        val distanceMeters: Double,
    )

    private data class Merged(val representative: Candidate, val extraCount: Int)
}
