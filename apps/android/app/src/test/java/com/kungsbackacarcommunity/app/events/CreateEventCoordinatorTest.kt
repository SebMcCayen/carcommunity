package com.kungsbackacarcommunity.app.events

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateEventCoordinatorTest {

    private class FakeRepo : EventsRepository {
        val created = mutableListOf<CreateEventInput>()
        var failWith: Exception? = null
        var returnId: String = "new-event"

        override fun observePublishedEvents(): Flow<EventsListState> = flowOf(EventsListState.Loading)

        override fun observeEvent(eventId: String): Flow<EventSummary?> = flowOf(null)

        override fun observeEventDetail(eventId: String): Flow<EventDetail?> = flowOf(null)

        override fun observeMyRsvp(eventId: String, uid: String): Flow<RsvpStatus?> = flowOf(null)

        override suspend fun setRsvp(eventId: String, uid: String, status: RsvpStatus) = Unit

        override suspend fun createEvent(input: CreateEventInput): String {
            failWith?.let { throw it }
            created += input
            return returnId
        }

        override suspend fun loadAttendees(eventId: String): EventAttendeesResult =
            EventAttendeesResult.Unavailable
    }

    private val validInput =
        CreateEventInput(
            title = "Cars & Coffee",
            approximateArea = "Kungsbacka",
            startsAtMillis = 1_800_000_000_000L,
        )

    @Test
    fun `submit creates the event and surfaces Success with the new id`() = runTest {
        val repo = FakeRepo().apply { returnId = "evt-42" }
        val coordinator = CreateEventCoordinator(repo)
        coordinator.submit(validInput)
        assertEquals(listOf(validInput), repo.created)
        assertEquals(CreateEventStatusUi.Success("evt-42"), coordinator.status.value)
    }

    @Test
    fun `invalid input is rejected without hitting the repository`() = runTest {
        val repo = FakeRepo()
        val coordinator = CreateEventCoordinator(repo)
        coordinator.submit(validInput.copy(title = "   "))
        assertTrue(repo.created.isEmpty())
        assertEquals(CreateEventStatusUi.Failed(CreateEventFailure.UNKNOWN), coordinator.status.value)
    }

    @Test
    fun `an unclassified write failure surfaces Failed UNKNOWN and can be reset`() = runTest {
        val repo = FakeRepo().apply { failWith = IllegalStateException("boom") }
        val coordinator = CreateEventCoordinator(repo)
        coordinator.submit(validInput)
        assertEquals(CreateEventStatusUi.Failed(CreateEventFailure.UNKNOWN), coordinator.status.value)
        coordinator.reset()
        assertEquals(CreateEventStatusUi.Idle, coordinator.status.value)
    }

    @Test
    fun `the per-member rate limit surfaces Failed RATE_LIMITED, not a generic error`() = runTest {
        // Exactly what FirebaseEventsRepository throws once events-create
        // answers `resource-exhausted` (the 3-per-rolling-24h member cap).
        val repo =
            FakeRepo().apply { failWith = CreateEventException(CreateEventFailure.RATE_LIMITED) }
        val coordinator = CreateEventCoordinator(repo)
        coordinator.submit(validInput)
        assertEquals(
            CreateEventStatusUi.Failed(CreateEventFailure.RATE_LIMITED),
            coordinator.status.value,
        )
    }

    @Test
    fun `a rate limit is resettable so the form stays usable`() = runTest {
        val repo =
            FakeRepo().apply { failWith = CreateEventException(CreateEventFailure.RATE_LIMITED) }
        val coordinator = CreateEventCoordinator(repo)
        coordinator.submit(validInput)
        coordinator.reset()
        assertEquals(CreateEventStatusUi.Idle, coordinator.status.value)
    }

    @Test
    fun `cancellation is rethrown and leaves Idle`() = runTest {
        val repo = FakeRepo().apply { failWith = CancellationException("cancelled") }
        val coordinator = CreateEventCoordinator(repo)
        var rethrown = false
        try {
            coordinator.submit(validInput)
        } catch (cancellation: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
        assertEquals(CreateEventStatusUi.Idle, coordinator.status.value)
    }
}
