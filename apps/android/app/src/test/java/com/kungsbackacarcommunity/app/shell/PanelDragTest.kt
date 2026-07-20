package com.kungsbackacarcommunity.app.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drag-to-dismiss gesture behind the translucent History / Social / Garage
 * panels, tested where the DECISIONS actually live.
 *
 * [PanelDrag] is deliberately pure so these are real assertions about the
 * gesture rather than "a drag happened and something changed": each case below
 * fails against a plausible wrong implementation — a distance-only rule, a
 * velocity-only rule, one that ignores an upward flick, one that lets the panel
 * eat a downward drag before the list sees it, or one that dismisses before the
 * card has ever been measured.
 */
class PanelDragTest {
    /** A representative measured card height (px) for a phone-sized window. */
    private val cardHeightPx = 2000
    private val threshold = PanelDrag.dismissThresholdPx(cardHeightPx)

    @Test
    fun dismissThreshold_isAFractionOfTheCardHeight_notAFixedDistance() {
        // Proportional, so the same gesture means the same thing on a tall
        // portrait window and a short landscape one. A fixed-dp threshold would
        // make these two equal.
        assertTrue(
            "expected a taller card to need a longer pull",
            PanelDrag.dismissThresholdPx(4000) > PanelDrag.dismissThresholdPx(1000),
        )
        assertEquals(
            cardHeightPx * PanelDrag.DISMISS_DISTANCE_FRACTION,
            threshold,
            0.001f,
        )
    }

    @Test
    fun dragPastTheThreshold_thenReleasingSlowly_dismisses() {
        assertTrue(
            PanelDrag.shouldDismiss(
                offsetPx = threshold + 1f,
                velocityPxPerSecond = 0f,
                dismissThresholdPx = threshold,
            ),
        )
    }

    @Test
    fun shortDragReleasedSlowly_doesNotDismiss_soThePanelSpringsBack() {
        // The whole point of a threshold: a small accidental pull must put the
        // panel back, not close the page the user was reading.
        assertFalse(
            PanelDrag.shouldDismiss(
                offsetPx = threshold - 1f,
                velocityPxPerSecond = 0f,
                dismissThresholdPx = threshold,
            ),
        )
    }

    @Test
    fun quickFlickDown_dismissesEvenFromAVeryShortDrag() {
        // Velocity, not just distance. A distance-only rule fails here: 10px is
        // nowhere near the 700px threshold, but the user unmistakably threw the
        // panel away.
        assertTrue(
            "expected a fast downward flick to dismiss from a short drag",
            PanelDrag.shouldDismiss(
                offsetPx = 10f,
                velocityPxPerSecond = PanelDrag.FLING_DISMISS_VELOCITY_PX_PER_SECOND,
                dismissThresholdPx = threshold,
            ),
        )
    }

    @Test
    fun slowDragBelowThreshold_isNotAFlick_andDoesNotDismiss() {
        // Guards the flick rule from degenerating into "any downward movement":
        // just under the fling velocity and under the distance threshold must
        // still spring back.
        assertFalse(
            PanelDrag.shouldDismiss(
                offsetPx = 10f,
                velocityPxPerSecond = PanelDrag.FLING_DISMISS_VELOCITY_PX_PER_SECOND - 1f,
                dismissThresholdPx = threshold,
            ),
        )
    }

    @Test
    fun flickedBackUpFromPastTheThreshold_springsBackInsteadOfDismissing() {
        // The user pulled the panel well past the threshold, changed their mind
        // and threw it back up. A distance-only rule dismisses here, which is
        // the opposite of what the gesture said.
        assertFalse(
            "expected an upward flick to keep the panel open",
            PanelDrag.shouldDismiss(
                offsetPx = threshold * 2f,
                velocityPxPerSecond = -PanelDrag.FLING_DISMISS_VELOCITY_PX_PER_SECOND,
                dismissThresholdPx = threshold,
            ),
        )
    }

    @Test
    fun atRest_neverDismisses_soATapOnTheHandleIsNotAPull() {
        assertFalse(
            PanelDrag.shouldDismiss(
                offsetPx = 0f,
                velocityPxPerSecond = 0f,
                dismissThresholdPx = threshold,
            ),
        )
    }

    @Test
    fun beforeTheCardHasBeenMeasured_nothingCanDismissIt() {
        // Height is 0 until the first layout pass. Without this guard the
        // threshold is 0 and the FIRST pixel of any drag would close the page.
        assertEquals(0f, PanelDrag.dismissThresholdPx(0), 0f)
        assertFalse(
            PanelDrag.shouldDismiss(
                offsetPx = 1f,
                velocityPxPerSecond = 0f,
                dismissThresholdPx = PanelDrag.dismissThresholdPx(0),
            ),
        )
    }

    // ── The list-vs-panel gesture conflict ──────────────────────────────────
    //
    // The panel is nested-scroll'd around a scrollable page. Compose offers a
    // drag to onPreScroll first, then to the CHILD, then whatever the child left
    // over to onPostScroll — so "what did the child leave" is precisely "was the
    // list already at its top".

    @Test
    fun draggingDownMidList_leavesNothingOver_soThePanelDoesNotMove() {
        // Mid-list the scroll container consumes the whole delta, so onPostScroll
        // is offered 0 available. The panel must take nothing: this is the trap
        // where a naive implementation drags the panel away while the user is
        // simply scrolling.
        assertEquals(0f, PanelDrag.postScrollConsumption(availableY = 0f), 0f)
    }

    @Test
    fun draggingDownWithTheListAtItsTop_movesThePanel() {
        // At the top the list can consume nothing, so the full delta arrives
        // unconsumed and the panel takes all of it.
        assertEquals(40f, PanelDrag.postScrollConsumption(availableY = 40f), 0f)
    }

    @Test
    fun draggingUpIsNeverTakenAfterTheList_soScrollingDownTheListWorks() {
        assertEquals(0f, PanelDrag.postScrollConsumption(availableY = -40f), 0f)
    }

    @Test
    fun draggingDownIsNeverTakenBeforeTheList() {
        // Pre-scroll must not pre-empt a downward drag, at rest or otherwise —
        // otherwise the panel would start moving instead of the list scrolling.
        assertEquals(0f, PanelDrag.preScrollConsumption(availableY = 40f, offsetPx = 0f), 0f)
        assertEquals(0f, PanelDrag.preScrollConsumption(availableY = 40f, offsetPx = 300f), 0f)
    }

    @Test
    fun draggingUpWhileThePanelIsPulledDown_putsThePanelBackBeforeTheListScrolls() {
        // The panel is 300px down and the user drags 100px up: all 100 go to the
        // panel, none to the list.
        assertEquals(
            -100f,
            PanelDrag.preScrollConsumption(availableY = -100f, offsetPx = 300f),
            0f,
        )
    }

    @Test
    fun anUpwardDragLongerThanTheOffset_takesOnlyTheOffsetAndHandsTheRestToTheList() {
        // 300px up while only 100px down: the panel takes exactly its 100px back
        // to rest and the remaining 200px continue into the list, so one
        // continuous gesture reseats the panel and then scrolls.
        assertEquals(
            -100f,
            PanelDrag.preScrollConsumption(availableY = -300f, offsetPx = 100f),
            0f,
        )
    }

    @Test
    fun atRest_anUpwardDragGoesEntirelyToTheList() {
        // Nothing outstanding, so the panel must not lift off the bottom edge.
        assertEquals(0f, PanelDrag.preScrollConsumption(availableY = -100f, offsetPx = 0f), 0f)
    }
}
