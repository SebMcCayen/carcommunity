package com.kungsbackacarcommunity.app.partners

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferCodeCoordinatorTest {

    private class FakeRepo : PartnersRepository {
        var code: String? = "SAVE20"
        var failWith: Exception? = null

        override fun observeActiveCompanies(): Flow<CompaniesState> = flowOf(CompaniesState.Loading)

        override fun observeActiveOffers(): Flow<List<PartnerOffer>> = flowOf(emptyList())

        override fun observeOfferDetail(offerId: String): Flow<OfferMemberDetail?> = flowOf(null)

        override fun observeSavedOfferIds(uid: String): Flow<Set<String>> = flowOf(emptySet())

        override suspend fun showOfferCode(offerId: String): String? {
            failWith?.let { throw it }
            return code
        }

        override suspend fun setSaved(uid: String, offerId: String, saved: Boolean) = Unit
    }

    @Test
    fun `reveal shows the code scoped to the offer`() = runTest {
        val coordinator = OfferCodeCoordinator(FakeRepo())
        coordinator.reveal("o1")
        val status = coordinator.status.value
        assertTrue(status is OfferCodeStatus.Shown)
        status as OfferCodeStatus.Shown
        assertEquals("o1", status.offerId)
        assertEquals("SAVE20", status.code)
    }

    @Test
    fun `a failed reveal is scoped Failed`() = runTest {
        val coordinator = OfferCodeCoordinator(FakeRepo().apply { failWith = IllegalStateException("x") })
        coordinator.reveal("o9")
        val status = coordinator.status.value
        assertTrue(status is OfferCodeStatus.Failed)
        assertEquals("o9", (status as OfferCodeStatus.Failed).offerId)
    }

    @Test
    fun `cancellation is rethrown and resets to Idle`() = runTest {
        val coordinator = OfferCodeCoordinator(FakeRepo().apply { failWith = CancellationException("c") })
        var rethrown = false
        try {
            coordinator.reveal("o1")
        } catch (c: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
        assertEquals(OfferCodeStatus.Idle, coordinator.status.value)
    }
}
