package com.kungsbackacarcommunity.app.crownhunt

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crown layer's fetching behaviour, and above all the FLAG GATE.
 *
 * "Default OFF" is only worth anything if off means *nothing happens*. A hidden
 * layer that still queried would cost every member a document read a minute for
 * a feature nobody switched on — and, worse, would make "the feature is off" a
 * claim about rendering rather than about behaviour. These tests are what make
 * it a claim about behaviour.
 */
class CrownSpawnControllerTest {

    /** Records every query so "zero queries" is assertable rather than asserted. */
    private class FakeRepo(
        var spawns: List<CrownSpawn> = emptyList(),
        var failWith: Throwable? = null,
        var claimResult: CrownSpawnClaimOutcome? = null,
        var claimFailure: Throwable? = null,
    ) : CrownSpawnRepository {
        var listCalls = 0
        var lastKeys: List<String>? = null
        var claimCalls = 0
        var lastIdempotencyKey: String? = null

        override suspend fun listNearby(cellKeys: List<String>, nowMillis: Long): List<CrownSpawn> {
            listCalls += 1
            lastKeys = cellKeys
            failWith?.let { throw it }
            return spawns
        }

        override suspend fun claimSpawn(
            spawnId: String,
            current: CrownFix,
            previous: CrownFix,
            idempotencyKey: String,
        ): CrownSpawnClaimOutcome {
            claimCalls += 1
            lastIdempotencyKey = idempotencyKey
            claimFailure?.let { throw it }
            return claimResult
                ?: CrownSpawnClaimOutcome(CrownSpawnClaimResult.AWARDED, 10, 110, CrownRarity.COMMON)
        }
    }

    private fun spawn(
        id: String = "s1",
        lat: Double = 57.4870,
        lon: Double = 12.0760,
        rarity: CrownRarity = CrownRarity.COMMON,
    ) = CrownSpawn(
        id = id,
        latitude = lat,
        longitude = lon,
        rarity = rarity,
        rewardPoints = rarity.rewardPoints,
        collectRadiusMeters = CrownSpawnLimits.COLLECT_RADIUS_METERS,
        expiresAtMillis = null,
    )

    private val centre = CrownQueryCenter(latitude = 57.4870, longitude = 12.0760)

    // ---- Off means OFF ----------------------------------------------------

    /**
     * The headline guarantee: with the flag off, a single refresh touches the
     * repository zero times.
     *
     * Note what is NOT asserted here — that the returned list is empty. An
     * implementation that queried and then discarded the rows would satisfy
     * "shows nothing" and still cost the reads. The assertion is on the CALL
     * COUNT, because that is the thing that costs money and battery.
     */
    @Test
    fun `a disabled feature issues no query at all`() = runTest {
        val repo = FakeRepo(spawns = listOf(spawn()))
        val controller = CrownSpawnController(repo)

        val queried =
            controller.refreshOnce(
                enabled = false,
                center = centre,
                visibleRadiusMeters = 1_000.0,
                force = true,
            )

        assertFalse("a disabled refresh must report that it did nothing", queried)
        assertEquals("the repository must not be touched at all", 0, repo.listCalls)
        assertTrue(controller.nearbySpawns.value.isEmpty())
    }

    /**
     * The same guarantee for the LOOP, which is what actually runs on the map:
     * a disabled feature spins no queries and no timers — it returns.
     *
     * Driven with a virtual clock across ten minutes, which is long enough for
     * every phase-1 retry AND several keep-alive intervals to have fired if the
     * gate were only cosmetic.
     */
    @Test
    fun `a disabled feature runs no poll loop and starts no timers`() = runTest {
        val repo = FakeRepo(spawns = listOf(spawn()))
        val controller = CrownSpawnController(repo)

        val job =
            backgroundScope.launch {
                controller.pollNearby(
                    enabledProvider = { false },
                    centerProvider = { centre },
                    radiusProvider = { 1_000.0 },
                    pollIntervalMs = 60_000L,
                    initialRetryMs = 3_000L,
                    initialAttempts = 5,
                )
            }

        runCurrent()
        advanceTimeBy(600_000L)
        runCurrent()

        assertEquals("ten minutes of a disabled layer must cost zero reads", 0, repo.listCalls)
        assertTrue("the loop must return rather than idle", job.isCompleted)
    }

