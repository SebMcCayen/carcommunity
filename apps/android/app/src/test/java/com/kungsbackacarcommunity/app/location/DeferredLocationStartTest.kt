package com.kungsbackacarcommunity.app.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class DeferredLocationStartTest {
    @Test fun refusedPromotionCanRetryTheRequestedOwner() {
        val start = DeferredLocationStart()
        start.requested("a")
        assertTrue(start.defer("a"))
        assertEquals("a", start.pendingUid)
        start.started("a")
        assertNull(start.pendingUid)
    }

    @Test fun lateRefusalCannotResurrectAStoppedSession() {
        val start = DeferredLocationStart()
        start.requested("a")
        start.stop()
        assertFalse(start.defer("a"))
        assertNull(start.pendingUid)
    }

    @Test fun staleAccountCallbacksCannotReplaceOrClearTheNewOwnersRetry() {
        val start = DeferredLocationStart()
        start.requested("a")
        start.requested("b")
        assertTrue(start.defer("b"))
        assertFalse(start.defer("a"))
        start.started("a")
        assertEquals("b", start.pendingUid)
    }

    @Test fun unsolicitedServiceRedeliveryCannotCreateANewStartRequest() {
        assertFalse(DeferredLocationStart().defer("a"))
    }
}
