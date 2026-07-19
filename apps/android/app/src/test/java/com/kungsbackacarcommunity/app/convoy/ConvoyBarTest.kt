package com.kungsbackacarcommunity.app.convoy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The convoy status bar's visibility + owner/member rules, exercised as pure
 * state. These are the assertions that keep a member's "leave" from ever being
 * treated as wired (which would have to fall through to the owner-only,
 * group-wide `convoy-end`), and keep the bar from claiming a membership the user
 * has not accepted.
 */
class ConvoyBarTest {
    private fun member(
        uid: String,
        role: ConvoyRole = ConvoyRole.Member,
        inviteStatus: ConvoyInviteStatus = ConvoyInviteStatus.Accepted,
    ) = ConvoyMember(
        uid = uid,
        role = role,
        inviteStatus = inviteStatus,
        joinedAt = null,
        displayName = null,
        avatarPath = null,
    )

    private fun convoy(
        convoyId: String = "c1",
        status: ConvoyStatus = ConvoyStatus.Active,
        viewer: ConvoyViewer? =
            ConvoyViewer(ConvoyRole.Member, ConvoyInviteStatus.Accepted),
        members: List<ConvoyMember> = listOf(member("owner", ConvoyRole.Owner), member("me")),
    ) = ConvoySummary(
        convoyId = convoyId,
        ownerUid = "owner",
        title = null,
        status = status,
        members = members,
        memberUids = members.map { it.uid },
        viewer = viewer,
        livePositionUids = emptyList(),
        summary = null,
        createdAt = null,
        startedAt = null,
        endedAt = null,
    )

    private fun loaded(vararg convoys: ConvoySummary) =
        ConvoyListStatus.Loaded(convoys = convoys.toList(), pendingInvites = emptyList())

    // --- visibility -------------------------------------------------------

    @Test
    fun `no bar while the snapshot is still loading`() {
        assertNull(ConvoyBar.stateFor(ConvoyListStatus.Loading))
    }

    @Test
    fun `no bar when the snapshot failed`() {
        assertNull(ConvoyBar.stateFor(ConvoyListStatus.Error(ConvoyActionError.Generic)))
    }

    @Test
    fun `no bar when the caller is in no convoys at all`() {
        assertNull(ConvoyBar.stateFor(loaded()))
    }

    @Test
    fun `bar renders for an accepted member of an active convoy`() {
        val state = ConvoyBar.stateFor(loaded(convoy()))
        assertNotNull(state)
        assertEquals("c1", state!!.convoyId)
    }

    @Test
    fun `a still-pending invite is not being in a convoy`() {
        val pending =
            convoy(viewer = ConvoyViewer(ConvoyRole.Member, ConvoyInviteStatus.Invited))
        assertNull(ConvoyBar.stateFor(loaded(pending)))
    }

    @Test
    fun `a declined invite is not being in a convoy`() {
        val declined =
            convoy(viewer = ConvoyViewer(ConvoyRole.Member, ConvoyInviteStatus.Declined))
        assertNull(ConvoyBar.stateFor(loaded(declined)))
    }

    @Test
    fun `an ended convoy shows no bar even for its owner`() {
        val ended =
            convoy(
                status = ConvoyStatus.Ended,
                viewer = ConvoyViewer(ConvoyRole.Owner, ConvoyInviteStatus.Accepted),
            )
        assertNull(ConvoyBar.stateFor(loaded(ended)))
    }

    @Test
    fun `a forming convoy still counts as being in one`() {
        // The roster exists and members are gathering — exactly when someone wants
        // to see who is in and (eventually) invite more.
        val state = ConvoyBar.stateFor(loaded(convoy(status = ConvoyStatus.Forming)))
        assertNotNull(state)
    }

    @Test
    fun `a convoy with no viewer membership is never surfaced`() {
        assertNull(ConvoyBar.stateFor(loaded(convoy(viewer = null))))
    }

