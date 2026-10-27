package com.kungsbackacarcommunity.app.profile

import com.kungsbackacarcommunity.app.memberprofile.MemberProfile
import org.junit.Assert.*
import org.junit.Test

class ClubBadgeTest {
    private fun fields(id: String = "club_12345678", name: String = "Östra Klubben", logo: Any? = null) =
        mapOf("clubId" to id, "clubName" to name, "logoUrl" to logo)

    private val logo = "https://firebasestorage.googleapis.com/v0/b/example.firebasestorage.app/o/" +
        "clubLogos%2Fclub_12345678%2Fowner_12345678%2Flogo.webp?alt=media"

    @Test fun canonicalProjectionPreservesIdentityAndApprovedImageWithoutResolution() {
        val badge = requireNotNull(ClubBadge.fromField(fields(logo = logo)))
        assertEquals("club_12345678", badge.clubId)
        assertEquals("Östra Klubben", badge.clubName)
        assertEquals("ÖK", badge.initials)
        assertEquals(logo, badge.logoUrl)
    }

    @Test fun absentUnknownAndMalformedAffiliationsFailClosed() {
        listOf(null, "club_12345678", emptyList<Any>(), emptyMap<String, Any>(),
            fields() + ("clubId" to 1), fields() + ("clubName" to false),
            fields(logo = 1), fields() + ("role" to "owner"),
        ).forEach { assertNull("Accepted $it", ClubBadge.fromField(it)) }
        listOf("", "short", "a".repeat(129), "club/a123", "club%2Fa1", "club id1").forEach {
            assertNull(ClubBadge.fromField(fields(id = it)))
        }
        listOf("", "ab", " ", " club", "club ", "a".repeat(61), "club\nname").forEach {
            assertNull(ClubBadge.fromField(fields(name = it)))
        }
    }

    @Test fun identifierAndUnicodeNameBoundsAreCodePointAware() {
        for (id in listOf("a".repeat(8), "a".repeat(128))) {
            for (name in listOf("abc", "a".repeat(60), "😀".repeat(60))) {
                assertNotNull(ClubBadge.fromField(fields(id, name)))
            }
        }
        assertNull(ClubBadge.fromField(fields(name = "😀".repeat(61))))
        assertEquals("ÖK", ClubBadge.fromField(fields(name = "Östra   Klubben"))!!.initials)
    }

    @Test fun absentAndUnsafeImagesKeepOnlyAnInitialsFallback() {
        val unsafe = listOf(null, "", "http://example.test/logo.webp", "https://example.test/logo.webp",
            "file:///logo.webp", "content://logo", "javascript:alert(1)",
            logo.replace("https://", "https://user:pass@"), logo + "#fragment",
            logo.replace(".com/", ".com:443/"), logo.replace("club_12345678", "club_87654321"),
            logo.replace("logo.webp", ".."), logo.replace("logo.webp", "%252F"),
            logo.replace("logo.webp", "extra%2Flogo.webp"), "https://" + "x".repeat(2048),
        )
        unsafe.forEach { url ->
            val badge = requireNotNull(ClubBadge.fromField(fields(logo = url)))
            assertNull("Unsafe image survived: $url", badge.logoUrl)
            assertEquals("ÖK", badge.initials)
        }
    }

    @Test fun clearedCanonicalMembershipOverridesStoredBadgeAndKeepsSupporterChoice() {
        val badge = requireNotNull(ClubBadge.fromField(fields()))
        val profile = UserProfile("Ada", null, onboardingComplete = true,
            supporterBadge = SupporterBadge(eligible = true), clubBadge = badge)
        val cleared = profile.copy(clubBadge = ClubBadge.fromField(null))
        assertNull(cleared.clubBadge)
        assertTrue(cleared.supporterBadge.visible)
        val stored = LiveProfile("Ada", null, badge)
        val live = LiveProfile("Ada", null, ClubBadge.fromField(null))
        assertNull(LiveProfiles.resolve("member", stored, mapOf("member" to live)).clubBadge)
        assertNull(UserProfile(null, null, onboardingComplete = true).clubBadge)
        assertNull(MemberProfile("member", null, null).clubBadge)
    }
}
