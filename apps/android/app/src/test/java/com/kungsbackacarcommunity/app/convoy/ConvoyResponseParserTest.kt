package com.kungsbackacarcommunity.app.convoy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure parsing of the convoy callable payloads (plain Map/List as the SDK deserializes). */
class ConvoyResponseParserTest {

    private fun convoyMap(
        id: String = "c1",
        status: String = "forming",
        viewerRole: String = "owner",
        viewerInvite: String = "accepted",
    ): Map<String, Any?> =
        mapOf(
            "convoyId" to id,
            "ownerUid" to "owner1",
            "title" to "Sunday cruise",
            "status" to status,
            "members" to
                listOf(
                    mapOf(
                        "uid" to "owner1",
                        "role" to "owner",
                        "inviteStatus" to "accepted",
                        "joinedAt" to "2026-07-15T09:00:00Z",
                        "displayName" to "Robin",
                        "avatarPath" to "avatars/owner1",
                    ),
                    mapOf(
                        "uid" to "m2",
                        "role" to "member",
                        "inviteStatus" to "invited",
                        "displayName" to "Kim",
                        "avatarPath" to null,
                    ),
                ),
            "memberUids" to listOf("owner1", "m2"),
            "viewer" to mapOf("role" to viewerRole, "inviteStatus" to viewerInvite),
            "livePositionUids" to listOf("owner1"),
            "summary" to null,
            "createdAt" to "2026-07-15T09:00:00Z",
            "startedAt" to null,
            "endedAt" to null,
        )

    @Test
    fun `parseList maps convoys and pending invites`() {
        val data =
            mapOf(
                "convoys" to listOf(convoyMap(id = "c1")),
                "pendingInvites" to listOf(convoyMap(id = "c2", viewerInvite = "invited", viewerRole = "member")),
            )
        val result = ConvoyResponseParser.parseList(data)
        assertEquals(1, result.convoys.size)
        assertEquals(1, result.pendingInvites.size)
        val convoy = result.convoys.first()
        assertEquals("c1", convoy.convoyId)
        assertEquals(ConvoyStatus.Forming, convoy.status)
        assertEquals(2, convoy.members.size)
        assertEquals(ConvoyRole.Owner, convoy.viewer?.role)
        assertTrue(convoy.viewerIsOwner)
        assertEquals(listOf("owner1"), convoy.livePositionUids)
    }

    @Test
    fun `parseList tolerates a null payload`() {
        val result = ConvoyResponseParser.parseList(null)
        assertTrue(result.convoys.isEmpty())
        assertTrue(result.pendingInvites.isEmpty())
    }

    @Test
    fun `member rows without a uid are dropped rather than crashing`() {
        val data =
            mapOf(
                "convoys" to
                    listOf(
                        convoyMap().toMutableMap().apply {
                            this["members"] = listOf(mapOf("role" to "member"), mapOf("uid" to "ok"))
                        },
                    ),
                "pendingInvites" to emptyList<Any?>(),
            )
        val convoy = ConvoyResponseParser.parseList(data).convoys.first()
        assertEquals(listOf("ok"), convoy.members.map { it.uid })
    }

    @Test
    fun `convoy rows with a missing or blank ownerUid are dropped as malformed`() {
        val data =
            mapOf(
                "convoys" to
                    listOf(
                        convoyMap(id = "c1").toMutableMap().apply { remove("ownerUid") },
                        convoyMap(id = "c2").toMutableMap().apply { this["ownerUid"] = "   " },
                        convoyMap(id = "c3"),
                    ),
                "pendingInvites" to emptyList<Any?>(),
            )
        val result = ConvoyResponseParser.parseList(data)
        // Only the well-formed row (c3) survives; the missing/blank-owner rows are dropped.
        assertEquals(listOf("c3"), result.convoys.map { it.convoyId })
    }

    @Test
    fun `parseCreate maps convoy, invited and skipped with reasons`() {
        val data =
            mapOf(
                "convoy" to convoyMap(),
                "invited" to listOf("m2"),
                "skipped" to
                    listOf(
                        mapOf("uid" to "x", "reason" to "not_friend"),
                        mapOf("uid" to "y", "reason" to "duplicate"),
                        mapOf("uid" to "z", "reason" to "weird_unknown"),
                    ),
            )
        val result = ConvoyResponseParser.parseCreate(data)
        assertTrue(result is CreateConvoyResult.Created)
        result as CreateConvoyResult.Created
        assertEquals(listOf("m2"), result.invited)
        assertEquals(
            listOf(ConvoySkipReason.NotFriend, ConvoySkipReason.Duplicate, ConvoySkipReason.Unknown),
            result.skipped.map { it.reason },
        )
    }