    /**
     * A flag flipped OFF mid-session stops the traffic on the next pass AND
     * takes the crowns off the map.
     *
     * Leaving them painted would be the worse failure of the two: a crown is
     * claimed once globally, so a frozen layer is a set of markers that may
     * already belong to someone else — an invitation to drive to nothing.
     */
    @Test
    fun `turning the flag off mid-session stops querying and clears the layer`() = runTest {
        val repo = FakeRepo(spawns = listOf(spawn()))
        val controller = CrownSpawnController(repo)
        var enabled = true

        backgroundScope.launch {
            controller.pollNearby(
                enabledProvider = { enabled },
                centerProvider = { centre },
                radiusProvider = { 1_000.0 },
                pollIntervalMs = 60_000L,
                initialRetryMs = 3_000L,
                initialAttempts = 5,
            )
        }

        runCurrent()
        assertEquals(1, repo.listCalls)
        assertEquals(1, controller.nearbySpawns.value.size)

        enabled = false
        advanceTimeBy(60_000L)
        runCurrent()
        val callsAtSwitchOff = repo.listCalls

        assertTrue("the layer must be taken down", controller.nearbySpawns.value.isEmpty())

        // And it stays down, without further reads, for as long as we care to wait.
        advanceTimeBy(600_000L)
        runCurrent()
        assertEquals(callsAtSwitchOff, repo.listCalls)
    }

    /**
     * A flag PROVIDER that throws reads as off, not as on.
     *
     * The opposite of `FeatureFlagsStore`'s general "flags never fail off" rule,
     * and deliberately so: that rule protects features whose contract default is
     * ON from a config outage. This one's default is OFF, so failing it open
     * would start querying a feature nobody switched on.
     */
    @Test
    fun `a failing flag read is treated as off`() = runTest {
        val repo = FakeRepo(spawns = listOf(spawn()))
        val controller = CrownSpawnController(repo)

        backgroundScope.launch {
            controller.pollNearby(
                enabledProvider = { error("config unavailable") },
                centerProvider = { centre },
                radiusProvider = { 1_000.0 },
                pollIntervalMs = 60_000L,
            )
        }
        runCurrent()
        advanceTimeBy(600_000L)
        runCurrent()

        assertEquals(0, repo.listCalls)
    }

    // ---- Cadence and battery ----------------------------------------------

    /** With the flag on, the layer refreshes on the keep-alive cadence. */
    @Test
    fun `an enabled layer refreshes on the keep-alive cadence`() = runTest {
        val repo = FakeRepo(spawns = listOf(spawn()))
        val controller = CrownSpawnController(repo)
        // The camera moves each pass, so shouldRequery lets every tick through
        // and the cadence itself is what is being measured.
        var lon = 12.0
        backgroundScope.launch {
            controller.pollNearby(
                enabledProvider = { true },
                centerProvider = {
                    lon += 0.05
                    CrownQueryCenter(latitude = 57.5, longitude = lon)
                },
                radiusProvider = { 1_000.0 },
                pollIntervalMs = 60_000L,
            )
        }

        runCurrent()
        assertEquals(1, repo.listCalls)
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(2, repo.listCalls)
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(3, repo.listCalls)
    }

    /**
     * The keep-alive is deliberately SLOWER than the incidents layer's.
     *
     * The spawner runs every 10 minutes and the shortest crown lives 6 hours, so
     * a faster poll has nothing to find; a parked phone with the map open costs
     * one small indexed query a minute rather than four.
     */
    @Test
    fun `the crown keep-alive is slower than the incident layer's`() {
        assertEquals(60_000L, CrownSpawnController.DEFAULT_POLL_INTERVAL_MS)
        assertTrue(
            "a crown poll must not be as eager as an incident poll",
            CrownSpawnController.DEFAULT_POLL_INTERVAL_MS >=
                4 * com.kungsbackacarcommunity.app.incidents.IncidentReportController
                    .DEFAULT_POLL_INTERVAL_MS,
        )
    }

