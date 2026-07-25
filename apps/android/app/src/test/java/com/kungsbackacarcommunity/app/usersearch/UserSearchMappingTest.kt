package com.kungsbackacarcommunity.app.usersearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the pure callable-failure mapping and response parsing. */
class UserSearchMappingTest {

    private fun error(code: UserSearchErrorCode, reason: String? = null) =
        UserSearchCallableError(code, reason)

    @Test
    fun `a too-short query is a state, never an error`() {
        // The backend tags it on an invalid-argument, which is the SAME code a
        // malformed payload carries. Branching on the reason first is what keeps
        // "you have typed one letter" from painting the field red.
        val outcome =
            UserSearchErrorMapper.map(
                error(UserSearchErrorCode.InvalidArgument, REASON_QUERY_TOO_SHORT),
            )
        assertEquals(UserSearchOutcome.TooShort, outcome)
    }

    @Test
    fun `an untagged invalid-argument is an app fault, not a typing state`() {
        val outcome = UserSearchErrorMapper.map(error(UserSearchErrorCode.InvalidArgument))
        assertEquals(UserSearchOutcome.Failed(UserSearchError.Generic), outcome)
    }

    @Test
    fun `each code maps to its own actionable category`() {
        assertEquals(
            UserSearchOutcome.Failed(UserSearchError.SignedOut),
            UserSearchErrorMapper.map(error(UserSearchErrorCode.Unauthenticated)),
        )
        assertEquals(
            UserSearchOutcome.Failed(UserSearchError.NotMember),
            UserSearchErrorMapper.map(error(UserSearchErrorCode.PermissionDenied)),
        )
        // Self-correcting and worth its own advice ("wait a moment"), so it must
        // not collapse into the useless "something went wrong".
        assertEquals(
            UserSearchOutcome.Failed(UserSearchError.RateLimited),
            UserSearchErrorMapper.map(error(UserSearchErrorCode.ResourceExhausted)),
        )
        assertEquals(
            UserSearchOutcome.Failed(UserSearchError.Network),
            UserSearchErrorMapper.map(error(UserSearchErrorCode.Unavailable)),
        )
        assertEquals(
            UserSearchOutcome.Failed(UserSearchError.Generic),
            UserSearchErrorMapper.map(error(UserSearchErrorCode.Other)),
        )
    }

    @Test
    fun `parses the members payload`() {
        val parsed =
            UserSearchResponseParser.parseMembers(
                mapOf(
                    "members" to
                        listOf(
                            mapOf(
                                "uid" to "uid-1",
                                "displayName" to "Gt_86",
                                "avatarPath" to "avatars/a.jpg",
                            ),
                        ),
                ),
            )
        assertEquals(listOf(MemberSearchResult("uid-1", "Gt_86", "avatars/a.jpg")), parsed)
    }

    @Test
    fun `a row without a usable uid is dropped, not crashed on`() {
        // A partial backend response should cost a suggestion, not the screen.
        val parsed =
            UserSearchResponseParser.parseMembers(
                mapOf(
                    "members" to
                        listOf(
                            mapOf("displayName" to "No uid"),
                            mapOf("uid" to "", "displayName" to "Blank uid"),
                            "not a map",
                            mapOf("uid" to "uid-ok", "displayName" to "Fine"),
                        ),
                ),
            )
        assertEquals(1, parsed.size)
        assertEquals("uid-ok", parsed[0].uid)
    }

    @Test
    fun `a missing or malformed payload parses to an empty list`() {
        assertTrue(UserSearchResponseParser.parseMembers(null).isEmpty())
        assertTrue(UserSearchResponseParser.parseMembers(emptyMap()).isEmpty())
        assertTrue(UserSearchResponseParser.parseMembers(mapOf("members" to "nope")).isEmpty())
    }

    @Test
    fun `a member with no name or avatar parses with nulls`() {
        val parsed = UserSearchResponseParser.parseMembers(mapOf("members" to listOf(mapOf("uid" to "u"))))
        assertEquals(MemberSearchResult("u", null, null), parsed[0])
    }

    @Test
    fun `reads the reason discriminator out of the error details`() {
        assertEquals(
            REASON_QUERY_TOO_SHORT,
            UserSearchResponseParser.reasonOf(mapOf("reason" to REASON_QUERY_TOO_SHORT)),
        )
        assertNull(UserSearchResponseParser.reasonOf(null))
        assertNull(UserSearchResponseParser.reasonOf("not a map"))
    }
}
