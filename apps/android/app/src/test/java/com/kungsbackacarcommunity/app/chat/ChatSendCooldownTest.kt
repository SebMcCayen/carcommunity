package com.kungsbackacarcommunity.app.chat

import org.junit.Assert.*
import org.junit.Test

class ChatSendCooldownTest {
    private fun details(retry: Any? = 5_000L) = mapOf("reason" to "chat-rate-limited", "retryAfterMs" to retry)

    @Test fun `only the explicit chat quota is recognized`() {
        for (code in listOf("RESOURCE_EXHAUSTED", "resource-exhausted", "functions/resource-exhausted"))
            assertEquals(5_000L, ChatRateLimits.parse(code, details())?.retryAfterMs)
        for (code in listOf(null, "UNAUTHENTICATED", "PERMISSION_DENIED", "INVALID_ARGUMENT", "UNAVAILABLE"))
            assertNull(ChatRateLimits.parse(code, details()))
        for (bad in listOf(null, "chat-rate-limited", mapOf("retryAfterMs" to 5000), details() + ("reason" to "another-quota")))
            assertNull(ChatRateLimits.parse("resource-exhausted", bad))
    }

    @Test fun `malformed retry data never invents a cooldown`() {
        for (bad in listOf(null, "5000", true, -1, 1.5, Double.NaN, Double.POSITIVE_INFINITY, Long.MAX_VALUE))
            assertNull("Bad duration: $bad", ChatRateLimits.parse("RESOURCE_EXHAUSTED", details(bad)))
        assertEquals(0L, ChatRateLimits.parse("RESOURCE_EXHAUSTED", details(0))?.retryAfterMs)
        assertEquals(600_000L, ChatRateLimits.parse("RESOURCE_EXHAUSTED", details(600_001))?.retryAfterMs)
    }

    @Test fun `optional server timestamps do not import device wall clock skew`() {
        val limit = ChatRateLimits.parse("RESOURCE_EXHAUSTED", details() + mapOf(
            "blockedUntilMillis" to 1_793_000_005_000L, "serverNowMillis" to 1_793_000_000_000L))!!
        var elapsed = 42L
        val gate = ChatSendCooldown { elapsed }
        gate.apply(limit)
        assertEquals(5, gate.remainingSeconds())
        elapsed += 4_001
        assertEquals(1, gate.remainingSeconds())
        elapsed += 999
        assertFalse(gate.isBlocked())
        assertEquals(0, gate.remainingSeconds())
        assertNull(ChatRateLimits.parse("RESOURCE_EXHAUSTED", details() + ("serverNowMillis" to "wrong"))!!.serverNowMillis)
    }

    @Test fun `out of order responses cannot shorten the active pause and delay is capped`() {
        var now = 100L
        val gate = ChatSendCooldown { now }
        assertFalse(gate.isBlocked())
        gate.apply(ChatRateLimit(30_000))
        now += 1_000
        gate.apply(ChatRateLimit(5_000))
        assertEquals(29_000L, gate.remainingMillis())
        gate.apply(ChatRateLimit(0))
        assertEquals(29_000L, gate.remainingMillis())
        gate.apply(ChatRateLimit(Long.MAX_VALUE))
        assertEquals(600_000L, gate.remainingMillis())
        now += 600_000
        assertFalse(gate.isBlocked())
    }

    @Test fun `event payload preserves legacy omission and validates optional idempotency key`() {
        assertEquals(mapOf("eventId" to "event", "message" to "Hej 🚗"), eventChatPostPayload("event", " Hej 🚗 ", null))
        assertEquals("a_1-2", eventChatPostPayload("event", "Hej", "a_1-2")["clientId"])
        for (id in listOf("", "a/b", "x".repeat(65))) {
            try { eventChatPostPayload("event", "Hej", id); fail("Invalid key accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun `delayed duplicate pause cannot extend or restart the local deadline`() {
        var elapsed = 100L
        val gate = ChatSendCooldown { elapsed }
        val first = ChatRateLimit(600_000, 1_600_000, 1_000_000)
        gate.apply(first)
        elapsed += 540_000
        gate.apply(first) // duplicate delivered nine minutes later
        assertEquals(60_000L, gate.remainingMillis())
        elapsed += 60_000
        gate.apply(first)
        assertEquals(0L, gate.remainingMillis())
        gate.apply(ChatRateLimit(5_000, 1_100_000, 1_095_000)) // older pause after local expiry
        assertFalse(gate.isBlocked())
    }

    @Test fun `genuinely later server penalty may replace an expired pause`() {
        var elapsed = 100L
        val gate = ChatSendCooldown { elapsed }
        gate.apply(ChatRateLimit(5_000, 105_000, 100_000))
        elapsed += 5_000
        assertFalse(gate.isBlocked())
        gate.apply(ChatRateLimit(30_000, 140_000, 110_000))
        assertEquals(30_000L, gate.remainingMillis())
        gate.apply(ChatRateLimit(4_000, 105_000, 101_000))
        assertEquals(30_000L, gate.remainingMillis())
    }

    @Test fun `inconsistent or malformed optional timestamp pair falls back to bounded relative timing`() {
        for (extra in listOf(
            mapOf("blockedUntilMillis" to 9_000, "serverNowMillis" to 3_000),
            mapOf("blockedUntilMillis" to 2_000, "serverNowMillis" to 3_000),
            mapOf("blockedUntilMillis" to 8_000),
        )) {
            val limit = ChatRateLimits.parse("RESOURCE_EXHAUSTED", details() + extra)!!
            assertNull(limit.trustedServerDeadline())
            assertEquals(5_000L, limit.retryAfterMs)
        }
        val gate = ChatSendCooldown { 100L }
        gate.apply(ChatRateLimit(-1, 100, 101))
        assertFalse(gate.isBlocked())
        gate.apply(ChatRateLimit(0, 105_000, 105_000))
        gate.apply(ChatRateLimit(5_000, 105_000, 100_000))
        assertFalse(gate.isBlocked()) // a trusted already-expired pause stays expired
    }
}
