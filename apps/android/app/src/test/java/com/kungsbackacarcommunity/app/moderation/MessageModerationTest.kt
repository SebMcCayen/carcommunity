package com.kungsbackacarcommunity.app.moderation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageModerationTest {

    @Test
    fun eventChat_and_community_have_a_wired_report() {
        // events-reportChatMessage and chatchannels-reportMessage (channel:
        // 'community') both exist and are wired.
        assertEquals(
            ReportAvailability.Wired,
            MessageModeration.reportAvailability(ChatSurface.EventChat),
        )
        assertEquals(
            ReportAvailability.Wired,
            MessageModeration.reportAvailability(ChatSurface.CommunityChannel),
        )
    }

    @Test
    fun convoy_and_dms_have_no_report_wiring_yet() {
        // Convoy shares chatchannels-reportMessage but its Android entry point is
        // not wired; DMs (dm.reportMessage) have no client wiring either. Both must
        // stay BackendMissing so an un-fileable report never looks filed.
        listOf(ChatSurface.ConvoyChannel, ChatSurface.DirectMessage)
            .forEach { surface ->
                assertEquals(
                    "$surface must not offer a working report until its route wires one",
                    ReportAvailability.BackendMissing,
                    MessageModeration.reportAvailability(surface),
                )
            }
    }

    @Test
    fun only_surfaces_with_a_real_callable_are_wired() {
        // What is NOT compiler-guarded is a surface being flipped to Wired before
        // its callable exists (or is wired on the client), which would render a
        // report row that submits into the void. Pin the wired set to exactly the
        // surfaces that both have a callable AND route wiring.
        val wired = ChatSurface.entries.filter {
            MessageModeration.reportAvailability(it) == ReportAvailability.Wired
        }
        assertEquals(
            "Only add a surface here once its report callable exists AND its route wires it",
            listOf(ChatSurface.EventChat, ChatSurface.CommunityChannel),
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
