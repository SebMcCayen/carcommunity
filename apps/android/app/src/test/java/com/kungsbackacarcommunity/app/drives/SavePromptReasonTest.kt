package com.kungsbackacarcommunity.app.drives

import com.kungsbackacarcommunity.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The reason→copy rules for the end-of-session save prompt.
 *
 * The bug this guards: a convoy member whose live session was stopped BECAUSE the
 * convoy ended under them got the same neutral "Drive saved" prompt as someone who
 * tapped Stop, with no hint why their session stopped. [savePromptReason] decides
 * when to explain that, and [SavePromptCopy] maps the decision to the strings the
 * dialog renders. The `assertNotEquals` against the neutral copy is the
 * load-bearing part — an implementation that regressed to always-neutral would
 * still "show some title" but fails here.
 */
class SavePromptReasonTest {

    @Test
    fun `a convoy-auto session stopped WITHOUT a self-stop or expiry reads as convoy ended`() {
        // The confusing case being fixed: the member did nothing, the session did
        // not expire, the convoy ended under them, and the backend stopped their
        // convoy-auto session.
        assertEquals(
            SavePromptReason.ConvoyEnded,
            savePromptReason(
                convoyAutoStarted = true,
                userEndedSession = false,
                endedByExpiry = false,
            ),
        )
    }

    @Test
    fun `a member who ended their own convoy-auto session keeps the neutral copy`() {
        // They tapped Stop / Hide / chose End or Leave — they know why it stopped.
        assertEquals(
            SavePromptReason.Default,
            savePromptReason(
                convoyAutoStarted = true,
                userEndedSession = true,
                endedByExpiry = false,
            ),
        )
    }

    @Test
    fun `a convoy-auto session that hit its expiry stays neutral`() {
        // The 6h hard cap: the session's own clock ran out (the convoy may still be
        // running for others), so "the convoy ended" would be wrong.
        assertEquals(
            SavePromptReason.Default,
            savePromptReason(
                convoyAutoStarted = true,
                userEndedSession = false,
                endedByExpiry = true,
            ),
        )
    }

    @Test
    fun `a manually-started solo session is never a convoy end`() {
        // A solo session is never stopped by a convoy ending, so neither the
        // self-stop nor the (impossible) remote-stop case may claim ConvoyEnded.
        assertEquals(
            SavePromptReason.Default,
            savePromptReason(
                convoyAutoStarted = false,
                userEndedSession = false,
                endedByExpiry = false,
            ),
        )
        assertEquals(
            SavePromptReason.Default,
            savePromptReason(
                convoyAutoStarted = false,
                userEndedSession = true,
                endedByExpiry = false,
            ),
        )
    }

    @Test
    fun `convoy-ended copy is distinct from the neutral copy`() {
        // The whole point of the change: the convoy-end reason must NOT fall back
        // to the neutral title/body the member found confusing.
        assertEquals(
            R.string.savedDrives_convoyEndedTitle,
            SavePromptCopy.titleRes(SavePromptReason.ConvoyEnded),
        )
        assertEquals(
            R.string.savedDrives_convoyEndedBody,
            SavePromptCopy.bodyRes(SavePromptReason.ConvoyEnded),
        )
        assertNotEquals(
            "The convoy-end title must differ from the neutral one",
            SavePromptCopy.titleRes(SavePromptReason.Default),
            SavePromptCopy.titleRes(SavePromptReason.ConvoyEnded),
        )
        assertNotEquals(
            "The convoy-end body must differ from the neutral one",
            SavePromptCopy.bodyRes(SavePromptReason.Default),
            SavePromptCopy.bodyRes(SavePromptReason.ConvoyEnded),
        )
    }

    @Test
    fun `the default reason keeps the existing auto-saved copy`() {
        // The control case: the neutral path must still point at the original
        // strings, so a self-stop is unchanged by this feature.
        assertEquals(
            R.string.savedDrives_autoSavedTitle,
            SavePromptCopy.titleRes(SavePromptReason.Default),
        )
        assertEquals(
            R.string.savedDrives_autoSavedBody,
            SavePromptCopy.bodyRes(SavePromptReason.Default),
        )
    }

    @Test
    fun `every reason maps to a distinct title and body`() {
        // Guards the general shape: a future reason quietly pointed at an existing
        // string fails here rather than shipping two states users can't tell apart.
        val titles = SavePromptReason.entries.associateWith { SavePromptCopy.titleRes(it) }
        val bodies = SavePromptReason.entries.associateWith { SavePromptCopy.bodyRes(it) }
        assertEquals("Two reasons share a title: $titles", titles.size, titles.values.toSet().size)
        assertEquals("Two reasons share a body: $bodies", bodies.size, bodies.values.toSet().size)
    }
}
