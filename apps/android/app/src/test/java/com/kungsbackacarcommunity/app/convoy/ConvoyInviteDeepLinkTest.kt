package com.kungsbackacarcommunity.app.convoy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The landing side of a convoy-invite notification tap: what the member is told
 * when the invite they tapped is no longer waiting for them.
 */
class ConvoyInviteDeepLinkTest {
    private fun convoy(
        convoyId: String,
        status: ConvoyStatus = ConvoyStatus.Active,
        inviteStatus: ConvoyInviteStatus = ConvoyInviteStatus.Invited,
    ) = ConvoySummary(
        convoyId = convoyId,
        ownerUid = "owner",
        title = null,
        status = status,
        members = emptyList(),
        memberUids = listOf("owner", "me"),
        viewer = ConvoyViewer(ConvoyRole.Member, inviteStatus),
        livePositionUids = emptyList(),
        summary = null,
        createdAt = null,
        startedAt = null,
        endedAt = null,
    )

    private fun loaded(
        convoys: List<ConvoySummary>,
        pendingInvites: List<ConvoySummary> = emptyList(),
    ) = ConvoyListStatus.Loaded(convoys = convoys, pendingInvites = pendingInvites)

    // --- outcome -------------------------------------------------------

    @Test
    fun `no deep link means nothing to say`() {
        assertNull(ConvoyInviteDeepLink.outcome(null, loaded(emptyList())))
        assertNull(ConvoyInviteDeepLink.outcome("  ", loaded(emptyList())))
    }

    @Test
    fun `stays silent until the list has actually loaded`() {
        // A verdict on a list we do not have would be a guess, and the guess
        // people would see is "your invite is gone" on a perfectly good invite.
        assertNull(ConvoyInviteDeepLink.outcome("c1", ConvoyListStatus.Loading))
        assertNull(
            ConvoyInviteDeepLink.outcome("c1", ConvoyListStatus.Error(ConvoyActionError.Generic)),
        )
    }

    @Test
    fun `an invite that is still waiting needs no notice`() {
        val c = convoy("c1")
        val outcome = ConvoyInviteDeepLink.outcome("c1", loaded(listOf(c), listOf(c)))
        assertEquals(ConvoyInviteDeepLinkOutcome.PENDING, outcome)
        assertFalse(ConvoyInviteDeepLink.needsNotice(outcome))
    }

    @Test
    fun `a convoy that ended between the notification and the tap says so`() {
        // THE RACE: the row looked actionable off a stale snapshot; by the time
        // the tap landed the owner had ended the convoy.
        val outcome =
            ConvoyInviteDeepLink.outcome(
                "c1",
                loaded(listOf(convoy("c1", status = ConvoyStatus.Ended))),
            )
        assertEquals(ConvoyInviteDeepLinkOutcome.ENDED, outcome)
        assertTrue(ConvoyInviteDeepLink.needsNotice(outcome))
    }

    @Test
    fun `an invite answered elsewhere says so`() {
        val outcome =
            ConvoyInviteDeepLink.outcome(
                "c1",
                // Present and live, but absent from pendingInvites — which the
                // backend builds as "not ended AND still invited".
                loaded(listOf(convoy("c1", inviteStatus = ConvoyInviteStatus.Accepted))),
            )
        assertEquals(ConvoyInviteDeepLinkOutcome.ANSWERED, outcome)
        assertTrue(ConvoyInviteDeepLink.needsNotice(outcome))
    }

    @Test
    fun `a convoy that is not in the list at all is reported as gone`() {
        val outcome = ConvoyInviteDeepLink.outcome("c9", loaded(listOf(convoy("c1"))))
        assertEquals(ConvoyInviteDeepLinkOutcome.GONE, outcome)
        assertTrue(ConvoyInviteDeepLink.needsNotice(outcome))
    }

    @Test
    fun `an ended convoy reads as ended even while it sits in pendingInvites`() {
        // Defensive: the two lists come from the same response, but a stale or
        // hand-built pendingInvites must not outrank the convoy's own status —
        // ended wins, because offering Accept on an ended convoy is the failure.
        val c = convoy("c1", status = ConvoyStatus.Ended)
        assertEquals(
            ConvoyInviteDeepLinkOutcome.ENDED,
            ConvoyInviteDeepLink.outcome("c1", loaded(listOf(c), pendingInvites = listOf(c))),
        )
    }

    // --- ordering ------------------------------------------------------

    @Test
    fun `the tapped invite is pulled to the front`() {
        val invites = listOf(convoy("a"), convoy("b"), convoy("c"))
        val ordered = ConvoyInviteDeepLink.inviteesFirst(invites, "c")
        assertEquals(listOf("c", "a", "b"), ordered.map { it.convoyId })
    }

    @Test
    fun `ordering keeps every row exactly once`() {
        val invites = listOf(convoy("a"), convoy("b"), convoy("c"))
        val ordered = ConvoyInviteDeepLink.inviteesFirst(invites, "b")
        assertEquals(invites.size, ordered.size)
        assertEquals(invites.map { it.convoyId }.toSet(), ordered.map { it.convoyId }.toSet())
    }

    @Test
    fun `no id, an unknown id, or a single row leaves the list untouched`() {
        val invites = listOf(convoy("a"), convoy("b"))
        assertSame(invites, ConvoyInviteDeepLink.inviteesFirst(invites, null))
        assertSame(invites, ConvoyInviteDeepLink.inviteesFirst(invites, " "))
        assertSame(invites, ConvoyInviteDeepLink.inviteesFirst(invites, "zz"))
        val single = listOf(convoy("a"))
        assertSame(single, ConvoyInviteDeepLink.inviteesFirst(single, "a"))
        assertSame(emptyList<ConvoySummary>(), ConvoyInviteDeepLink.inviteesFirst(emptyList(), "a"))
    }
}
