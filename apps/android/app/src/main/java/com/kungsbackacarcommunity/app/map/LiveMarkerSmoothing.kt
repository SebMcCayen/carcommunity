package com.kungsbackacarcommunity.app.map

import com.kungsbackacarcommunity.app.drives.DriveSummary
import kotlin.math.abs

/**
 * Stops other people's live markers TELEPORTING around the map.
 *
 * ## The problem this exists for
 * A convoy member's position is published, not streamed: the sharing device
 * only writes a fix once it has moved past
 * [com.kungsbackacarcommunity.app.location.BackgroundLocation.MOVEMENT_THRESHOLD_METERS]
 * (15 m), at a fix cadence of
 * [com.kungsbackacarcommunity.app.location.BackgroundLocation.UPDATE_INTERVAL_MS]
 * (5 s). Drawn naively, the person driving beside you therefore does not move at
 * all for five seconds and then jumps ~100 m in one frame — which is exactly the
 * "icons jumping here and there even though they are driving next to me" this
 * fixes. A bad fix (a reflected/urban-canyon GPS sample) makes it worse: the
 * marker leaps a kilometre sideways and back.
 *
 * ## The two rules
 * 1. **Reject the impossible.** A fix implying more than
 *    [DriveSummary.MAX_PLAUSIBLE_SPEED_MPS] (~200 km/h) since the last accepted
 *    one is a GPS glitch, not a car — the SAME threshold the drive-distance and
 *    top-speed scans already apply, read from [DriveSummary] rather than copied,
 *    so the two can never drift apart. Non-finite / out-of-range coordinates and
 *    out-of-order samples go the same way.
 * 2. **Glide, don't snap.** An accepted fix becomes the TARGET of a short
 *    animation from wherever the marker currently is, so a member crosses the
 *    gap between two fixes continuously instead of teleporting.
 *
 * Everything here is pure Kotlin — no Android, Compose or Mapbox types — because
 * the rendering itself can only be judged on a device, so the accept/reject and
 * position-at-time maths is where the testable value is. [LiveMarkerSmoother]
 * holds the (still Android-free) per-member state; the overlay only asks it
 * "where do I draw everyone right now?".
 */
object LiveMarkerSmoothing {
    /**
     * Shortest glide. Below this the animation is not perceptible as motion and
     * a burst of fixes would render as a stutter rather than a slide.
     */
    const val GLIDE_MIN_MS: Long = 300L

    /**
     * Longest glide, and the reason it is capped: a member who drops out (tunnel,
     * dead signal) and reappears minutes later at a genuinely reachable position
     * must not then crawl across the map for minutes. Their marker slides for at
     * most this long and settles.
     */
    const val GLIDE_MAX_MS: Long = 3_000L

    /**
     * Glide used when the gap between two fixes is not yet known — the second
     * fix of a session, essentially. Roughly a walking-pace slide; the third fix
     * onwards uses the measured cadence.
     */
    const val GLIDE_DEFAULT_MS: Long = 1_200L

    /**
     * Minimum wall time between two rendered frames of a glide (~30 fps).
     *
     * Repositioning a marker means re-projecting every member through the live
     * map (a native call each) and recomposing the overlay, per member, per
     * frame. Thirty steps a second is already past the point where a 100 m slide
     * reads as continuous motion, so the display refresh rate buys nothing and
     * costs projections. Consumed by the overlay's frame loop.
     */
    const val GLIDE_FRAME_INTERVAL_MS: Long = 32L

