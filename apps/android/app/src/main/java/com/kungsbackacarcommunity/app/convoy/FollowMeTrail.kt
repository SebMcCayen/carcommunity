package com.kungsbackacarcommunity.app.convoy

import com.kungsbackacarcommunity.app.drives.RoutePoint
import com.kungsbackacarcommunity.app.drives.RouteCodec
import com.kungsbackacarcommunity.app.shell.BreadcrumbTrail
import com.kungsbackacarcommunity.app.shell.MapPoint
import java.util.Base64

/**
 * Pure, Firebase-free logic for the convoy "Follow me" LEADER TRAIL — the
 * persistent, shared line of where the current leader has recently driven, drawn
 * on every convoy member's map so a separated member can rejoin.
 *
 * This is a DIFFERENT thing from the transient follow-me REACTION
 * ([ConvoyReactionKind.FollowMe], a ~30s animation): the trail is durable shared
 * state that stays until the leader turns it off, is taken over, or leaves. The
 * two are wired together — pressing Follow-me fires the animation on activation
 * AND toggles this trail — but the trail's maths live here so they are
 * JVM-unit-testable ([FollowMeTrailTest]).
 *
 * Shared state lives at Firestore `convoys/{convoyId}/followMe/current`
 * (see [ConvoyFollowMeRepository]); the polyline is the CCRB-encoded ([RouteCodec])
 * ~15 km rolling trail, base64'd for the string field. Encode/decode here reuse
 * the exact same [RouteCodec] the server decodes with, so the two ends cannot
 * drift.
 */
object FollowMeTrail {
    /**
     * The rolling trail length in metres — ~15 km, far longer than the private
     * ~1 km self-breadcrumb ([BreadcrumbTrail.DEFAULT_WINDOW_METERS]), so a member
     * who fell well behind still sees a continuous line back to the leader. Mirrors
     * the backend `FOLLOW_ME_TRAIL_WINDOW_METERS`.
     */
    const val TRAIL_WINDOW_METERS: Double = 15_000.0

    /**
     * Member-side freshness window in millis. If the leader's trail has not been
     * refreshed within this window (crash, lost signal, killed process) members
     * stop drawing the line rather than leave a stale ghost. Mirrors the backend
     * `FOLLOW_ME_STALE_MS`. Deliberately generous (the owner did not want an
     * inactivity timeout) — it only hides a genuinely silent leader's line, and
     * the instant they write again it reappears.
     */
    const val STALE_MS: Long = 90_000L

    /**
     * How often the member-side render gate re-evaluates staleness on a TIMER,
     * independent of data changes. The draw effect otherwise only re-runs when its
     * inputs change (a live-marker tick, a trail write); if the leader goes silent
     * while everyone is stationary NOTHING changes, so without this a stale trail
     * would linger until the next unrelated update. Ticking at this interval takes
     * a genuinely stale trail down within ~[STALE_RECHECK_MS] of [STALE_MS] elapsing.
     */
    const val STALE_RECHECK_MS: Long = 30_000L

    /**
     * Minimum spacing between direct trail WRITES by the leader, in millis. The
     * leader's position stream ticks far faster; the trail is flushed to Firestore
     * at most this often (while it actually changed), so the ~15 km trail costs a
     * write every few seconds rather than one per GPS fix.
     */
    const val WRITE_THROTTLE_MS: Long = 4_000L

    /** A fresh [BreadcrumbTrail] sized for the ~15 km leader trail window. */
    fun newTrailBuffer(): BreadcrumbTrail = BreadcrumbTrail(windowMeters = TRAIL_WINDOW_METERS)

