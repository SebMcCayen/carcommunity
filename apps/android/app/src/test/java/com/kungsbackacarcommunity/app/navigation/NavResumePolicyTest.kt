package com.kungsbackacarcommunity.app.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure nav-robustness decisions: when BACK confirms an exit, and when a
 * persisted navigation is still worth offering to resume (freshness, "not
 * already navigating", and the persist/clear lifecycle that separates a
 * confirmed exit from an interruption).
 */
class NavResumePolicyTest {

    private val now = 1_700_000_000_000L
    private val maxAge = NavResumePolicy.RESUME_MAX_AGE_MILLIS

    // A sendable Kungsbacka-ish coordinate (Mapbox lng-first).
    private val dest = LatLng(longitude = 12.07, latitude = 57.49)

    private fun record(
        destination: LatLng = dest,
        startedAtMillis: Long = now,
    ) = ActiveNavigation(destination = destination, label = "Home", startedAtMillis = startedAtMillis)

    // --- back-key confirm --------------------------------------------------

    @Test
    fun `back confirms an exit while navigating`() {
        assertTrue(NavResumePolicy.shouldConfirmBackExit(navigating = true))
    }

    @Test
    fun `back is not intercepted when not navigating`() {
        assertFalse(NavResumePolicy.shouldConfirmBackExit(navigating = false))
    }

    // --- resume eligibility ------------------------------------------------

    @Test
    fun `a fresh record is offered when nothing is running`() {
        assertTrue(
            NavResumePolicy.shouldOfferResume(
                persisted = record(),
                nowMillis = now,
                currentlyNavigating = false,
            ),
        )
    }

    @Test
    fun `no record means no offer`() {
        assertFalse(
            NavResumePolicy.shouldOfferResume(
                persisted = null,
                nowMillis = now,
                currentlyNavigating = false,
            ),
        )
    }

    @Test
    fun `a live session is never re-offered`() {
        assertFalse(
            NavResumePolicy.shouldOfferResume(
                persisted = record(),
                nowMillis = now,
                currentlyNavigating = true,
            ),
        )
    }

    @Test
    fun `a record older than the cap is stale and not offered`() {
        assertFalse(
            NavResumePolicy.shouldOfferResume(
                persisted = record(startedAtMillis = now - maxAge - 1),
                nowMillis = now,
                currentlyNavigating = false,
            ),
        )
    }

    @Test
    fun `a record exactly at the cap is still offered`() {
        assertTrue(
            NavResumePolicy.shouldOfferResume(
                persisted = record(startedAtMillis = now - maxAge),
                nowMillis = now,
                currentlyNavigating = false,
            ),
        )
    }

    @Test
    fun `a record from the future is not offered`() {
        // A backwards clock jump must not make an old record look fresh.
        assertFalse(
            NavResumePolicy.shouldOfferResume(
                persisted = record(startedAtMillis = now + 1),
                nowMillis = now,
                currentlyNavigating = false,
            ),
        )
    }

    @Test
    fun `a record with an out-of-bounds coordinate is not offered`() {
        assertFalse(
            NavResumePolicy.shouldOfferResume(
                persisted = record(destination = LatLng(longitude = 999.0, latitude = 999.0)),
                nowMillis = now,
                currentlyNavigating = false,
            ),
        )
    }

    // --- persist / clear lifecycle ----------------------------------------
    //
    // The store is the durable half; here we model its two outcomes as the
    // input the policy sees. A confirmed exit clears the record before the next
    // launch asks, so the policy sees null and offers nothing. An interruption
    // leaves the record in place, so the policy offers a resume.

    @Test
    fun `a confirmed exit (record cleared) offers nothing`() {
        val afterConfirmedExit: ActiveNavigation? = null
        assertFalse(
            NavResumePolicy.shouldOfferResume(
                persisted = afterConfirmedExit,
                nowMillis = now,
                currentlyNavigating = false,
            ),
        )
    }

    @Test
    fun `an interruption (record kept) offers a resume`() {
        val afterInterruption: ActiveNavigation? = record()
        assertTrue(
            NavResumePolicy.shouldOfferResume(
                persisted = afterInterruption,
                nowMillis = now,
                currentlyNavigating = false,
            ),
        )
    }
}
