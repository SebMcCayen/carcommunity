package com.kungsbackacarcommunity.app.drives

/**
 * What to do the instant a live session is OBSERVED to have ended (the
 * recording-lifecycle effect in `AuthenticatedApp` just decided the session is no
 * longer sharing and it is a genuine end, not a config-change transient).
 *
 * Historically every end flowed straight into [SingleSessionRecording.stop], which
 * raises the Keep/Delete save prompt. That is right for the ends the MEMBER caused
 * or expected (Stop / Hide me now / a convoy exit they chose / the 6h expiry). It
 * is NOT right for the one end a member did not choose: when someone ELSE ends a
 * convoy the member is in, the backend (`stopConvoyAutoSession`) stops the member's
 * convoy-auto session under them. Stopping there forces the drive to end even
 * though the member may want to keep driving — so that case asks first.
 */
sealed interface EndedSessionAction {
    /**
     * End the recording now and raise the Keep/Delete save prompt with [reason].
     * The ends the member caused or expected take this path unchanged.
     */
    data class StopAndSave(val reason: SavePromptReason) : EndedSessionAction

    /**
     * The convoy ended under the member (not their own stop, not an expiry). Ask
     * whether to end the session too or keep going solo, INSTEAD of stopping. The
     * recording is left running so [ConvoyEndChoice.ContinueAsSingle] can transfer
     * it to a standalone session with no gap; [ConvoyEndChoice.EndSession] then
     * takes the same [SingleSessionRecording.stop] path with
     * [SavePromptReason.ConvoyEnded].
     */
    data object AskEndOrContinue : EndedSessionAction
}

/**
 * The member's answer to the "the convoy ended" prompt.
 */
enum class ConvoyEndChoice {
    /** End the live session too — stop recording and show the save prompt. */
    EndSession,

    /**
     * Keep driving: DON'T cancel the session, transfer it from the (now stopped)
     * convoy-auto session to a standalone single/solo live session. The recording
     * keeps running throughout — the convoy portion and the solo continuation land
     * in ONE drive, no data loss and no visible gap.
     */
    ContinueAsSingle,
}

/**
 * How a resolved [ConvoyEndChoice] maps onto the recording, kept pure and separate
 * from the framework glue (starting a fresh session / stopping the recorder) so the
 * rule itself is unit-testable and cannot drift.
 */
sealed interface ConvoyEndResolution {
    /** Stop the recording and raise the save prompt with [reason]. */
    data class Stop(val reason: SavePromptReason) : ConvoyEndResolution

    /**
     * Do NOT stop the recording. Transfer the session to a standalone single
     * session (start a fresh solo session while the recorder keeps accumulating).
     */
    data object TransferToSingle : ConvoyEndResolution
}

/**
 * Pure decision logic for the convoy-end-under-a-member flow.
 *
 * Kept off the composable and expressed over plain values (mirroring
 * [savePromptReason] / [com.kungsbackacarcommunity.app.live.LiveSessionRecordingLifecycle])
 * so the "prompt vs stop" and "End vs Continue" rules are JVM-unit-testable: the
 * composable only renders what these return and wires each outcome to its effect.
 */
object ConvoyEndSessionChoice {
    /**
     * Whether an ended session should ASK the member (convoy ended under them) or
     * stop-and-save straight away. Keyed on [SavePromptReason] so the exact same
     * discriminator that already decides the save-prompt copy
     * ([SavePromptReason.ConvoyEnded] == convoy-auto session, not a self-stop, not
     * an expiry) also decides whether to offer the choice — the two can't drift.
     */
    fun onSessionEnded(reason: SavePromptReason): EndedSessionAction =
        when (reason) {
            SavePromptReason.ConvoyEnded -> EndedSessionAction.AskEndOrContinue
            SavePromptReason.Default -> EndedSessionAction.StopAndSave(reason)
        }

    /**
     * What the member's [choice] does to the recording. [EndSession] stops with the
     * convoy-ended copy (the existing #771 save prompt); [ContinueAsSingle] never
     * stops — it transfers the still-running recording to a solo session.
     */
    fun resolve(choice: ConvoyEndChoice): ConvoyEndResolution =
        when (choice) {
            ConvoyEndChoice.EndSession -> ConvoyEndResolution.Stop(SavePromptReason.ConvoyEnded)
            ConvoyEndChoice.ContinueAsSingle -> ConvoyEndResolution.TransferToSingle
        }
}