    /**
     * Encodes an oldest→newest list of trail points to the base64 CCRB string
     * stored on the followMe doc. Timestamps are synthesised as the point index
     * (the shared trail carries geometry only, no real times), which keeps the
     * per-point delta stream well-formed for [RouteCodec]/the server decoder.
     */
    fun encode(points: List<MapPoint>): String {
        if (points.isEmpty()) return ""
        val route =
            points.mapIndexed { index, p ->
                RoutePoint(latitude = p.latitude, longitude = p.longitude, timestampMs = index.toLong())
            }
        val bytes = RouteCodec.encode(route)
        return Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * Decodes a stored base64 CCRB polyline back to oldest→newest map points.
     * Returns an empty list for a blank/absent/corrupt value (never throws) —
     * [RouteCodec.decode] is itself total, and a bad base64 string is caught.
     */
    fun decode(polyline: String?): List<MapPoint> {
        if (polyline.isNullOrEmpty()) return emptyList()
        val bytes =
            try {
                Base64.getDecoder().decode(polyline)
            } catch (_: IllegalArgumentException) {
                return emptyList()
            }
        val route = RouteCodec.decode(bytes) ?: return emptyList()
        return route.map { MapPoint(longitude = it.longitude, latitude = it.latitude) }
    }

    /**
     * True when the local user is the current trail leader (the button shows its
     * ACTIVE/toggled state). Pure so the button-state mapping is unit-testable.
     */
    fun isSelfLeading(leaderUid: String?, selfUid: String?): Boolean =
        leaderUid != null && selfUid != null && leaderUid == selfUid

    /**
     * Whether THIS member should DRAW the shared trail — the member-side render
     * gate, mirrored from the backend `shouldDrawFollowMeTrail`. Draws when a
     * leader is set, it is not the viewer themselves (the leader keeps their own
     * private self-trail), the leader is still an accepted member, and the trail
     * is fresh. So a vanished/removed leader's line stops drawing even without the
     * server cleanup and without any inactivity timer.
     */
    fun shouldDraw(
        leaderUid: String?,
        selfUid: String?,
        leaderIsMember: Boolean,
        lastFreshMs: Long?,
        nowMs: Long,
        windowMs: Long = STALE_MS,
    ): Boolean {
        if (leaderUid == null) return false
        if (selfUid != null && leaderUid == selfUid) return false
        if (!leaderIsMember) return false
        return isFresh(lastFreshMs, nowMs, windowMs)
    }

    /** Fresh when a signal exists and is within [windowMs] of [nowMs]. Fails closed on null. */
    fun isFresh(lastFreshMs: Long?, nowMs: Long, windowMs: Long = STALE_MS): Boolean {
        if (lastFreshMs == null) return false
        return nowMs - lastFreshMs < windowMs
    }
}

/**
 * PURE leader-side trail buffer + write throttle, extracted so the "add a fix →
 * should we flush a new polyline?" decision is unit-testable without Firebase or
 * a clock ([FollowMeTrailTest]). Not thread-safe: driven from one position stream.
 *
 * Feed it the leader's own position fixes; it maintains a ~15 km
 * [BreadcrumbTrail] (the same distance-window + jitter/jump cleaning the private
 * self-trail uses) and tells the caller when to WRITE the encoded polyline to the
 * followMe doc — at most once per [FollowMeTrail.WRITE_THROTTLE_MS], and only when
 * the trail actually changed since the last flush.
 */
class FollowMeTrailPublisher(
    private val throttleMs: Long = FollowMeTrail.WRITE_THROTTLE_MS,
) {
    private val buffer = FollowMeTrail.newTrailBuffer()
    // null = never flushed yet (so the first change flushes immediately). A
    // sentinel long would overflow the `nowMs - last` throttle comparison.
    private var lastWriteAtMs: Long? = null
    private var dirty: Boolean = false

    /**
     * Feed one position fix at [nowMs]. Returns the base64 polyline to WRITE now,
     * or null when nothing should be written yet (fix was jitter/unchanged, or the
     * throttle window has not elapsed). A returned string marks the buffer flushed.
     */
    fun onFix(point: MapPoint, nowMs: Long): String? {
        if (buffer.add(point)) dirty = true
        if (!dirty) return null
        val last = lastWriteAtMs
        if (last != null && nowMs - last < throttleMs) return null
        lastWriteAtMs = nowMs
        dirty = false
        return FollowMeTrail.encode(buffer.points())
    }

    /** Current buffered trail (oldest→newest) — for tests / an immediate initial draw. */
    fun points(): List<MapPoint> = buffer.points()

    /** Drop everything (the local user stopped leading). */
    fun reset() {
        buffer.clear()
        lastWriteAtMs = null
        dirty = false
    }
}
