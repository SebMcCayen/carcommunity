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
        assertEquals(3000, CrownAllowance.fromWire(payload + ("cap" to 3000))?.cap)
    }

    @Test
    fun toleratesOldResponsesAndRejectsMalformedValues() {
        assertNull(CrownAllowance.fromWire(null))
        for (bad in listOf(-1, 0.5, Double.NaN, Double.POSITIVE_INFINITY, 3000)) {
            assertNull(CrownAllowance.fromWire(payload + ("remaining" to bad)))
        }
        assertNull(CrownAllowance.fromWire(payload + ("resetsAt" to "invalid")))
        for (badCap in listOf(0, 1, 2249, 2251, 3001, Int.MAX_VALUE)) {
            assertNull(CrownAllowance.fromWire(payload + ("cap" to badCap)))
        }
    }
}
