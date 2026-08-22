package com.kungsbackacarcommunity.app.crownhunt

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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
        var collectedFlow: Flow<Map<String, Long?>> = emptyFlow(),
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

        override fun observeCollected(uid: String): Flow<Map<String, Long?>> = collectedFlow
    }

    /**
     * In-memory [CollectedCrownStore] standing in for SharedPreferences: keeps a
     * per-uid snapshot so "survives an app restart" is modelled as a fresh
     * controller reading the same store, and "uid-scoped" as two distinct keys.
     */
    private class FakeCollectedStore(
        initial: Map<String, Map<String, Long?>> = emptyMap(),
    ) : CollectedCrownStore {
        val byUid = HashMap<String, Map<String, Long?>>(initial)
        var loads = 0
        var saves = 0

        override fun load(uid: String): Map<String, Long?> {
            loads += 1
            return byUid[uid] ?: emptyMap()
        }

        override fun save(uid: String, entries: Map<String, Long?>) {
            saves += 1
            byUid[uid] = entries.toMap()
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
     * Collecting an EXCLUSIVE crown (rare/legendary) removes it from the layer
     * straight away: it is claimed once GLOBALLY and gone for everyone, so waiting
     * for the next refresh to notice would leave a collectable-looking marker on
     * the map for up to a minute.
     */
    @Test
    fun `an awarded exclusive crown leaves the map immediately`() = runTest {
        val repo =
            FakeRepo(
                spawns =
                    listOf(spawn("gone", rarity = CrownRarity.RARE), spawn("stays")),
                claimResult =
                    CrownSpawnClaimOutcome(
                        CrownSpawnClaimResult.AWARDED,
                        100,
                        210,
                        CrownRarity.RARE,
                    ),
            )
        val controller = CrownSpawnController(repo)
        controller.refreshOnce(true, centre, 1_000.0, force = true)
        assertEquals(2, controller.nearbySpawns.value.size)

        val now = 1_000_000L
        controller.collect(
            spawn = spawn("gone", rarity = CrownRarity.RARE),
            current = CrownFix(57.5, 12.0, now),
            previous = CrownFix(57.5, 12.0, now - 10_000),
            idempotencyKey = "k1",
        )

        assertEquals(1, repo.claimCalls)
        assertEquals(listOf("stays"), controller.nearbySpawns.value.map { it.id })
        assertTrue(
            "an exclusive crown is gone for everyone, not marked collected",
            controller.collectedSpawnIds.value.isEmpty(),
        )
        val status = controller.claimStatus.value
        assertTrue(status is CrownClaimStatus.Done)
        assertEquals(
            CrownSpawnClaimResult.AWARDED,
            (status as CrownClaimStatus.Done).outcome.result,
        )
    }

    /**
     * The SERVER's rarity wins over a stale local one. If the local spawn says
     * COMMON (shared) but the awarded outcome carries RARE (exclusive) — a
     * mismatched or out-of-date local document — the crown must be DROPPED, not
     * kept and marked: an exclusive crown is gone for everyone, and trusting the
     * local rarity would strand a "collected" marker on a crown nobody can take.
     */
    @Test
    fun `an awarded crown follows the server rarity, not a stale local one`() = runTest {
        val staleLocalShared = spawn("mismatch", rarity = CrownRarity.COMMON)
        val repo =
            FakeRepo(
                spawns = listOf(staleLocalShared),
                claimResult =
                    CrownSpawnClaimOutcome(
                        CrownSpawnClaimResult.AWARDED,
                        100,
                        210,
                        // Server says this was an EXCLUSIVE rare crown.
                        CrownRarity.RARE,
                    ),
            )
        val controller = CrownSpawnController(repo)
        controller.refreshOnce(true, centre, 1_000.0, force = true)

        val now = 1_000_000L
        controller.collect(
            spawn = staleLocalShared,
            current = CrownFix(57.5, 12.0, now),
            previous = CrownFix(57.5, 12.0, now - 10_000),
            idempotencyKey = "k1",
        )

        assertTrue(
            "an exclusive award (per the server) drops the crown despite a stale local shared rarity",
            controller.nearbySpawns.value.isEmpty(),
        )
        assertTrue(
            "and it is never marked collected-by-you",
            controller.collectedSpawnIds.value.isEmpty(),
        )
    }

    /**
     * Collecting a SHARED crown (common/uncommon) for the FIRST time returns
     * `awarded`, but the crown stays `live` for OTHER members — so it must be KEPT
     * on the map and marked collected-by-you, exactly like a later re-tap. Dropping
     * it on `awarded` would make it flicker away and reappear looking collectable
     * on the very next refresh, which is the confusion this feature removes.
     */
    @Test
    fun `an awarded shared crown is kept and marked collected`() = runTest {
        val shared = spawn("shared", rarity = CrownRarity.COMMON) // expiresAtMillis = null
        val repo =
            FakeRepo(
                spawns = listOf(shared),
                claimResult =
                    CrownSpawnClaimOutcome(
                        CrownSpawnClaimResult.AWARDED,
                        10,
                        110,
                        CrownRarity.COMMON,
                    ),
            )
        val controller = CrownSpawnController(repo)
        controller.refreshOnce(true, centre, 1_000.0, force = true)

        val now = 1_000_000L
        controller.collect(
            spawn = shared,
            current = CrownFix(57.5, 12.0, now),
            previous = CrownFix(57.5, 12.0, now - 10_000),
            idempotencyKey = "k1",
        )

        // Kept on the map and flagged collected — the distinct marker appears on
        // the first collect, not only after a redundant re-tap.
        assertEquals(
            listOf("shared"),
            controller.nearbySpawns.value.map { it.id },
        )
        assertEquals(setOf("shared"), controller.collectedSpawnIds.value)

        // And it stays marked across a refresh, since it is still live for others.
        controller.refreshOnce(true, centre, 1_000.0, force = true)
        assertEquals(
            listOf("shared"),
            controller.nearbySpawns.value.map { it.id },
        )
        assertEquals(setOf("shared"), controller.collectedSpawnIds.value)
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

    /**
     * Re-tapping a SHARED crown you already collected is a benign, EXPECTED
     * outcome (the crown stays on the map for others), not a transport failure.
     * It must surface as a Done result — so the popup shows "you already got this
     * one" — and the crown must STAY on the map, now tracked as collected-by-you
     * so the layer can draw it with the distinct "collected" marker (revises #874,
     * which HID it instead). Regression for #874's parse fix too: the missing enum
     * value once made the response fail to parse and show a generic error.
     */
    @Test
    fun `already collected keeps the crown on the map and marks it collected`() = runTest {
        val repo =
            FakeRepo(
                spawns = listOf(spawn("shared")),
                claimResult =
                    CrownSpawnClaimOutcome(
                        CrownSpawnClaimResult.ALREADY_COLLECTED,
                        null,
                        null,
                        CrownRarity.COMMON,
                    ),
            )
        val controller = CrownSpawnController(repo)
        controller.refreshOnce(true, centre, 1_000.0, force = true)

        val now = 1_000_000L
        controller.collect(
            spawn = spawn("shared"),
            current = CrownFix(57.5, 12.0, now),
            previous = CrownFix(57.5, 12.0, now - 10_000),
            idempotencyKey = "k1",
        )

        val status = controller.claimStatus.value
        assertTrue("already_collected must not be a transport failure", status is CrownClaimStatus.Done)
        assertEquals(
            CrownSpawnClaimResult.ALREADY_COLLECTED,
            (status as CrownClaimStatus.Done).outcome.result,
        )
        // Kept, not hidden — and flagged so the layer draws the "collected" marker.
        assertEquals(
            "a collected shared crown stays on the map for others",
            listOf("shared"),
            controller.nearbySpawns.value.map { it.id },
        )
        assertEquals(
            "the crown must be flagged collected-by-you",
            setOf("shared"),
            controller.collectedSpawnIds.value,
        )
    }

    /**
     * The collected mark must SURVIVE a refresh. A shared crown the user collected
     * is still `live` for others, so listNearby keeps returning it — and every
     * refresh must keep it flagged collected-by-you so the distinct marker sticks
     * rather than reverting to a collectable-looking crown (revises #874).
     */
    @Test
    fun `a refresh keeps a collected crown on the map and still marked`() = runTest {
        val shared = spawn("shared") // expiresAtMillis = null → never pruned
        val repo =
            FakeRepo(
                spawns = listOf(shared),
                claimResult =
                    CrownSpawnClaimOutcome(
                        CrownSpawnClaimResult.ALREADY_COLLECTED,
                        null,
                        null,
                        CrownRarity.COMMON,
                    ),
            )
        val controller = CrownSpawnController(repo)
        controller.refreshOnce(true, centre, 1_000.0, force = true)

        val now = 1_000_000L
        controller.collect(
            spawn = shared,
            current = CrownFix(57.5, 12.0, now),
            previous = CrownFix(57.5, 12.0, now - 10_000),
            idempotencyKey = "k1",
        )
        assertEquals(setOf("shared"), controller.collectedSpawnIds.value)

        // The crown is STILL returned by the backend (it is live for others); the
        // refresh keeps it on the map AND keeps it flagged collected-by-you.
        controller.refreshOnce(true, centre, 1_000.0, force = true)
        assertEquals(
            "a collected shared crown stays on the map across refreshes",
            listOf("shared"),
            controller.nearbySpawns.value.map { it.id },
        )
        assertEquals(
            "the collected flag survives a refresh",
            setOf("shared"),
            controller.collectedSpawnIds.value,
        )
    }

    /**
     * The collected set self-prunes: once a collected crown's own expiry has
     * passed it is gone for everyone (the backend stops returning it), so the flag
     * is forgotten and the set never grows for the life of the session.
     */
    @Test
    fun `the collected flag is forgotten once the crown expires`() = runTest {
        var clock = 1_000_000L
        val expiring =
            CrownSpawn(
                id = "expiring",
                latitude = 57.4870,
                longitude = 12.0760,
                rarity = CrownRarity.COMMON,
                rewardPoints = CrownRarity.COMMON.rewardPoints,
                collectRadiusMeters = CrownSpawnLimits.COLLECT_RADIUS_METERS,
                expiresAtMillis = 2_000_000L,
            )
        val repo =
            FakeRepo(
                spawns = listOf(expiring),
                claimResult =
                    CrownSpawnClaimOutcome(
                        CrownSpawnClaimResult.ALREADY_COLLECTED,
                        null,
                        null,
                        CrownRarity.COMMON,
                    ),
            )
        val controller = CrownSpawnController(repo, nowMillis = { clock })
        controller.refreshOnce(true, centre, 1_000.0, force = true)
        controller.collect(
            spawn = expiring,
            current = CrownFix(57.5, 12.0, clock),
            previous = CrownFix(57.5, 12.0, clock - 10_000),
            idempotencyKey = "k1",
        )
        // Before expiry: on the map and flagged collected.
        controller.refreshOnce(true, centre, 1_000.0, force = true)
        assertEquals(setOf("expiring"), controller.collectedSpawnIds.value)

        // After the crown's own expiry the flag is pruned — nothing is left to
        // grow, and the crown is gone for everyone anyway.
        clock = 2_000_001L
        controller.refreshOnce(true, centre, 1_000.0, force = true)
        assertTrue(
            "the collected flag is dropped once the crown expires",
            controller.collectedSpawnIds.value.isEmpty(),
        )
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

    // ---- Durable, uid-scoped collected-set (owner bug: mark vanishes on restart) --

    /**
     * The reported bug: after collecting a shared crown, closing the app, and
     * reopening, the "collected by you" mark was GONE until a refused re-tap
     * re-learned it. The mark must survive a process restart — modelled here as a
     * FRESH controller reading the SAME store, with NO tap.
     */
    @Test
    fun `a collected mark is persisted and reappears in a new controller without a re-tap`() =
        runTest {
            val store = FakeCollectedStore()
            val shared = spawn("shared") // expiresAtMillis = null → never pruned

            // Session 1: collect the shared crown, which persists the mark.
            val repo1 =
                FakeRepo(
                    spawns = listOf(shared),
                    claimResult =
                        CrownSpawnClaimOutcome(
                            CrownSpawnClaimResult.ALREADY_COLLECTED,
                            null,
                            null,
                            CrownRarity.COMMON,
                        ),
                    collectedFlow = emptyFlow(),
                )
            val controller1 = CrownSpawnController(repo1, collectedStore = store)
            controller1.bindUser("u1")
            controller1.refreshOnce(true, centre, 1_000.0, force = true)
            val now = 1_000_000L
            controller1.collect(
                spawn = shared,
                current = CrownFix(57.5, 12.0, now),
                previous = CrownFix(57.5, 12.0, now - 10_000),
                idempotencyKey = "k1",
            )
            assertEquals(setOf("shared"), controller1.collectedSpawnIds.value)

            // Session 2: a brand-new controller (fresh process) with the SAME store.
            // Before any refresh or tap, the mark must already be there.
            val controller2 = CrownSpawnController(FakeRepo(), collectedStore = store)
            controller2.bindUser("u1")
            assertEquals(
                "the collected mark must survive a restart with no re-tap",
                setOf("shared"),
                controller2.collectedSpawnIds.value,
            )
        }

    /**
     * The persisted set is UID-scoped: account B on the same device must never see
     * account A's collected marks, and switching back to A restores A's.
     */
    @Test
    fun `the persisted collected-set is uid-scoped`() = runTest {
        val store = FakeCollectedStore()
        val shared = spawn("shared")
        val repo =
            FakeRepo(
                spawns = listOf(shared),
                claimResult =
                    CrownSpawnClaimOutcome(
                        CrownSpawnClaimResult.ALREADY_COLLECTED,
                        null,
                        null,
                        CrownRarity.COMMON,
                    ),
            )
        val controller = CrownSpawnController(repo, collectedStore = store)

        controller.bindUser("A")
        controller.refreshOnce(true, centre, 1_000.0, force = true)
        val now = 1_000_000L
        controller.collect(
            spawn = shared,
            current = CrownFix(57.5, 12.0, now),
            previous = CrownFix(57.5, 12.0, now - 10_000),
            idempotencyKey = "k1",
        )
        assertEquals(setOf("shared"), controller.collectedSpawnIds.value)

        // Switch to B: a fresh account starts empty, never inheriting A's marks.
        controller.bindUser("B")
        assertTrue(
            "account B must not see account A's collected marks",
            controller.collectedSpawnIds.value.isEmpty(),
        )

        // Back to A: A's marks are restored from durable storage.
        controller.bindUser("A")
        assertEquals(setOf("shared"), controller.collectedSpawnIds.value)
    }

    /**
     * On load, an entry whose crown has already expired is pruned — the same
     * self-clean the in-memory path does — so a stale mark never lingers and the
     * stored blob shrinks too.
     */
    @Test
    fun `bindUser prunes an already-expired entry loaded from disk`() = runTest {
        val clock = 5_000_000L
        val store =
            FakeCollectedStore(
                initial =
                    mapOf(
                        "u1" to
                            mapOf(
                                "expired" to 1_000_000L, // < clock → pruned
                                "live" to 9_000_000L, // > clock → kept
                            ),
                    ),
            )
        val controller = CrownSpawnController(FakeRepo(), nowMillis = { clock }, collectedStore = store)

        controller.bindUser("u1")

        assertEquals(
            "an expired entry must be pruned on load",
            setOf("live"),
            controller.collectedSpawnIds.value,
        )
        assertEquals(
            "the self-cleaned set must be written back to storage",
            mapOf<String, Long?>("live" to 9_000_000L),
            store.byUid["u1"],
        )
    }

    /**
     * Server-authoritative recovery: after a reinstall / on a new device the local
     * cache starts empty, but the `crownSpawnCollectors` listener supplies the
     * truth — the mark appears with no tap, and is written through so the next cold
     * start is already correct.
     */
    @Test
    fun `the server-authoritative set recovers a mark the empty cache lacks`() = runTest {
        val clock = 1_000L
        val store = FakeCollectedStore() // fresh install: nothing cached
        // A single-shot flow standing in for the (in prod, infinite) listener: it
        // emits one snapshot and completes, so awaiting the sync returns.
        val repo = FakeRepo(collectedFlow = flowOf(mapOf("srv" to 9_000_000L)))
        val controller = CrownSpawnController(repo, nowMillis = { clock }, collectedStore = store)

        controller.syncCollectedForUser("u1")

        assertEquals(
            "the server truth must mark a crown the empty cache never knew about",
            setOf("srv"),
            controller.collectedSpawnIds.value,
        )
        assertEquals(
            "and it must be written through to the cache for the next cold start",
            mapOf<String, Long?>("srv" to 9_000_000L),
            store.byUid["u1"],
        )
    }

    /**
     * Privacy + no-drop, proven with a paused load dispatcher that holds the
     * switch's prefs read open so we can observe the window:
     *
     *  - account A's marks are cleared the INSTANT we switch to B, BEFORE B's load
     *    runs — never visible on B's map for the width of the (cold) read; and
     *  - a crown collected DURING that load window is MERGED, not overwritten, so
     *    B's in-flight pickup is still there once the load completes.
     */
    @Test
    fun `bindUser clears the prior account before load and keeps a mark collected during it`() =
        runTest {
            // A has a stored mark; B has nothing stored yet (a fresh account).
            val store = FakeCollectedStore(initial = mapOf("A" to mapOf("aCrown" to null)))
            // The load runs on a PAUSED dispatcher so we can act inside the window.
            val controller =
                CrownSpawnController(
                    FakeRepo(
                        spawns = listOf(spawn("bWindow")),
                        claimResult =
                            CrownSpawnClaimOutcome(
                                CrownSpawnClaimResult.ALREADY_COLLECTED,
                                null,
                                null,
                                CrownRarity.COMMON,
                            ),
                    ),
                    nowMillis = { 1_000L },
                    collectedStore = store,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            // Account A is bound with its mark.
            controller.bindUser("A")
            advanceUntilIdle()
            assertEquals(setOf("aCrown"), controller.collectedSpawnIds.value)

            // Switch to B: bindUser reaches the load and suspends (dispatcher paused).
            val job = launch { controller.bindUser("B") }
            runCurrent()
            assertTrue(
                "account A's marks must be cleared the instant we switch, before B's load lands",
                controller.collectedSpawnIds.value.isEmpty(),
            )

            // A crown collected DURING the load window (B is already the bound uid).
            controller.refreshOnce(true, centre, 1_000.0, force = true)
            controller.collect(
                spawn = spawn("bWindow"),
                current = CrownFix(57.5, 12.0, 1_000_000L),
                previous = CrownFix(57.5, 12.0, 990_000L),
                idempotencyKey = "k",
            )
            assertTrue(controller.collectedSpawnIds.value.contains("bWindow"))

            // The load completes and MERGES — the in-flight mark is not dropped, and
            // account A's mark never reappears for B.
            advanceUntilIdle()
            job.join()
            assertEquals(
                "B's mark collected during the load survives the merge",
                setOf("bWindow"),
                controller.collectedSpawnIds.value,
            )
            assertFalse(
                "account A's mark must never appear for account B",
                controller.collectedSpawnIds.value.contains("aCrown"),
            )
        }
}
