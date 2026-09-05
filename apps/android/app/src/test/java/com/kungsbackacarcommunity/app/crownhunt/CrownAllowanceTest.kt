package com.kungsbackacarcommunity.app.crownhunt

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrownAllowanceTest {
    private val payload = mapOf("cap" to 2250, "remaining" to 0, "resetsAt" to "2026-03-29T22:00:00Z")

    @Test
    fun parsesServerAllowanceIncludingDstReset() {
        assertEquals(CrownAllowance(2250, 0, Instant.parse("2026-03-29T22:00:00Z")), CrownAllowance.fromWire(payload))
    }

    @Test
    fun toleratesOldResponsesAndRejectsMalformedValues() {
        assertNull(CrownAllowance.fromWire(null))
        for (bad in listOf(-1, 0.5, Double.NaN, Double.POSITIVE_INFINITY, 3000)) {
            assertNull(CrownAllowance.fromWire(payload + ("remaining" to bad)))
        }
        assertNull(CrownAllowance.fromWire(payload + ("resetsAt" to "invalid")))
        assertNull(CrownAllowance.fromWire(payload + ("cap" to 0)))
    }
}
