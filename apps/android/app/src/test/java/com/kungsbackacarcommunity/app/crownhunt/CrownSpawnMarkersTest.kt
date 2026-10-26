package com.kungsbackacarcommunity.app.crownhunt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CrownSpawnMarkersTest {
    private val now = 1_000_000L
    private val center = CrownQueryCenter(57.48, 12.07)
    private val previous = CrownFix(57.48, 12.07, now - 5_000)
    private val current = CrownFix(57.48, 12.07, now)

    private fun spawn(rarity: CrownRarity = CrownRarity.COMMON) =
        CrownSpawn(rarity.wire, 57.48, 12.07, rarity, 17, 75.0, now + 100_000)

    private fun markerIds(controller: CrownSpawnController) =
        CrownSpawnMarkers.markers(
            spawns = controller.nearbySpawns.value,
            visible = true,
            collectedIds = controller.collectedSpawnIds.value,
            glyphRes = { it.ordinal + 100 },
        ).map { it.id }

    private class Store : CollectedCrownStore {
        val entries = mutableMapOf<String, Map<String, Long?>>()
        override fun load(uid: String): Map<String, Long?> = entries[uid].orEmpty()
        override fun save(uid: String, entries: Map<String, Long?>) {
            this.entries[uid] = entries.toMap()
        }
    }

    private class Repo(private val spawn: CrownSpawn) : CrownSpawnRepository {
        var result = CrownSpawnClaimResult.AWARDED
        var fail = false
        var claimGate: CompletableDeferred<Unit>? = null
        val serverCollected = MutableStateFlow<Map<String, Long?>>(emptyMap())

        override suspend fun listNearby(cellKeys: List<String>, nowMillis: Long) = listOf(spawn)
        override fun observeCollected(uid: String): Flow<Map<String, Long?>> = serverCollected
        override suspend fun claimSpawn(
            spawnId: String,
            current: CrownFix,
            previous: CrownFix,
            idempotencyKey: String,
        ): CrownSpawnClaimOutcome {
            claimGate?.await()
            if (fail) error("transport failure")
            return CrownSpawnClaimOutcome(result, 7, 107, spawn.rarity)
        }
    }

    @Test
    fun confirmedSharedPickupsDisappearAndStayHiddenAcrossRefreshRestartAndAccountSwitch() = runTest {
        for (result in listOf(CrownSpawnClaimResult.AWARDED, CrownSpawnClaimResult.ALREADY_COLLECTED)) {
            val shared = spawn()
            val repo = Repo(shared).also { it.result = result }
            val store = Store()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val controller = CrownSpawnController(repo, { now }, store, dispatcher)
            controller.bindUser("A")
            controller.refreshOnce(true, center, 1_000.0, force = true)
            assertEquals(listOf(shared.id), markerIds(controller))

            // The open popup owns this latched object independently of marker visibility.
            val latchedSpawn = controller.nearbySpawns.value.single()
            controller.collect(latchedSpawn, current, previous, "claim")
            val done = controller.claimStatus.value as CrownClaimStatus.Done
            assertEquals(result, done.outcome.result)
            assertEquals(shared.id, done.spawnId)
            assertTrue(markerIds(controller).isEmpty())
            assertEquals(listOf(shared), controller.nearbySpawns.value)
            assertEquals(setOf(shared.id), store.entries.getValue("A").keys)

            controller.refreshOnce(true, center, 1_000.0, force = true)
            assertTrue(markerIds(controller).isEmpty())
            assertEquals(done, controller.claimStatus.value)
            assertEquals(shared, latchedSpawn)

            val restarted = CrownSpawnController(repo, { now }, store, dispatcher)
            restarted.bindUser("A")
            restarted.refreshOnce(true, center, 1_000.0, force = true)
            assertTrue(markerIds(restarted).isEmpty())
            restarted.bindUser("B")
            assertEquals(listOf(shared.id), markerIds(restarted))
            restarted.bindUser("A")
            assertTrue(markerIds(restarted).isEmpty())
        }
    }

    @Test
    fun exclusiveAwardRemovesMarkerAndRetainsConfirmedPopupOutcome() = runTest {
        for (rarity in listOf(CrownRarity.RARE, CrownRarity.LEGENDARY)) {
            val exclusive = spawn(rarity)
            val controller = CrownSpawnController(Repo(exclusive), { now })
            controller.refreshOnce(true, center, 1_000.0, force = true)
            val latchedSpawn = controller.nearbySpawns.value.single()
            controller.collect(latchedSpawn, current, previous, "exclusive")
            assertTrue(markerIds(controller).isEmpty())
            val done = controller.claimStatus.value as CrownClaimStatus.Done
            assertEquals(CrownSpawnClaimResult.AWARDED, done.outcome.result)
            assertEquals(7, done.outcome.pointsAwarded)
            assertEquals(exclusive.id, done.spawnId)
            assertEquals(exclusive, latchedSpawn)
        }
    }

    @Test
    fun serverCollectorSyncRemovesMarkersWithoutAClaimOrAnotherNearbyQuery() = runTest {
        val shared = spawn()
        val repo = Repo(shared)
        val controller = CrownSpawnController(repo, { now }, ioDispatcher = StandardTestDispatcher(testScheduler))
        controller.refreshOnce(true, center, 1_000.0, force = true)
        val sync = backgroundScope.launch { controller.syncCollectedForUser("A") }
        runCurrent()
        assertEquals(listOf(shared.id), markerIds(controller))

        repo.serverCollected.value = mapOf(shared.id to shared.expiresAtMillis)
        runCurrent()
        assertTrue(markerIds(controller).isEmpty())
        assertEquals(CrownClaimStatus.Idle, controller.claimStatus.value)
        assertEquals(listOf(shared), controller.nearbySpawns.value)
        sync.cancel()
    }

    @Test
    fun pendingPositionPendingRequestTransportFailureAndEligibilityRejectionsKeepMarkers() = runTest {
        val shared = spawn()
        val repo = Repo(shared)
        val controller = CrownSpawnController(repo, { now })
        controller.refreshOnce(true, center, 1_000.0, force = true)
        controller.collect(shared, null, null, "no-position")
        assertEquals(CrownClaimStatus.NeedsPosition, controller.claimStatus.value)
        assertEquals(listOf(shared.id), markerIds(controller))

        val gate = CompletableDeferred<Unit>()
        repo.claimGate = gate
        repo.fail = true
        val pending = launch { controller.collect(shared, current, previous, "pending") }
        runCurrent()
        assertEquals(CrownClaimStatus.Collecting, controller.claimStatus.value)
        assertEquals(listOf(shared.id), markerIds(controller))
        gate.complete(Unit)
        pending.join()
        assertEquals(CrownClaimStatus.Failed, controller.claimStatus.value)
        assertEquals(listOf(shared.id), markerIds(controller))

        repo.fail = false
        for (result in CrownSpawnClaimResult.entries - setOf(
            CrownSpawnClaimResult.AWARDED,
            CrownSpawnClaimResult.ALREADY_COLLECTED,
            // Existing controller behaviour removes server-confirmed unavailable spawns.
            CrownSpawnClaimResult.ALREADY_TAKEN,
            CrownSpawnClaimResult.CROWN_EXPIRED,
        )) {
            repo.result = result
            controller.collect(shared, current, previous, result.wire)
            assertEquals(result.wire, listOf(shared.id), markerIds(controller))
            assertTrue(controller.collectedSpawnIds.value.isEmpty())
        }
    }

    @Test
    fun mappingPreservesAllFourGlyphsCoordinatesAndDocumentRewardWithNoFixSlate() {
        val spawns = CrownRarity.entries.map { spawn(it) }
        val markers = CrownSpawnMarkers.markers(spawns, true, emptySet(), glyphRes = { it.ordinal + 100 })
        assertEquals(4, markers.size)
        markers.zip(spawns).forEach { (marker, spawn) ->
            assertEquals(spawn.id, marker.id)
            assertEquals(spawn.rarity, marker.rarity)
            assertEquals(17, marker.rewardPoints)
            assertEquals(spawn.rarity.ordinal + 100, marker.iconRes)
            assertEquals(spawn.latitude, marker.latitude, 0.0)
            assertEquals(spawn.longitude, marker.longitude, 0.0)
            assertEquals(CrownMarkerStyle.OUT_OF_RANGE_DISC, marker.discColorArgb)
            assertFalse(marker.collectedByYou)
            assertNull(marker.glowColorArgb)
        }
        val near = CrownSpawnMarkers.markers(
            spawns, true, emptySet(), inRangeIds = spawns.map { it.id }.toSet(), glyphRes = { 1 },
        )
        near.forEach {
            assertEquals(CrownMarkerStyle.discColorArgb(it.rarity!!), it.discColorArgb)
            assertNull(it.glowColorArgb)
        }
    }

    @Test
    fun visibilityAndZoomGatesNeverReintroduceCollectedSpawnsOrMutateInput() {
        val spawns = CrownRarity.entries.map { spawn(it) }
        val collected = setOf(CrownRarity.RARE.wire)
        fun markers(visible: Boolean, zoomedOut: Boolean) = CrownSpawnMarkers.markers(
            spawns, visible, collected, zoomedOut = zoomedOut, glyphRes = { 1 },
        )
        assertTrue(markers(false, false).isEmpty())
        assertEquals(listOf("legendary"), markers(true, true).map { it.id })
        assertEquals(listOf("common", "uncommon", "legendary"), markers(true, false).map { it.id })
        assertEquals(4, spawns.size)
        assertEquals(setOf("rare"), collected)
    }
}
