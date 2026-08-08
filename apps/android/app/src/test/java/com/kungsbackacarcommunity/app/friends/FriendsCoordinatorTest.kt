package com.kungsbackacarcommunity.app.friends

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendsCoordinatorTest {

    private class FakeRepo : FriendsRepository {
        var listResult: FriendsResult =
            FriendsResult.Loaded(FriendsData(emptyList(), emptyList(), emptyList()))
        var sendResult: SendRequestResult = SendRequestResult.Requested
        var sendToUidResult: SendRequestResult = SendRequestResult.Requested
        var respondResult: RespondResult = RespondResult.Accepted
        var cancelResult: CancelResult = CancelResult.Cancelled
        var removeResult: RemoveResult = RemoveResult.Removed

        var lastNickname: String? = null
        var lastToUid: String? = null
        var lastCancelToUid: String? = null
        var cancelCalls = 0
        var listCalls = 0

        override suspend fun list(): FriendsResult {
            listCalls++
            return listResult
        }

        override suspend fun sendRequestByNickname(nickname: String): SendRequestResult {
            lastNickname = nickname
            return sendResult
        }

        override suspend fun sendRequestToUid(toUid: String): SendRequestResult {
            lastToUid = toUid
            return sendToUidResult
        }

        override suspend fun respond(requestId: String, accept: Boolean): RespondResult = respondResult

        override suspend fun cancelRequest(toUid: String): CancelResult {
            cancelCalls++
            lastCancelToUid = toUid
            return cancelResult
        }

        override suspend fun remove(friendUid: String): RemoveResult = removeResult
    }

    /** Captures what the shared error pipeline would receive. */
    private class FakeReporter : com.kungsbackacarcommunity.app.diagnostics.ClientErrorReporter {
        data class Report(val feature: String, val message: String, val code: String?)

        val reports = mutableListOf<Report>()

        override fun report(feature: String, message: String, code: String?) {
            reports += Report(feature, message, code)
        }
    }

    /** A points source for the Crown Points overlay; configurable per test. */
    private class FakePointsRepo : FriendPointsRepository {
        var result: Map<String, Long> = emptyMap()
        var throwOnRead = false
        var lastUids: List<String>? = null

        override suspend fun balancesFor(uids: List<String>): Map<String, Long> {
            lastUids = uids
            if (throwOnRead) error("points read blew up")
            return result
        }
    }

    private fun loaded(friends: List<FriendSummary>) =
        FriendsResult.Loaded(FriendsData(friends, emptyList(), emptyList()))

    private fun friend(uid: String) = FriendSummary(uid, "Name-$uid", null, null)

    // --- error reporting -------------------------------------------------------
    // Seb's report was an undiagnosable "Something went wrong" that filed no
    // issue. Generic is exactly the unclassified case, so it MUST reach the
    // pipeline carrying the raw code — and the ordinary outcomes must NOT, or
    // the real faults drown in noise.

    @Test
    fun `an unclassified send failure is reported with its raw code`() = runTest {
        val reporter = FakeReporter()
        val repo =
            FakeRepo().apply {
                sendResult = SendRequestResult.Failed(FriendActionError.Generic, "INTERNAL")
            }
        FriendsCoordinator(repo, reporter).sendRequestByNickname("Gt86_swe")

        assertEquals(1, reporter.reports.size)
        val report = reporter.reports.single()
        assertEquals("friends.sendRequest", report.feature)
        assertEquals("INTERNAL", report.code)
        // PRIVACY: the pipeline files a PUBLIC GitHub issue. The nickname the
        // user typed is their content and must never ride along.
        assertTrue(!report.message.contains("Gt86_swe"))
        assertTrue(!(report.code ?: "").contains("Gt86_swe"))
    }

    @Test
    fun `expected outcomes are never reported`() = runTest {
        for (error in
            listOf(
                FriendActionError.NotFound,
                FriendActionError.AlreadyFriends,
                FriendActionError.RequestAlreadySent,
                FriendActionError.SelfRequest,
                FriendActionError.NotAddable,
                FriendActionError.Invalid,
                FriendActionError.Network,
                FriendActionError.SignedOut,
                FriendActionError.NotMember,
            )) {
            val reporter = FakeReporter()
            val repo = FakeRepo().apply { sendResult = SendRequestResult.Failed(error) }
            FriendsCoordinator(repo, reporter).sendRequestByNickname("Someone")
            assertTrue("$error must not be reported", reporter.reports.isEmpty())
        }
    }

    @Test
    fun `an unclassified list failure is reported`() = runTest {
        val reporter = FakeReporter()
        val repo =
            FakeRepo().apply {
                listResult = FriendsResult.Failed(FriendActionError.Generic, "UNAVAILABLE_TOKEN")
            }
        FriendsCoordinator(repo, reporter).load()

        assertEquals(1, reporter.reports.size)
        assertEquals("friends.list", reporter.reports.single().feature)
        assertEquals("UNAVAILABLE_TOKEN", reporter.reports.single().code)
    }

    @Test
    fun `a null reporter (config-less build) never breaks the flow`() = runTest {
        val repo =
            FakeRepo().apply {
                sendResult = SendRequestResult.Failed(FriendActionError.Generic, "INTERNAL")
            }
        val coordinator = FriendsCoordinator(repo, errorReporter = null)
        coordinator.sendRequestByNickname("Someone")
        assertEquals(AddFriendState.Error(FriendActionError.Generic), coordinator.add.value)
    }

    @Test
    fun `load publishes the snapshot`() = runTest {
        val repo =
            FakeRepo().apply { listResult = loaded(listOf(FriendSummary("f1", "Robin", null, null))) }
        val coordinator = FriendsCoordinator(repo)
        coordinator.load()
        val status = coordinator.status.value
        assertTrue(status is FriendsStatus.Loaded)
        assertEquals(1, (status as FriendsStatus.Loaded).friends.size)
    }

    // --- Crown Points overlay --------------------------------------------------
    // Each friend's public balance is overlaid onto the loaded list. It is a
    // decorative "how active they have been" number, so it must never fail or
    // delay the list: a failed read leaves status.points empty and the list
    // intact, and the UI renders any uid missing from the map as 0.

    @Test
    fun `load overlays each friends crown points`() = runTest {
        val repo = FakeRepo().apply { listResult = loaded(listOf(friend("f1"), friend("f2"))) }
        val points = FakePointsRepo().apply { result = mapOf("f1" to 1_240L) }
        val coordinator = FriendsCoordinator(repo, pointsRepository = points)

        coordinator.load()

        val status = coordinator.status.value as FriendsStatus.Loaded
        // f1 carries its balance; f2 has no wallet doc, so it is absent (the
        // screen renders that as 0) rather than a fabricated zero.
        assertEquals(mapOf("f1" to 1_240L), status.points)
        assertEquals(listOf("f1", "f2"), points.lastUids)
    }

    @Test
    fun `a points read failure leaves the list intact without points`() = runTest {
        val repo = FakeRepo().apply { listResult = loaded(listOf(friend("f1"))) }
        val points = FakePointsRepo().apply { throwOnRead = true }
        val coordinator = FriendsCoordinator(repo, pointsRepository = points)

        coordinator.load()

        val status = coordinator.status.value as FriendsStatus.Loaded
        assertEquals(1, status.friends.size)
        assertTrue("a failed points read must not fail the list", status.points.isEmpty())
    }

    @Test
    fun `no points repository yields an empty points map`() = runTest {
        val repo = FakeRepo().apply { listResult = loaded(listOf(friend("f1"))) }
        val coordinator = FriendsCoordinator(repo)

        coordinator.load()

        val status = coordinator.status.value as FriendsStatus.Loaded
        assertTrue(status.points.isEmpty())
    }

    @Test
    fun `an empty friends list never reads points`() = runTest {
        val repo = FakeRepo().apply { listResult = loaded(emptyList()) }
        val points = FakePointsRepo().apply { result = mapOf("x" to 5L) }
        val coordinator = FriendsCoordinator(repo, pointsRepository = points)

        coordinator.load()

        assertNull("no friends → no points read", points.lastUids)
        assertTrue((coordinator.status.value as FriendsStatus.Loaded).points.isEmpty())
    }

    @Test
    fun `load failure surfaces Error carrying the mapped code`() = runTest {
        val repo = FakeRepo().apply { listResult = FriendsResult.Failed(FriendActionError.NotMember) }
        val coordinator = FriendsCoordinator(repo)
        coordinator.load()
        assertEquals(FriendsStatus.Error(FriendActionError.NotMember), coordinator.status.value)
    }

    @Test
    fun `blank nickname is rejected without calling the backend`() = runTest {
        val repo = FakeRepo()
        val coordinator = FriendsCoordinator(repo)
        coordinator.sendRequestByNickname("   ")
        assertEquals(AddFriendState.Error(FriendActionError.Invalid), coordinator.add.value)
        assertNull(repo.lastNickname)
    }

    @Test
    fun `send trims the nickname and reports Sent`() = runTest {
        val repo = FakeRepo()
        val coordinator = FriendsCoordinator(repo)
        coordinator.sendRequestByNickname("  Alex ")
        assertEquals("Alex", repo.lastNickname)
        assertEquals(AddFriendState.Sent(nowFriends = false), coordinator.add.value)
        // A landed request reloads the snapshot.
        assertTrue(repo.listCalls >= 1)
    }

    @Test
    fun `send that auto-accepts reports now friends`() = runTest {
        val repo = FakeRepo().apply { sendResult = SendRequestResult.NowFriends }
        val coordinator = FriendsCoordinator(repo)
        coordinator.sendRequestByNickname("Alex")
        assertEquals(AddFriendState.Sent(nowFriends = true), coordinator.add.value)
    }

    @Test
    fun `ambiguous nickname surfaces the candidate chooser`() = runTest {
        val candidates = listOf(FriendUser("a", "Alex", null), FriendUser("b", "Alex", null))
        val repo = FakeRepo().apply { sendResult = SendRequestResult.Ambiguous(candidates) }
        val coordinator = FriendsCoordinator(repo)
        coordinator.sendRequestByNickname("Alex")
        val state = coordinator.add.value
        assertTrue(state is AddFriendState.Chooser)
        assertEquals(candidates, (state as AddFriendState.Chooser).candidates)
    }

    @Test
    fun `choosing a candidate re-sends by uid`() = runTest {
        val repo = FakeRepo().apply { sendToUidResult = SendRequestResult.Requested }
        val coordinator = FriendsCoordinator(repo)
        coordinator.chooseCandidate("b")
        assertEquals("b", repo.lastToUid)
        assertEquals(AddFriendState.Sent(nowFriends = false), coordinator.add.value)
    }

    @Test
    fun `send failure surfaces the mapped error`() = runTest {
        val repo = FakeRepo().apply { sendResult = SendRequestResult.Failed(FriendActionError.NotAddable) }
        val coordinator = FriendsCoordinator(repo)
        coordinator.sendRequestByNickname("Alex")
        assertEquals(AddFriendState.Error(FriendActionError.NotAddable), coordinator.add.value)
    }

    @Test
    fun `accept reloads and clears any prior action error`() = runTest {
        val repo = FakeRepo()
        val coordinator = FriendsCoordinator(repo)
        val before = repo.listCalls
        coordinator.accept("r1")
        assertTrue(repo.listCalls > before)
        assertNull(coordinator.actionError.value)
    }

    @Test
    fun `respond failure surfaces action error and still resyncs`() = runTest {
        val repo = FakeRepo().apply { respondResult = RespondResult.Failed(FriendActionError.RequestGone) }
        val coordinator = FriendsCoordinator(repo)
        val before = repo.listCalls
        coordinator.decline("r1")
        assertEquals(FriendActionError.RequestGone, coordinator.actionError.value)
        assertTrue(repo.listCalls > before)
    }

    @Test
    fun `remove reloads on success`() = runTest {
        val repo = FakeRepo()
        val coordinator = FriendsCoordinator(repo)
        val before = repo.listCalls
        coordinator.remove("f1")
        assertTrue(repo.listCalls > before)
        assertNull(coordinator.actionError.value)
    }

    @Test
    fun `remove failure surfaces action error`() = runTest {
        val repo = FakeRepo().apply { removeResult = RemoveResult.Failed(FriendActionError.Generic) }
        val coordinator = FriendsCoordinator(repo)
        coordinator.remove("f1")
        assertEquals(FriendActionError.Generic, coordinator.actionError.value)
    }

    // --- cancel an outgoing (sent-by-me) pending request ----------------------
    // "Cancel request" withdraws a request I sent by mistake. It is addressed by
    // the RECIPIENT uid (cancelRequest(toUid)); success resyncs so the outgoing
    // row disappears.

    private fun outgoing(toUid: String) =
        FriendRequestSummary(
            requestId = "req-$toUid",
            fromUid = "me",
            toUid = toUid,
            direction = FriendRequestDirection.Outgoing,
            otherUser = FriendUser(toUid, "Pending Pal", null),
            createdAt = null,
        )

    @Test
    fun `cancel withdraws the outgoing request by recipient uid and reloads`() = runTest {
        val repo =
            FakeRepo().apply {
                listResult = FriendsResult.Loaded(FriendsData(emptyList(), emptyList(), listOf(outgoing("u2"))))
            }
        val coordinator = FriendsCoordinator(repo)
        coordinator.load()
        assertEquals(1, (coordinator.status.value as FriendsStatus.Loaded).outgoing.size)

        // After the cancel, the backend no longer returns that pending request.
        repo.listResult = FriendsResult.Loaded(FriendsData(emptyList(), emptyList(), emptyList()))
        val before = repo.listCalls
        coordinator.cancel("u2")

        assertEquals("u2", repo.lastCancelToUid)
        assertEquals(1, repo.cancelCalls)
        assertTrue(repo.listCalls > before)
        assertEquals(emptyList<FriendRequestSummary>(), (coordinator.status.value as FriendsStatus.Loaded).outgoing)
        assertNull(coordinator.actionError.value)
    }

    @Test
    fun `cancel failure surfaces action error and still resyncs`() = runTest {
        val repo =
            FakeRepo().apply { cancelResult = CancelResult.Failed(FriendActionError.RequestGone) }
        val coordinator = FriendsCoordinator(repo)
        val before = repo.listCalls
        coordinator.cancel("u2")
        assertEquals(FriendActionError.RequestGone, coordinator.actionError.value)
        // A request already handled server-side must not linger — resync anyway.
        assertTrue(repo.listCalls > before)
    }

    @Test
    fun `an unclassified cancel failure is reported with its raw code`() = runTest {
        val reporter = FakeReporter()
        val repo =
            FakeRepo().apply { cancelResult = CancelResult.Failed(FriendActionError.Generic, "INTERNAL") }
        FriendsCoordinator(repo, reporter).cancel("u2")

        assertEquals(1, reporter.reports.size)
        val report = reporter.reports.single()
        assertEquals("friends.cancelRequest", report.feature)
        assertEquals("INTERNAL", report.code)
    }

    @Test
    fun `a second cancel for the same uid is ignored while one is in flight`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo =
            object : FriendsRepository {
                var cancelCalls = 0

                override suspend fun list(): FriendsResult =
                    FriendsResult.Loaded(FriendsData(emptyList(), emptyList(), emptyList()))

                override suspend fun sendRequestByNickname(nickname: String) = SendRequestResult.Requested

                override suspend fun sendRequestToUid(toUid: String) = SendRequestResult.Requested

                override suspend fun respond(requestId: String, accept: Boolean) = RespondResult.Accepted

                override suspend fun cancelRequest(toUid: String): CancelResult {
                    cancelCalls++
                    gate.await()
                    return CancelResult.Cancelled
                }

                override suspend fun remove(friendUid: String) = RemoveResult.Removed
            }
        val coordinator = FriendsCoordinator(repo)

        val first = launch { coordinator.cancel("u2") }
        runCurrent()
        assertTrue(FriendsCoordinator.cancelBusyKey("u2") in coordinator.busyRows.value)
        coordinator.cancel("u2")
        assertEquals(1, repo.cancelCalls)

        gate.complete(Unit)
        first.join()
        assertTrue(coordinator.busyRows.value.isEmpty())
    }

    @Test
    fun `busyRows keys are namespaced so cancel and remove of the same id never collide`() = runTest {
        // A friend uid and the recipient uid of a pending request can be the SAME
        // string. Without namespacing, removing friend "x" would mark a pending
        // cancel row for "x" busy (and vice versa). The prefixes make that
        // impossible: only the acting row is busy.
        val gate = CompletableDeferred<Unit>()
        val repo =
            object : FriendsRepository {
                override suspend fun list(): FriendsResult =
                    FriendsResult.Loaded(FriendsData(emptyList(), emptyList(), emptyList()))

                override suspend fun sendRequestByNickname(nickname: String) = SendRequestResult.Requested

                override suspend fun sendRequestToUid(toUid: String) = SendRequestResult.Requested

                override suspend fun respond(requestId: String, accept: Boolean) = RespondResult.Accepted

                override suspend fun cancelRequest(toUid: String) = CancelResult.Cancelled

                override suspend fun remove(friendUid: String): RemoveResult {
                    gate.await()
                    return RemoveResult.Removed
                }
            }
        val coordinator = FriendsCoordinator(repo)

        val removing = launch { coordinator.remove("x") }
        runCurrent()

        // Only the remove row for "x" is busy...
        assertTrue(FriendsCoordinator.removeBusyKey("x") in coordinator.busyRows.value)
        // ...a cancel row for the SAME raw id "x" is NOT dragged into a busy state.
        assertTrue(FriendsCoordinator.cancelBusyKey("x") !in coordinator.busyRows.value)

        gate.complete(Unit)
        removing.join()
        assertTrue(coordinator.busyRows.value.isEmpty())
    }

    @Test
    fun `a second remove for the same uid is ignored while one is in flight`() = runTest {
        // Gate the first remove inside the repository so it stays in flight while
        // we fire a second one for the same uid.
        val gate = CompletableDeferred<Unit>()
        val repo =
            object : FriendsRepository {
                var removeCalls = 0

                override suspend fun list(): FriendsResult =
                    FriendsResult.Loaded(FriendsData(emptyList(), emptyList(), emptyList()))

                override suspend fun sendRequestByNickname(nickname: String) = SendRequestResult.Requested

                override suspend fun sendRequestToUid(toUid: String) = SendRequestResult.Requested

                override suspend fun respond(requestId: String, accept: Boolean) = RespondResult.Accepted

                override suspend fun cancelRequest(toUid: String) = CancelResult.Cancelled

                override suspend fun remove(friendUid: String): RemoveResult {
                    removeCalls++
                    gate.await()
                    return RemoveResult.Removed
                }
            }
        val coordinator = FriendsCoordinator(repo)

        val first = launch { coordinator.remove("f1") }
        // Let the launched coroutine run up to its gate suspension.
        runCurrent()
        // The first call is now parked on the gate and marked in flight.
        assertTrue(FriendsCoordinator.removeBusyKey("f1") in coordinator.busyRows.value)
        // A second call for the same uid must be dropped, not started.
        coordinator.remove("f1")
        assertEquals(1, repo.removeCalls)

        gate.complete(Unit)
        first.join()
        // The guard is released once the mutation finishes.
        assertTrue(coordinator.busyRows.value.isEmpty())
    }

    @Test
    fun `resetAdd returns to Idle`() = runTest {
        val repo = FakeRepo().apply { sendResult = SendRequestResult.Requested }
        val coordinator = FriendsCoordinator(repo)
        coordinator.sendRequestByNickname("Alex")
        coordinator.resetAdd()
        assertEquals(AddFriendState.Idle, coordinator.add.value)
    }

    // --- empty list is NOT a failure (regression, 2026-07-19) -----------------
    // Conflating "you have no friends yet" with "the load failed" is how a
    // total backend outage could look like an ordinary empty state (and vice
    // versa). These two must be different STATES, not just different strings.

    @Test
    fun `an empty friends list is a loaded state, not an error`() = runTest {
        val repo = FakeRepo().apply { listResult = loaded(emptyList()) }
        val reporter = FakeReporter()
        val coordinator = FriendsCoordinator(repo, reporter)

        coordinator.load()

        val status = coordinator.status.value
        assertTrue("empty list must be Loaded, got $status", status is FriendsStatus.Loaded)
        assertEquals(emptyList<FriendSummary>(), (status as FriendsStatus.Loaded).friends)
        // An empty list is an expected outcome and must never file an issue.
        assertTrue("empty list must not be reported", reporter.reports.isEmpty())
    }

    @Test
    fun `a failed load is an error state, distinguishable from an empty list`() = runTest {
        val repo =
            FakeRepo().apply {
                listResult =
                    FriendsResult.Failed(FriendActionError.TemporarilyUnavailable, "UNAVAILABLE")
            }
        val coordinator = FriendsCoordinator(repo, FakeReporter())

        coordinator.load()

        assertEquals(
            FriendsStatus.Error(FriendActionError.TemporarilyUnavailable),
            coordinator.status.value,
        )
    }

    @Test
    fun `a backend-unavailable load is reported as a genuine fault`() = runTest {
        val repo =
            FakeRepo().apply {
                listResult =
                    FriendsResult.Failed(FriendActionError.TemporarilyUnavailable, "UNAVAILABLE")
            }
        val reporter = FakeReporter()

        FriendsCoordinator(repo, reporter).load()

        // THE REGRESSION: the production outage produced no report at all, so it
        // stayed invisible until a human noticed and described it by hand.
        assertEquals(1, reporter.reports.size)
        val report = reporter.reports.single()
        assertEquals("friends.list", report.feature)
        assertEquals("UNAVAILABLE", report.code)
        assertTrue(
            "report must say it is the backend, got: ${report.message}",
            report.message.contains("cannot serve"),
        )
    }

    @Test
    fun `an expected refusal on load is not reported`() = runTest {
        // Being signed out / not a member is a normal, actionable outcome — it
        // must be shown specifically but must NOT file an issue.
        for (expected in listOf(
            FriendActionError.SignedOut,
            FriendActionError.NotMember,
            FriendActionError.Network,
        )) {
            val repo = FakeRepo().apply { listResult = FriendsResult.Failed(expected, "X") }
            val reporter = FakeReporter()
            val coordinator = FriendsCoordinator(repo, reporter)

            coordinator.load()

            // Still surfaced to the user, and surfaced SPECIFICALLY...
            assertEquals(FriendsStatus.Error(expected), coordinator.status.value)
            // ...but never filed as a fault.
            assertTrue("$expected must not be reported", reporter.reports.isEmpty())
        }
    }
}
