package com.kungsbackacarcommunity.app.crownhunt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Kronjakt SHOP pure logic (Crown Hunt Shop PR2): the
 * catalog/inventory/balance → render-state mapper and the buy coordinator's
 * in-flight guard + error mapping. Firebase-free.
 */
class PerkShopTest {

    private val spike =
        PerkCatalogEntry("spike_strip", PerkKind.TRAP, "Spikmatta", "perk_spike_strip", 150, "…")
    private val shield =
        PerkCatalogEntry("shield", PerkKind.SHIELD, "Sköld", "perk_shield", 100, "…")
    private val boost =
        PerkCatalogEntry("boost", PerkKind.BOOST, "Dubbla Poäng", "perk_boost", 120, "…")

    // ---- PerkKind.fromWire --------------------------------------------------

    @Test
    fun `perk kind parses known wire values and rejects unknown`() {
        assertEquals(PerkKind.TRAP, PerkKind.fromWire("trap"))
        assertEquals(PerkKind.SHIELD, PerkKind.fromWire("shield"))
        assertEquals(PerkKind.BOOST, PerkKind.fromWire("boost"))
        assertEquals(null, PerkKind.fromWire("mystery"))
        assertEquals(null, PerkKind.fromWire(null))
    }

    // ---- PerkShop.toUiState -------------------------------------------------

    @Test
    fun `loading or error catalog dominates regardless of inventory or balance`() {
        assertEquals(
            PerkShopUiState.Loading,
            PerkShop.toUiState(PerkCatalogState.Loading, mapOf("shield" to 3L), 999L),
        )
        assertEquals(
            PerkShopUiState.Error,
            PerkShop.toUiState(PerkCatalogState.Error, emptyMap(), 999L),
        )
    }

    @Test
    fun `loaded maps owned counts and affordability against the balance`() {
        val state =
            PerkShop.toUiState(
                catalog = PerkCatalogState.Loaded(listOf(spike, shield, boost)),
                inventory = mapOf("shield" to 2L),
                balanceKp = 120L,
            )
        val loaded = state as PerkShopUiState.Loaded
        assertEquals(120L, loaded.balanceKp)
        // spike costs 150 > 120 → not affordable, 0 owned
        assertEquals(PerkShopItem(spike, 0L, false), loaded.items[0])
        // shield costs 100 <= 120 → affordable, 2 owned
        assertEquals(PerkShopItem(shield, 2L, true), loaded.items[1])
        // boost costs 120 == 120 → affordable (>=), 0 owned
        assertEquals(PerkShopItem(boost, 0L, true), loaded.items[2])
    }

    @Test
    fun `null balance renders as zero and nothing is affordable`() {
        val loaded =
            PerkShop.toUiState(PerkCatalogState.Loaded(listOf(shield)), emptyMap(), null)
                as PerkShopUiState.Loaded
        assertEquals(0L, loaded.balanceKp)
        assertEquals(false, loaded.items[0].affordable)
    }

    @Test
    fun `negative inventory count is clamped to zero`() {
        val loaded =
            PerkShop.toUiState(PerkCatalogState.Loaded(listOf(shield)), mapOf("shield" to -5L), 500L)
                as PerkShopUiState.Loaded
        assertEquals(0L, loaded.items[0].ownedCount)
    }

    // ---- toPerkPurchaseException discriminator ------------------------------

    @Test
    fun `reason parser reads details reason and tolerates absent or malformed details`() {
        assertEquals("insufficient_funds", perkPurchaseReasonOf(mapOf("reason" to "insufficient_funds")))
        assertEquals("shop_unavailable", perkPurchaseReasonOf(mapOf("reason" to "shop_unavailable")))
        // No reason key, wrong value type, non-map, and null all yield null.
        assertEquals(null, perkPurchaseReasonOf(mapOf("other" to "x")))
        assertEquals(null, perkPurchaseReasonOf(mapOf("reason" to 42)))
        assertEquals(null, perkPurchaseReasonOf("insufficient_funds"))
        assertEquals(null, perkPurchaseReasonOf(null))
    }

    @Test
    fun `failed-precondition maps insufficient_funds reason to the insufficient family`() {
        assertTrue(
            perkPurchaseFailedPreconditionException("insufficient_funds")
                is PerkPurchaseInsufficientFundsException,
        )
    }

    @Test
    fun `failed-precondition maps every other reason to the unavailable family`() {
        assertTrue(
            perkPurchaseFailedPreconditionException("shop_unavailable")
                is PerkPurchaseUnavailableException,
        )
        // An unrecognised reason, and a missing reason, both fall back to unavailable
        // rather than misclassifying as insufficient funds.
        assertTrue(
            perkPurchaseFailedPreconditionException("mystery") is PerkPurchaseUnavailableException,
        )
        assertTrue(
            perkPurchaseFailedPreconditionException(null) is PerkPurchaseUnavailableException,
        )
    }