    @Test
    fun `parseCreate without a convoy is a failure`() {
        val result = ConvoyResponseParser.parseCreate(mapOf("invited" to emptyList<Any?>()))
        assertTrue(result is CreateConvoyResult.Failed)
    }

    @Test
    fun `parseMutation reads the convoy and its summary`() {
        val ended =
            convoyMap(id = "c9", status = "ended").toMutableMap().apply {
                this["summary"] =
                    mapOf(
                        "durationSeconds" to 3661.0,
                        "participantUids" to listOf("owner1", "m2"),
                        "participantCount" to 2,
                        "distanceMeters" to null,
                    )
                this["endedAt"] = "2026-07-15T10:00:00Z"
            }
        val result = ConvoyResponseParser.parseMutation(mapOf("convoy" to ended))
        assertTrue(result is ConvoyMutationResult.Updated)
        val convoy = (result as ConvoyMutationResult.Updated).convoy
        assertEquals(ConvoyStatus.Ended, convoy.status)
        assertEquals(3661L, convoy.summary?.durationSeconds)
        assertEquals(2, convoy.summary?.participantCount)
        assertNull(convoy.summary?.distanceMeters)
    }

    @Test
    fun `parseMutation without a convoy is a failure`() {
        val result = ConvoyResponseParser.parseMutation(emptyMap())
        assertTrue(result is ConvoyMutationResult.Failed)
    }

    // --- convoy-leave: richer than a plain mutation -----------------------

    @Test
    fun `parseLeave carries what the exit DID and who inherited leadership`() {
        val result =
            ConvoyResponseParser.parseLeave(
                mapOf(
                    "convoy" to convoyMap(id = "c1"),
                    "remainingMemberCount" to 2.0,
                    "outcome" to "left",
                    "newLeaderUid" to "successor",
                ),
            )
        assertTrue(result is LeaveConvoyResult.Left)
        val left = result as LeaveConvoyResult.Left
        assertEquals(2, left.remainingMemberCount)
        assertEquals(ConvoyLeaveOutcome.Left, left.outcome)
        assertEquals("successor", left.newLeaderUid)
    }

    @Test
    fun `parseLeave reads the convoy-ended outcome rather than inferring it`() {
        // The threshold is a SERVER rule; the client takes the answer as given.
        val result =
            ConvoyResponseParser.parseLeave(
                mapOf(
                    "convoy" to convoyMap(id = "c1", status = "ended"),
                    "remainingMemberCount" to 1.0,
                    "outcome" to "left_and_ended",
                    "newLeaderUid" to null,
                ),
            ) as LeaveConvoyResult.Left
        assertEquals(ConvoyLeaveOutcome.LeftAndEnded, result.outcome)
        assertNull(result.newLeaderUid)
    }

    @Test
    fun `parseLeave degrades an unknown or missing outcome to the conservative one`() {
        // Announcing "the convoy ended" when it did not is the worse error, so an
        // unreadable outcome reads as a plain leave.
        val result =
            ConvoyResponseParser.parseLeave(mapOf("convoy" to convoyMap(id = "c1")))
                as LeaveConvoyResult.Left
        assertEquals(ConvoyLeaveOutcome.Left, result.outcome)
        assertEquals(0, result.remainingMemberCount)
        assertNull(result.newLeaderUid)

        val garbled =
            ConvoyResponseParser.parseLeave(
                mapOf("convoy" to convoyMap(id = "c1"), "outcome" to "something-new"),
            ) as LeaveConvoyResult.Left
        assertEquals(ConvoyLeaveOutcome.Left, garbled.outcome)
    }

    @Test
    fun `parseLeave treats a blank successor uid as nobody`() {
        // A uid that names nobody must not render as "someone took over".
        val result =
            ConvoyResponseParser.parseLeave(
                mapOf("convoy" to convoyMap(id = "c1"), "newLeaderUid" to "   "),
            ) as LeaveConvoyResult.Left
        assertNull(result.newLeaderUid)
    }

    @Test
    fun `parseLeave without a convoy is a failure`() {
        assertTrue(ConvoyResponseParser.parseLeave(emptyMap()) is LeaveConvoyResult.Failed)
    }
}
