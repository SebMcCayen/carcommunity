package com.kungsbackacarcommunity.app.convoy

import com.kungsbackacarcommunity.app.profile.LiveProfile
import com.kungsbackacarcommunity.app.profile.LiveProfileRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvoyCoordinatorTest {

    private fun convoy(
        id: String,
        status: ConvoyStatus = ConvoyStatus.Forming,
        viewerInvite: ConvoyInviteStatus = ConvoyInviteStatus.Accepted,
        viewerRole: ConvoyRole = ConvoyRole.Owner,
    ) = ConvoySummary(
        convoyId = id,
        ownerUid = "owner",
        title = "Trip $id",
        status = status,
        members = emptyList(),
        memberUids = listOf("owner"),
        viewer = ConvoyViewer(viewerRole, viewerInvite),
        livePositionUids = emptyList(),
        summary = null,
        createdAt = null,
        startedAt = null,
        endedAt = null,
    )

    private class FakeRepo : ConvoyRepository {
        var listResult: ConvoyListResult = ConvoyListResult.Loaded(emptyList(), emptyList())
        var createResult: CreateConvoyResult =
            CreateConvoyResult.Created(
                ConvoySummary(
                    "new", "owner", null, ConvoyStatus.Forming, emptyList(),
                    emptyList(), null, emptyList(), null, null, null, null,
                ),
                invited = emptyList(),
                skipped = emptyList(),
            )
        var respondResult: ConvoyMutationResult =
            ConvoyMutationResult.Updated(
                ConvoySummary(
                    "c", "owner", null, ConvoyStatus.Forming, emptyList(),
                    emptyList(), null, emptyList(), null, null, null, null,
                ),
            )
        var startResult: ConvoyMutationResult = respondResult
        var endResult: ConvoyMutationResult = respondResult

        var listCalls = 0
        var lastInvitees: List<String>? = null
        var lastTitle: String? = null
        var createCalls = 0

        // Live-observe seam: the test emits into this to simulate a Firestore
        // snapshot firing, and records what the coordinator asked to observe.
        val observeFlow = MutableSharedFlow<ConvoySummary?>(replay = 1)
        val observedConvoyIds = mutableListOf<String>()
        var lastObservedCallerUid: String? = null

        override fun observeConvoy(convoyId: String, callerUid: String?): Flow<ConvoySummary?> {
            observedConvoyIds += convoyId
            lastObservedCallerUid = callerUid
            return observeFlow
        }

        override suspend fun list(): ConvoyListResult {
            listCalls++
            return listResult
        }

        override suspend fun create(inviteeUids: List<String>, title: String?): CreateConvoyResult {
            createCalls++
            lastInvitees = inviteeUids
            lastTitle = title
            return createResult
        }

        var respondCalls = 0
        val respondAccepts = mutableListOf<Boolean>()

        /** Holds `convoy.respond` open so a second answer can race the first. */
        var respondGate: CompletableDeferred<Unit>? = null

        override suspend fun respond(convoyId: String, accept: Boolean): ConvoyMutationResult {
            respondCalls++
            respondAccepts += accept
            respondGate?.await()
            return respondResult
        }

        var inviteResult: CreateConvoyResult = createResult
        var lastInviteConvoyId: String? = null
        var lastInviteeUids: List<String>? = null

        // When set, invite() suspends here until the test completes it — lets a
        // test hold an invite mid-flight (coordinator state == Working).
        var inviteGate: CompletableDeferred<Unit>? = null
        var inviteCalls = 0

        override suspend fun invite(convoyId: String, inviteeUids: List<String>): CreateConvoyResult {
            inviteCalls++
            lastInviteConvoyId = convoyId
            lastInviteeUids = inviteeUids
            inviteGate?.await()
            return inviteResult
        }

        var leaveResult: LeaveConvoyResult =
            LeaveConvoyResult.Left(
                convoy = LEFT_CONVOY,
                remainingMemberCount = 2,
                outcome = ConvoyLeaveOutcome.Left,
                newLeaderUid = null,
            )
        var lastLeaveConvoyId: String? = null

        override suspend fun leave(convoyId: String): LeaveConvoyResult {
            lastLeaveConvoyId = convoyId
            return leaveResult
        }

        override suspend fun start(convoyId: String): ConvoyMutationResult = startResult

        // When set, end() suspends here until the test completes it — lets a test
        // observe the coordinator's OPTIMISTIC state between the tap and the call
        // returning.
        var endGate: CompletableDeferred<Unit>? = null

        override suspend fun end(convoyId: String): ConvoyMutationResult {
            endGate?.await()
            return endResult
        }

        companion object {
            /**
             * The convoy as it comes back from a successful leave: `viewer` is
             * null, because the caller is no longer a member.
             */
            val LEFT_CONVOY =
                ConvoySummary(
                    "c1", "owner", null, ConvoyStatus.Active, emptyList(),
                    emptyList(), null, emptyList(), null, null, null, null,
                )
        }
    }

    @Test
    fun `load publishes the snapshot and myConvoys excludes pending invites`() = runTest {
        val pending = convoy("p1", viewerInvite = ConvoyInviteStatus.Invited, viewerRole = ConvoyRole.Member)
        val repo =
            FakeRepo().apply {
                listResult = ConvoyListResult.Loaded(convoys = listOf(convoy("c1"), pending), pendingInvites = listOf(pending))
            }
        val coordinator = ConvoyCoordinator(repo)
        coordinator.load()
        val status = coordinator.status.value
        assertTrue(status is ConvoyListStatus.Loaded)
        status as ConvoyListStatus.Loaded
        assertEquals(listOf("c1"), status.myConvoys.map { it.convoyId })
        assertEquals(listOf("p1"), status.pendingInvites.map { it.convoyId })
        assertEquals("c1", status.convoy("c1")?.convoyId)
    }

    @Test
    fun `a failing profile overlay cannot turn a loaded list into an error`() = runTest {
        // load() publishes the stored snapshot BEFORE hydrating, and the whole
        // body sits inside a catch-all that maps any throw to
        // ConvoyListStatus.Error — so an unguarded cosmetic overlay would replace
        // a convoy list the member can already see with an error screen.
        val throwing =
            object : LiveProfileRepository {
                override fun observeProfiles(uids: Set<String>): Flow<Map<String, LiveProfile>> =
                    flow { throw IllegalStateException("profile read blew up") }

                override suspend fun loadProfiles(uids: Set<String>): Map<String, LiveProfile> =
                    throw IllegalStateException("profile read blew up")
            }
        val repo =
            FakeRepo().apply {
                listResult =
                    ConvoyListResult.Loaded(convoys = listOf(convoy("c1")), pendingInvites = emptyList())
            }

        val coordinator = ConvoyCoordinator(repo, liveProfiles = throwing)
        coordinator.load()

        val status = coordinator.status.value
        assertTrue(status is ConvoyListStatus.Loaded)
        assertEquals(listOf("c1"), (status as ConvoyListStatus.Loaded).myConvoys.map { it.convoyId })
    }

    @Test
    fun `load failure surfaces the mapped error`() = runTest {
        val repo = FakeRepo().apply { listResult = ConvoyListResult.Failed(ConvoyActionError.NotMember) }
        val coordinator = ConvoyCoordinator(repo)
        coordinator.load()
        assertEquals(ConvoyListStatus.Error(ConvoyActionError.NotMember), coordinator.status.value)
    }

    @Test
    fun `create with no invitees is rejected without calling the backend`() = runTest {
        val repo = FakeRepo()
        val coordinator = ConvoyCoordinator(repo)
        coordinator.create(inviteeUids = listOf("", "  "), title = null)
        assertEquals(CreateConvoyState.Error(ConvoyActionError.NoInvitees), coordinator.createState.value)
        assertEquals(0, repo.createCalls)
    }

    @Test
    fun `create dedupes invitees, trims a blank title, reports Created and reloads`() = runTest {
        val repo = FakeRepo()
        val coordinator = ConvoyCoordinator(repo)
        coordinator.create(inviteeUids = listOf("a", "a", "b"), title = "   ")
        val state = coordinator.createState.value
        assertTrue(state is CreateConvoyState.Created)
        assertEquals("new", (state as CreateConvoyState.Created).convoyId)
        assertEquals(listOf("a", "b"), repo.lastInvitees)
        assertNull(repo.lastTitle)
        assertEquals(1, repo.listCalls) // reloaded after create
    }

    @Test
    fun `create surfaces skipped invitees`() = runTest {
        val repo =
            FakeRepo().apply {
                createResult =
                    CreateConvoyResult.Created(
                        (respondResult as ConvoyMutationResult.Updated).convoy,
                        invited = listOf("a"),
                        skipped = listOf(SkippedInvitee("x", ConvoySkipReason.NotFriend)),
                    )
            }
        val coordinator = ConvoyCoordinator(repo)
        coordinator.create(listOf("a", "x"), null)
        val state = coordinator.createState.value as CreateConvoyState.Created
        assertEquals(listOf(ConvoySkipReason.NotFriend), state.skipped.map { it.reason })
    }

    @Test
    fun `invite with no invitees is rejected without calling the backend`() = runTest {
        val repo = FakeRepo()
        val coordinator = ConvoyCoordinator(repo)
        coordinator.invite("c1", inviteeUids = listOf("", "  "))
        assertEquals(InviteConvoyState.Error(ConvoyActionError.NoInvitees), coordinator.inviteState.value)
        assertNull(repo.lastInviteConvoyId)
    }

    @Test
    fun `invite dedupes invitees, reports Done with skipped, and reloads`() = runTest {
        val repo =
            FakeRepo().apply {
                inviteResult =
                    CreateConvoyResult.Created(
                        (respondResult as ConvoyMutationResult.Updated).convoy,
                        invited = listOf("a"),
                        skipped = listOf(SkippedInvitee("x", ConvoySkipReason.AlreadyMember)),
                    )
            }
        val coordinator = ConvoyCoordinator(repo)
        coordinator.invite("c1", listOf("a", "a", "x"))
        val state = coordinator.inviteState.value
        assertTrue(state is InviteConvoyState.Done)
        // Mixed outcome: the backend's invited AND skipped lists both flow through
        // Done, so the confirmation can report both counts ("Invited 1 · 1 …").
        state as InviteConvoyState.Done
        assertEquals(listOf("a"), state.invited)
        assertEquals(listOf(ConvoySkipReason.AlreadyMember), state.skipped.map { it.reason })
        assertEquals("c1", repo.lastInviteConvoyId)
        assertEquals(listOf("a", "x"), repo.lastInviteeUids)
        assertEquals(1, repo.listCalls) // reloaded after a successful invite
    }

    @Test
    fun `invite reports Done with the full invited list when nobody is skipped`() = runTest {
        val repo =
            FakeRepo().apply {
                inviteResult =
                    CreateConvoyResult.Created(
                        (respondResult as ConvoyMutationResult.Updated).convoy,
                        invited = listOf("a", "b"),
                        skipped = emptyList(),
                    )
            }
        val coordinator = ConvoyCoordinator(repo)
        coordinator.invite("c1", listOf("a", "b"))
        val state = coordinator.inviteState.value
        assertTrue(state is InviteConvoyState.Done)
        // All invited, nothing skipped → confirmation says only "Invited 2".
        state as InviteConvoyState.Done
        assertEquals(listOf("a", "b"), state.invited)
        assertTrue(state.skipped.isEmpty())
    }

    @Test
    fun `invite reports Done with no invited when everyone is skipped`() = runTest {
        val repo =
            FakeRepo().apply {
                inviteResult =
                    CreateConvoyResult.Created(
                        (respondResult as ConvoyMutationResult.Updated).convoy,
                        invited = emptyList(),
                        skipped =
                            listOf(
                                SkippedInvitee("x", ConvoySkipReason.AlreadyMember),
                                SkippedInvitee("y", ConvoySkipReason.Duplicate),
                            ),
                    )
            }
        val coordinator = ConvoyCoordinator(repo)
        coordinator.invite("c1", listOf("x", "y"))
        val state = coordinator.inviteState.value
        assertTrue(state is InviteConvoyState.Done)
        // Nobody added → invited is empty so the confirmation says "no one new".
        state as InviteConvoyState.Done
        assertTrue(state.invited.isEmpty())
        assertEquals(2, state.skipped.size)
    }

    @Test
    fun `invite failure surfaces the mapped error and does not reload`() = runTest {
        val repo = FakeRepo().apply { inviteResult = CreateConvoyResult.Failed(ConvoyActionError.NotFound) }
        val coordinator = ConvoyCoordinator(repo)
        coordinator.invite("c1", listOf("a"))
        assertEquals(InviteConvoyState.Error(ConvoyActionError.NotFound), coordinator.inviteState.value)
        assertEquals(0, repo.listCalls)
    }

    @Test
    fun `resetInvite returns the sub-state to Idle`() = runTest {
        val repo = FakeRepo().apply { inviteResult = CreateConvoyResult.Failed(ConvoyActionError.Generic) }
        val coordinator = ConvoyCoordinator(repo)
        coordinator.invite("c1", listOf("a"))
        assertTrue(coordinator.inviteState.value is InviteConvoyState.Error)
        coordinator.resetInvite()
        assertEquals(InviteConvoyState.Idle, coordinator.inviteState.value)
    }

    @Test
    fun `opening or closing the picker while an invite is in flight cannot start a second invite or clear Working`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            val repo = FakeRepo().apply { inviteGate = gate }
            val coordinator = ConvoyCoordinator(repo)

            // First invite runs eagerly up to the repo's gate: state is Working and
            // the overlap guard is armed.
            backgroundScope.launch { coordinator.invite("c1", listOf("a")) }
            assertEquals(InviteConvoyState.Working, coordinator.inviteState.value)
            assertEquals(1, repo.inviteCalls)

            // The picker being (re)opened or dismissed mid-flight calls resetInvite();
            // it must NOT clear Working, or the guard is lost.
            coordinator.resetInvite()
            assertEquals(InviteConvoyState.Working, coordinator.inviteState.value)

            // A second invite while Working is a no-op — the backend is not hit again
            // and the recorded payload is still the first invite's.
            coordinator.invite("c2", listOf("b"))
            assertEquals(1, repo.inviteCalls)
            assertEquals("c1", repo.lastInviteConvoyId)
            assertEquals(listOf("a"), repo.lastInviteeUids)

            // Letting the in-flight invite finish resolves it normally, exactly as if
            // the picker had never been touched.
            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(coordinator.inviteState.value is InviteConvoyState.Done)
        }

    @Test
    fun `leave calls the backend for the convoy and re-fetches`() = runTest {
        val repo = FakeRepo()
        val coordinator = ConvoyCoordinator(repo)
        coordinator.leave("c1")
        assertEquals("c1", repo.lastLeaveConvoyId)
        assertEquals(1, repo.listCalls)
        assertNull(coordinator.actionError.value)
    }

    @Test
    fun `leave failure sets the action error and still reloads`() = runTest {
        val repo = FakeRepo().apply {
            leaveResult = LeaveConvoyResult.Failed(ConvoyActionError.AlreadyEnded)
        }
        val coordinator = ConvoyCoordinator(repo)
        coordinator.leave("c1")
        assertEquals(ConvoyActionError.AlreadyEnded, coordinator.actionError.value)
        assertEquals(1, repo.listCalls)
        // A failed leave has nothing to confirm — the user is still in the convoy.
        assertNull(coordinator.leftNotice.value)
    }

    @Test
    fun `leave publishes what the exit DID, straight from the backend`() = runTest {
        // The outcome cannot be read off the refreshed snapshot (the convoy simply
        // disappears from the caller's list either way), so it is carried through
        // from the callable rather than re-derived from the roster.
        val repo = FakeRepo().apply {
            leaveResult =
                LeaveConvoyResult.Left(
                    convoy = FakeRepo.LEFT_CONVOY,
                    remainingMemberCount = 1,
                    outcome = ConvoyLeaveOutcome.LeftAndEnded,
                    newLeaderUid = null,
                )
        }
        val coordinator = ConvoyCoordinator(repo)
        coordinator.leave("c1")
        assertEquals(
            ConvoyLeftNotice(ConvoyLeaveOutcome.LeftAndEnded, transferredLeadership = false),
            coordinator.leftNotice.value,
        )
        // Shown once: the host clears it, and it does not come back.
        coordinator.clearLeftNotice()
        assertNull(coordinator.leftNotice.value)
    }

    @Test
    fun `a LEADER leaving reports that leadership transferred`() = runTest {
        val repo = FakeRepo().apply {
            leaveResult =
                LeaveConvoyResult.Left(
                    convoy = FakeRepo.LEFT_CONVOY,
                    remainingMemberCount = 2,
                    outcome = ConvoyLeaveOutcome.Left,
                    newLeaderUid = "successor",
                )
        }
        val coordinator = ConvoyCoordinator(repo)
        coordinator.leave("c1")
        assertEquals(
            ConvoyLeftNotice(ConvoyLeaveOutcome.Left, transferredLeadership = true),
            coordinator.leftNotice.value,
        )
    }

    @Test
    fun `accept re-fetches the snapshot`() = runTest {
        val repo = FakeRepo()
        val coordinator = ConvoyCoordinator(repo)
        coordinator.accept("p1")
        assertEquals(1, repo.listCalls)
        assertNull(coordinator.actionError.value)
    }

    /**
     * The double-answer guard, in the shape [LiveLocationCoordinator]'s `Busy`
     * test pins: a second respond raced against one already in flight must not
     * REACH the repository at all.
     *
     * This is what makes a fumbled double-tap on Accept impossible to turn into
     * two `convoy.respond` calls, the second of which the backend would reject
     * as an invite that has already been answered.
     */
    @Test
    fun `a second answer while one is in flight never reaches the repository`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            val repo = FakeRepo().apply { respondGate = gate }
            val coordinator = ConvoyCoordinator(repo)

            val inFlight = backgroundScope.launch { coordinator.accept("p1") }
            advanceUntilIdle()
            assertTrue("the first answer is in flight", "p1" in coordinator.busyConvoys.value)

            // The double tap. It returns without calling through — one respond.
            coordinator.accept("p1")
            assertEquals(1, repo.respondCalls)

            gate.complete(Unit)
            inFlight.join()
            assertEquals("exactly one convoy.respond", 1, repo.respondCalls)
            assertEquals(listOf(true), repo.respondAccepts)
            assertTrue("the guard is released", "p1" !in coordinator.busyConvoys.value)
        }

    /** The same guard covers Decline, and the two share one in-flight key. */
    @Test
    fun `a decline racing an in-flight accept for the same convoy is dropped`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            val repo = FakeRepo().apply { respondGate = gate }
            val coordinator = ConvoyCoordinator(repo)

            val inFlight = backgroundScope.launch { coordinator.accept("p1") }
            advanceUntilIdle()
            coordinator.decline("p1")
            assertEquals(1, repo.respondCalls)

            gate.complete(Unit)
            inFlight.join()
            assertEquals(listOf(true), repo.respondAccepts)
        }

    @Test
    fun `start failure sets the action error and still reloads`() = runTest {
        val repo = FakeRepo().apply { startResult = ConvoyMutationResult.Failed(ConvoyActionError.CannotStart) }
        val coordinator = ConvoyCoordinator(repo)
        coordinator.start("c1")
        assertEquals(ConvoyActionError.CannotStart, coordinator.actionError.value)
        assertEquals(1, repo.listCalls)
    }

    @Test
    fun `end maps a failure to AlreadyEnded`() = runTest {
        val repo = FakeRepo().apply { endResult = ConvoyMutationResult.Failed(ConvoyActionError.AlreadyEnded) }
        val coordinator = ConvoyCoordinator(repo)
        coordinator.end("c1")
        assertEquals(ConvoyActionError.AlreadyEnded, coordinator.actionError.value)
    }

    @Test
    fun `end optimistically clears the active convoy before the call settles`() =
        runTest(UnconfinedTestDispatcher()) {
            // The convoy is active and the caller is the accepted owner, so the bar
            // shows it (ConvoyBar.activeConvoy != null) before the end.
            val active = convoy("c1", status = ConvoyStatus.Active)
            val repo =
                FakeRepo().apply {
                    listResult = ConvoyListResult.Loaded(listOf(active), emptyList())
                    // Hold the end callable open so we can observe the state BETWEEN
                    // the tap and the call returning — the window that used to leave
                    // the bar greyed for a whole round-trip.
                    endGate = CompletableDeferred()
                }
            val coordinator = ConvoyCoordinator(repo)
            coordinator.load()
            assertEquals("c1", ConvoyBar.activeConvoy(coordinator.status.value)?.convoyId)

            val ending = launch { coordinator.end("c1") }
            advanceUntilIdle()
            // Optimistic: the convoy reads as ended and the bar is already gone,
            // even though the callable has NOT returned yet.
            assertEquals(
                ConvoyStatus.Ended,
                (coordinator.status.value as ConvoyListStatus.Loaded).convoy("c1")?.status,
            )
            assertNull(ConvoyBar.activeConvoy(coordinator.status.value))

            repo.endGate?.complete(Unit)
            ending.join()
            // No error on the happy path, and the resync ran.
            assertNull(coordinator.actionError.value)
        }

    @Test
    fun `end failure revives the convoy via the resync and surfaces the error`() =
        runTest(UnconfinedTestDispatcher()) {
            val active = convoy("c1", status = ConvoyStatus.Active)
            val repo =
                FakeRepo().apply {
                    // The list still reports the convoy ACTIVE (the end did not take
                    // on the server), so the reconciling load() must bring it back.
                    listResult = ConvoyListResult.Loaded(listOf(active), emptyList())
                    endResult = ConvoyMutationResult.Failed(ConvoyActionError.Generic)
                }
            val coordinator = ConvoyCoordinator(repo)
            coordinator.load()

            coordinator.end("c1")
            advanceUntilIdle()

            // The optimistic end was undone by the resync: the convoy is active
            // again and the bar is back, with the error surfaced.
            assertEquals(
                ConvoyStatus.Active,
                (coordinator.status.value as ConvoyListStatus.Loaded).convoy("c1")?.status,
            )
            assertEquals("c1", ConvoyBar.activeConvoy(coordinator.status.value)?.convoyId)
            assertEquals(ConvoyActionError.Generic, coordinator.actionError.value)
        }

    @Test
    fun `observeActiveConvoy watches the active convoy with the viewer uid`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo =
                FakeRepo().apply {
                    listResult = ConvoyListResult.Loaded(listOf(convoy("c1")), emptyList())
                }
            val coordinator = ConvoyCoordinator(repo)
            coordinator.load()
            backgroundScope.launch { coordinator.observeActiveConvoy("me") }
            assertEquals(listOf("c1"), repo.observedConvoyIds)
            assertEquals("me", repo.lastObservedCallerUid)
        }

    @Test
    fun `observeActiveConvoy folds a live destination into the active convoy`() =
        runTest(UnconfinedTestDispatcher()) {
            val active = convoy("c1")
            val repo =
                FakeRepo().apply {
                    listResult = ConvoyListResult.Loaded(listOf(active), emptyList())
                }
            val coordinator = ConvoyCoordinator(repo)
            coordinator.load()
            backgroundScope.launch { coordinator.observeActiveConvoy("me") }

            val fresh =
                active.copy(
                    destination =
                        ConvoyDestination(
                            latitude = 57.0,
                            longitude = 12.0,
                            label = "Torg",
                            setByUid = "someone",
                            setByDisplayName = "Anna",
                            setAt = null,
                        ),
                )
            repo.observeFlow.emit(fresh)

            val loaded = coordinator.status.value as ConvoyListStatus.Loaded
            assertEquals("Torg", loaded.convoy("c1")?.destination?.label)
        }

    @Test
    fun `observeActiveConvoy ignores a null emission and keeps the last value`() =
        runTest(UnconfinedTestDispatcher()) {
            val active = convoy("c1")
            val repo =
                FakeRepo().apply {
                    listResult = ConvoyListResult.Loaded(listOf(active), emptyList())
                }
            val coordinator = ConvoyCoordinator(repo)
            coordinator.load()
            backgroundScope.launch { coordinator.observeActiveConvoy("me") }

            repo.observeFlow.emit(active.copy(title = "Renamed"))
            repo.observeFlow.emit(null) // doc gone / read denied — must not wipe it

            val loaded = coordinator.status.value as ConvoyListStatus.Loaded
            assertEquals("Renamed", loaded.convoy("c1")?.title)
        }

    @Test
    fun `clearActionError resets the row error`() = runTest {
        val repo = FakeRepo().apply { startResult = ConvoyMutationResult.Failed(ConvoyActionError.Generic) }
        val coordinator = ConvoyCoordinator(repo)
        coordinator.start("c1")
        assertEquals(ConvoyActionError.Generic, coordinator.actionError.value)
        coordinator.clearActionError()
        assertNull(coordinator.actionError.value)
    }
}
