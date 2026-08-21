package com.kungsbackacarcommunity.app.crownhunt

import com.kungsbackacarcommunity.app.navigation.LatLng
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Kronjakt DEPLOY pure logic (Crown Hunt Shop PR4): the
 * inventory + active-effect → menu-state derivation, the eligibility rules, the
 * countdown maths, and the deploy coordinator's in-flight guard, location
 * resolution, session-window tracking + error mapping. Firebase-free.
 */
class PerkDeployTest {

    private val spike =
        PerkCatalogEntry("spike_strip", PerkKind.TRAP, "Spikmatta", "perk_spike_strip", 150, "…")
    private val shield =
        PerkCatalogEntry("shield", PerkKind.SHIELD, "Sköld", "perk_shield", 100, "…")
    private val boost =
        PerkCatalogEntry("boost", PerkKind.BOOST, "Dubbla Poäng", "perk_boost", 120, "…")
    private val catalog = PerkCatalogState.Loaded(listOf(spike, shield, boost))

    private val now = 1_000_000L

    private fun menu(
        inventory: Map<String, Long>,
        shieldUntil: Long? = null,
        boostUntil: Long? = null,
        trapCount: Int = 0,
        nowMillis: Long = now,
    ): PerkDeployMenuState.Loaded =
        PerkDeploy.toMenuState(
            catalog = catalog,
            inventory = inventory,
            shieldActiveUntilMillis = shieldUntil,
            boostActiveUntilMillis = boostUntil,
            activeTrapCount = trapCount,
            nowMillis = nowMillis,
        ) as PerkDeployMenuState.Loaded

    private fun PerkDeployMenuState.Loaded.item(perkId: String) =
        items.first { it.perkId == perkId }

    // ---- catalog state domination -------------------------------------------

    @Test
    fun `loading or error catalog dominates regardless of inventory or effects`() {
        assertEquals(
            PerkDeployMenuState.Loading,
            PerkDeploy.toMenuState(PerkCatalogState.Loading, mapOf("shield" to 9L), 9L, 9L, 3, now),
        )
        assertEquals(
            PerkDeployMenuState.Error,
            PerkDeploy.toMenuState(PerkCatalogState.Error, mapOf("shield" to 9L), 9L, 9L, 3, now),
        )
    }

    @Test
    fun `menu preserves catalog order and defaults absent owned counts to zero`() {
        val loaded = menu(inventory = mapOf("shield" to 2L))
        assertEquals(listOf("spike_strip", "shield", "boost"), loaded.items.map { it.perkId })
        assertEquals(0L, loaded.item("spike_strip").ownedCount)
        assertEquals(2L, loaded.item("shield").ownedCount)
    }

    @Test
    fun `a negative inventory value is clamped to zero`() {
        val loaded = menu(inventory = mapOf("boost" to -5L))
        assertEquals(0L, loaded.item("boost").ownedCount)
        assertFalse(loaded.item("boost").activatable)
    }

    // ---- trap eligibility ---------------------------------------------------

    @Test
    fun `trap is activatable when owned and below the active-trap cap`() {
        val trap = menu(inventory = mapOf("spike_strip" to 1L), trapCount = 0).item("spike_strip")
        assertTrue(trap.activatable)
        assertFalse(trap.active)
        assertEquals(0, trap.activeTrapCount)
        assertNull(trap.activeUntilMillis)
    }

    @Test
    fun `trap is blocked at the one-active-trap cap and reports the active count`() {
        val trap = menu(inventory = mapOf("spike_strip" to 3L), trapCount = 1).item("spike_strip")
        assertFalse(trap.activatable)
        assertTrue(trap.active)
        assertEquals(1, trap.activeTrapCount)
    }

    @Test
    fun `trap is not activatable when the member owns none`() {
        val trap = menu(inventory = emptyMap(), trapCount = 0).item("spike_strip")
        assertFalse(trap.activatable)
    }

    // ---- shield / boost eligibility -----------------------------------------

    @Test
    fun `shield is activatable when owned and not currently active`() {
        val s = menu(inventory = mapOf("shield" to 1L), shieldUntil = null).item("shield")
        assertTrue(s.activatable)
        assertFalse(s.active)
        assertNull(s.activeUntilMillis)
    }

    @Test
    fun `an active shield is not re-raisable and carries its expiry`() {
        val until = now + 3 * 60 * 60 * 1000L
        val s = menu(inventory = mapOf("shield" to 4L), shieldUntil = until).item("shield")
        assertTrue(s.active)
        assertFalse(s.activatable)
        assertEquals(until, s.activeUntilMillis)
    }

