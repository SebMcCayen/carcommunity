package com.kungsbackacarcommunity.app.friends

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure HttpsError-code → result mapping (the load-bearing branching logic). */
class FriendsErrorMapperTest {

    private fun error(
        code: FriendErrorCode,
        reason: String? = null,
        candidates: List<FriendUser> = emptyList(),
    ) = FriendCallableError(code, reason, candidates)

    @Test
    fun `send unauthenticated maps to signed out`() {
        val result = FriendsErrorMapper.mapSend(error(FriendErrorCode.Unauthenticated))
        assertEquals(SendRequestResult.Failed(FriendActionError.SignedOut), result)
    }

    @Test
    fun `send permission denied maps to not member`() {
        val result = FriendsErrorMapper.mapSend(error(FriendErrorCode.PermissionDenied))
        assertEquals(SendRequestResult.Failed(FriendActionError.NotMember), result)
    }

    @Test
    fun `send invalid argument maps to invalid`() {
        val result = FriendsErrorMapper.mapSend(error(FriendErrorCode.InvalidArgument))
        assertEquals(SendRequestResult.Failed(FriendActionError.Invalid), result)
    }

    @Test
    fun `send not found maps to not found`() {
        val result = FriendsErrorMapper.mapSend(error(FriendErrorCode.NotFound))
        assertEquals(SendRequestResult.Failed(FriendActionError.NotFound), result)
    }

    @Test
    fun `send already exists without a reason falls back to already friends`() {
        val result = FriendsErrorMapper.mapSend(error(FriendErrorCode.AlreadyExists))
        assertEquals(SendRequestResult.Failed(FriendActionError.AlreadyFriends), result)
    }

    // --- reason discriminators -------------------------------------------------
    // 'already-exists' covers TWO distinct outcomes and 'failed-precondition'
    // covers two more, so the code alone cannot pick the right message. These
    // pin that the reason — not the code — decides.

    @Test
    fun `send separates already-friends from request-already-sent by reason`() {
        assertEquals(
            SendRequestResult.Failed(FriendActionError.AlreadyFriends),
            FriendsErrorMapper.mapSend(
                error(FriendErrorCode.AlreadyExists, FriendsErrorMapper.REASON_ALREADY_FRIENDS),
            ),
        )
        assertEquals(
            SendRequestResult.Failed(FriendActionError.RequestAlreadySent),
            FriendsErrorMapper.mapSend(
                error(FriendErrorCode.AlreadyExists, FriendsErrorMapper.REASON_REQUEST_ALREADY_SENT),
            ),
        )
    }

    @Test
    fun `send maps the self-request and nickname-not-found reasons`() {
        assertEquals(
            SendRequestResult.Failed(FriendActionError.SelfRequest),
            FriendsErrorMapper.mapSend(
                error(FriendErrorCode.InvalidArgument, FriendsErrorMapper.REASON_SELF_REQUEST),
            ),
        )
        assertEquals(
            SendRequestResult.Failed(FriendActionError.NotFound),
            FriendsErrorMapper.mapSend(
                error(FriendErrorCode.NotFound, FriendsErrorMapper.REASON_NICKNAME_NOT_FOUND),
            ),
        )
    }

    @Test
    fun `a transport failure reads as network, not as a generic app fault`() {
        assertEquals(
            SendRequestResult.Failed(FriendActionError.Network),
            FriendsErrorMapper.mapSend(error(FriendErrorCode.Unavailable)),
        )
        assertEquals(FriendActionError.Network, FriendsErrorMapper.mapGeneric(error(FriendErrorCode.Unavailable)))
        assertEquals(FriendActionError.Network, FriendsErrorMapper.mapRespond(error(FriendErrorCode.Unavailable)))
    }

    @Test
    fun `an unclassified failure keeps its raw code for the error report`() {
        // Generic is the only reported category; the raw code is what makes the
        // filed issue diagnosable instead of another "Something went wrong".
        val result =
            FriendsErrorMapper.mapSend(
                FriendCallableError(FriendErrorCode.Other, null, emptyList(), rawCode = "INTERNAL"),
            )
        assertEquals(SendRequestResult.Failed(FriendActionError.Generic, "INTERNAL"), result)
    }

    @Test
    fun `send failed precondition with ambiguous reason yields a picker`() {
        val candidates =
            listOf(
                FriendUser("a", "Alex", "avatars/a"),
                FriendUser("b", "Alex", null),
            )
        val result =
            FriendsErrorMapper.mapSend(
                error(
                    FriendErrorCode.FailedPrecondition,
                    reason = FriendsErrorMapper.REASON_AMBIGUOUS,
                    candidates = candidates,
                ),
            )
        assertTrue(result is SendRequestResult.Ambiguous)
        assertEquals(candidates, (result as SendRequestResult.Ambiguous).candidates)
    }

