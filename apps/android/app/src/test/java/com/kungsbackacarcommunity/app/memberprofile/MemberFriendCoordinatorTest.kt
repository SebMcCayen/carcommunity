package com.kungsbackacarcommunity.app.memberprofile

import com.kungsbackacarcommunity.app.friends.CancelResult
import com.kungsbackacarcommunity.app.friends.FriendActionError
import com.kungsbackacarcommunity.app.friends.FriendRelationship
import com.kungsbackacarcommunity.app.friends.FriendRequestDirection
import com.kungsbackacarcommunity.app.friends.FriendRequestSummary
import com.kungsbackacarcommunity.app.friends.FriendSummary
import com.kungsbackacarcommunity.app.friends.FriendUser
import com.kungsbackacarcommunity.app.friends.FriendsData
import com.kungsbackacarcommunity.app.friends.FriendsRepository
import com.kungsbackacarcommunity.app.friends.FriendsResult
import com.kungsbackacarcommunity.app.friends.RemoveResult
import com.kungsbackacarcommunity.app.friends.RespondResult
import com.kungsbackacarcommunity.app.friends.SendRequestResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The friend action on ANOTHER member's profile: which control each
 * relationship renders, and how send / cancel / accept / decline move it.
 */
class MemberFriendCoordinatorTest {

    private companion object {
        const val TARGET = "target-uid"
    }

    private open class FakeRepo : FriendsRepository {
        var listResult: FriendsResult = FriendsResult.Loaded(FriendsData(emptyList(), emptyList(), emptyList()))
        var sendResult: SendRequestResult = SendRequestResult.Requested
        var cancelResult: CancelResult = CancelResult.Cancelled
        var respondResult: RespondResult = RespondResult.Accepted

        var listCalls = 0
        var sendCalls = 0
        var cancelCalls = 0
        var lastSendUid: String? = null
        var lastCancelUid: String? = null
        var lastRespondId: String? = null
        var lastRespondAccept: Boolean? = null

        override suspend fun list(): FriendsResult {
            listCalls++
            return listResult
        }

        override suspend fun sendRequestByNickname(nickname: String): SendRequestResult =
            error("the profile never sends by nickname")

        override suspend fun sendRequestToUid(toUid: String): SendRequestResult {
            sendCalls++
            lastSendUid = toUid
            return sendResult
        }

        override suspend fun respond(requestId: String, accept: Boolean): RespondResult {
            lastRespondId = requestId
            lastRespondAccept = accept
            return respondResult
        }

        override suspend fun cancelRequest(toUid: String): CancelResult {
            cancelCalls++
            lastCancelUid = toUid
            return cancelResult
        }

        override suspend fun remove(friendUid: String): RemoveResult = error("unused")
    }

    private fun loaded(
        friends: List<FriendSummary> = emptyList(),
        incoming: List<FriendRequestSummary> = emptyList(),
        outgoing: List<FriendRequestSummary> = emptyList(),
    ) = FriendsResult.Loaded(FriendsData(friends, incoming, outgoing))

    private fun incomingFrom(uid: String, requestId: String) =
        FriendRequestSummary(
            requestId = requestId,
            fromUid = uid,
            toUid = "me",
            direction = FriendRequestDirection.Incoming,
            otherUser = FriendUser(uid, "Name", null),
            createdAt = null,
        )

    private fun outgoingTo(uid: String) =
        FriendRequestSummary(
            requestId = "out-1",
            fromUid = "me",
            toUid = uid,
            direction = FriendRequestDirection.Outgoing,
            otherUser = FriendUser(uid, "Name", null),
            createdAt = null,
        )

    // --- control per relationship ---------------------------------------------

    @Test
    fun `before the graph loads no control is offered`() {
        // Not "Add friend": offering it before we know invites a request to
        // someone the viewer may already be friends with.
        val state = MemberFriendState()
        assertEquals(FriendRelationship.Unknown, state.relationship)
        assertEquals(MemberFriendControl.None, state.control)
    }

    @Test
    fun `each relationship maps to exactly one control`() {
        assertEquals(
            MemberFriendControl.Add,
            MemberFriendState(relationship = FriendRelationship.None).control,
        )
        assertEquals(
            MemberFriendControl.CancelRequest,
            MemberFriendState(relationship = FriendRelationship.OutgoingPending).control,
        )
        assertEquals(
            MemberFriendControl.Respond,
            MemberFriendState(relationship = FriendRelationship.IncomingPending("r")).control,
        )
        assertEquals(
            MemberFriendControl.Friends,
            MemberFriendState(relationship = FriendRelationship.Friends).control,
        )
    }

    @Test
    fun `controls disable themselves while a callable is in flight`() {
        assertTrue(MemberFriendState(relationship = FriendRelationship.None).enabled)
        assertEquals(
            false,
            MemberFriendState(
                relationship = FriendRelationship.None,
                inFlight = FriendActionInFlight.Send,
            ).enabled,
        )
    }

    // --- load ------------------------------------------------------------------

