package com.kungsbackacarcommunity.app.moderation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageModerationTest {

    @Test
    fun eventChat_is_the_only_surface_with_a_wired_report() {
        // events-reportChatMessage is the only report callable that exists today.
        assertEquals(
            ReportAvailability.Wired,
            MessageModeration.reportAvailability(ChatSurface.EventChat),
        )
    }

    @Test
    fun channels_and_dms_have_no_report_backend() {
        // These must stay BackendMissing until their callables land — the sheet
        // renders the action disabled off the back of this, which is the whole
        // point: an un-fileable report must never look filed.
        listOf(ChatSurface.CommunityChannel, ChatSurface.ConvoyChannel, ChatSurface.DirectMessage)
            .forEach { surface ->
                assertEquals(
                    "$surface must not offer a working report until its callable exists",
                    ReportAvailability.BackendMissing,
                    MessageModeration.reportAvailability(surface),
                )
            }
    }

    @Test
    fun every_surface_has_an_explicit_report_availability() {
        // Guards the exhaustive `when`: a new surface must consciously declare
        // whether it can report, rather than defaulting into "Wired".
        ChatSurface.entries.forEach { MessageModeration.reportAvailability(it) }
    }

    @Test
    fun another_members_message_is_actionable() {
        assertTrue(MessageModeration.canActOn(authorUid = "other", currentUid = "me"))
    }

    @Test
    fun own_message_is_never_actionable() {
        assertFalse(MessageModeration.canActOn(authorUid = "me", currentUid = "me"))
    }

    @Test
    fun message_with_no_resolvable_author_is_not_actionable() {
        // A malformed message would otherwise open a sheet targeting nobody.
        assertFalse(MessageModeration.canActOn(authorUid = "", currentUid = "me"))
        assertFalse(MessageModeration.canActOn(authorUid = "   ", currentUid = "me"))
    }
}
