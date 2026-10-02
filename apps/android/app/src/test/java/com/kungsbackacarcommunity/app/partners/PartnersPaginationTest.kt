package com.kungsbackacarcommunity.app.partners

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartnersPaginationTest {
    @Test
    fun livePageBoundary_detectsChangesAndResetsForReload() {
        val boundary = PartnerPageBoundary()
        val first = PartnerPageCursor(2, 0, "old")
        val updated = PartnerPageCursor(3, 0, "new")

        assertFalse(boundary.update(first))
        assertFalse(boundary.update(first))
        assertTrue(boundary.update(updated))
        assertFalse(boundary.update(updated))

        boundary.reset()
        assertFalse(boundary.update(updated))
    }

    @Test
    fun offerListenerFailure_isReportedAgainAfterSuccessfulSnapshot() {
        val failureGate = PartnerOffersListenerFailureGate()

        assertTrue(failureGate.shouldReportFailure())
        assertFalse(failureGate.shouldReportFailure())
        failureGate.didLoadSnapshot()
        assertTrue(failureGate.shouldReportFailure())
    }
}
