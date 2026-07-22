package com.kungsbackacarcommunity.app.convoy

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The post-create navigation decision: a SUCCESSFUL create leaves the flow for the
 * map (where the convoy bar shows the new convoy); every other state stays on the
 * create screen so a FAILURE is shown there and the map is never entered as if
 * creation had succeeded.
 */
class PostCreateNavTest {

    @Test
    fun `created goes to the map`() {
        val state = CreateConvoyState.Created(convoyId = "c1", skipped = emptyList())
        assertEquals(PostCreateNav.GoToMap, postCreateNav(state))
    }

    @Test
    fun `created with skipped invitees still goes to the map`() {
        // The invite call still ran (invitees are part of create); skipped invitees
        // no longer block on a confirmation page — success still lands on the map.
        val state =
            CreateConvoyState.Created(
                convoyId = "c1",
                skipped = listOf(SkippedInvitee(uid = "u2", reason = ConvoySkipReason.NotFriend)),
            )
        assertEquals(PostCreateNav.GoToMap, postCreateNav(state))
    }

    @Test
    fun `error stays on the create screen`() {
        val state = CreateConvoyState.Error(ConvoyActionError.NoInvitees)
        assertEquals(PostCreateNav.Stay, postCreateNav(state))
    }

    @Test
    fun `generic error stays and does not fall through to the map`() {
        val state = CreateConvoyState.Error(ConvoyActionError.Generic)
        assertEquals(PostCreateNav.Stay, postCreateNav(state))
    }

    @Test
    fun `idle stays`() {
        assertEquals(PostCreateNav.Stay, postCreateNav(CreateConvoyState.Idle))
    }

    @Test
    fun `working stays`() {
        assertEquals(PostCreateNav.Stay, postCreateNav(CreateConvoyState.Working))
    }
}