    @Test
    fun `an expired shield window reads as inactive and re-raisable`() {
        val s = menu(inventory = mapOf("shield" to 1L), shieldUntil = now - 1L).item("shield")
        assertFalse(s.active)
        assertTrue(s.activatable)
        assertNull(s.activeUntilMillis)
    }

    @Test
    fun `an active boost is not re-raisable and carries its expiry`() {
        val until = now + 60 * 60 * 1000L
        val b = menu(inventory = mapOf("boost" to 2L), boostUntil = until).item("boost")
        assertTrue(b.active)
        assertFalse(b.activatable)
        assertEquals(until, b.activeUntilMillis)
    }

    @Test
    fun `shield and boost windows do not bleed into each other`() {
        val until = now + 60 * 60 * 1000L
        val loaded = menu(inventory = mapOf("shield" to 1L, "boost" to 1L), boostUntil = until)
        // Only boost is active; the shield stays idle/activatable.
        assertTrue(loaded.item("boost").active)
        assertFalse(loaded.item("shield").active)
        assertTrue(loaded.item("shield").activatable)
    }

    // ---- empty determination (owns-nothing + nothing-active) ----------------

    @Test
    fun `menu is empty when the member owns nothing and has no active effect`() {
        val loaded = menu(inventory = emptyMap())
        assertTrue(loaded.isEmpty)
    }

    @Test
    fun `menu is not empty when the member owns any perk`() {
        assertFalse(menu(inventory = mapOf("shield" to 1L)).isEmpty)
        assertFalse(menu(inventory = mapOf("spike_strip" to 2L)).isEmpty)
    }

    @Test
    fun `menu is not empty when an effect is active even with zero inventory`() {
        // A live shield with nothing owned still has something to show (the
        // countdown), so it must NOT collapse to the buy-guidance.
        assertFalse(menu(inventory = emptyMap(), shieldUntil = now + 1_000L).isEmpty)
        assertFalse(menu(inventory = emptyMap(), boostUntil = now + 1_000L).isEmpty)
        assertFalse(menu(inventory = emptyMap(), trapCount = 1).isEmpty)
    }

    @Test
    fun `an expired effect with zero inventory is still empty`() {
        // The shield window is in the past → not active → nothing to show.
        assertTrue(menu(inventory = emptyMap(), shieldUntil = now - 1L).isEmpty)
    }

    // ---- liveTrapCount against a moving now ---------------------------------

    @Test
    fun `liveTrapCount counts only expiries strictly in the future`() {
        val expiries = listOf(now + 5_000L, now - 1L, now)
        assertEquals(1, PerkDeploy.liveTrapCount(expiries, now))
        assertEquals(0, PerkDeploy.liveTrapCount(emptyList(), now))
    }

    @Test
    fun `a trap expiring while the menu stays open drops out of the count`() {
        // One armed trap expiring at now+10s. As the ticking `now` crosses it,
        // the same expiry list yields 1 → 0 without any Firestore re-emit.
        val expiries = listOf(now + 10_000L)
        assertEquals(1, PerkDeploy.liveTrapCount(expiries, now))
        assertEquals(1, PerkDeploy.liveTrapCount(expiries, now + 9_000L))
        assertEquals(0, PerkDeploy.liveTrapCount(expiries, now + 10_000L))
        assertEquals(0, PerkDeploy.liveTrapCount(expiries, now + 30_000L))
    }

    // ---- isActive / remaining ------------------------------------------------

    @Test
    fun `isActive is strictly future`() {
        assertTrue(PerkDeploy.isActive(now + 1L, now))
        assertFalse(PerkDeploy.isActive(now, now))
        assertFalse(PerkDeploy.isActive(now - 1L, now))
        assertFalse(PerkDeploy.isActive(null, now))
    }

    @Test
    fun `remaining above one minute reports ceiled minutes and seconds`() {
        // 2 min 30 s left → "2 min 30 s".
        assertEquals(
            PerkRemaining.MinutesSeconds(2L, 30L),
            PerkDeploy.remaining(now + 150_000L, now),
        )
        // Exactly one minute → 1 min 0 s (the minutes bucket begins here).
        assertEquals(
            PerkRemaining.MinutesSeconds(1L, 0L),
            PerkDeploy.remaining(now + 60_000L, now),
        )
        // Just over a minute ceils the odd millis UP into the seconds → 1 min 1 s.
        assertEquals(
            PerkRemaining.MinutesSeconds(1L, 1L),
            PerkDeploy.remaining(now + 60_001L, now),
        )
        // The last ~1 s of a sub-minute window ceils into a full minute (correct —
        // ~1 minute really is left); the Composable renders this tidy "1 min".
        assertEquals(
            PerkRemaining.MinutesSeconds(1L, 0L),
            PerkDeploy.remaining(now + 59_001L, now),
        )
        assertEquals(
            PerkRemaining.MinutesSeconds(1L, 0L),
            PerkDeploy.remaining(now + 60_000L - 1L, now),
        )
    }

