package com.kungsbackacarcommunity.app.events

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the creator edit/remove feature: the creator gate
 * ([Events.canManageOwnEvent]), the events-update partial payload
 * ([Events.updatePayload]), the callable error mapping
 * ([Events.manageFailureFromCode]) and the [EditEventCoordinator] state machine.
 * No Firebase/Android — everything under test is pure Kotlin.
 */
class EventManageTest {

    // --- Creator gate ---

    @Test
    fun `creator can manage a published or draft event`() {
        assertTrue(Events.canManageOwnEvent(isCreator = true, status = EventStatus.PUBLISHED))
        assertTrue(Events.canManageOwnEvent(isCreator = true, status = EventStatus.DRAFT))
    }

    @Test
    fun `creator cannot manage a cancelled or completed event`() {
        assertFalse(Events.canManageOwnEvent(isCreator = true, status = EventStatus.CANCELLED))
        assertFalse(Events.canManageOwnEvent(isCreator = true, status = EventStatus.COMPLETED))
    }

    @Test
    fun `a non-creator can never manage the event`() {
        assertFalse(Events.canManageOwnEvent(isCreator = false, status = EventStatus.PUBLISHED))
        assertFalse(Events.canManageOwnEvent(isCreator = false, status = EventStatus.DRAFT))
    }

    // --- Update payload ---

    @Test
    fun `updatePayload carries the form-managed fields and omits nothing it edits`() {
        val input =
            CreateEventInput(
                title = "  Cars & Coffee  ",
                startsAtMillis = 1_800_000_000_000L,
                description = " Bring your car ",
                address = " Storgatan 1 ",
                latitude = 57.4874,
                longitude = 12.0757,
            )
        val payload = Events.updatePayload("evt-1", input)
        assertEquals("evt-1", payload["eventId"])
        assertEquals("Cars & Coffee", payload["title"])
        assertEquals(Events.toIsoUtc(1_800_000_000_000L), payload["startsAt"])
        assertEquals("Bring your car", payload["description"])
        assertEquals("Storgatan 1", payload["address"])
        assertEquals(57.4874, payload["latitude"])
        assertEquals(12.0757, payload["longitude"])
    }

    @Test
    fun `updatePayload sends null for cleared optional fields so they clear server-side`() {
        val input =
            CreateEventInput(
                title = "Title",
                startsAtMillis = 1_800_000_000_000L,
                description = "   ",
                address = null,
                latitude = null,
                longitude = null,
            )
        val payload = Events.updatePayload("evt-2", input)
        // Keys present but null — a partial update that CLEARS them.
        assertTrue(payload.containsKey("description"))
        assertNull(payload["description"])
        assertTrue(payload.containsKey("address"))
        assertNull(payload["address"])
        assertNull(payload["latitude"])
        assertNull(payload["longitude"])
    }

    @Test
    fun `updatePayload never carries fields the edit form does not manage`() {
        val payload =
            Events.updatePayload(
                "evt-3",
                CreateEventInput(title = "T", startsAtMillis = 1_800_000_000_000L, publicSiteEnabled = true),
            )
        // publicSiteEnabled is forbidden by the update schema; endsAt/summary/
        // approximateArea/locationName/isOfficial are left untouched server-side.
        assertFalse(payload.containsKey("publicSiteEnabled"))
        assertFalse(payload.containsKey("endsAt"))
        assertFalse(payload.containsKey("summary"))
        assertFalse(payload.containsKey("approximateArea"))
        assertFalse(payload.containsKey("locationName"))
        assertFalse(payload.containsKey("isOfficial"))
    }

    // --- Error mapping ---

    @Test
    fun `manageFailureFromCode maps both the enum name and the wire spelling`() {
        assertEquals(ManageEventFailure.PERMISSION_DENIED, Events.manageFailureFromCode("PERMISSION_DENIED"))
        assertEquals(ManageEventFailure.PERMISSION_DENIED, Events.manageFailureFromCode("permission-denied"))
        assertEquals(ManageEventFailure.IMMUTABLE, Events.manageFailureFromCode("FAILED_PRECONDITION"))
        assertEquals(ManageEventFailure.IMMUTABLE, Events.manageFailureFromCode("failed-precondition"))
        assertEquals(ManageEventFailure.UNKNOWN, Events.manageFailureFromCode("internal"))
        assertEquals(ManageEventFailure.UNKNOWN, Events.manageFailureFromCode(null))
    }