    /**
     * Whether a coordinate can be drawn at all: finite and inside the WGS-84
     * range. A NaN/infinite latitude or longitude is not a position, and passing
     * one to the map projection yields an undefined pixel — so such a fix is
     * dropped before it can be a target, and a member whose FIRST fix is like
     * that is simply not drawn.
     */
    fun isDrawable(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() &&
            longitude.isFinite() &&
            abs(latitude) <= 90.0 &&
            abs(longitude) <= 180.0

    /**
     * Whether a newly arrived fix should be accepted as the next target, given
     * the last accepted one.
     *
     * Rejected when:
     * - the coordinate is not drawable ([isDrawable]);
     * - the sample is not NEWER than the last accepted one (a non-positive time
     *   delta) — Realtime Database re-delivers an unchanged `latest` node, and an
     *   out-of-order sample would drag the marker backwards;
     * - the implied speed since the last accepted fix exceeds
     *   [DriveSummary.MAX_PLAUSIBLE_SPEED_MPS].
     *
     * A NULL timestamp on either side means the delta is unknown, and an unknown
     * delta is not evidence of a glitch — the fix is accepted rather than
     * silently freezing a member whose publisher does not date its samples. This
     * matches how the convoy planner treats an undateable position (see
     * [ConvoyArrowPlanner]).
     *
     * The timestamps are the PUBLISHER's `recordedAt`, not arrival times: implied
     * speed is a statement about the car, so it has to be measured between the
     * moments the samples were taken, not the moments they happened to arrive.
     */
    fun acceptsFix(
        previousLatitude: Double,
        previousLongitude: Double,
        previousRecordedAtMillis: Long?,
        latitude: Double,
        longitude: Double,
        recordedAtMillis: Long?,
    ): Boolean {
        if (!isDrawable(latitude, longitude)) return false
        if (previousRecordedAtMillis == null || recordedAtMillis == null) return true
        val deltaMillis = recordedAtMillis - previousRecordedAtMillis
        if (deltaMillis <= 0L) return false
        val metres =
            DriveSummary.haversineMetres(
                previousLatitude,
                previousLongitude,
                latitude,
                longitude,
            )
        val impliedSpeedMps = metres / (deltaMillis / 1000.0)
        return impliedSpeedMps.isFinite() &&
            impliedSpeedMps <= DriveSummary.MAX_PLAUSIBLE_SPEED_MPS
    }

    /**
     * How long the glide to a new target should take, given how long it has been
     * since the previous accepted fix ARRIVED.
     *
     * Matching the glide to the observed arrival cadence is what makes the motion
     * continuous rather than stop-start: at the publisher's 5 s cadence the
     * marker is still sliding when the next fix lands, so it never sits frozen
     * and then jumps. Clamped to [GLIDE_MIN_MS]..[GLIDE_MAX_MS]; an unknown or
     * non-positive gap falls back to [GLIDE_DEFAULT_MS].
     *
     * ARRIVAL gap, deliberately — unlike [acceptsFix], which is about the car,
     * this is about the renderer: it should pace itself by how often it is
     * actually fed, network delay included.
     */
    fun glideDurationMillis(arrivalGapMillis: Long?): Long {
        val gap = arrivalGapMillis ?: return GLIDE_DEFAULT_MS
        if (gap <= 0L) return GLIDE_DEFAULT_MS
        return gap.coerceIn(GLIDE_MIN_MS, GLIDE_MAX_MS)
    }

    /**
     * Fraction of the glide completed: 0 at the start, 1 at or after the end,
     * linear in between. Linear on purpose — the thing being animated is a car
     * travelling at a roughly constant speed, so easing in and out of every fix
     * would invent an acceleration that is not there.
     *
     * A non-positive duration is "already arrived" (1.0), so a member with no
     * glide yet renders exactly at their fix.
     */
    fun progress(elapsedMillis: Long, durationMillis: Long): Double {
        if (durationMillis <= 0L) return 1.0
        if (elapsedMillis <= 0L) return 0.0
        if (elapsedMillis >= durationMillis) return 1.0
        return elapsedMillis.toDouble() / durationMillis.toDouble()
    }

    /**
     * Latitude [fraction] of the way from [from] to [to]. Endpoints are returned
     * EXACTLY (no floating-point drift at either end), so a settled marker sits
     * precisely on its reported position.
     */
    fun lerpLatitude(from: Double, to: Double, fraction: Double): Double =
        when {
            fraction <= 0.0 -> from
            fraction >= 1.0 -> to
            else -> from + (to - from) * fraction
        }

    /**
     * Longitude [fraction] of the way from [from] to [to], the SHORT way round.
     *
     * Interpolating longitude naively crosses the whole globe when a pair
     * straddles the antimeridian (179.9 → -179.9 would sweep westwards through
     * Europe instead of stepping 0.2 degrees east). The delta is therefore
     * normalized into (-180, 180] first and the result wrapped back into
     * [-180, 180). Irrelevant in Kungsbacka; free to get right, and a lurking
     * 20 000 km glide otherwise. Endpoints are returned exactly, as in
     * [lerpLatitude].
     */
    fun lerpLongitude(from: Double, to: Double, fraction: Double): Double {
        if (fraction <= 0.0) return from
        if (fraction >= 1.0) return to
        var delta = (to - from) % 360.0
        if (delta > 180.0) delta -= 360.0
        if (delta < -180.0) delta += 360.0
        val value = from + delta * fraction
        return ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    }
}

/**
 * Per-member glide state for the live markers currently on the map.
 *
 * One instance is held by the overlay for as long as it is composed. It is NOT
 * thread-safe and is not meant to be: every call comes from the main thread
 * (the roster arrives through a collected flow, the frames through the Compose
 * frame clock).
 *
 * Cost is bounded by the roster: at most one entry per convoy member (so at most
 * `MAX_CONVOY_INVITEES`), each a single mutable object that is UPDATED IN PLACE
 * on every fix rather than replaced — a newer fix overwrites the running glide's
 * target instead of stacking a second animation on top of it, so nothing can
 * accumulate. Members who stop sharing are pruned on the next roster.
 */
class LiveMarkerSmoother {

