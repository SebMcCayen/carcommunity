package com.kungsbackacarcommunity.app.map

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * What the map camera is framing while the user is in a convoy.
 *
 * The default is [Me] and [Me] is byte-for-byte the behaviour that existed
 * before convoy focus was a thing — the camera follows the user's puck at the
 * own-marker zoom. Nothing about the default path changes; [Convoy] is purely
 * additive and is only ever reachable while in a convoy.
 */
enum class ConvoyFocusMode {
    /** Follow me, exactly as the map has always done. */
    Me,

    /** Keep every known convoy member framed, zooming as the group spreads. */
    Convoy,
}

/** A coordinate, free of Mapbox types so the planner stays unit-testable. */
data class ConvoyLatLng(val latitude: Double, val longitude: Double)

/** What the camera should be doing this frame. */
sealed interface ConvoyCameraPlan {
    /** Normal follow-the-puck. The pre-existing behaviour, unmodified. */
    data object FollowSelf : ConvoyCameraPlan

    /** Fit the camera so every point in [points] is framed. */
    data class FitConvoy(val points: List<ConvoyLatLng>) : ConvoyCameraPlan
}

/**
 * Session-scoped holder for the convoy focus choice.
 *
 * ## Why the session and not disk
 * The choice is deliberately NOT persisted across an app restart. It is a
 * property of a drive, not of the user: "keep the whole group framed" is a
 * sensible thing to want for the convoy you are in right now, and a surprising
 * thing to have silently reinstated a week later on a different convoy with
 * different people — you would open the map, find it zoomed out over half the
 * county, and have no idea why. It also matches the neighbouring map-chrome
 * toggles (traffic, day/night, 3D), which are all session-only in-memory flows
 * on the map surface for the same reason.
 *
 * Within the session it is sticky across screens, so switching to navigation and
 * back does not silently reframe the map.
 *
 * [onActiveConvoyChanged] is the restore hook: leaving a convoy, or the convoy
 * ending, drops the mode back to [ConvoyFocusMode.Me] so the very next camera
 * plan is a normal follow. Joining a DIFFERENT convoy also resets — the choice
 * was about that group, not this one.
 *
 * [requestConvoyFocusOnJoin] is the one exception to that reset, and it exists
 * for the accept-an-invite hand-off: the member is being put on the map
 * *because* they just joined a group, so "show me the group" is the whole point
 * of the trip. It has to be a REQUEST rather than a `setMode` because of
 * ordering — the convoy the member accepted into does not become the active
 * convoy until the bar's own coordinator refreshes, and that refresh is exactly
 * what calls [onActiveConvoyChanged] and resets the mode. Setting the mode
 * eagerly would therefore be undone a moment later by the very event the mode
 * was set in anticipation of.
 */
class ConvoyFocusStore {
    private val modeFlow = MutableStateFlow(ConvoyFocusMode.Me)

    /** The current focus choice; [ConvoyFocusMode.Me] whenever not in a convoy. */
    val mode: StateFlow<ConvoyFocusMode> = modeFlow.asStateFlow()

    private var activeConvoyId: String? = null

    /** A pending "frame this convoy once it arrives" request; see below. */
    private var focusOnJoinConvoyId: String? = null

    /** The user picked a focus mode in the convoy bar. */
    fun setMode(mode: ConvoyFocusMode) {
        modeFlow.value = mode
    }

    /**
     * Frame [convoyId]'s group as soon as it is the active convoy.
     *
     * One-shot, and matched by ID rather than by "the next convoy that shows
     * up": a request that is never satisfied (the accept did not actually put
     * the caller in that convoy) must not silently attach itself to some
     * unrelated convoy joined an hour later. A blank id clears the request.
     *
     * Honoured immediately when the convoy is ALREADY active — the bar can
     * refresh before the hand-off completes — so the request cannot be stranded
     * by winning that race.
     */
    fun requestConvoyFocusOnJoin(convoyId: String) {
        focusOnJoinConvoyId = convoyId.takeIf { it.isNotBlank() }
        if (focusOnJoinConvoyId != null && focusOnJoinConvoyId == activeConvoyId) {
            focusOnJoinConvoyId = null
            modeFlow.value = ConvoyFocusMode.Convoy
        }
    }