    @Test
    fun `remaining under one minute reports whole ceiled seconds and never understates`() {
        assertEquals(PerkRemaining.SecondsOnly(45L), PerkDeploy.remaining(now + 45_000L, now))
        // Exactly 59 s stays in the seconds bucket; 59.001 s ceils to 60 → a full
        // minute (asserted above), so 59_000 is the top of the seconds bucket.
        assertEquals(PerkRemaining.SecondsOnly(59L), PerkDeploy.remaining(now + 59_000L, now))
        // Ceiling means a live countdown never UNDERSTATES: 1,999 ms reads "2 s".
        assertEquals(PerkRemaining.SecondsOnly(2L), PerkDeploy.remaining(now + 1_999L, now))
        assertEquals(PerkRemaining.SecondsOnly(2L), PerkDeploy.remaining(now + 1_001L, now))
        // And a live sub-second window never collapses to "0 s".
        assertEquals(PerkRemaining.SecondsOnly(1L), PerkDeploy.remaining(now + 1L, now))
        assertEquals(PerkRemaining.SecondsOnly(1L), PerkDeploy.remaining(now + 1_000L, now))
    }

    @Test
    fun `remaining is Expired at or past the window and for a null expiry`() {
        assertEquals(PerkRemaining.Expired, PerkDeploy.remaining(null, now))
        assertEquals(PerkRemaining.Expired, PerkDeploy.remaining(now, now))
        assertEquals(PerkRemaining.Expired, PerkDeploy.remaining(now - 1L, now))
    }

    // ---- Coordinator: a fake repo + a fake location source ------------------

    private class FakeRepo(
        private val result: PerkDeployResult? = null,
        private val failWith: Exception? = null,
    ) : PerkShopRepository {
        var deploys = 0
        val calls = mutableListOf<Triple<String, Double?, Double?>>()
        val keysSeen = mutableListOf<String>()

        override fun observeCatalog() = throw UnsupportedOperationException()

        override fun observeInventory(uid: String) = throw UnsupportedOperationException()

        override suspend fun buyPerk(perkId: String, idempotencyKey: String) =
            throw UnsupportedOperationException()

        override fun observeShieldActiveUntil(uid: String) = throw UnsupportedOperationException()

        override fun observeActiveTrapExpiries(uid: String) = throw UnsupportedOperationException()

        override fun observeOwnActiveTraps(uid: String) = throw UnsupportedOperationException()

        override suspend fun deployPerk(
            perkId: String,
            latitude: Double?,
            longitude: Double?,
            idempotencyKey: String,
        ): PerkDeployResult {
            calls += Triple(perkId, latitude, longitude)
            keysSeen += idempotencyKey
            failWith?.let { throw it }
            deploys++
            return result
                ?: PerkDeployResult(perkId, PerkKind.TRAP, "eff", 1_001_000L, 0, alreadyDeployed = false)
        }
    }

    private val stockholm = LatLng(longitude = 18.07, latitude = 59.33)

    @Test
    fun `a trap deploy sends the current GPS and surfaces the result`() = runTest {
        val repo =
            FakeRepo(PerkDeployResult("spike_strip", PerkKind.TRAP, "trap_1", now + 6000L, 2, false))
        val coordinator =
            PerkDeployCoordinator(repo, locationSource = { stockholm }, keyFactory = { "k-1" })
        coordinator.deploy("spike_strip", PerkKind.TRAP)
        assertEquals(1, repo.deploys)
        assertEquals(Triple("spike_strip", 59.33, 18.07), repo.calls.single())
        assertEquals(
            PerkDeployStatus.Deployed("spike_strip", PerkKind.TRAP, now + 6000L, 2, false),
            coordinator.status.value,
        )
    }

    @Test
    fun `a trap deploy with no location fix fails locally without a round-trip`() = runTest {
        val repo = FakeRepo()
        val coordinator = PerkDeployCoordinator(repo, locationSource = { null })
        coordinator.deploy("spike_strip", PerkKind.TRAP)
        assertEquals(0, repo.deploys)
        assertEquals(0, repo.calls.size)
        assertEquals(
            PerkDeployStatus.Failed("spike_strip", PerkDeployFailureReason.NO_LOCATION),
            coordinator.status.value,
        )
    }