    /**
     * A camera settle that lands on the SAME cells costs nothing. This is what
     * keeps nudging the map around a car park from costing a fresh fan-out of
     * queries a nudge.
     */
    @Test
    fun `a settle inside the same cells is not re-queried`() = runTest {
        val repo = FakeRepo(spawns = listOf(spawn()))
        val controller = CrownSpawnController(repo)

        assertTrue(controller.refreshOnce(true, centre, 500.0, force = true))
        assertEquals(1, repo.listCalls)

        // A few metres away — same cell, same neighbours, byte-identical result.
        val nudged = CrownQueryCenter(centre.latitude + 0.00005, centre.longitude + 0.00005)
        assertFalse(controller.refreshOnce(true, nudged, 500.0))
        assertEquals("a nudge must not cost a read", 1, repo.listCalls)

        // A real pan into new cells does query.
        val moved = CrownQueryCenter(centre.latitude + 0.05, centre.longitude)
        assertTrue(controller.refreshOnce(true, moved, 500.0))
        assertEquals(2, repo.listCalls)
    }

    /** No centre yet (cold open, no camera, no fix) is a no-op, not a blind query. */
    @Test
    fun `no centre means no query`() = runTest {
        val repo = FakeRepo()
        val controller = CrownSpawnController(repo)
        assertFalse(controller.refreshOnce(true, null, 1_000.0, force = true))
        assertEquals(0, repo.listCalls)
    }

    /**
     * A transient failure keeps the last-known crowns rather than blanking the
     * layer — an empty map is indistinguishable from "there are no crowns here",
     * which is a lie the user cannot detect.
     */
    @Test
    fun `a failed refresh keeps the crowns already drawn`() = runTest {
        val repo = FakeRepo(spawns = listOf(spawn()))
        val controller = CrownSpawnController(repo)

        assertTrue(controller.refreshOnce(true, centre, 1_000.0, force = true))
        assertEquals(1, controller.nearbySpawns.value.size)

        repo.failWith = IllegalStateException("offline")
        val moved = CrownQueryCenter(centre.latitude + 0.05, centre.longitude)
        assertFalse(controller.refreshOnce(true, moved, 1_000.0))
        assertEquals(
            "an outage must not blank the layer",
            1,
            controller.nearbySpawns.value.size,
        )
    }

    /** A camera-idle pulse refreshes without waiting for the keep-alive. */
    @Test
    fun `a camera-idle pulse refreshes immediately`() = runTest {
        val repo = FakeRepo(spawns = listOf(spawn()))
        val controller = CrownSpawnController(repo)
        val ticks = Channel<Unit>(Channel.CONFLATED)
        var lon = 12.0

        backgroundScope.launch {
            controller.pollNearby(
                enabledProvider = { true },
                centerProvider = {
                    CrownQueryCenter(latitude = 57.5, longitude = lon)
                },
                radiusProvider = { 1_000.0 },
                pollIntervalMs = 60_000L,
                requeryTicks = ticks,
            )
        }
        runCurrent()
        assertEquals(1, repo.listCalls)

        // The camera settles somewhere new, well inside the keep-alive window.
        lon = 12.4
        ticks.trySend(Unit)
        advanceTimeBy(100L)
        runCurrent()
        assertEquals("an idle pulse must not wait for the keep-alive", 2, repo.listCalls)
    }

    // ---- Collecting -------------------------------------------------------

    /**
     * Without a usable proof PAIR the app says "wait a moment" locally instead of
     * spending a round-trip to be told `must_be_stationary` — which would read as
     * a refusal when it was really an impatient tap.
     */
    @Test
    fun `an unusable proof pair never reaches the backend`() = runTest {
        val repo = FakeRepo()
        val controller = CrownSpawnController(repo)
        val now = 1_000_000L
        val current = CrownFix(57.5, 12.0, now)

        // No fixes at all.
        controller.collect(spawn(), null, null, "key")
        assertEquals(CrownClaimStatus.NeedsPosition, controller.claimStatus.value)
        assertEquals(0, repo.claimCalls)

        // A pair that is too tight to prove a dwell.
        controller.resetClaim()
        controller.collect(spawn(), current, CrownFix(57.5, 12.0, now - 500), "key")
        assertEquals(CrownClaimStatus.NeedsPosition, controller.claimStatus.value)
        assertEquals(0, repo.claimCalls)
    }

