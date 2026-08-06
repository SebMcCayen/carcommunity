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
                // Invited-but-unanswered is a PENDING invitee — it surfaces in the
                // "waiting to join" rows, never in the accepted roster or the count.
                // Declined is neither in the convoy nor waiting for it.
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

    // --- pending invitees in the "In this convoy" list --------------------

    @Test
    fun `pendingInvitees are the invited-but-unanswered, declined and accepted excluded`() {
        val members =
            listOf(
                member("owner", ConvoyRole.Owner),
                member("me"),
                member("inv1", inviteStatus = ConvoyInviteStatus.Invited).copy(displayName = "Ivar"),
                member("inv2", inviteStatus = ConvoyInviteStatus.Invited).copy(displayName = "Iris"),
                member("declined", inviteStatus = ConvoyInviteStatus.Declined),
            )
        val pending = ConvoyBar.pendingInvitees(convoy(members = members))
        assertEquals(listOf("inv1", "inv2"), pending.map { it.uid })
        assertEquals(listOf("Ivar", "Iris"), pending.map { it.displayName })
    }

    @Test
    fun `state carries the pending invitees separately from the accepted members`() {
        val members =
            listOf(
                member("owner", ConvoyRole.Owner),
                member("me"),
                member("inv", inviteStatus = ConvoyInviteStatus.Invited),
            )
        val state = ConvoyBar.stateFor(loaded(convoy(members = members)))!!
        // The waiting person never inflates the count or the accepted roster...
        assertEquals(2, state.memberCount)
        assertEquals(listOf("owner", "me"), state.members.map { it.uid })
        // ...but is carried for the popup to show as "waiting to join".
        assertEquals(listOf("inv"), state.pendingInvitees.map { it.uid })
    }

    @Test
    fun `memberListEntries lists joined members first then waiting invitees`() {
        val members =
            listOf(
                member("owner", ConvoyRole.Owner),
                member("me"),
                member("inv", inviteStatus = ConvoyInviteStatus.Invited),
            )
        val entries = ConvoyBar.memberListEntries(convoy(members = members))
        assertEquals(
            listOf(
                "owner" to ConvoyMemberPresence.Joined,
                "me" to ConvoyMemberPresence.Joined,
                "inv" to ConvoyMemberPresence.WaitingToJoin,
            ),
            entries.map { it.member.uid to it.presence },
        )
    }

    @Test
    fun `a pending invitee moves from waiting to joined once they accept`() {
        val invited =
            listOf(member("owner", ConvoyRole.Owner), member("me"), member("guest", inviteStatus = ConvoyInviteStatus.Invited))
        val before = ConvoyBar.memberListEntries(convoy(members = invited))
        assertEquals(ConvoyMemberPresence.WaitingToJoin, before.first { it.member.uid == "guest" }.presence)

        // The same convoy after `convoy-respond` flips the invite to accepted.
        val accepted =
            invited.map { if (it.uid == "guest") it.copy(inviteStatus = ConvoyInviteStatus.Accepted) else it }
        val after = ConvoyBar.memberListEntries(convoy(members = accepted))
        assertEquals(ConvoyMemberPresence.Joined, after.first { it.member.uid == "guest" }.presence)
        assertTrue(after.none { it.presence == ConvoyMemberPresence.WaitingToJoin })
    }

    @Test
    fun `a declined or cancelled invitee disappears from the list entirely`() {
        val invited =
            listOf(member("owner", ConvoyRole.Owner), member("me"), member("guest", inviteStatus = ConvoyInviteStatus.Invited))
        // Declined: the row drops out of the waiting set and is not joined either.
        val declined =
            invited.map { if (it.uid == "guest") it.copy(inviteStatus = ConvoyInviteStatus.Declined) else it }
        val afterDecline = ConvoyBar.memberListEntries(convoy(members = declined))
        assertTrue(afterDecline.none { it.member.uid == "guest" })

        // Cancelled: the invite is removed from the roster altogether.
        val cancelled = invited.filterNot { it.uid == "guest" }
        val afterCancel = ConvoyBar.memberListEntries(convoy(members = cancelled))
        assertTrue(afterCancel.none { it.member.uid == "guest" })
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
        // `convoy-leave` is deployed, so a non-leader member's Leave is Wired. The
        // bar still routes the trailing control on the exit CHOICE (asserted in
        // the ConvoyStatusBar UI test), so this being Wired can never turn a
        // member's Leave into the leader-only, group-wide End.
        assertEquals(ConvoyBarActionAvailability.Wired, state.leaveAvailability)
        assertEquals(false, state.viewerIsOwner)
    }

    // --- the two exits ----------------------------------------------------

    private fun accepted(count: Int): List<ConvoyMember> =
        List(count) { index ->
            if (index == 0) member("owner", ConvoyRole.Owner) else member("m$index")
        }

    @Test
    fun `the LEADER gets a real choice only while the convoy would survive`() {
        // 3 accepted → 2 remain → the convoy lives on without them, so leaving and
        // ending are genuinely different outcomes and both are offered.
        assertEquals(
            ConvoyExitChoice.LeaveOrEnd,
            ConvoyBar.exitChoice(viewerIsOwner = true, acceptedMemberCount = 3),
        )
        // 2 accepted → 1 would remain → leaving ENDS it anyway, so offering both
        // would be two buttons for one outcome. End alone, honestly labelled.
        assertEquals(
            ConvoyExitChoice.EndOnly,
            ConvoyBar.exitChoice(viewerIsOwner = true, acceptedMemberCount = 2),
        )
        // Alone in it: same reasoning.
        assertEquals(
            ConvoyExitChoice.EndOnly,
            ConvoyBar.exitChoice(viewerIsOwner = true, acceptedMemberCount = 1),
        )
    }

    @Test
    fun `a NON-leader is never offered end-for-everyone, whatever the count`() {
        // The whole point of the split: ending is leader-only, so no member count
        // can ever produce an option that closes other people's convoy.
        for (count in 0..6) {
            val choice = ConvoyBar.exitChoice(viewerIsOwner = false, acceptedMemberCount = count)
            assertTrue(
                choice == ConvoyExitChoice.LeaveOnly || choice == ConvoyExitChoice.LeaveEndsConvoy,
            )
        }
        assertEquals(
            ConvoyExitChoice.LeaveOnly,
            ConvoyBar.exitChoice(viewerIsOwner = false, acceptedMemberCount = 3),
        )
    }

    @Test
    fun `a NON-leader whose exit ends the convoy is still offered Leave`() {
        // Seb's rule says "Leave" is only a real leave when 2+ remain — but HIDING
        // it here would trap a member in a two-person convoy: they may not end it
        // (leader-only) and would have no exit at all. So it is still offered, and
        // the confirmation says the convoy will end.
        val choice = ConvoyBar.exitChoice(viewerIsOwner = false, acceptedMemberCount = 2)
        assertEquals(ConvoyExitChoice.LeaveEndsConvoy, choice)
        assertTrue(choice.endsConvoy)
    }

    @Test
    fun `an unloaded or impossible roster never reads as plenty-will-remain`() {
        // A negative remaining count must not sneak through as "the convoy
        // survives" — that would offer a plain Leave for an exit that ends it.
        assertEquals(
            ConvoyExitChoice.LeaveEndsConvoy,
            ConvoyBar.exitChoice(viewerIsOwner = false, acceptedMemberCount = 0),
        )
        assertEquals(
            ConvoyExitChoice.EndOnly,
            ConvoyBar.exitChoice(viewerIsOwner = true, acceptedMemberCount = -3),
        )
    }

    @Test
    fun `the bar state derives its exit choice from the roster it renders`() {
        // Derived, not stored: the count on the bar and the option behind it come
        // from the same list, so they cannot disagree.
        val leaderOfThree =
            ConvoyBar.stateFor(
                loaded(
                    convoy(
                        viewer = ConvoyViewer(ConvoyRole.Owner, ConvoyInviteStatus.Accepted),
                        members = accepted(3),
                    ),
                ),
            )!!
        assertEquals(3, leaderOfThree.memberCount)
        assertEquals(ConvoyExitChoice.LeaveOrEnd, leaderOfThree.exitChoice)

        val memberOfTwo = ConvoyBar.stateFor(loaded(convoy(members = accepted(2))))!!
        assertEquals(ConvoyExitChoice.LeaveEndsConvoy, memberOfTwo.exitChoice)
    }

    @Test
    fun `still-invited members do not keep a convoy above the survival threshold`() {
        // The threshold counts ACCEPTED members, matching the server: two people
        // who were invited but never answered are not two people driving along.
        val state =
            ConvoyBar.stateFor(
                loaded(
                    convoy(
                        viewer = ConvoyViewer(ConvoyRole.Owner, ConvoyInviteStatus.Accepted),
                        members =
                            listOf(
                                member("owner", ConvoyRole.Owner),
                                member("me"),
                                member("pending1", inviteStatus = ConvoyInviteStatus.Invited),
                                member("pending2", inviteStatus = ConvoyInviteStatus.Invited),
                            ),
                    ),
                ),
            )!!
        assertEquals(2, state.memberCount)
        assertEquals(ConvoyExitChoice.EndOnly, state.exitChoice)
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
                members = emptyList(),
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
            members = emptyList(),
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

    // --- convoy-chat unread badge -----------------------------------------

    /**
     * Zero must be ABSENCE, not a drawn "0". A badge is an invitation to open the
     * chat; a "0" sitting on the icon is a permanent, meaningless decoration and
     * the one thing a caught-up member must not see.
     */
    @Test
    fun `the unread badge is nothing at all at zero`() {
        assertNull(ConvoyBar.unreadBadgeLabel(0))
        // Defensive: a negative count is no more badge-worthy than zero.
        assertNull(ConvoyBar.unreadBadgeLabel(-1))
    }

    @Test
    fun `the unread badge prints the count up to the cap`() {
        assertEquals("1", ConvoyBar.unreadBadgeLabel(1))
        assertEquals("5", ConvoyBar.unreadBadgeLabel(5))
        assertEquals(
            ConvoyBar.UNREAD_DISPLAY_MAX.toString(),
            ConvoyBar.unreadBadgeLabel(ConvoyBar.UNREAD_DISPLAY_MAX),
        )
    }

    /**
     * The cap is what keeps the badge one character wide. The bar is a single
     * compact row sharing the map shell with the member count and three other
     * controls, so an uncapped number on a long-running convoy would widen this
     * control until they were squeezed off.
     */
    @Test
    fun `the unread badge saturates past the cap instead of growing`() {
        val saturated = "${ConvoyBar.UNREAD_DISPLAY_MAX}+"
        assertEquals(saturated, ConvoyBar.unreadBadgeLabel(ConvoyBar.UNREAD_DISPLAY_MAX + 1))
        assertEquals(saturated, ConvoyBar.unreadBadgeLabel(1000))
        assertEquals(saturated, ConvoyBar.unreadBadgeLabel(Int.MAX_VALUE))
    }

    @Test
    fun `the unread count is carried onto the bar state, defaulting to none`() {
        val status = loaded(convoy(convoyId = "c1"))
        assertEquals(0, ConvoyBar.stateFor(status)?.unreadChatCount)
        assertEquals(
            4,
            ConvoyBar.stateFor(status, emptySet(), "me", unreadChatCount = 4)?.unreadChatCount,
        )
    }
}
