package com.kungsbackacarcommunity.app.clubs

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

// Deliberately synthetic. No fixture consults Firebase, credentials, or a network service.
internal val calendarFixtureToken = "A".repeat(43)
internal val calendarFixtureUrl = "https://europe-west1-demo-calendar.cloudfunctions.net/clubCalendar-feed/calendar.ics?token=$calendarFixtureToken"

internal class CalendarFixtureRequest(
    val clubId: String, val action: String, val expectedVersion: Long?,
) {
    val result = CompletableDeferred<CalendarSubscription>()
    @Volatile var cancelled = false
}

/** Deferred per request: tests decide when a response, failure, or cancellation arrives. */
internal class CalendarFixtureRepository : ClubCalendarSubscriptionRepository {
    val requests = CopyOnWriteArrayList<CalendarFixtureRequest>()
    override suspend fun manage(clubId: String, action: String, expectedVersion: Long?): CalendarSubscription {
        val request = CalendarFixtureRequest(clubId, action, expectedVersion)
        requests += request
        return try {
            request.result.await()
        } catch (error: CancellationException) {
            request.cancelled = true
            throw error
        }
    }
}
