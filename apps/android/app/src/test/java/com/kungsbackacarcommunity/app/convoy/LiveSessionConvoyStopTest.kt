package com.kungsbackacarcommunity.app.convoy

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule behind "ending a live session while a convoy is active must ask what
 * to do with the convoy, and offer only the exits the caller is allowed."
 *
 * [LiveSessionConvoyStop.plan] gates on the convoy being active and reuses
 * [ConvoyBar.exitChoice] for the owner/non-owner + survival semantics;
 * [LiveSessionConvoyStop.actionsFor] shapes that into the ordered convoy actions
 * the dialog offers. These tests pin both — a non-owner is NEVER offered
 * "end for everyone", and no active convoy means no dialog at all.
 */
class LiveSessionConvoyStopTest {

    // --- The gate: only an ACTIVE convoy raises the prompt. ---

    @Test
    fun noConvoyStopsImmediately() {
        assertEquals(
            LiveSessionStopPlan.StopNow,
            LiveSessionConvoyStop.plan(
                inActiveConvoy = false,
                viewerIsOwner = false,
                acceptedMemberCount = 0,
            ),
        )
    }

    @Test
    fun noConvoyStopsImmediatelyEvenIfOwnerFlagsAreStale() {
        // The convoy-derived flags are meaningless when there is no active
        // convoy; the gate must win regardless.
        assertEquals(
            LiveSessionStopPlan.StopNow,
            LiveSessionConvoyStop.plan(
                inActiveConvoy = false,
                viewerIsOwner = true,
                acceptedMemberCount = 5,
            ),
        )
    }

    // --- The exit choice carried into the dialog matches ConvoyBar.exitChoice. ---

    @Test
    fun ownerWithEnoughMembersIsAskedLeaveOrEnd() {
        // owner + 3 accepted (2 left if they go) -> both exits.
        assertEquals(
            LiveSessionStopPlan.AskConvoy(ConvoyExitChoice.LeaveOrEnd),
            LiveSessionConvoyStop.plan(
                inActiveConvoy = true,
                viewerIsOwner = true,
                acceptedMemberCount = 3,
            ),
        )
    }

    @Test
    fun ownerWhoseLeavingWouldEndItIsAskedEndOnly() {
        // owner + 2 accepted (1 left) -> below survival threshold -> end only.
        assertEquals(
            LiveSessionStopPlan.AskConvoy(ConvoyExitChoice.EndOnly),
            LiveSessionConvoyStop.plan(
                inActiveConvoy = true,
                viewerIsOwner = true,
                acceptedMemberCount = 2,
            ),
        )
    }

    @Test
    fun memberWithEnoughLeftBehindIsAskedLeaveOnly() {
        assertEquals(
            LiveSessionStopPlan.AskConvoy(ConvoyExitChoice.LeaveOnly),
            LiveSessionConvoyStop.plan(
                inActiveConvoy = true,
                viewerIsOwner = false,
                acceptedMemberCount = 3,
            ),
        )
    }

    @Test
    fun memberWhoseLeavingEndsItIsAskedLeaveEndsConvoy() {
        assertEquals(
            LiveSessionStopPlan.AskConvoy(ConvoyExitChoice.LeaveEndsConvoy),
            LiveSessionConvoyStop.plan(
                inActiveConvoy = true,
                viewerIsOwner = false,
                acceptedMemberCount = 2,
            ),
        )
    }

    // --- The button set each exit choice maps to. ---

    @Test
    fun ownerLeaveOrEndOffersEndThenLeave() {
        assertEquals(
            listOf(ConvoyStopAction.EndConvoy, ConvoyStopAction.LeaveConvoy),
            LiveSessionConvoyStop.actionsFor(ConvoyExitChoice.LeaveOrEnd),
        )
    }

    @Test
    fun ownerEndOnlyOffersEndOnly() {
        assertEquals(
            listOf(ConvoyStopAction.EndConvoy),
            LiveSessionConvoyStop.actionsFor(ConvoyExitChoice.EndOnly),
        )
    }

    @Test
    fun memberChoicesNeverOfferEndConvoy() {
        // The decisive safety property: a non-owner can only ever leave.
        assertEquals(
            listOf(ConvoyStopAction.LeaveConvoy),
            LiveSessionConvoyStop.actionsFor(ConvoyExitChoice.LeaveOnly),
        )
        assertEquals(
            listOf(ConvoyStopAction.LeaveConvoy),
            LiveSessionConvoyStop.actionsFor(ConvoyExitChoice.LeaveEndsConvoy),
        )
    }
}
