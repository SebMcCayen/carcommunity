package com.kungsbackacarcommunity.app.convoy

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

        override suspend fun respond(convoyId: String, accept: Boolean): ConvoyMutationResult = respondResult

        override suspend fun start(convoyId: String): ConvoyMutationResult = startResult

        override suspend fun end(convoyId: String): ConvoyMutationResult = endResult
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
    fun `accept re-fetches the snapshot`() = runTest {
        val repo = FakeRepo()
        val coordinator = ConvoyCoordinator(repo)
        coordinator.accept("p1")
        assertEquals(1, repo.listCalls)
        assertNull(coordinator.actionError.value)
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