    /**
     * One member's glide: where the marker is coming FROM, where it is going TO,
     * when that started and how long it takes, plus the bookkeeping the accept
     * rule needs about the last fix that was allowed through.
     */
    private class Track(
        var fromLatitude: Double,
        var fromLongitude: Double,
        var toLatitude: Double,
        var toLongitude: Double,
        var startedAtMillis: Long,
        var durationMillis: Long,
        var acceptedRecordedAtMillis: Long?,
        var acceptedAtMillis: Long,
    )

    private val tracks = HashMap<String, Track>()

    /**
     * Feed a fresh roster snapshot, at wall time [nowMillis].
     *
     * Each member's position is accepted or rejected by
     * [LiveMarkerSmoothing.acceptsFix]; an accepted position that actually MOVED
     * restarts the glide from wherever the marker is being drawn at this instant
     * (not from the previous target), which is what makes a fix arriving
     * mid-glide continue the motion instead of snapping the marker back. A
     * rejected position changes nothing at all: the member keeps gliding to, or
     * resting at, their last believable position.
     *
     * Members absent from [members] are dropped, so someone who stops sharing
     * cannot leave a track behind.
     */
    fun onPositions(members: List<ConvoyMemberPosition>, nowMillis: Long) {
        for (member in members) {
            val track = tracks[member.uid]
            if (track == null) {
                // First sight of this member: nothing to glide from, so they
                // simply appear where they are. An undrawable first fix is not
                // tracked at all — they are drawn once a usable one arrives.
                if (!LiveMarkerSmoothing.isDrawable(member.latitude, member.longitude)) continue
                tracks[member.uid] =
                    Track(
                        fromLatitude = member.latitude,
                        fromLongitude = member.longitude,
                        toLatitude = member.latitude,
                        toLongitude = member.longitude,
                        startedAtMillis = nowMillis,
                        durationMillis = 0L,
                        acceptedRecordedAtMillis = member.updatedAtMillis,
                        acceptedAtMillis = nowMillis,
                    )
                continue
            }

            val accepted =
                LiveMarkerSmoothing.acceptsFix(
                    previousLatitude = track.toLatitude,
                    previousLongitude = track.toLongitude,
                    previousRecordedAtMillis = track.acceptedRecordedAtMillis,
                    latitude = member.latitude,
                    longitude = member.longitude,
                    recordedAtMillis = member.updatedAtMillis,
                )
            if (!accepted) continue

            val arrivalGapMillis = nowMillis - track.acceptedAtMillis
            // Bookkeeping advances even for a stationary heartbeat (same
            // coordinates, newer timestamp): the NEXT move must be judged against
            // the interval since this sample, not since the member last actually
            // moved, or a long park would make any subsequent jump look slow
            // enough to be plausible.
            track.acceptedRecordedAtMillis = member.updatedAtMillis
            track.acceptedAtMillis = nowMillis

            val moved =
                member.latitude != track.toLatitude || member.longitude != track.toLongitude
            if (!moved) continue

            val currentFraction =
                LiveMarkerSmoothing.progress(
                    nowMillis - track.startedAtMillis,
                    track.durationMillis,
                )
            val currentLatitude =
                LiveMarkerSmoothing.lerpLatitude(
                    track.fromLatitude,
                    track.toLatitude,
                    currentFraction,
                )
            val currentLongitude =
                LiveMarkerSmoothing.lerpLongitude(
                    track.fromLongitude,
                    track.toLongitude,
                    currentFraction,
                )
            track.fromLatitude = currentLatitude
            track.fromLongitude = currentLongitude
            track.toLatitude = member.latitude
            track.toLongitude = member.longitude
            track.startedAtMillis = nowMillis
            track.durationMillis = LiveMarkerSmoothing.glideDurationMillis(arrivalGapMillis)
        }
        // Drop anyone who is no longer on the roster. A linear scan per surviving
        // track rather than an intermediate set: this runs once per roster update
        // (seconds apart), never per frame, over at most `MAX_CONVOY_INVITEES`
        // members either side — cheaper than the allocation it avoids.
        if (tracks.isNotEmpty()) {
            tracks.keys.retainAll { uid -> members.any { it.uid == uid } }
        }
    }