    // ---- PerkShopCoordinator ------------------------------------------------

    private class FakeRepo(
        private val result: PerkPurchaseResult? = null,
        private val failWith: Exception? = null,
    ) : PerkShopRepository {
        var buys = 0
        val keysSeen = mutableListOf<String>()

        override fun observeCatalog() = throw UnsupportedOperationException()

        override fun observeInventory(uid: String) = throw UnsupportedOperationException()

        override suspend fun buyPerk(perkId: String, idempotencyKey: String): PerkPurchaseResult {
            keysSeen += idempotencyKey
            failWith?.let { throw it }
            buys++
            return result ?: PerkPurchaseResult(perkId, 1, 100, 400, 1, alreadyPurchased = false)
        }

        override fun observeShieldActiveUntil(uid: String) = throw UnsupportedOperationException()

        override fun observeActiveTrapExpiries(uid: String) = throw UnsupportedOperationException()

        override fun observeOwnActiveTraps(uid: String) = throw UnsupportedOperationException()

        override suspend fun deployPerk(
            perkId: String,
            latitude: Double?,
            longitude: Double?,
            idempotencyKey: String,
        ) = throw UnsupportedOperationException()
    }

    @Test
    fun `a successful buy surfaces the post-purchase totals`() = runTest {
        val repo =
            FakeRepo(PerkPurchaseResult("shield", 1, 100, 400, 3, alreadyPurchased = false))
        val coordinator = PerkShopCoordinator(repo, keyFactory = { "key-1" })
        coordinator.buy("shield", affordable = true)
        assertEquals(1, repo.buys)
        assertEquals(
            PerkBuyStatus.Bought("shield", newBalance = 400, inventoryCount = 3, alreadyPurchased = false),
            coordinator.status.value,
        )
    }

    @Test
    fun `an unaffordable buy is rejected locally without calling the backend`() = runTest {
        val repo = FakeRepo()
        val coordinator = PerkShopCoordinator(repo)
        coordinator.buy("spike_strip", affordable = false)
        assertEquals(0, repo.buys)
        assertEquals(
            PerkBuyStatus.Failed("spike_strip", PerkBuyFailureReason.INSUFFICIENT_FUNDS),
            coordinator.status.value,
        )
    }

    @Test
    fun `server insufficient-funds maps to the insufficient reason`() = runTest {
        val coordinator =
            PerkShopCoordinator(FakeRepo(failWith = PerkPurchaseInsufficientFundsException()))
        coordinator.buy("shield", affordable = true)
        assertEquals(
            PerkBuyStatus.Failed("shield", PerkBuyFailureReason.INSUFFICIENT_FUNDS),
            coordinator.status.value,
        )
    }

    @Test
    fun `shop-unavailable maps to the unavailable reason`() = runTest {
        val coordinator =
            PerkShopCoordinator(FakeRepo(failWith = PerkPurchaseUnavailableException()))
        coordinator.buy("shield", affordable = true)
        assertEquals(
            PerkBuyStatus.Failed("shield", PerkBuyFailureReason.UNAVAILABLE),
            coordinator.status.value,
        )
    }

    @Test
    fun `a generic failure is UNKNOWN and can reset`() = runTest {
        val coordinator = PerkShopCoordinator(FakeRepo(failWith = IllegalStateException("boom")))
        coordinator.buy("shield", affordable = true)
        assertEquals(
            PerkBuyStatus.Failed("shield", PerkBuyFailureReason.UNKNOWN),
            coordinator.status.value,
        )
        coordinator.reset()
        assertEquals(PerkBuyStatus.Idle, coordinator.status.value)
    }

    @Test
    fun `cancellation is rethrown and leaves Idle`() = runTest {
        val coordinator = PerkShopCoordinator(FakeRepo(failWith = CancellationException("c")))
        var rethrown = false
        try {
            coordinator.buy("shield", affordable = true)
        } catch (c: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
        assertEquals(PerkBuyStatus.Idle, coordinator.status.value)
    }

    @Test
    fun `an idempotent replay is surfaced as alreadyPurchased`() = runTest {
        val repo =
            FakeRepo(PerkPurchaseResult("boost", 1, 120, 80, 2, alreadyPurchased = true))
        val coordinator = PerkShopCoordinator(repo)
        coordinator.buy("boost", affordable = true)
        assertEquals(
            PerkBuyStatus.Bought("boost", newBalance = 80, inventoryCount = 2, alreadyPurchased = true),
            coordinator.status.value,
        )
    }

    @Test
    fun `each buy uses a fresh idempotency key`() = runTest {
        val repo = FakeRepo()
        var n = 0
        val coordinator = PerkShopCoordinator(repo, keyFactory = { "key-${n++}" })
        coordinator.buy("shield", affordable = true)
        coordinator.reset()
        coordinator.buy("shield", affordable = true)
        assertEquals(listOf("key-0", "key-1"), repo.keysSeen)
    }
}