    @Test
    fun `an active convoy outranks a forming one`() {
        val state =
            ConvoyBar.stateFor(
                loaded(
                    convoy(convoyId = "forming", status = ConvoyStatus.Forming),
                    convoy(convoyId = "active", status = ConvoyStatus.Active),
                ),
            )
        assertEquals("active", state?.convoyId)
    }

    // --- member count -----------------------------------------------------

    @Test
    fun `member count counts accepted members only`() {
        val state =
            ConvoyBar.stateFor(
                loaded(
                    convoy(
                        members =
                            listOf(
                                member("owner", ConvoyRole.Owner),
                                member("me"),
                                member("pending", inviteStatus = ConvoyInviteStatus.Invited),
                                member("nope", inviteStatus = ConvoyInviteStatus.Declined),
                            ),
                    ),
                ),
            )
        assertEquals(2, state?.memberCount)
    }

    // --- owner vs member --------------------------------------------------

    @Test
    fun `a member's leave has no backend and must not be wired`() {
        val state = ConvoyBar.stateFor(loaded(convoy()))!!
        // If this ever flips to Wired without a convoy-leave callable existing,
        // the only thing the button could call is the owner-only, group-wide
        // convoy-end — i.e. a member's "leave" would end everyone's drive.
        assertEquals(ConvoyBarActionAvailability.BackendMissing, state.leaveAvailability)
        assertEquals(false, state.viewerIsOwner)
    }

    @Test
    fun `the owner's end-convoy action is wired`() {
        val state =
            ConvoyBar.stateFor(
                loaded(convoy(viewer = ConvoyViewer(ConvoyRole.Owner, ConvoyInviteStatus.Accepted))),
            )!!
        assertTrue(state.viewerIsOwner)
        assertEquals(ConvoyBarActionAvailability.Wired, state.leaveAvailability)
    }

    @Test
    fun `inviting into an existing convoy has no backend for either role`() {
        val asMember = ConvoyBar.stateFor(loaded(convoy()))!!
        val asOwner =
            ConvoyBar.stateFor(
                loaded(convoy(viewer = ConvoyViewer(ConvoyRole.Owner, ConvoyInviteStatus.Accepted))),
            )!!
        assertEquals(ConvoyBarActionAvailability.BackendMissing, asMember.inviteAvailability)
        assertEquals(ConvoyBarActionAvailability.BackendMissing, asOwner.inviteAvailability)
    }

    // --- the honest explanation line --------------------------------------

    @Test
    fun `a member is told both invite and leave are missing`() {
        val state = ConvoyBar.stateFor(loaded(convoy()))!!
        assertEquals(ConvoyBarNotice.InviteAndLeaveMissing, state.notice)
    }

    @Test
    fun `an owner is told only invite is missing`() {
        val state =
            ConvoyBar.stateFor(
                loaded(convoy(viewer = ConvoyViewer(ConvoyRole.Owner, ConvoyInviteStatus.Accepted))),
            )!!
        assertEquals(ConvoyBarNotice.InviteMissing, state.notice)
    }

    @Test
    fun `no explanation once both actions are wired`() {
        val state =
            ConvoyBarState(
                convoyId = "c1",
                memberCount = 2,
                viewerIsOwner = true,
                busy = false,
                inviteAvailability = ConvoyBarActionAvailability.Wired,
                leaveAvailability = ConvoyBarActionAvailability.Wired,
            )
        assertEquals(ConvoyBarNotice.None, state.notice)
    }

    // --- in-flight guard --------------------------------------------------

    @Test
    fun `busy reflects the coordinator's in-flight set for this convoy`() {
        val status = loaded(convoy(convoyId = "c1"))
        assertEquals(false, ConvoyBar.stateFor(status, emptySet())?.busy)
        assertEquals(true, ConvoyBar.stateFor(status, setOf("c1"))?.busy)
        assertEquals(false, ConvoyBar.stateFor(status, setOf("other"))?.busy)
    }
}