    /**
     * A successful claim removes the crown from the layer straight away.
     *
     * It is claimed once GLOBALLY, so waiting for the next refresh to notice
     * would leave a collectable-looking marker on the map for up to a minute.
     */
    @Test
    fun `an awarded crown leaves the map immediately`() = runTest {
        val repo = FakeRepo(spawns = listOf(spawn("gone"), spawn("stays")))
        val controller = CrownSpawnController(repo)
        controller.refreshOnce(true, centre, 1_000.0, force = true)
        assertEquals(2, controller.nearbySpawns.value.size)

        val now = 1_000_000L
        controller.collect(
            spawn = spawn("gone"),
            current = CrownFix(57.5, 12.0, now),
            previous = CrownFix(57.5, 12.0, now - 10_000),
            idempotencyKey = "k1",
        )

        assertEquals(1, repo.claimCalls)
        assertEquals(listOf("stays"), controller.nearbySpawns.value.map { it.id })
        val status = controller.claimStatus.value
        assertTrue(status is CrownClaimStatus.Done)
        assertEquals(
            CrownSpawnClaimResult.AWARDED,
            (status as CrownClaimStatus.Done).outcome.result,
        )
    }

    /**
     * Losing the race, and having the crown expire, ALSO take it off the map —
     * both mean it is no longer collectable by anyone, and a marker left behind
     * would invite the user to try again for something that does not exist.
     */
    @Test
    fun `a crown someone else took, or one that expired, also leaves the map`() = runTest {
        for (result in
            listOf(CrownSpawnClaimResult.ALREADY_TAKEN, CrownSpawnClaimResult.CROWN_EXPIRED)) {
            val repo =
                FakeRepo(
                    spawns = listOf(spawn("gone")),
                    claimResult = CrownSpawnClaimOutcome(result, null, null, null),
                )
            val controller = CrownSpawnController(repo)
            controller.refreshOnce(true, centre, 1_000.0, force = true)

            val now = 1_000_000L
            controller.collect(
                spawn = spawn("gone"),
                current = CrownFix(57.5, 12.0, now),
                previous = CrownFix(57.5, 12.0, now - 10_000),
                idempotencyKey = "k1",
            )
            assertTrue("$result must clear the marker", controller.nearbySpawns.value.isEmpty())
        }
    }

    /**
     * A REFUSAL that leaves the crown collectable (too far, still moving) must
     * NOT take it off the map — the member is expected to try again.
     */
    @Test
    fun `a refusal that leaves the crown collectable keeps it on the map`() = runTest {
        val repo =
            FakeRepo(
                spawns = listOf(spawn("still-there")),
                claimResult =
                    CrownSpawnClaimOutcome(
                        CrownSpawnClaimResult.MUST_BE_STATIONARY,
                        null,
                        null,
                        null,
                    ),
            )
        val controller = CrownSpawnController(repo)
        controller.refreshOnce(true, centre, 1_000.0, force = true)

        val now = 1_000_000L
        controller.collect(
            spawn = spawn("still-there"),
            current = CrownFix(57.5, 12.0, now),
            previous = CrownFix(57.5, 12.0, now - 10_000),
            idempotencyKey = "k1",
        )
        assertEquals(listOf("still-there"), controller.nearbySpawns.value.map { it.id })
    }

    /** A transport failure says "something went wrong", never judging the user. */
    @Test
    fun `a transport failure is a failure, not a refusal`() = runTest {
        val repo = FakeRepo(claimFailure = IllegalStateException("offline"))
        val controller = CrownSpawnController(repo)
        val now = 1_000_000L
        controller.collect(
            spawn = spawn(),
            current = CrownFix(57.5, 12.0, now),
            previous = CrownFix(57.5, 12.0, now - 10_000),
            idempotencyKey = "k1",
        )
        assertEquals(CrownClaimStatus.Failed, controller.claimStatus.value)
    }
}
