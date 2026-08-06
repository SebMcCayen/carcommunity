package com.kungsbackacarcommunity.app.events

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CheckInCoordinator] — the window gate, the local mock refusal,
 * the one-shot location handling, and the server-result → UI-state mapping. Pure
 * JVM (fake repository + fake location source), no Firebase/Android.
 */
class CheckInCoordinatorTest {

    private val now = 1_000_000_000_000L

    /** An event whose check-in window is open at [now]. */
    private fun openEvent(): EventSummary =
        EventSummary(
            id = "e1",
            title = "Meet",
            summary = null,
            startsAtMillis = now - 5 * 60_000L,
            endsAtMillis = now + 60 * 60_000L,
            approximateArea = null,
            latitude = 57.48,
            longitude = 12.07,
            isOfficial = false,
            status = EventStatus.PUBLISHED,
            counts = RsvpCounts.EMPTY,
        )

    private fun fix(isMock: Boolean = false): CheckInFix =
        CheckInFix(
            latitude = 57.48,
            longitude = 12.07,
            accuracyMeters = 8.0,
            capturedAtMillis = now,
            isMock = isMock,
        )

    private class FakeRepo(
        private val result: CheckInResult = CheckInResult.RECORDED,
        private val throwOnCheckIn: Boolean = false,
    ) : EventsRepository {
        var checkInCalls = 0
            private set

        override fun observePublishedEvents(): Flow<EventsListState> = flowOf(EventsListState.Loading)
        override fun observePastEvents(): Flow<EventsListState> = flowOf(EventsListState.Loading)
        override fun observeEvent(eventId: String): Flow<EventSummary?> = flowOf(null)
        override fun observeEventDetail(eventId: String): Flow<EventDetail?> = flowOf(null)
        override fun observeMyRsvp(eventId: String, uid: String): Flow<RsvpStatus?> = flowOf(null)
        override fun observeMyAttendance(
            eventId: String,
            uid: String,
        ): Flow<EventAttendanceStatus?> = flowOf(null)

        override suspend fun checkIn(eventId: String, fix: CheckInFix): CheckInResult {
            checkInCalls++
            if (throwOnCheckIn) throw RuntimeException("network")
            return result
        }

        override suspend fun setRsvp(eventId: String, uid: String, status: RsvpStatus) = Unit
        override suspend fun createEvent(input: CreateEventInput): String = "unused"
        override suspend fun loadAttendees(eventId: String): EventAttendeesResult =
            EventAttendeesResult.Unavailable
    }

    private fun coordinator(
        repo: FakeRepo,
        fix: CheckInFix? = fix(),
    ): CheckInCoordinator =
        CheckInCoordinator(
            repository = repo,
            locationSource = { fix },
            clock = { now },
        )

    @Test
    fun `outside the window fails without touching the network`() = runTest {
        val repo = FakeRepo()
        val past =
            openEvent().copy(
                startsAtMillis = now - 10 * 60 * 60_000L,
                endsAtMillis = now - 9 * 60 * 60_000L,
            )
        val c = coordinator(repo)
        c.checkIn(past)
        assertEquals(CheckInUiState.Failed(CheckInError.WINDOW_CLOSED), c.status.value)
        assertEquals(0, repo.checkInCalls)
    }

    @Test
    fun `a mock fix is refused locally, before any call`() = runTest {
        val repo = FakeRepo()
        val c = coordinator(repo, fix = fix(isMock = true))
        c.checkIn(openEvent())
        assertEquals(CheckInUiState.Failed(CheckInError.MOCK_LOCATION), c.status.value)
        assertEquals(0, repo.checkInCalls)
    }

    @Test
    fun `no fix available maps to POSITION_UNAVAILABLE`() = runTest {
        val repo = FakeRepo()
        val c = coordinator(repo, fix = null)
        c.checkIn(openEvent())
        assertEquals(CheckInUiState.Failed(CheckInError.POSITION_UNAVAILABLE), c.status.value)
        assertEquals(0, repo.checkInCalls)
    }