    @Test
    fun `an invalid location fix is treated as no location`() = runTest {
        val repo = FakeRepo()
        // Out-of-bounds latitude → rejected by the WGS-84 pre-check.
        val coordinator = PerkDeployCoordinator(repo, locationSource = { LatLng(0.0, 999.0) })
        coordinator.deploy("spike_strip", PerkKind.TRAP)
        assertEquals(0, repo.deploys)
        assertEquals(
            PerkDeployStatus.Failed("spike_strip", PerkDeployFailureReason.NO_LOCATION),
            coordinator.status.value,
        )
    }

    @Test
    fun `a shield deploy needs no location and records its session window`() = runTest {
        val repo =
            FakeRepo(PerkDeployResult("shield", PerkKind.SHIELD, "uid", now + 10_800_000L, 1, false))
        // A location source that would throw if it were ever called proves the
        // shield path never touches GPS.
        val coordinator =
            PerkDeployCoordinator(repo, locationSource = { error("location must not be requested") })
        coordinator.deploy("shield", PerkKind.SHIELD)
        assertEquals(Triple("shield", null, null), repo.calls.single())
        assertEquals(now + 10_800_000L, coordinator.shieldActiveUntilMillis.value)
        assertNull(coordinator.boostActiveUntilMillis.value)
    }

    @Test
    fun `a boost deploy records its session window`() = runTest {
        val repo =
            FakeRepo(PerkDeployResult("boost", PerkKind.BOOST, "uid", now + 3_600_000L, 0, false))
        val coordinator = PerkDeployCoordinator(repo, locationSource = { null })
        coordinator.deploy("boost", PerkKind.BOOST)
        assertEquals(now + 3_600_000L, coordinator.boostActiveUntilMillis.value)
        assertNull(coordinator.shieldActiveUntilMillis.value)
    }

    @Test
    fun `unavailable maps to the unavailable reason`() = runTest {
        val coordinator =
            PerkDeployCoordinator(
                FakeRepo(failWith = PerkDeployUnavailableException()),
                locationSource = { stockholm },
            )
        coordinator.deploy("shield", PerkKind.SHIELD)
        assertEquals(
            PerkDeployStatus.Failed("shield", PerkDeployFailureReason.UNAVAILABLE),
            coordinator.status.value,
        )
    }

    @Test
    fun `a server missing-location rejection maps to NO_LOCATION`() = runTest {
        val coordinator =
            PerkDeployCoordinator(
                FakeRepo(failWith = PerkDeployMissingLocationException()),
                locationSource = { stockholm },
            )
        coordinator.deploy("spike_strip", PerkKind.TRAP)
        assertEquals(
            PerkDeployStatus.Failed("spike_strip", PerkDeployFailureReason.NO_LOCATION),
            coordinator.status.value,
        )
    }

    @Test
    fun `a generic failure is UNKNOWN and can reset`() = runTest {
        val coordinator =
            PerkDeployCoordinator(
                FakeRepo(failWith = IllegalStateException("boom")),
                locationSource = { stockholm },
            )
        coordinator.deploy("boost", PerkKind.BOOST)
        assertEquals(
            PerkDeployStatus.Failed("boost", PerkDeployFailureReason.UNKNOWN),
            coordinator.status.value,
        )
        coordinator.reset()
        assertEquals(PerkDeployStatus.Idle, coordinator.status.value)
    }

    @Test
    fun `cancellation is rethrown and leaves Idle`() = runTest {
        val coordinator =
            PerkDeployCoordinator(
                FakeRepo(failWith = CancellationException("c")),
                locationSource = { stockholm },
            )
        var rethrown = false
        try {
            coordinator.deploy("shield", PerkKind.SHIELD)
        } catch (c: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
        assertEquals(PerkDeployStatus.Idle, coordinator.status.value)
    }

    @Test
    fun `a fresh idempotency key is used per deploy`() = runTest {
        var n = 0
        val repo = FakeRepo(PerkDeployResult("boost", PerkKind.BOOST, "uid", now + 1L, 0, false))
        val coordinator =
            PerkDeployCoordinator(repo, locationSource = { null }, keyFactory = { "key-${n++}" })
        coordinator.deploy("boost", PerkKind.BOOST)
        coordinator.reset()
        coordinator.deploy("boost", PerkKind.BOOST)
        assertEquals(listOf("key-0", "key-1"), repo.keysSeen)
    }
}
