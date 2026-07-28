package com.kungsbackacarcommunity.app.convoy

import com.kungsbackacarcommunity.app.profile.LiveProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The convoy half of live-profile hydration, including the property that made
 * convoy different from the other two surfaces: the roster is fed by BOTH the
 * `convoy-list` callable and a live document listener, so hydrating one path
 * alone would be undone by the other.
 */
class ConvoyLiveProfileHydrationTest {

    private fun member(
        uid: String,
        displayName: String? = "Old Name",
        avatarPath: String? = "profileImages/$uid/old.jpg",
    ) = ConvoyMember(
        uid = uid,
        role = if (uid == "owner") ConvoyRole.Owner else ConvoyRole.Member,
        inviteStatus = ConvoyInviteStatus.Accepted,
        joinedAt = null,
        displayName = displayName,
        avatarPath = avatarPath,
    )

    private fun summary(id: String, members: List<ConvoyMember>) =
        ConvoySummary(
            convoyId = id,
            ownerUid = "owner",
            title = "Kvällstur",
            status = ConvoyStatus.Active,
            members = members,
            memberUids = members.map { it.uid },
            viewer = ConvoyViewer(ConvoyRole.Owner, ConvoyInviteStatus.Accepted),
            livePositionUids = members.map { it.uid },
            summary = null,
            createdAt = null,
            startedAt = null,
            endedAt = null,
        )

    @Test
    fun `a member who changed their avatar after being invited shows the new one`() {
        val convoy = summary("c1", listOf(member("owner"), member("eva")))
        val live = mapOf("eva" to LiveProfile("Eva Ny", "profileImages/eva/new.jpg"))

        val eva = hydrateConvoy(convoy, live).members.single { it.uid == "eva" }

        assertEquals("Eva Ny", eva.displayName)
        assertEquals("profileImages/eva/new.jpg", eva.avatarPath)
    }

    @Test
    fun `a deleted avatar disappears from the roster`() {
        val convoy = summary("c1", listOf(member("eva")))
        val live = mapOf("eva" to LiveProfile("Eva", null))

        assertEquals(null, hydrateConvoy(convoy, live).members.single().avatarPath)
    }

    @Test
    fun `a member with no live profile keeps the copy captured at invite time`() {
        val convoy = summary("c1", listOf(member("gone")))

        val hydrated = hydrateConvoy(convoy, mapOf("eva" to LiveProfile("Eva", null)))

        assertEquals("Old Name", hydrated.members.single().displayName)
        assertEquals("profileImages/gone/old.jpg", hydrated.members.single().avatarPath)
    }

    @Test
    fun `hydration cannot change membership or authorization`() {
        // Membership is derived from memberUids / inviteStatus, never from the
        // profile map — so an overlay must leave every one of those untouched.
        val convoy = summary("c1", listOf(member("owner"), member("eva")))
        val live = mapOf("eva" to LiveProfile("Eva", null), "outsider" to LiveProfile("Nope", "x.jpg"))

        val hydrated = hydrateConvoy(convoy, live)

        assertEquals(convoy.memberUids, hydrated.memberUids)
        assertEquals(convoy.livePositionUids, hydrated.livePositionUids)
        assertEquals(convoy.members.map { it.uid }, hydrated.members.map { it.uid })
        assertEquals(convoy.members.map { it.role }, hydrated.members.map { it.role })
        assertEquals(convoy.members.map { it.inviteStatus }, hydrated.members.map { it.inviteStatus })
        assertEquals(convoy.ownerUid, hydrated.ownerUid)
        assertEquals(convoy.status, hydrated.status)
    }

    @Test
    fun `a live listener update carries the refreshed roster into the loaded snapshot`() {
        // The convoy-specific trap: the listener's snapshot holds the STORED
        // memberProfiles, so merging it un-hydrated would revert the roster to
        // old avatars the moment anything about the convoy changed. Hydrating
        // before the merge is what ConvoyCoordinator.observeActiveConvoy does.
        val live = mapOf("eva" to LiveProfile("Eva Ny", "profileImages/eva/new.jpg"))
        val loaded =
            ConvoyListStatus.Loaded(
                convoys = listOf(hydrateConvoy(summary("c1", listOf(member("eva"))), live)),
                pendingInvites = emptyList(),
            )

        val freshFromListener = summary("c1", listOf(member("eva")))
        val mergedRaw = mergeConvoyUpdate(loaded, freshFromListener) as ConvoyListStatus.Loaded
        val mergedHydrated =
            mergeConvoyUpdate(loaded, hydrateConvoy(freshFromListener, live)) as ConvoyListStatus.Loaded

        // Negative control for the ordering rule: merging raw REVERTS the avatar.
        assertEquals("profileImages/eva/old.jpg", mergedRaw.convoys.single().members.single().avatarPath)
        assertEquals(
            "profileImages/eva/new.jpg",
            mergedHydrated.convoys.single().members.single().avatarPath,
        )
    }

    @Test
    fun `profile uids are gathered across convoys and de-duplicated`() {
        val convoys =
            listOf(
                summary("c1", listOf(member("owner"), member("eva"))),
                summary("c2", listOf(member("owner"), member("nils"))),
            )

        // "owner" is in both convoys and must be paid for once.
        assertEquals(setOf("owner", "eva", "nils"), convoyProfileUids(convoys))
    }

    @Test
    fun `no live profiles at all is a no-op`() {
        val convoy = summary("c1", listOf(member("eva")))
        assertSame(convoy, hydrateConvoy(convoy, emptyMap()))
    }
}
