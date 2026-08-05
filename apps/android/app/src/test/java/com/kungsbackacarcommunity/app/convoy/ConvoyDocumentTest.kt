package com.kungsbackacarcommunity.app.convoy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the RAW convoy document → [ConvoySummary] mapper and the live
 * merge — the client mirror of the backend `toConvoySummary`. `toIso` is passed
 * as an identity-ish passthrough so the mapper stays Firebase-free here.
 */
class ConvoyDocumentTest {

    private val isoPassthrough: (Any?) -> String? = { it as? String }

    private fun doc(
        ownerUid: String? = "owner",
        status: String = "active",
        members: Map<String, Any?> =
            mapOf(
                "owner" to memberEntry("owner", "owner", "accepted"),
                "m2" to memberEntry("m2", "member", "invited"),
            ),
        profiles: Map<String, Any?> =
            mapOf(
                "owner" to mapOf("displayName" to "Olle", "avatarPath" to "a/olle.jpg"),
                "m2" to mapOf("displayName" to "Maja", "avatarPath" to null),
            ),
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> =
        buildMap {
            if (ownerUid != null) put("ownerUid", ownerUid)
            put("status", status)
            put("memberUids", listOf("owner", "m2"))
            put("members", members)
            put("memberProfiles", profiles)
            put("createdAt", "2026-07-19T10:00:00Z")
            putAll(extra)
        }

    private fun memberEntry(uid: String, role: String, inviteStatus: String): Map<String, Any?> =
        mapOf("uid" to uid, "role" to role, "inviteStatus" to inviteStatus, "joinedAt" to null)

    @Test
    fun `maps roster owner-first, derives viewer and accepted-only live set`() {
        val summary =
            ConvoyDocument.toSummary("c1", doc(), callerUid = "m2", toIso = isoPassthrough)!!

        assertEquals("c1", summary.convoyId)
        assertEquals("owner", summary.ownerUid)
        assertEquals(ConvoyStatus.Active, summary.status)
        // Owner sorts first regardless of uid order in the map.
        assertEquals(listOf("owner", "m2"), summary.members.map { it.uid })
        assertEquals("Olle", summary.members.first().displayName)
        // Viewer is derived from members[callerUid].
        assertEquals(ConvoyRole.Member, summary.viewer?.role)
        assertEquals(ConvoyInviteStatus.Invited, summary.viewer?.inviteStatus)
        // Only accepted members are in the live-position set (owner accepted, m2 not).
        assertEquals(listOf("owner"), summary.livePositionUids)
    }

    @Test
    fun `null caller yields a null viewer`() {
        val summary = ConvoyDocument.toSummary("c1", doc(), callerUid = null, toIso = isoPassthrough)!!
        assertNull(summary.viewer)
    }

    @Test
    fun `a document without an owner is dropped`() {
        assertNull(
            ConvoyDocument.toSummary(
                "c1",
                doc(ownerUid = null),
                callerUid = "owner",
                toIso = isoPassthrough,
            ),
        )
    }

    @Test
    fun `null data (missing document) maps to null`() {
        assertNull(ConvoyDocument.toSummary("c1", null, "owner", isoPassthrough))
    }

    @Test
    fun `summary present is parsed and distance stays null when absent`() {
        val summary =
            ConvoyDocument.toSummary(
                "c1",
                doc(
                    status = "ended",
                    extra =
                        mapOf(
                            "summary" to
                                mapOf(
                                    "durationSeconds" to 3600L,
                                    "participantUids" to listOf("owner", "m2"),
                                    "participantCount" to 2L,
                                    // distanceMeters intentionally absent (backend gap).
                                ),
                        ),
                ),
                callerUid = "owner",
                toIso = isoPassthrough,
            )!!
        assertEquals(3600L, summary.summary?.durationSeconds)
        assertEquals(2, summary.summary?.participantCount)
        assertNull(summary.summary?.distanceMeters)
    }

    @Test
    fun `a destination with a corrupt coordinate is dropped`() {
        val summary =
            ConvoyDocument.toSummary(
                "c1",
                doc(
                    extra =
                        mapOf(
                            "destination" to
                                mapOf(
                                    "latitude" to 999.0, // out of WGS-84 bounds
                                    "longitude" to 12.0,
                                    "setByUid" to "owner",
                                ),
                        ),
                ),
                callerUid = "owner",
                toIso = isoPassthrough,
            )!!
        assertNull(summary.destination)
    }

    @Test
    fun `a valid destination is parsed with attribution`() {
        val summary =
            ConvoyDocument.toSummary(
                "c1",
                doc(
                    extra =
                        mapOf(
                            "destination" to
                                mapOf(
                                    "latitude" to 57.48,
                                    "longitude" to 12.07,
                                    "label" to "Torg",
                                    "setByUid" to "m2",
                                    "setByDisplayName" to "Maja",
                                ),
                        ),
                ),
                callerUid = "owner",
                toIso = isoPassthrough,
            )!!
        assertEquals("Torg", summary.destination?.label)
        assertEquals("m2", summary.destination?.setByUid)
    }

    // ---- mergeConvoyUpdate -------------------------------------------------

    private fun summary(id: String, title: String?) =
        ConvoySummary(
            convoyId = id,
            ownerUid = "owner",
            title = title,
            status = ConvoyStatus.Active,
            members = emptyList(),
            memberUids = listOf("owner"),
            viewer = ConvoyViewer(ConvoyRole.Owner, ConvoyInviteStatus.Accepted),
            livePositionUids = emptyList(),
            summary = null,
            createdAt = null,
            startedAt = null,
            endedAt = null,
        )

    @Test
    fun `merge replaces the matching convoy in place and preserves order`() {
        val status =
            ConvoyListStatus.Loaded(
                convoys = listOf(summary("a", "A"), summary("b", "B"), summary("c", "C")),
                pendingInvites = emptyList(),
            )
        val merged = mergeConvoyUpdate(status, summary("b", "B-updated")) as ConvoyListStatus.Loaded
        assertEquals(listOf("a", "b", "c"), merged.convoys.map { it.convoyId })
        assertEquals("B-updated", merged.convoy("b")?.title)
    }

    @Test
    fun `merge for a convoy not in the snapshot is a no-op`() {
        val status =
            ConvoyListStatus.Loaded(convoys = listOf(summary("a", "A")), pendingInvites = emptyList())
        val merged = mergeConvoyUpdate(status, summary("z", "Z"))
        assertSame(status, merged)
    }

    @Test
    fun `merge on a non-loaded status returns it unchanged`() {
        val status = ConvoyListStatus.Loading
        assertSame(status, mergeConvoyUpdate(status, summary("a", "A")))
    }

    @Test
    fun `merge also replaces a still-pending invite`() {
        val status =
            ConvoyListStatus.Loaded(
                convoys = listOf(summary("p", "P")),
                pendingInvites = listOf(summary("p", "P")),
            )
        val merged = mergeConvoyUpdate(status, summary("p", "P2")) as ConvoyListStatus.Loaded
        assertTrue(merged.pendingInvites.all { it.title == "P2" })
    }

    // ---- endConvoyLocally --------------------------------------------------

    @Test
    fun `endConvoyLocally marks only the matching convoy ended and preserves order`() {
        val status =
            ConvoyListStatus.Loaded(
                convoys = listOf(summary("a", "A"), summary("b", "B"), summary("c", "C")),
                pendingInvites = emptyList(),
            )
        val ended = endConvoyLocally(status, "b") as ConvoyListStatus.Loaded
        assertEquals(listOf("a", "b", "c"), ended.convoys.map { it.convoyId })
        assertEquals(ConvoyStatus.Ended, ended.convoy("b")?.status)
        assertEquals(ConvoyStatus.Active, ended.convoy("a")?.status)
        assertEquals(ConvoyStatus.Active, ended.convoy("c")?.status)
    }

    @Test
    fun `endConvoyLocally hides the convoy from the active-bar selector`() {
        val status =
            ConvoyListStatus.Loaded(convoys = listOf(summary("a", "A")), pendingInvites = emptyList())
        // Before: the accepted, active convoy is what the bar shows.
        assertEquals("a", ConvoyBar.activeConvoy(status)?.convoyId)
        // After: nothing active is left, so the bar renders nothing.
        assertNull(ConvoyBar.activeConvoy(endConvoyLocally(status, "a")))
    }

    @Test
    fun `endConvoyLocally on a non-loaded status returns it unchanged`() {
        val status = ConvoyListStatus.Loading
        assertSame(status, endConvoyLocally(status, "a"))
    }

    @Test
    fun `endConvoyLocally for an absent convoy leaves every status untouched`() {
        val status =
            ConvoyListStatus.Loaded(convoys = listOf(summary("a", "A")), pendingInvites = emptyList())
        val result = endConvoyLocally(status, "missing") as ConvoyListStatus.Loaded
        assertEquals(ConvoyStatus.Active, result.convoy("a")?.status)
    }
}
