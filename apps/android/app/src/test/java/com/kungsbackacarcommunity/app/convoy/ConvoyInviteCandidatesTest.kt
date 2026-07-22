package com.kungsbackacarcommunity.app.convoy

import com.kungsbackacarcommunity.app.friends.FriendSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The convoy invite-picker candidate rules: only friends who are NOT already in
 * the convoy (and never the caller) may be offered, because `convoy-invite`
 * silently skips anyone already in `memberUids`. These are the assertions that
 * keep the picker from listing people who are already in the convoy — the bug
 * where inviting showed current members as choices.
 */
class ConvoyInviteCandidatesTest {
    private fun friend(uid: String) =
        FriendSummary(
            uid = uid,
            displayName = uid,
            avatarPath = null,
            friendsSince = null,
        )

    private fun member(
        uid: String,
        role: ConvoyRole = ConvoyRole.Member,
        inviteStatus: ConvoyInviteStatus = ConvoyInviteStatus.Accepted,
    ) = ConvoyMember(
        uid = uid,
        role = role,
        inviteStatus = inviteStatus,
        joinedAt = null,
        displayName = uid,
        avatarPath = null,
    )

    private fun convoy(members: List<ConvoyMember>) =
        ConvoySummary(
            convoyId = "c1",
            ownerUid = "owner",
            title = null,
            status = ConvoyStatus.Active,
            members = members,
            memberUids = members.map { it.uid },
            viewer = ConvoyViewer(ConvoyRole.Member, ConvoyInviteStatus.Accepted),
            livePositionUids = emptyList(),
            summary = null,
            createdAt = null,
            startedAt = null,
            endedAt = null,
        )

    // --- the exclude set --------------------------------------------------

    @Test
    fun `excluded set is the convoy members plus the caller`() {
        val convoy =
            convoy(
                listOf(
                    member("owner", ConvoyRole.Owner),
                    member("me"),
                    member("pending", inviteStatus = ConvoyInviteStatus.Invited),
                    member("declined", inviteStatus = ConvoyInviteStatus.Declined),
                ),
            )
        // Owner, every invitee regardless of invite status, and the caller.
        assertEquals(
            setOf("owner", "me", "pending", "declined"),
            convoy.inviteExcludedUids("me"),
        )
    }

    @Test
    fun `caller is excluded even when not present in memberUids`() {
        val convoy = convoy(listOf(member("owner", ConvoyRole.Owner)))
        assertTrue(convoy.inviteExcludedUids("me").contains("me"))
    }

    @Test
    fun `null caller leaves only the convoy members excluded`() {
        val convoy = convoy(listOf(member("owner", ConvoyRole.Owner), member("me")))
        assertEquals(setOf("owner", "me"), convoy.inviteExcludedUids(null))
    }

    // --- the candidate filter (fail-first: member present unfiltered) -----

    @Test
    fun `a friend already in the convoy is dropped from the candidates`() {
        val friends = listOf(friend("me"), friend("owner"), friend("alice"), friend("bob"))
        val convoy =
            convoy(listOf(member("owner", ConvoyRole.Owner), member("me"), member("alice")))
        val excluded = convoy.inviteExcludedUids("me")

        // Fail-first framing: before filtering, the members ARE in the friend list.
        assertTrue(friends.any { it.uid == "owner" })
        assertTrue(friends.any { it.uid == "alice" })

        val candidates = invitableFriends(friends, excluded)

        // After filtering, only friends NOT in the convoy remain.
        assertEquals(listOf("bob"), candidates.map { it.uid })
        assertFalse(candidates.any { it.uid == "owner" })
        assertFalse(candidates.any { it.uid == "alice" })
        assertFalse(candidates.any { it.uid == "me" })
    }

    @Test
    fun `a pending-invited friend is not offered again`() {
        val friends = listOf(friend("me"), friend("pending"), friend("carol"))
        val convoy =
            convoy(
                listOf(
                    member("owner", ConvoyRole.Owner),
                    member("me"),
                    member("pending", inviteStatus = ConvoyInviteStatus.Invited),
                ),
            )
        val candidates = invitableFriends(friends, convoy.inviteExcludedUids("me"))
        assertEquals(listOf("carol"), candidates.map { it.uid })
    }

    @Test
    fun `order is preserved for the remaining candidates`() {
        val friends = listOf(friend("carol"), friend("alice"), friend("bob"))
        val candidates = invitableFriends(friends, setOf("alice"))
        assertEquals(listOf("carol", "bob"), candidates.map { it.uid })
    }

    // --- empty-state discrimination ---------------------------------------

    @Test
    fun `all friends already in the convoy yields an empty candidate list`() {
        val friends = listOf(friend("owner"), friend("me"), friend("alice"))
        val convoy =
            convoy(listOf(member("owner", ConvoyRole.Owner), member("me"), member("alice")))
        val candidates = invitableFriends(friends, convoy.inviteExcludedUids("me"))
        // Non-empty friend list, empty candidates -> the "all already in convoy"
        // empty state, distinct from having no friends at all.
        assertTrue(friends.isNotEmpty())
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `no exclusions offers every friend`() {
        val friends = listOf(friend("alice"), friend("bob"))
        assertEquals(friends, invitableFriends(friends, emptySet()))
    }

    // --- selection reconciliation (the open-picker roster race) -----------

    @Test
    fun `a selected friend who becomes excluded is dropped from the selection`() {
        val selected = setOf("alice", "bob")
        // 'alice' just joined the convoy while the picker was open.
        val convoy =
            convoy(listOf(member("owner", ConvoyRole.Owner), member("me"), member("alice")))
        val stillInvitable = invitableSelection(selected, convoy.inviteExcludedUids("me"))
        // The invite payload / enabled-state carries only the still-invitable pick.
        assertEquals(setOf("bob"), stillInvitable)
    }

    @Test
    fun `selection survives untouched when nothing is excluded`() {
        val selected = setOf("alice", "bob")
        assertEquals(selected, invitableSelection(selected, emptySet()))
    }

    @Test
    fun `all selected friends excluded yields an empty invite payload`() {
        val selected = setOf("alice", "bob")
        assertTrue(invitableSelection(selected, setOf("alice", "bob", "carol")).isEmpty())
    }
}
