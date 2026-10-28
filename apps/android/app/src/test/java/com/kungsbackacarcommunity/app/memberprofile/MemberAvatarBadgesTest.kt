package com.kungsbackacarcommunity.app.memberprofile

import org.junit.Assert.*
import org.junit.Test

class MemberAvatarBadgesTest {
    private val affiliation = mapOf("clubId" to "club", "clubName" to "Club name", "logoUrl" to null)
    private val initial = mapOf("clubBadge" to affiliation, "supporterBadgeEligible" to true, "showSupporterBadge" to true)

    @Test fun oneCanonicalSnapshotProjectsBothBadgesAndPreservesProfileIdentity() {
        val result = MemberAvatarBadges.fromFields(initial)
            .applyTo(MemberProfile("member", "Ada", "Biography", avatarPath = "profileImages/member/photo"))
        assertTrue(result.supporterBadge.visible)
        assertEquals("club", result.clubBadge?.clubId)
        assertEquals("Ada", result.displayName)
        assertEquals("Biography", result.bio)
        assertEquals("profileImages/member/photo", result.avatarPath)
    }

    @Test fun explicitNullAndMissingMembershipCannotResurrectLoadedAffiliation() {
        val loaded = MemberAvatarBadges.fromFields(initial).applyTo(MemberProfile("member", "Ada", null))
        for (next in listOf(initial + ("clubBadge" to null), initial - "clubBadge")) {
            val updated = MemberAvatarBadges.fromFields(next).applyTo(loaded)
            assertNull(updated.clubBadge)
            assertTrue(updated.supporterBadge.visible)
        }
    }

    @Test fun deniedMissingOrMalformedSnapshotClearsUnverifiedCosmetics() {
        val loaded = MemberAvatarBadges.fromFields(initial).applyTo(MemberProfile("member", "Ada", null))
        val unavailable = MemberAvatarBadges.fromFields(null).applyTo(loaded)
        assertNull(unavailable.clubBadge)
        assertFalse(unavailable.supporterBadge.visible)
        val invalid = MemberAvatarBadges.fromFields(initial + ("clubBadge" to "unknown"))
        assertNull(invalid.clubBadge)
        assertTrue(invalid.supporterBadge.visible)
    }
}
