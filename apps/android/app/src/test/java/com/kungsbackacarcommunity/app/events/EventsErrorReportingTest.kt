package com.kungsbackacarcommunity.app.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the auto-report gate for the events list.
 *
 * These assertions are the actual contract, not a shape check: the Past tab's
 * dead-end was a missing composite index (`FAILED_PRECONDITION`), and the whole
 * point of the gate is that THAT files an issue while a user with no signal
 * (`UNAVAILABLE`) files nothing.
 */
class EventsErrorReportingTest {

    @Before
    fun clearLatch() = EventsErrorReporting.resetForTest()

    @Test
    fun `structural failures are reportable`() {
        assertTrue(EventsErrorReporting.isReportable("FAILED_PRECONDITION"))
        assertTrue(EventsErrorReporting.isReportable("PERMISSION_DENIED"))
    }

    @Test
    fun `offline and transient failures are not reportable`() {
        // A user in a tunnel is not a bug. These must never file an issue.
        for (code in listOf("UNAVAILABLE", "DEADLINE_EXCEEDED", "CANCELLED", "ABORTED", "UNKNOWN")) {
            assertFalse("$code must stay silent", EventsErrorReporting.isReportable(code))
        }
    }

    @Test
    fun `a missing code is not reportable`() {
        assertFalse(EventsErrorReporting.isReportable(null))
        assertFalse(EventsErrorReporting.isReportable(""))
        assertFalse(EventsErrorReporting.isReportable("   "))
    }

    @Test
    fun `code matching tolerates case and wire-style hyphens`() {
        assertTrue(EventsErrorReporting.isReportable("failed-precondition"))
        assertTrue(EventsErrorReporting.isReportable(" Permission_Denied "))
    }

    @Test
    fun `a feature reports at most once per process`() {
        val feature = EventsErrorReporting.FEATURE_PAST_LIST
        assertTrue(EventsErrorReporting.shouldReport(feature, "FAILED_PRECONDITION"))
        // Re-entering the error state (tab flip, retry tap) must not re-file.
        assertFalse(EventsErrorReporting.shouldReport(feature, "FAILED_PRECONDITION"))
        assertFalse(EventsErrorReporting.shouldReport(feature, "PERMISSION_DENIED"))
    }

    @Test
    fun `the two tabs latch independently`() {
        assertTrue(
            EventsErrorReporting.shouldReport(
                EventsErrorReporting.FEATURE_PAST_LIST,
                "FAILED_PRECONDITION",
            ),
        )
        assertTrue(
            EventsErrorReporting.shouldReport(
                EventsErrorReporting.FEATURE_UPCOMING_LIST,
                "FAILED_PRECONDITION",
            ),
        )
    }

    @Test
    fun `an unreportable code does not consume the latch`() {
        val feature = EventsErrorReporting.FEATURE_PAST_LIST
        // Going offline first must not suppress the real fault that follows.
        assertFalse(EventsErrorReporting.shouldReport(feature, "UNAVAILABLE"))
        assertTrue(EventsErrorReporting.shouldReport(feature, "FAILED_PRECONDITION"))
    }

    @Test
    fun `reported payloads carry no free text or identifiers`() {
        // The GitHub issue is world-readable. Everything the route sends is one
        // of these literals plus a Firestore status name from a closed enum;
        // guard against someone later interpolating a uid or an exception into
        // the message.
        val payloads =
            listOf(
                EventsErrorReporting.FEATURE_PAST_LIST,
                EventsErrorReporting.FEATURE_UPCOMING_LIST,
                EventsErrorReporting.MESSAGE_PAST_LIST,
                EventsErrorReporting.MESSAGE_UPCOMING_LIST,
            )
        for (payload in payloads) {
            assertFalse("template interpolation in: $payload", payload.contains("$"))
            assertFalse("possible address in: $payload", payload.contains("@"))
            assertFalse("URL in: $payload", payload.contains("http"))
        }
        assertEquals("events.pastList", EventsErrorReporting.FEATURE_PAST_LIST)
        assertEquals("events.upcomingList", EventsErrorReporting.FEATURE_UPCOMING_LIST)
    }
}
