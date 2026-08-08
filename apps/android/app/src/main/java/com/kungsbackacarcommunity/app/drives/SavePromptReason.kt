package com.kungsbackacarcommunity.app.drives

import androidx.annotation.StringRes
import com.kungsbackacarcommunity.app.R

/**
 * Why the end-of-session save prompt ([SessionSummaryDialog]) was raised.
 *
 * A live session ends for several reasons, and the drive-save summary that
 * follows used to read the same neutral "Drive saved" copy for all of them. That
 * is confusing for the one case the MEMBER did not cause: when the convoy leader
 * (or another member) ends the convoy, the backend stops each member's
 * convoy-auto-started live session (functions `stopConvoyAutoSession`, matched on
 * the RTDB `convoyAutoStarted` flag), so the member's session simply stops and the
 * save prompt appears out of nowhere. This reason lets that prompt SAY why.
 */
enum class SavePromptReason {
    /** The member ended their own session (Stop / Hide me now / a convoy exit
     *  they chose), or it simply expired — the neutral "Drive saved" copy. */
    Default,

    /** The convoy ended under the member (leader/another member ended it), which
     *  is what stopped their live session. The prompt explains that. */
    ConvoyEnded,
}

/**
 * Decides the [SavePromptReason] for a session that just ended.
 *
 * Pure so the rule is JVM-unit-testable off the composable (mirroring the other
 * drive seams in this package). A [convoyAutoStarted] session that stopped without
 * the member ending it themselves ([userEndedSession] false) AND before its expiry
 * ([endedByExpiry] false) can only have been stopped by the convoy ending: that is
 * the one remaining thing that stops a convoy-auto session (the backend's
 * `stopConvoyAutoSession`). The two exclusions are why they matter:
 *  - a member who stopped it themselves already knows why, so keeps the neutral
 *    copy even on a convoy-auto session;
 *  - a convoy-auto session that simply hit the 6h hard cap expired on its own —
 *    the convoy may still be running for others — so "the convoy ended" would be
 *    wrong, and it too stays neutral.
 *
 * @param convoyAutoStarted the ended session was auto-started BY a convoy
 *   (`LiveSessionInfo.convoyAutoStarted`) — a manually-started solo session is
 *   never stopped by a convoy ending, so it is always [Default].
 * @param userEndedSession the member themselves ended this session (tapped Stop /
 *   Hide me now, or chose End/Leave in the convoy-stop dialog).
 * @param endedByExpiry the session reached its expiry (the 6h hard cap) rather
 *   than being stopped early — its own clock ran out, not the convoy ending.
 */
fun savePromptReason(
    convoyAutoStarted: Boolean,
    userEndedSession: Boolean,
    endedByExpiry: Boolean,
): SavePromptReason =
    if (convoyAutoStarted && !userEndedSession && !endedByExpiry) {
        SavePromptReason.ConvoyEnded
    } else {
        SavePromptReason.Default
    }

/**
 * The title / body string resources the save prompt renders for each
 * [SavePromptReason]. Pure `@StringRes` lookup (the same seam pattern as
 * `signInFailureMessageRes` / `GarageStrings`), so the reason→copy mapping is
 * unit-testable without inflating the dialog.
 */
object SavePromptCopy {
    @StringRes
    fun titleRes(reason: SavePromptReason): Int =
        when (reason) {
            SavePromptReason.Default -> R.string.savedDrives_autoSavedTitle
            SavePromptReason.ConvoyEnded -> R.string.savedDrives_convoyEndedTitle
        }

    @StringRes
    fun bodyRes(reason: SavePromptReason): Int =
        when (reason) {
            SavePromptReason.Default -> R.string.savedDrives_autoSavedBody
            SavePromptReason.ConvoyEnded -> R.string.savedDrives_convoyEndedBody
        }
}