    /**
     * Whether any member is still mid-glide at [nowMillis] — i.e. whether the
     * overlay has a reason to ask for another frame. False the moment every
     * marker has settled, which is what lets the frame loop stop instead of
     * spinning for the whole time a convoy is on screen.
     */
    fun isGliding(nowMillis: Long): Boolean =
        tracks.values.any { nowMillis - it.startedAtMillis < it.durationMillis }

    /**
     * [members] with each position replaced by where that member's marker should
     * be DRAWN at [nowMillis] — identity fields (name, car photo, the reported
     * timestamp the staleness rule reads) are carried through untouched.
     *
     * A member with no track yet is returned unchanged when their coordinate is
     * drawable and dropped when it is not, so the very first frame after someone
     * joins draws them immediately rather than blinking.
     *
     * Pure: it reads the tracks, never advances them. A settled member is
     * returned as the SAME instance rather than a copy, so a parked convoy costs
     * no allocation per frame.
     */
    fun rendered(members: List<ConvoyMemberPosition>, nowMillis: Long): List<ConvoyMemberPosition> =
        members.mapNotNull { member ->
            val track = tracks[member.uid]
            if (track == null) {
                member.takeIf { LiveMarkerSmoothing.isDrawable(it.latitude, it.longitude) }
            } else {
                val fraction =
                    LiveMarkerSmoothing.progress(
                        nowMillis - track.startedAtMillis,
                        track.durationMillis,
                    )
                val latitude =
                    LiveMarkerSmoothing.lerpLatitude(
                        track.fromLatitude,
                        track.toLatitude,
                        fraction,
                    )
                val longitude =
                    LiveMarkerSmoothing.lerpLongitude(
                        track.fromLongitude,
                        track.toLongitude,
                        fraction,
                    )
                if (latitude == member.latitude && longitude == member.longitude) {
                    member
                } else {
                    member.copy(latitude = latitude, longitude = longitude)
                }
            }
        }
}
