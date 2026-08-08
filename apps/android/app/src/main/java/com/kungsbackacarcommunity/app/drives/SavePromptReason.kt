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
 * drive seams in this package). The signal is the same one the backend uses to
 * stop the session: a [convoyAutoStarted] session that STOPPED without the member
 * ending it themselves ([userEndedSession] false) can only have been stopped by
 * the convoy ending — nothing else stops a convoy-auto session but the member's
 * own Stop/Hide/exit. A member who stopped it themselves keeps the neutral copy,
 * even on a convoy-auto session, because they already know why it stopped.
 *
 * @param convoyAutoStarted the ended session was auto-started BY a convoy
 *   (`LiveSessionInfo.convoyAutoStarted`) — a manually-started solo session is
 *   never stopped by a convoy ending, so it is always [Default].
 * @param userEndedSession the member themselves ended this session (tapped Stop /
 *   Hide me now, or chose End/Leave in the convoy-stop dialog).
 */
fun savePromptReason(convoyAutoStarted: Boolean, userEndedSession: Boolean): SavePromptReason =
    if (convoyAutoStarted && !userEndedSession) {
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
