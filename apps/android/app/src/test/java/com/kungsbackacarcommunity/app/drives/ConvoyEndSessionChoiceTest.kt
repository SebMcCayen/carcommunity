package com.kungsbackacarcommunity.app.drives

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The decision logic for "someone else ended a convoy I'm in".
 *
 * Two rules are guarded here:
 *  1. Which ended sessions ASK the member (convoy ended under them) versus
 *     stop-and-save straight away — [ConvoyEndSessionChoice.onSessionEnded].
 *  2. What the member's answer does to the recording — [ConvoyEndSessionChoice.resolve]:
 *     End stops with the convoy-ended copy; Continue NEVER stops (it transfers the
 *     still-running recording to a solo session). The Continue → TransferToSingle
 *     mapping is the load-bearing part: a regression that stopped on Continue would
 *     cancel the very session the member asked to keep.
 */
class ConvoyEndSessionChoiceTest {

    @Test
    fun `convoy ended under the member asks End-or-Continue instead of stopping`() {
        assertEquals(
            EndedSessionAction.AskEndOrContinue,
            ConvoyEndSessionChoice.onSessionEnded(SavePromptReason.ConvoyEnded),
        )
    }

    @Test
    fun `an end the member caused or expected stops and saves straight away`() {
        // Self-stop / Hide / a chosen convoy exit / the 6h expiry all resolve to the
        // neutral Default reason (see savePromptReason) and must NOT be interrupted
        // by the choice dialog — they stop and save exactly as before.
        assertEquals(
            EndedSessionAction.StopAndSave(SavePromptReason.Default),
            ConvoyEndSessionChoice.onSessionEnded(SavePromptReason.Default),
        )
    }

    @Test
    fun `choosing End stops the recording with the convoy-ended save copy`() {
        assertEquals(
            ConvoyEndResolution.Stop(SavePromptReason.ConvoyEnded),
            ConvoyEndSessionChoice.resolve(ConvoyEndChoice.EndSession),
        )
    }

    @Test
    fun `choosing Continue transfers the session and never stops the recording`() {
        // The whole point: the session must not be cancelled, only transferred from
        // a convoy session to a standalone single session.
        assertEquals(
            ConvoyEndResolution.TransferToSingle,
            ConvoyEndSessionChoice.resolve(ConvoyEndChoice.ContinueAsSingle),
        )
    }
}
