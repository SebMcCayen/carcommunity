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
    fun `state carries the accepted members for the popup, count matches`() {
        val members =
            listOf(
                member("owner", ConvoyRole.Owner).copy(displayName = "Olle"),
                member("me").copy(displayName = "Maja"),
                // Invited-but-unanswered and declined are NOT in the convoy and
                // must not appear in the list or inflate the count.
                member("invited", inviteStatus = ConvoyInviteStatus.Invited),
                member("declined", inviteStatus = ConvoyInviteStatus.Declined),
            )
        val state = ConvoyBar.stateFor(loaded(convoy(members = members)))
        assertNotNull(state)
        assertEquals(2, state!!.memberCount)
        assertEquals(2, state.members.size)
        assertEquals(listOf("owner", "me"), state.members.map { it.uid })
        assertEquals(listOf("Olle", "Maja"), state.members.map { it.displayName })
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

    @Test
    fun `the bar shows for a convoy the user JOINED as an accepted member`() {
        // The bar must be visible whether the user joined a convoy or started one
        // themselves. This is the JOINED half — an accepted, non-owner membership
        // must surface the bar just like the owner's does, so the visibility rule
        // is member-OR-leader, not leader-only.
        val joined =
            convoy(viewer = ConvoyViewer(ConvoyRole.Member, ConvoyInviteStatus.Accepted))
        val state = ConvoyBar.stateFor(loaded(joined))
        assertNotNull(state)
        assertEquals(false, state!!.viewerIsOwner)
    }

    @Test
    fun `the bar shows for a convoy the user STARTED as its owner`() {
        // The STARTED half — the owner is always accepted, so the same non-ended
        // membership rule surfaces the bar. Paired with the JOINED test above, this
        // pins the "member OR leader" visibility the bar must never narrow back to
        // owner-only.
        val started =
            convoy(
                viewer = ConvoyViewer(ConvoyRole.Owner, ConvoyInviteStatus.Accepted),
                members = listOf(member("me", ConvoyRole.Owner)),
            )
        val state = ConvoyBar.stateFor(loaded(started))
        assertNotNull(state)
        assertEquals(true, state!!.viewerIsOwner)
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
    fun `a member's leave is now wired to convoy-leave`() {
        val state = ConvoyBar.stateFor(loaded(convoy()))!!
        // `convoy-leave` is deployed, so a non-owner member's Leave is Wired. The
        // bar still routes the trailing control on viewerIsOwner (asserted in the
        // ConvoyStatusBar UI test), so this being Wired can never turn a member's
        // Leave into the owner-only, group-wide End.
        assertEquals(ConvoyBarActionAvailability.Wired, state.leaveAvailability)
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
    fun `inviting into an existing convoy is wired for either role`() {
        val asMember = ConvoyBar.stateFor(loaded(convoy()))!!
        val asOwner =
            ConvoyBar.stateFor(
                loaded(convoy(viewer = ConvoyViewer(ConvoyRole.Owner, ConvoyInviteStatus.Accepted))),
            )!!
        assertEquals(ConvoyBarActionAvailability.Wired, asMember.inviteAvailability)
        assertEquals(ConvoyBarActionAvailability.Wired, asOwner.inviteAvailability)
    }

    // --- the honest explanation line --------------------------------------

    @Test
    fun `a member sees no missing-action notice now both are wired`() {
        val state = ConvoyBar.stateFor(loaded(convoy()))!!
        assertEquals(ConvoyBarNotice.None, state.notice)
    }

    @Test
    fun `an owner sees no missing-action notice now both are wired`() {
        val state =
            ConvoyBar.stateFor(
                loaded(convoy(viewer = ConvoyViewer(ConvoyRole.Owner, ConvoyInviteStatus.Accepted))),
            )!!
        assertEquals(ConvoyBarNotice.None, state.notice)
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

    /**
     * The notice must be derived from BOTH availabilities, not inferred from
     * leave alone. `convoy.invite` and `convoy.leave` are separate backend work
     * and will land in separate PRs, so the half-shipped combinations are the
     * ones that actually get shown to users — and the "invite shipped, leave has
     * not" row is exactly the one a leave-only derivation gets wrong, telling
     * people inviting is unavailable while an enabled invite button sits above
     * the sentence.
     */
    @Test
    fun `the explanation names exactly which actions are missing, for all four combinations`() {
        fun noticeFor(
            invite: ConvoyBarActionAvailability,
            leave: ConvoyBarActionAvailability,
        ) = ConvoyBarState(
            convoyId = "c1",
            memberCount = 2,
            viewerIsOwner = false,
            busy = false,
            inviteAvailability = invite,
            leaveAvailability = leave,
        ).notice

        val wired = ConvoyBarActionAvailability.Wired
        val missing = ConvoyBarActionAvailability.BackendMissing

        assertEquals(ConvoyBarNotice.None, noticeFor(wired, wired))
        assertEquals(ConvoyBarNotice.InviteMissing, noticeFor(missing, wired))
        assertEquals(ConvoyBarNotice.LeaveMissing, noticeFor(wired, missing))
        assertEquals(ConvoyBarNotice.InviteAndLeaveMissing, noticeFor(missing, missing))
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