    @Test
    fun `recorded maps to a pending success`() = runTest {
        val repo = FakeRepo(result = CheckInResult.RECORDED)
        val c = coordinator(repo)
        c.checkIn(openEvent())
        assertEquals(CheckInUiState.Success(verified = false), c.status.value)
        assertEquals(1, repo.checkInCalls)
    }

    @Test
    fun `verified maps to a confirmed success`() = runTest {
        val repo = FakeRepo(result = CheckInResult.VERIFIED)
        val c = coordinator(repo)
        c.checkIn(openEvent())
        assertEquals(CheckInUiState.Success(verified = true), c.status.value)
    }

    @Test
    fun `already verified is also a confirmed success`() = runTest {
        val repo = FakeRepo(result = CheckInResult.ALREADY_VERIFIED)
        val c = coordinator(repo)
        c.checkIn(openEvent())
        assertEquals(CheckInUiState.Success(verified = true), c.status.value)
    }

    @Test
    fun `outside geofence maps to its own error`() = runTest {
        val repo = FakeRepo(result = CheckInResult.OUTSIDE_GEOFENCE)
        val c = coordinator(repo)
        c.checkIn(openEvent())
        assertEquals(CheckInUiState.Failed(CheckInError.OUTSIDE_GEOFENCE), c.status.value)
    }

    @Test
    fun `server risk_review maps to the mock-location message`() = runTest {
        val repo = FakeRepo(result = CheckInResult.RISK_REVIEW)
        val c = coordinator(repo)
        c.checkIn(openEvent())
        assertEquals(CheckInUiState.Failed(CheckInError.MOCK_LOCATION), c.status.value)
    }

    @Test
    fun `event_not_checkinable maps to WINDOW_CLOSED message`() = runTest {
        val repo = FakeRepo(result = CheckInResult.EVENT_NOT_CHECKINABLE)
        val c = coordinator(repo)
        c.checkIn(openEvent())
        assertEquals(CheckInUiState.Failed(CheckInError.NOT_CHECKINABLE), c.status.value)
    }

    @Test
    fun `an unknown result is a generic failure, never a silent success`() = runTest {
        val repo = FakeRepo(result = CheckInResult.UNKNOWN)
        val c = coordinator(repo)
        c.checkIn(openEvent())
        assertEquals(CheckInUiState.Failed(CheckInError.GENERIC), c.status.value)
    }

    @Test
    fun `a thrown transport error is a generic failure`() = runTest {
        val repo = FakeRepo(throwOnCheckIn = true)
        val c = coordinator(repo)
        c.checkIn(openEvent())
        assertEquals(CheckInUiState.Failed(CheckInError.GENERIC), c.status.value)
    }

    @Test
    fun `reset clears a failure but leaves a success`() = runTest {
        val failing = FakeRepo(result = CheckInResult.OUTSIDE_GEOFENCE)
        val c = coordinator(failing)
        c.checkIn(openEvent())
        assertTrue(c.status.value is CheckInUiState.Failed)
        c.reset()
        assertEquals(CheckInUiState.Idle, c.status.value)

        val ok = FakeRepo(result = CheckInResult.VERIFIED)
        val c2 = coordinator(ok)
        c2.checkIn(openEvent())
        c2.reset()
        assertEquals(CheckInUiState.Success(verified = true), c2.status.value)
    }

    @Test
    fun `result wire mapping round-trips and defaults to UNKNOWN`() {
        assertEquals(CheckInResult.VERIFIED, CheckInResult.fromWire("verified"))
        assertEquals(CheckInResult.OUTSIDE_WINDOW, CheckInResult.fromWire("outside_window"))
        assertEquals(CheckInResult.UNKNOWN, CheckInResult.fromWire("gibberish"))
        assertEquals(CheckInResult.UNKNOWN, CheckInResult.fromWire(null))
    }
}
