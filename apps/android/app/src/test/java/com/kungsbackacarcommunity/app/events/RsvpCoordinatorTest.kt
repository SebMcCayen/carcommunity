package com.kungsbackacarcommunity.app.events

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RsvpCoordinatorTest {

    private class FakeRepo : EventsRepository {
        val writes = mutableListOf<Triple<String, String, RsvpStatus>>()
        var failWith: Exception? = null

        override fun observePublishedEvents(): Flow<EventsListState> = flowOf(EventsListState.Loading)

        override fun observeEvent(eventId: String): Flow<EventSummary?> = flowOf(null)

        override fun observeEventDetail(eventId: String): Flow<EventDetail?> = flowOf(null)

        override fun observeMyRsvp(eventId: String, uid: String): Flow<RsvpStatus?> = flowOf(null)

        override suspend fun setRsvp(eventId: String, uid: String, status: RsvpStatus) {
            failWith?.let { throw it }
            writes += Triple(eventId, uid, status)
        }
    }

    @Test
    fun `submit writes the answer and returns to Idle`() = runTest {
        val repo = FakeRepo()
        val coordinator = RsvpCoordinator(repo)
        coordinator.submit("e1", "u1", RsvpStatus.GOING)
        assertEquals(listOf(Triple("e1", "u1", RsvpStatus.GOING)), repo.writes)
        assertEquals(RsvpStatusUi.Idle, coordinator.status.value)
    }

    @Test
    fun `a failed write surfaces Failed and can be reset`() = runTest {
        val repo = FakeRepo().apply { failWith = IllegalStateException("denied") }
        val coordinator = RsvpCoordinator(repo)
        coordinator.submit("e1", "u1", RsvpStatus.MAYBE)
        assertEquals(RsvpStatusUi.Failed, coordinator.status.value)
        coordinator.reset()
        assertEquals(RsvpStatusUi.Idle, coordinator.status.value)
    }

    @Test
    fun `cancellation is rethrown and leaves Idle`() = runTest {
        val repo = FakeRepo().apply { failWith = CancellationException("cancelled") }
        val coordinator = RsvpCoordinator(repo)
        var rethrown = false
        try {
            coordinator.submit("e1", "u1", RsvpStatus.NOT_GOING)
        } catch (cancellation: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
        assertEquals(RsvpStatusUi.Idle, coordinator.status.value)
    }
}