    @Test
    fun `load resolves the relationship from the viewers own snapshot`() = runTest {
        val repo = FakeRepo().apply { listResult = loaded(outgoing = listOf(outgoingTo(TARGET))) }
        val coordinator = MemberFriendCoordinator(repo, TARGET)

        coordinator.load()

        assertEquals(FriendRelationship.OutgoingPending, coordinator.state.value.relationship)
        assertEquals(MemberFriendControl.CancelRequest, coordinator.state.value.control)
    }

    @Test
    fun `a failed load stays Unknown and raises no error banner`() = runTest {
        // The friend graph is secondary content on someone else's profile; a red
        // notice about it would read as though the PROFILE had failed to load.
        val repo = FakeRepo().apply { listResult = FriendsResult.Failed(FriendActionError.Network) }
        val coordinator = MemberFriendCoordinator(repo, TARGET)

        coordinator.load()

        assertEquals(FriendRelationship.Unknown, coordinator.state.value.relationship)
        assertEquals(MemberFriendControl.None, coordinator.state.value.control)
        assertNull(coordinator.state.value.error)
    }

    // --- send ------------------------------------------------------------------

    @Test
    fun `sending flips to the pending control and addresses the profile owner`() = runTest {
        val repo = FakeRepo()
        val coordinator = MemberFriendCoordinator(repo, TARGET)
        coordinator.load()
        // The backend now holds the request, so the re-sync that follows the
        // mutation sees it — as it would in production.
        repo.listResult = loaded(outgoing = listOf(outgoingTo(TARGET)))

        coordinator.sendRequest()

        assertEquals(TARGET, repo.lastSendUid)
        assertEquals(FriendRelationship.OutgoingPending, coordinator.state.value.relationship)
        assertEquals(MemberFriendControl.CancelRequest, coordinator.state.value.control)
        assertNull(coordinator.state.value.error)
        assertNull(coordinator.state.value.inFlight)
    }

    @Test
    fun `a send that auto-accepts an inbound request lands on Friends`() = runTest {
        val repo = FakeRepo().apply { sendResult = SendRequestResult.NowFriends }
        val coordinator = MemberFriendCoordinator(repo, TARGET)
        coordinator.load()
        repo.listResult = loaded(friends = listOf(FriendSummary(TARGET, "Name", null, null)))

        coordinator.sendRequest()

        assertEquals(FriendRelationship.Friends, coordinator.state.value.relationship)
    }

    @Test
    fun `a failed send reverts to the control the user tapped and surfaces the error`() = runTest {
        val repo =
            FakeRepo().apply {
                sendResult = SendRequestResult.Failed(FriendActionError.NotAddable)
            }
        val coordinator = MemberFriendCoordinator(repo, TARGET)
        coordinator.load()

        coordinator.sendRequest()

        // Unchanged relationship → the Add button is still there to retry with.
        assertEquals(FriendRelationship.None, coordinator.state.value.relationship)
        assertEquals(MemberFriendControl.Add, coordinator.state.value.control)
        assertEquals(FriendActionError.NotAddable, coordinator.state.value.error)
        // No re-sync after a failure: the backend state never changed.
        assertEquals(1, repo.listCalls)
    }

    @Test
    fun `a double tap cannot send two requests`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo =
            object : FakeRepo() {
                override suspend fun sendRequestToUid(toUid: String): SendRequestResult {
                    sendCalls++
                    gate.await()
                    return SendRequestResult.Requested
                }
            }
        val coordinator = MemberFriendCoordinator(repo, TARGET)
        coordinator.load()
        repo.listResult = loaded(outgoing = listOf(outgoingTo(TARGET)))

        val first = launch { coordinator.sendRequest() }
        runCurrent()
        assertEquals(FriendActionInFlight.Send, coordinator.state.value.inFlight)

        // The second tap must be dropped, not queued behind the first.
        coordinator.sendRequest()
        assertEquals(1, repo.sendCalls)

