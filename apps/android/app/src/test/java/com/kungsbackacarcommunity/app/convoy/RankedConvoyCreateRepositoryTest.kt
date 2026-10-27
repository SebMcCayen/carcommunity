package com.kungsbackacarcommunity.app.convoy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RankedConvoyCreateRepositoryTest {
    private class Usage : ConvoyInviteUsageStore {
        val records = mutableListOf<Pair<String, List<String>>>()
        var broken = false
        override fun counts(uid: String) = emptyMap<String, Int>()
        override fun record(uid: String, invitedUids: List<String>) {
            if (broken) error("storage unavailable")
            records += uid to invitedUids
        }
    }

    private class Repo : ConvoyRepository {
        var result: CreateConvoyResult = success()
        var gate: CompletableDeferred<Unit>? = null
        var thrown: Exception? = null
        var calls = 0
        var lastVehicleId: String? = null
        var snapshot: ConvoyListResult = ConvoyListResult.Loaded(emptyList(), emptyList())
        override suspend fun list() = snapshot
        override fun observeConvoy(convoyId: String, callerUid: String?) = emptyFlow<ConvoySummary?>()
        override suspend fun create(inviteeUids: List<String>, title: String?, vehicleId: String?): CreateConvoyResult {
            calls++
            lastVehicleId = vehicleId
            gate?.await()
            thrown?.let { throw it }
            return result
        }
        override suspend fun respond(convoyId: String, accept: Boolean) = error("unused")
        override suspend fun invite(convoyId: String, inviteeUids: List<String>) = error("unused")
        override suspend fun leave(convoyId: String): LeaveConvoyResult = error("unused")
        override suspend fun start(convoyId: String) = error("unused")
        override suspend fun end(convoyId: String) = error("unused")
    }

    @Test fun accountsOnlyActualRequestedSuccessesBeforeCreatedIsPublished() = runTest {
        val repo = Repo()
        val usage = Usage()
        repo.result = success(listOf("a", "a", "unexpected", "me"))
        val coordinator = ConvoyCoordinator(RankedConvoyCreateRepository(repo, usage, "me") { "me" })
        coordinator.load()
        coordinator.create(listOf("a", "skipped"), null, "car")
        assertTrue(coordinator.createState.value is CreateConvoyState.Created)
        assertEquals(listOf("me" to listOf("a")), usage.records)
        assertEquals("car", repo.lastVehicleId)
    }

    @Test fun failureExceptionCancellationAndUnsubmittedPickerNeverRecord() = runTest {
        val repo = Repo()
        val usage = Usage()
        val wrapped = RankedConvoyCreateRepository(repo, usage, "me") { "me" }
        assertTrue(usage.records.isEmpty())
        repo.result = CreateConvoyResult.Failed(ConvoyActionError.Generic)
        wrapped.create(listOf("a"), null)
        repo.thrown = IllegalStateException("offline")
        try { wrapped.create(listOf("a"), null) } catch (_: IllegalStateException) { }
        repo.thrown = null
        repo.result = success()
        repo.gate = CompletableDeferred()
        val job = launch { wrapped.create(listOf("a"), null) }
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(usage.records.isEmpty())
    }

    @Test fun accountSwitchOrSignedOutCannotWriteOldUsage() = runTest {
        val usage = Usage()
        RankedConvoyCreateRepository(Repo(), usage, "me") { "other" }.create(listOf("a"), null)
        RankedConvoyCreateRepository(Repo(), usage, null) { null }.create(listOf("a"), null)
        assertTrue(usage.records.isEmpty())
    }

    @Test fun switchingAccountWhileCreateIsInFlightDoesNotRecordForEitherAccount() = runTest {
        val repo = Repo().apply { gate = CompletableDeferred() }
        val usage = Usage()
        var currentUid = "me"
        val wrapped = RankedConvoyCreateRepository(repo, usage, "me") { currentUid }
        val job = launch { wrapped.create(listOf("a"), null) }
        runCurrent()
        currentUid = "other"
        repo.gate!!.complete(Unit)
        job.join()
        assertTrue(usage.records.isEmpty())
    }

    @Test fun emptyServerConfirmedInvitedSetNeverRecordsSelectedOrSkippedFriends() = runTest {
        val repo = Repo().apply { result = success(emptyList()) }
        val usage = Usage()
        RankedConvoyCreateRepository(repo, usage, "me") { "me" }.create(listOf("skipped"), null)
        assertTrue(usage.records.isEmpty())
    }

    @Test fun localStorageFailureDoesNotFailBackendSuccess() = runTest {
        val usage = Usage().apply { broken = true }
        assertTrue(RankedConvoyCreateRepository(Repo(), usage, "me") { "me" }
            .create(listOf("a"), null) is CreateConvoyResult.Created)
    }

    @Test fun existingCoordinatorStillGuardsDuplicateAndUncertainMembership() = runTest {
        val repo = Repo().apply { gate = CompletableDeferred() }
        val usage = Usage()
        val coordinator = ConvoyCoordinator(RankedConvoyCreateRepository(repo, usage, "me") { "me" })
        coordinator.load()
        val first = launch { coordinator.create(listOf("a"), null) }
        runCurrent()
        coordinator.create(listOf("a"), null)
        assertEquals(1, repo.calls)
        repo.gate!!.complete(Unit)
        first.join()
        assertEquals(1, usage.records.size)
        repo.snapshot = ConvoyListResult.Loaded(emptyList(), emptyList(), isExhaustive = false)
        coordinator.load()
        coordinator.resetCreate()
        coordinator.create(listOf("a"), null)
        assertEquals(CreateConvoyState.Error(ConvoyActionError.MembershipUncertain), coordinator.createState.value)
        assertEquals(1, repo.calls)
    }

    private companion object {
        fun success(invited: List<String> = listOf("a")) = CreateConvoyResult.Created(
            ConvoySummary("new", "me", null, ConvoyStatus.Active, emptyList(), emptyList(),
                null, emptyList(), null, null, null, null),
            invited, listOf(SkippedInvitee("skipped", ConvoySkipReason.NotFriend)),
        )
    }
}