    @Test
    fun `send not-addable reason maps to the neutral not-addable error`() {
        val result =
            FriendsErrorMapper.mapSend(
                error(FriendErrorCode.FailedPrecondition, reason = FriendsErrorMapper.REASON_NOT_ADDABLE),
            )
        assertEquals(SendRequestResult.Failed(FriendActionError.NotAddable), result)
    }

    @Test
    fun `send untagged failed precondition falls back to not-addable`() {
        val result = FriendsErrorMapper.mapSend(error(FriendErrorCode.FailedPrecondition))
        assertEquals(SendRequestResult.Failed(FriendActionError.NotAddable), result)
    }

    @Test
    fun `send unknown code maps to generic`() {
        val result = FriendsErrorMapper.mapSend(error(FriendErrorCode.Other))
        assertEquals(SendRequestResult.Failed(FriendActionError.Generic), result)
    }

    @Test
    fun `respond not found maps to request gone`() {
        assertEquals(
            FriendActionError.RequestGone,
            FriendsErrorMapper.mapRespond(error(FriendErrorCode.NotFound)),
        )
    }

    @Test
    fun `respond failed precondition (already handled) maps to request gone`() {
        assertEquals(
            FriendActionError.RequestGone,
            FriendsErrorMapper.mapRespond(error(FriendErrorCode.FailedPrecondition)),
        )
    }

    @Test
    fun `respond unknown maps to generic`() {
        assertEquals(
            FriendActionError.Generic,
            FriendsErrorMapper.mapRespond(error(FriendErrorCode.Other)),
        )
    }

    @Test
    fun `generic mapping honours member and auth gates`() {
        assertEquals(
            FriendActionError.NotMember,
            FriendsErrorMapper.mapGeneric(error(FriendErrorCode.PermissionDenied)),
        )
        assertEquals(
            FriendActionError.SignedOut,
            FriendsErrorMapper.mapGeneric(error(FriendErrorCode.Unauthenticated)),
        )
        assertEquals(
            FriendActionError.Generic,
            FriendsErrorMapper.mapGeneric(error(FriendErrorCode.NotFound)),
        )
    }

    // --- friend-list load failures (regression, 2026-07-19) --------------------
    // Production friend-list failed for EVERY caller because the friendRequests
    // composite indexes had never been deployed. It surfaced as an opaque
    // INTERNAL, which mapGeneric collapsed to Generic and both the Friends page
    // and the convoy invite picker rendered as one flat "couldn't load your
    // friends" — indistinguishable from being signed out, from a dropped
    // connection, or from having no friends at all.

    @Test
    fun `list backend-unavailable is distinct from an unclassified failure`() {
        val backendDown =
            FriendsErrorMapper.mapList(
                error(FriendErrorCode.Unavailable, reason = FriendsErrorMapper.REASON_BACKEND_UNAVAILABLE),
            )
        val unknown = FriendsErrorMapper.mapList(error(FriendErrorCode.Other))

        assertEquals(FriendActionError.TemporarilyUnavailable, backendDown)
        assertEquals(FriendActionError.Generic, unknown)
        // THE REGRESSION: pre-fix these were the same value, so the UI could not
        // tell a backend outage from an unknown app fault.
        assertNotEquals(backendDown, unknown)
    }

    @Test
    fun `list backend-unavailable is distinct from a dropped connection`() {
        // Both arrive as `unavailable`; only the reason separates them, and they
        // want OPPOSITE advice ("check your internet" vs "nothing is wrong with
        // your account, it's us").
        val backendDown =
            FriendsErrorMapper.mapList(
                error(FriendErrorCode.Unavailable, reason = FriendsErrorMapper.REASON_BACKEND_UNAVAILABLE),
            )
        val offline = FriendsErrorMapper.mapList(error(FriendErrorCode.Unavailable))

        assertEquals(FriendActionError.TemporarilyUnavailable, backendDown)
        assertEquals(FriendActionError.Network, offline)
        assertNotEquals(backendDown, offline)
    }

    @Test
    fun `list keeps the specific auth and member categories`() {
        assertEquals(
            FriendActionError.SignedOut,
            FriendsErrorMapper.mapList(error(FriendErrorCode.Unauthenticated)),
        )
        assertEquals(
            FriendActionError.NotMember,
            FriendsErrorMapper.mapList(error(FriendErrorCode.PermissionDenied)),
        )
    }

    @Test
    fun `every error category renders a distinct user-facing message`() {
        // A category that maps to the same string as another is a category that
        // does not exist as far as the user is concerned. Guards the whole point
        // of this fix: specific failures must READ specifically.
        val all = FriendActionError.values().toList()
        assertEquals(all.size, all.map { it.messageRes() }.distinct().size)
    }
}
