package com.kungsbackacarcommunity.app.partners

import com.kungsbackacarcommunity.app.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PartnerTrafficHeatTest {
    private fun area() = mapOf(
        "cellId" to "8a0894310217fff", "latitude" to 57.49, "longitude" to 12.08,
        "band" to 5L, "boundary" to listOf(
            mapOf("latitude" to 57.48, "longitude" to 12.07),
            mapOf("latitude" to 57.50, "longitude" to 12.07),
            mapOf("latitude" to 57.49, "longitude" to 12.09),
        ),
    )
    private fun response() = mapOf<String, Any?>(
        "generatedAt" to "2026-11-21T12:00:00Z", "windowDays" to 90L,
        "expiresInSeconds" to 2L, "truncated" to false, "cells" to listOf(area()),
    )
    private fun snapshot() = parsePartnerTrafficHeat(response())

    @Test fun `privacy projection accepts the documented aggregate without contributor data`() {
        val parsed = snapshot()
        assertEquals(90, parsed.windowDays)
        assertEquals(5, parsed.cells.single().band)
        assertEquals(3, parsed.cells.single().boundary.size)
        assertFalse(parsed.truncated)
        val empty = parsePartnerTrafficHeat(response() + mapOf("generatedAt" to null, "expiresInSeconds" to 0L, "cells" to emptyList<Any>()))
        assertNull(empty.generatedAt)
        assertTrue(empty.cells.isEmpty())
    }

    @Test fun `invalid or unbounded snapshots fail closed before reaching the map`() {
        val invalid = listOf(
            response() + ("windowDays" to 1L),
            response() + ("expiresInSeconds" to 172801L),
            response() + ("expiresInSeconds" to 1.5),
            response() + ("expiresInSeconds" to Double.NaN),
            response() + ("generatedAt" to "invalid-date"),
            response() + ("generatedAt" to 4L),
            response() + ("generatedAt" to null),
            response() + ("expiresInSeconds" to 0L),
            response() + ("cells" to List(1001) { area() }),
            response() + ("cells" to listOf(area(), area())),
            response() + ("cells" to listOf(area() + ("band" to 6))),
            response() + ("cells" to listOf(area() + ("latitude" to Double.POSITIVE_INFINITY))),
            response() + ("cells" to listOf(area() + ("longitude" to 181))),
            response() + ("cells" to listOf(area() + ("cellId" to "private-account-id"))),
            response() + ("cells" to listOf(area() + ("boundary" to emptyList<Any>()))),
            response() + ("truncated" to null),
        )
        invalid.forEach { row -> assertThrows(RuntimeException::class.java) { parsePartnerTrafficHeat(row) } }
    }

    @Test fun `snapshot expires from request start and network delay never extends its lifetime`() = runTest {
        var elapsed = 100L
        val state = PartnerTrafficHeatState { elapsed }
        val waiting = CompletableDeferred<PartnerTrafficHeatSnapshot>()
        val repo = HeatRepo().apply { load = { waiting.await() } }
        val request = launch(UnconfinedTestDispatcher(testScheduler)) { state.load(repo, "company") }
        assertTrue(state.busy)
        elapsed = 1_500L
        waiting.complete(snapshot()); request.join()
        assertNotNull(state.snapshot)
        elapsed = 2_100L
        state.expireIfNeeded()
        assertNull(state.snapshot)
        assertTrue(state.expired)
        assertFalse(state.busy)
    }

    @Test fun `a response arriving after expiry is never displayed`() = runTest {
        var elapsed = 0L
        val state = PartnerTrafficHeatState { elapsed }
        val repo = HeatRepo().apply { load = { elapsed = 2_001L; snapshot() } }
        state.load(repo, "company")
        assertNull(state.snapshot)
        assertTrue(state.expired)
    }

    @Test fun `account switch drops an outstanding success and performs no anonymous read`() = runTest {
        val waiting = CompletableDeferred<PartnerTrafficHeatSnapshot>()
        val repo = HeatRepo().apply { load = { waiting.await() } }
        val state = PartnerTrafficHeatState { 0L }
        val request = launch(UnconfinedTestDispatcher(testScheduler)) { state.load(repo, "company") }
        repo.identity = "other-manager"
        waiting.complete(snapshot()); request.join()
        assertNull(state.snapshot)
        repo.identity = null
        state.load(repo, "company")
        assertEquals(listOf("company"), repo.reads)
        assertFalse(state.busy)
    }

    @Test fun `clear for background or navigation fences late results`() = runTest {
        val waiting = CompletableDeferred<PartnerTrafficHeatSnapshot>()
        val repo = HeatRepo().apply { load = { waiting.await() } }
        val state = PartnerTrafficHeatState { 0L }
        val request = launch(UnconfinedTestDispatcher(testScheduler)) { state.load(repo, "company") }
        state.clear()
        waiting.complete(snapshot()); request.join()
        assertNull(state.snapshot)
        assertFalse(state.busy)
        assertNull(state.error)
    }

    @Test fun `new company load wins over an older company response`() = runTest {
        val older = CompletableDeferred<PartnerTrafficHeatSnapshot>()
        val current = snapshot().copy(truncated = true)
        val repo = HeatRepo().apply { load = { if (it == "old-company") older.await() else current } }
        val state = PartnerTrafficHeatState { 0L }
        val request = launch(UnconfinedTestDispatcher(testScheduler)) { state.load(repo, "old-company") }
        state.load(repo, "new-company")
        older.complete(snapshot()); request.join()
        assertEquals(current, state.snapshot)
        assertEquals(listOf("old-company", "new-company"), repo.reads)
    }

    @Test fun `failed reauthorization removes previously visible data and retry starts fresh`() = runTest {
        val repo = HeatRepo().apply { load = { snapshot() } }
        val state = PartnerTrafficHeatState { 0L }
        state.load(repo, "company")
        assertNotNull(state.snapshot)
        repo.load = { error("revoked") }
        state.load(repo, "company")
        assertNull(state.snapshot)
        assertEquals(R.string.partnerPortal_error, state.error)
        repo.load = { snapshot() }
        state.load(repo, "company")
        assertNotNull(state.snapshot)
        assertNull(state.error)
    }

    @Test fun `same account company refresh retains its valid map until server denies the read`() = runTest {
        val repo = HeatRepo().apply { load = { snapshot() } }
        val state = PartnerTrafficHeatState { 0L }
        state.load(repo, "company")
        val first = state.snapshot
        val waiting = CompletableDeferred<PartnerTrafficHeatSnapshot>()
        repo.load = { waiting.await() }
        val request = launch(UnconfinedTestDispatcher(testScheduler)) { state.load(repo, "company") }
        assertEquals(first, state.snapshot)
        assertTrue(state.busy)
        waiting.completeExceptionally(IllegalStateException("Denied by server")); request.join()
        assertNull(state.snapshot)
        assertNotNull(state.error)
    }

    @Test fun `pending refresh expires independently and does not cross an account change`() = runTest {
        var elapsed = 0L
        val repo = HeatRepo().apply { load = { snapshot() } }
        val state = PartnerTrafficHeatState { elapsed }
        state.load(repo, "company")
        val waiting = CompletableDeferred<PartnerTrafficHeatSnapshot>()
        repo.load = { waiting.await() }
        val request = launch(UnconfinedTestDispatcher(testScheduler)) { state.load(repo, "company") }
        elapsed = 2_001L
        state.expireIfNeeded()
        assertNull(state.snapshot)
        assertTrue(state.expired)
        assertTrue(state.busy)
        repo.identity = "other-account"
        waiting.complete(snapshot()); request.join()
        assertNull(state.snapshot)
        assertFalse(state.busy)
        assertNull(state.error)
    }

    @Test fun `cancellation propagates without keeping protected data or displaying a failure`() = runTest {
        val repo = HeatRepo().apply { load = { snapshot() } }
        val state = PartnerTrafficHeatState { 0L }
        state.load(repo, "company")
        assertNotNull(state.snapshot)
        repo.load = { throw CancellationException() }
        var cancelled = false
        try { state.load(repo, "company") } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertNull(state.snapshot)
        assertNull(state.error)
        assertFalse(state.busy)
    }

    private class HeatRepo : PartnerPortalRepository {
        var identity: String? = "manager"
        override val trafficSessionKey get() = identity
        val reads = mutableListOf<String>()
        var load: suspend (String) -> PartnerTrafficHeatSnapshot = { error("Not configured") }
        override suspend fun driveHeat(companyId: String): PartnerTrafficHeatSnapshot { reads += companyId; return load(companyId) }
        override suspend fun workspace(): PartnerWorkspace = error("unused")
        override suspend fun offers(companyId: String): List<ManagedPartnerOffer> = error("unused")
        override suspend fun submitOffer(companyId: String, fields: Map<String, Any?>) = error("unused")
        override suspend fun pause(companyId: String, offerId: String, paused: Boolean) = error("unused")
        override suspend fun updateProfile(companyId: String, description: String, phone: String, website: String) = error("unused")
        override suspend fun uploadLogo(companyId: String, base64: String) = error("unused")
        override suspend fun statistics(companyId: String, period: String): PartnerAggregateStatistics = error("unused")
        override suspend fun offer(offerId: String): PartnerOfferReservationDetail = error("unused")
        override suspend fun reserve(offerId: String, requestId: String, expectedVersion: Int): PartnerReservation = error("unused")
        override suspend fun reservations(): List<PartnerReservation> = error("unused")
        override suspend fun cancel(reservationId: String): PartnerReservation = error("unused")
        override suspend fun token(reservationId: String): PartnerReservationToken = error("unused")
        override suspend fun inspect(token: String): PartnerReservation = error("unused")
        override suspend fun redeem(token: String, requestId: String): PartnerReservation = error("unused")
    }
}
