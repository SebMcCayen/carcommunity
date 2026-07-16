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
    fun only_surfaces_with_a_real_callable_are_wired() {
        // The old version of this test just CALLED reportAvailability for every
        // surface and asserted nothing — it could only fail if the function threw,
        // and the exhaustiveness it claimed to guard is already a compile error
        // (the `when` is expression-form, so a new ChatSurface breaks the build).
        // What is NOT compiler-guarded is a surface being flipped to Wired before
        // its callable exists, which would render a report row that submits into
        // the void. Pin the wired set to exactly the callables that exist.
        val wired = ChatSurface.entries.filter {
            MessageModeration.reportAvailability(it) == ReportAvailability.Wired
        }
        assertEquals(
            "Only add a surface here once its report callable actually exists",
            listOf(ChatSurface.EventChat),
            wired,
        )
    }

    @Test
    fun reporting_a_user_from_the_profile_is_not_wired_yet() {
        // No moderation.reportUser callable exists, so the profile's report row
        // must stay hidden. Same rule as the per-surface map, separate switch.
        assertEquals(ReportAvailability.BackendMissing, MessageModeration.reportUserAvailability)
    }

    @Test
    fun sheet_is_worth_opening_only_when_it_has_an_action() {
        // Guards the empty-sheet case: hiding an unbacked report means a surface
        // with no block either would open a sheet of nothing but a Close button.
        assertFalse(
            "A config-less build on a report-less surface must open no sheet",
            MessageModeration.hasActions(
                canBlock = false,
                reportAvailability = ReportAvailability.BackendMissing,
            ),
        )
        assertTrue(
            MessageModeration.hasActions(
                canBlock = true,
                reportAvailability = ReportAvailability.BackendMissing,
            ),
        )
        assertTrue(
            MessageModeration.hasActions(
                canBlock = false,
                reportAvailability = ReportAvailability.Wired,
            ),
        )
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
