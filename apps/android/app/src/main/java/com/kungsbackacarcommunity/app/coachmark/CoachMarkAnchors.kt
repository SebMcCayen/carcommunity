package com.kungsbackacarcommunity.app.coachmark

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * A tiny live registry mapping each [CoachMarkStep] to the on-screen bounds
 * (in the composition root's coordinate space) of the control it points at.
 *
 * Controls opt in with [Modifier.coachMarkAnchor]; the [CoachMarkOverlay] reads
 * back the bounds for the current step to place its spotlight + bubble. Bounds
 * are held in a `mutableStateMapOf` so a control re-laying-out (rotation, font
 * scaling, the bottom bar switching the centre disc) re-reports and the overlay
 * follows without any manual invalidation.
 */
class CoachMarkAnchorRegistry {
    private val bounds = mutableStateMapOf<CoachMarkStep, Rect>()

    /** Records (or updates) the measured [rect] for [step]. */
    fun report(step: CoachMarkStep, rect: Rect) {
        bounds[step] = rect
    }

    /** Forgets [step]'s bounds when its control leaves the composition. */
    fun clear(step: CoachMarkStep) {
        bounds.remove(step)
    }

    /** The current bounds of [step]'s control, or null if it isn't laid out. */
    fun boundsOf(step: CoachMarkStep): Rect? = bounds[step]
}

/**
 * The registry the coach-mark anchors report into. Defaults to null — outside a
 * running tour (previews, tests, ordinary use once the tour is seen) the
 * [coachMarkAnchor] modifier is then a no-op, so anchored controls carry no
 * behaviour change and add nothing to the layout.
 */
val LocalCoachMarkAnchors = staticCompositionLocalOf<CoachMarkAnchorRegistry?> { null }

/**
 * Marks the receiver as the control the coach-mark tour points at for [step].
 * Reports the control's root-space bounds into [LocalCoachMarkAnchors] as it is
 * laid out, and clears them when it leaves the composition. A no-op (adds no
 * layout node) when no registry is provided, so it is safe to leave on shared
 * controls permanently.
 */
fun Modifier.coachMarkAnchor(step: CoachMarkStep): Modifier =
    composed {
        val registry = LocalCoachMarkAnchors.current
        if (registry == null) {
            this
        } else {
            this.onGloballyPositioned { coordinates ->
                if (coordinates.isAttached) {
                    registry.report(step, coordinates.boundsInRoot())
                }
            }
        }
    }