        gate.complete(Unit)
        first.join()
        assertNull(coordinator.state.value.inFlight)
        assertEquals(FriendRelationship.OutgoingPending, coordinator.state.value.relationship)
    }

    // --- cancel ----------------------------------------------------------------

    @Test
    fun `cancelling a pending request returns to the Add control`() = runTest {
        val repo = FakeRepo().apply { listResult = loaded(outgoing = listOf(outgoingTo(TARGET))) }
        val coordinator = MemberFriendCoordinator(repo, TARGET)
        coordinator.load()
        // After the cancel the coordinator re-syncs; the graph no longer has the
        // request, matching what the backend just did.
        repo.listResult = loaded()

        coordinator.cancelRequest()

        // Addressed by RECIPIENT — the client never needs a request id to cancel.
        assertEquals(TARGET, repo.lastCancelUid)
        assertEquals(FriendRelationship.None, coordinator.state.value.relationship)
        assertEquals(MemberFriendControl.Add, coordinator.state.value.control)
    }

    @Test
    fun `a failed cancel keeps the request pending and surfaces the error`() = runTest {
        val repo =
            FakeRepo().apply {
                listResult = loaded(outgoing = listOf(outgoingTo(TARGET)))
                cancelResult = CancelResult.Failed(FriendActionError.Network)
            }
        val coordinator = MemberFriendCoordinator(repo, TARGET)
        coordinator.load()

        coordinator.cancelRequest()

        assertEquals(FriendRelationship.OutgoingPending, coordinator.state.value.relationship)
        assertEquals(MemberFriendControl.CancelRequest, coordinator.state.value.control)
        assertEquals(FriendActionError.Network, coordinator.state.value.error)
    }

    @Test
    fun `the re-sync corrects an optimistic guess the backend disagrees with`() = runTest {
        // The case the re-sync exists for: the other member ACCEPTED a moment
        // before the cancel landed, so the callable no-ops (nothing pending to
        // withdraw) and the optimistic "no longer connected" is simply wrong.
        // The follow-up read settles it on the truth — they are friends.
        val repo = FakeRepo().apply { listResult = loaded(outgoing = listOf(outgoingTo(TARGET))) }
        val coordinator = MemberFriendCoordinator(repo, TARGET)
        coordinator.load()
        repo.listResult = loaded(friends = listOf(FriendSummary(TARGET, "Name", null, null)))

        coordinator.cancelRequest()

        assertEquals(FriendRelationship.Friends, coordinator.state.value.relationship)
        assertEquals(MemberFriendControl.Friends, coordinator.state.value.control)
    }

    @Test
    fun `a re-sync that fails keeps the optimistic post-state`() = runTest {
        // The mutation succeeded; dropping back to Unknown because the FOLLOW-UP
        // read failed would hide the control and make the successful cancel look
        // like it did nothing.
        val repo = FakeRepo().apply { listResult = loaded(outgoing = listOf(outgoingTo(TARGET))) }
        val coordinator = MemberFriendCoordinator(repo, TARGET)
        coordinator.load()
        repo.listResult = FriendsResult.Failed(FriendActionError.Network)

        coordinator.cancelRequest()

        assertEquals(FriendRelationship.None, coordinator.state.value.relationship)
        assertNull(coordinator.state.value.error)
    }

    // --- respond ---------------------------------------------------------------

    @Test
    fun `accepting an incoming request uses its id and lands on Friends`() = runTest {
        val repo =
            FakeRepo().apply { listResult = loaded(incoming = listOf(incomingFrom(TARGET, "r-7"))) }
        val coordinator = MemberFriendCoordinator(repo, TARGET)
        coordinator.load()
        repo.listResult = loaded(friends = listOf(FriendSummary(TARGET, "Name", null, null)))

        coordinator.acceptRequest()

        assertEquals("r-7", repo.lastRespondId)
        assertEquals(true, repo.lastRespondAccept)
        assertEquals(FriendRelationship.Friends, coordinator.state.value.relationship)
        assertEquals(MemberFriendControl.Friends, coordinator.state.value.control)
    }

    @Test
    fun `declining an incoming request returns to the Add control`() = runTest {
        val repo =
            FakeRepo().apply {
                listResult = loaded(incoming = listOf(incomingFrom(TARGET, "r-8")))
                respondResult = RespondResult.Declined
            }
        val coordinator = MemberFriendCoordinator(repo, TARGET)
        coordinator.load()
        repo.listResult = loaded()

        coordinator.declineRequest()

        assertEquals("r-8", repo.lastRespondId)
        assertEquals(false, repo.lastRespondAccept)
        assertEquals(FriendRelationship.None, coordinator.state.value.relationship)
    }

    @Test
    fun `responding without an incoming request never calls the backend`() = runTest {
        // A stale tap on a control that has since recomposed away: there is no
        // request id to answer with, so nothing must be sent.
        val repo = FakeRepo()
        val coordinator = MemberFriendCoordinator(repo, TARGET)
        coordinator.load()

        coordinator.acceptRequest()

        assertNull(repo.lastRespondId)
        assertEquals(FriendRelationship.None, coordinator.state.value.relationship)
    }

    @Test
    fun `a failed accept keeps the request answerable`() = runTest {
        val repo =
            FakeRepo().apply {
                listResult = loaded(incoming = listOf(incomingFrom(TARGET, "r-9")))
                respondResult = RespondResult.Failed(FriendActionError.RequestGone)
            }
        val coordinator = MemberFriendCoordinator(repo, TARGET)
        coordinator.load()

        coordinator.acceptRequest()

        assertEquals(
            FriendRelationship.IncomingPending("r-9"),
            coordinator.state.value.relationship,
        )
        assertEquals(FriendActionError.RequestGone, coordinator.state.value.error)
    }

    @Test
    fun `starting a new action clears the previous error`() = runTest {
        val repo =
            FakeRepo().apply { sendResult = SendRequestResult.Failed(FriendActionError.Network) }
        val coordinator = MemberFriendCoordinator(repo, TARGET)
        coordinator.load()
        coordinator.sendRequest()
        assertEquals(FriendActionError.Network, coordinator.state.value.error)

        repo.sendResult = SendRequestResult.Requested
        repo.listResult = loaded(outgoing = listOf(outgoingTo(TARGET)))
        coordinator.sendRequest()

        assertNull(coordinator.state.value.error)
        assertEquals(FriendRelationship.OutgoingPending, coordinator.state.value.relationship)
    }
}