    /**
     * The active convoy changed identity — including to null, which is "left the
     * convoy / the convoy ended". Resets the mode so the camera goes back to
     * normal. Idempotent: repeated calls with the same id (the coordinator
     * re-emits on every refresh) do not clobber a choice the user just made.
     *
     * A pending [requestConvoyFocusOnJoin] for the arriving convoy is consumed
     * here, AFTER the reset, so the member's own join wins over it. Any other
     * non-null convoy becoming active drops the request instead — it was about
     * a join that evidently did not happen.
     */
    fun onActiveConvoyChanged(convoyId: String?) {
        val changed = convoyId != activeConvoyId
        activeConvoyId = convoyId
        if (changed) modeFlow.value = ConvoyFocusMode.Me
        if (convoyId == null) return
        if (convoyId == focusOnJoinConvoyId) {
            focusOnJoinConvoyId = null
            modeFlow.value = ConvoyFocusMode.Convoy
        } else {
            focusOnJoinConvoyId = null
        }
    }
}

/**
 * Pure decision layer for convoy focus: turns "what mode is selected and who do
 * we know about" into "what should the camera frame".
 *
 * This exists as a separate pure object because the failure mode of this feature
 * is not a wrong pixel, it is TWO THINGS OWNING THE CAMERA. Keeping the decision
 * here — and having exactly one caller apply it, inside the surface's existing
 * follow path — means there is never a second camera owner to fight with the
 * first. The surface asks this what to do; it never decides for itself.
 */
object ConvoyFocusPlanner {

    /**
     * How far apart the framed set has to move before the camera is re-fitted.
     *
     * Live positions tick constantly, and re-easing the camera on every tick
     * makes a convoy fit visibly seasick. A fit is only redone when the bounding
     * box the camera should be showing has actually changed by more than this,
     * measured in degrees on either axis. ~0.0002° is roughly 20 m — below the
     * jitter of a consumer GPS fix, so normal noise never moves the camera, but
     * any real spreading or bunching of the group does.
     */
    const val REFIT_EPSILON_DEGREES: Double = 0.0002

    /**
     * Plan the camera for one update.
     *
     * @param mode the user's choice from the convoy bar.
     * @param ownPosition the viewer's own live position, or null when unknown.
     * @param memberPositions other convoy members with known live positions.
     *
     * Falls back to [ConvoyCameraPlan.FollowSelf] whenever there is nothing to
     * fit that is not already the follow behaviour:
     * - mode is [ConvoyFocusMode.Me];
     * - no other member's position is known — fitting a one-point box would ask
     *   the camera for an undefined zoom and, unclamped, zoom out to the whole
     *   world for a convoy whose members have simply not started sharing yet.
     *   "Me" is the honest answer there, and it is also what the user gets back
     *   automatically the moment somebody does start sharing.
     */
    fun plan(
        mode: ConvoyFocusMode,
        ownPosition: ConvoyLatLng?,
        memberPositions: List<ConvoyLatLng>,
    ): ConvoyCameraPlan {
        if (mode != ConvoyFocusMode.Convoy) return ConvoyCameraPlan.FollowSelf
        if (memberPositions.isEmpty()) return ConvoyCameraPlan.FollowSelf
        val points = buildList {
            if (ownPosition != null) add(ownPosition)
            addAll(memberPositions)
        }
        // Own position unknown and exactly one member known is still a single
        // point — same undefined-zoom problem as above.
        if (points.size < 2) return ConvoyCameraPlan.FollowSelf
        return ConvoyCameraPlan.FitConvoy(points)
    }

    /**
     * Whether a newly-planned fit is different enough from the one currently
     * applied to be worth moving the camera for. See [REFIT_EPSILON_DEGREES].
     *
     * Compares the two BOUNDING BOXES rather than the point lists, because that
     * is what the camera actually renders: a member moving inside the existing
     * box changes nothing the user can see, and a member joining or leaving the
     * box changes everything.
     */
    fun shouldRefit(previous: List<ConvoyLatLng>?, next: List<ConvoyLatLng>): Boolean {
        if (next.isEmpty()) return false
        if (previous.isNullOrEmpty()) return true
        val a = boundsOf(previous)
        val b = boundsOf(next)
        return abs(a.south - b.south) > REFIT_EPSILON_DEGREES ||
            abs(a.north - b.north) > REFIT_EPSILON_DEGREES ||
            abs(a.west - b.west) > REFIT_EPSILON_DEGREES ||
            abs(a.east - b.east) > REFIT_EPSILON_DEGREES
    }

    /** Axis-aligned bounds of a set of points. */
    fun boundsOf(points: List<ConvoyLatLng>): ConvoyBounds =
        ConvoyBounds(
            south = points.minOf { it.latitude },
            north = points.maxOf { it.latitude },
            west = points.minOf { it.longitude },
            east = points.maxOf { it.longitude },
        )
}

/** Axis-aligned lat/lng bounds. */
data class ConvoyBounds(
    val south: Double,
    val north: Double,
    val west: Double,
    val east: Double,
)