    // --- Edit coordinator ---

    private class FakeRepo : EventsRepository {
        val updated = mutableListOf<Pair<String, CreateEventInput>>()
        var failWith: Exception? = null

        override fun observePublishedEvents(): Flow<EventsListState> = flowOf(EventsListState.Loading)
        override fun observePastEvents(): Flow<EventsListState> = flowOf(EventsListState.Loading)
        override fun observeEvent(eventId: String): Flow<EventSummary?> = flowOf(null)
        override fun observeEventDetail(eventId: String): Flow<EventDetail?> = flowOf(null)
        override fun observeMyRsvp(eventId: String, uid: String): Flow<RsvpStatus?> = flowOf(null)
        override fun observeMyAttendance(eventId: String, uid: String): Flow<EventAttendanceStatus?> =
            flowOf(null)
        override suspend fun checkIn(eventId: String, fix: CheckInFix): CheckInResult = CheckInResult.UNKNOWN
        override suspend fun setRsvp(eventId: String, uid: String, status: RsvpStatus) = Unit
        override suspend fun createEvent(input: CreateEventInput): String = "unused"
        override suspend fun updateEvent(eventId: String, input: CreateEventInput) {
            failWith?.let { throw it }
            updated += eventId to input
        }
        override suspend fun cancelEvent(eventId: String, reason: String) = Unit
        override suspend fun loadAttendees(eventId: String): EventAttendeesResult =
            EventAttendeesResult.Unavailable
    }

    private val validInput =
        CreateEventInput(title = "Cars & Coffee", startsAtMillis = 1_800_000_000_000L)

    @Test
    fun `submit updates the event and surfaces Success with its id`() = runTest {
        val repo = FakeRepo()
        val coordinator = EditEventCoordinator(repo)
        coordinator.submit("evt-9", validInput)
        assertEquals(listOf("evt-9" to validInput), repo.updated)
        assertEquals(EditEventStatusUi.Success("evt-9"), coordinator.status.value)
    }

    @Test
    fun `invalid input is rejected without hitting the repository`() = runTest {
        val repo = FakeRepo()
        val coordinator = EditEventCoordinator(repo)
        coordinator.submit("evt-9", validInput.copy(title = "   "))
        assertTrue(repo.updated.isEmpty())
        assertEquals(EditEventStatusUi.Failed(ManageEventFailure.UNKNOWN), coordinator.status.value)
    }

    @Test
    fun `a blank event id is rejected without hitting the repository`() = runTest {
        val repo = FakeRepo()
        val coordinator = EditEventCoordinator(repo)
        coordinator.submit("  ", validInput)
        assertTrue(repo.updated.isEmpty())
        assertEquals(EditEventStatusUi.Failed(ManageEventFailure.UNKNOWN), coordinator.status.value)
    }

    @Test
    fun `a permission-denied failure surfaces Failed PERMISSION_DENIED and can be reset`() = runTest {
        val repo =
            FakeRepo().apply { failWith = UpdateEventException(ManageEventFailure.PERMISSION_DENIED) }
        val coordinator = EditEventCoordinator(repo)
        coordinator.submit("evt-9", validInput)
        assertEquals(
            EditEventStatusUi.Failed(ManageEventFailure.PERMISSION_DENIED),
            coordinator.status.value,
        )
        coordinator.reset()
        assertEquals(EditEventStatusUi.Idle, coordinator.status.value)
    }

    @Test
    fun `an immutable-event failure surfaces Failed IMMUTABLE`() = runTest {
        val repo = FakeRepo().apply { failWith = UpdateEventException(ManageEventFailure.IMMUTABLE) }
        val coordinator = EditEventCoordinator(repo)
        coordinator.submit("evt-9", validInput)
        assertEquals(EditEventStatusUi.Failed(ManageEventFailure.IMMUTABLE), coordinator.status.value)
    }
}
