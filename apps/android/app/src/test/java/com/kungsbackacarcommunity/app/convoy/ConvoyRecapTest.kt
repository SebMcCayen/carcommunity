package com.kungsbackacarcommunity.app.convoy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Present / partial / absent-field logic for the ended-convoy recap. */
class ConvoyRecapTest {

    private fun member(uid: String, name: String?) =
        ConvoyMember(
            uid = uid,
            role = if (uid == "owner") ConvoyRole.Owner else ConvoyRole.Member,
            inviteStatus = ConvoyInviteStatus.Accepted,
            joinedAt = null,
            displayName = name,
            avatarPath = null,
        )

    private fun ended(
        summary: ConvoySummaryStats?,
        members: List<ConvoyMember> = emptyList(),
        status: ConvoyStatus = ConvoyStatus.Ended,
    ) = ConvoySummary(
        convoyId = "c1",
        ownerUid = "owner",
        title = "Trip",
        status = status,
        members = members,
        memberUids = members.map { it.uid },
        viewer = ConvoyViewer(ConvoyRole.Owner, ConvoyInviteStatus.Accepted),
        livePositionUids = emptyList(),
        summary = summary,
        createdAt = null,
        startedAt = null,
        endedAt = null,
    )

    private fun stats(
        duration: Long = 1800,
        uids: List<String> = listOf("owner", "m2"),
        count: Int = 2,
        distance: Double? = null,
    ) = ConvoySummaryStats(
        durationSeconds = duration,
        participantUids = uids,
        participantCount = count,
        distanceMeters = distance,
    )

    @Test
    fun `no recap for a live convoy`() {
        assertNull(ConvoyRecap.stateFor(ended(stats(), status = ConvoyStatus.Active)))
    }

    @Test
    fun `no recap when no summary was stored`() {
        assertNull(ConvoyRecap.stateFor(ended(summary = null)))
    }

    @Test
    fun `joins participant uids to roster names and avatars`() {
        val recap =
            ConvoyRecap.stateFor(
                ended(
                    summary = stats(uids = listOf("owner", "m2")),
                    members = listOf(member("owner", "Olle"), member("m2", "Maja")),
                ),
            )!!
        assertEquals(listOf("Olle", "Maja"), recap.participants.map { it.displayName })
        assertEquals(2, recap.participantCount)
    }

    @Test
    fun `a participant missing from the roster keeps a null name and still counts`() {
        val recap =
            ConvoyRecap.stateFor(
                ended(
                    summary = stats(uids = listOf("owner", "ghost"), count = 2),
                    members = listOf(member("owner", "Olle")),
                ),
            )!!
        assertEquals(listOf("Olle", null), recap.participants.map { it.displayName })
        assertEquals(2, recap.participantCount)
    }

    @Test
    fun `absent distance stays null for a graceful not-available render`() {
        val recap = ConvoyRecap.stateFor(ended(summary = stats(distance = null)))!!
        assertNull(recap.distanceMeters)
    }

    @Test
    fun `present distance is carried through`() {
        val recap = ConvoyRecap.stateFor(ended(summary = stats(distance = 12345.0)))!!
        assertEquals(12345.0, recap.distanceMeters!!, 0.0)
    }

    @Test
    fun `empty participant uids fall back to the stored count`() {
        val recap =
            ConvoyRecap.stateFor(
                ended(summary = stats(uids = emptyList(), count = 3)),
            )!!
        assertEquals(emptyList<ConvoyRecapParticipant>(), recap.participants)
        assertEquals(3, recap.participantCount)
    }
}
