package com.kungsbackacarcommunity.app.convoy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure convoy-reaction client logic: kind <-> wire mapping, the
 * anti-spam cooldown tracker (client mirror of the server window, with the server
 * override), and the callable payload shape.
 */
class ConvoyReactionTest {

    @Test
    fun wire_values_round_trip_and_match_the_backend() {
        assertEquals("police", ConvoyReactionKind.Police.wire)
        assertEquals("hello", ConvoyReactionKind.HelloGoodbye.wire)
        assertEquals("follow_me", ConvoyReactionKind.FollowMe.wire)
        for (kind in ConvoyReactionKind.entries) {
            assertEquals(kind, ConvoyReactionKind.fromWire(kind.wire))
        }
        assertNull(ConvoyReactionKind.fromWire("wave"))
        assertNull(ConvoyReactionKind.fromWire(null))
    }

    @Test
    fun cooldown_windows_mirror_the_server() {
        assertEquals(60_000L, convoyReactionCooldownMs(ConvoyReactionKind.Police))
        assertEquals(15_000L, convoyReactionCooldownMs(ConvoyReactionKind.HelloGoodbye))
        assertEquals(30_000L, convoyReactionCooldownMs(ConvoyReactionKind.FollowMe))
    }

    @Test
    fun a_fresh_tracker_is_ready_for_every_kind() {
        val state = ConvoyReactionCooldownState()
        for (kind in ConvoyReactionKind.entries) {
            assertTrue(state.isReady(kind, 1_000L))
            assertEquals(0L, state.remainingMs(kind, 1_000L))
        }
    }

    @Test
    fun recordSent_starts_the_window_and_it_elapses_at_the_boundary() {
        val now = 1_000_000L
        val state = ConvoyReactionCooldownState().recordSent(ConvoyReactionKind.Police, now)

        assertFalse(state.isReady(ConvoyReactionKind.Police, now))
        assertEquals(60_000L, state.remainingMs(ConvoyReactionKind.Police, now))
        // 1ms before the window closes: still blocked.
        assertFalse(state.isReady(ConvoyReactionKind.Police, now + 59_999))
        // Exactly at the boundary: ready.
        assertTrue(state.isReady(ConvoyReactionKind.Police, now + 60_000))
        assertEquals(0L, state.remainingMs(ConvoyReactionKind.Police, now + 60_000))
    }

    @Test
    fun each_kind_cools_down_independently() {
        val now = 1_000_000L
        val state = ConvoyReactionCooldownState().recordSent(ConvoyReactionKind.Police, now)
        // Sending police does not block hello or follow-me.
        assertTrue(state.isReady(ConvoyReactionKind.HelloGoodbye, now))
        assertTrue(state.isReady(ConvoyReactionKind.FollowMe, now))
        assertFalse(state.isReady(ConvoyReactionKind.Police, now))
    }

    @Test
    fun server_cooldown_overrides_the_local_estimate() {
        val now = 1_000_000L
        // Local mirror would say 60s, but the server knows there are only 5s left
        // (e.g. a send from another device) — the server wins.
        val state =
            ConvoyReactionCooldownState()
                .recordSent(ConvoyReactionKind.Police, now)
                .applyServerCooldown(ConvoyReactionKind.Police, retryAfterMs = 5_000L, nowMs = now)
        assertEquals(5_000L, state.remainingMs(ConvoyReactionKind.Police, now))
        assertTrue(state.isReady(ConvoyReactionKind.Police, now + 5_000))
    }

    @Test
    fun a_nonpositive_server_cooldown_clears_the_window() {
        val now = 1_000_000L
        val state =
            ConvoyReactionCooldownState()
                .recordSent(ConvoyReactionKind.Police, now)
                .applyServerCooldown(ConvoyReactionKind.Police, retryAfterMs = 0L, nowMs = now)
        assertTrue(state.isReady(ConvoyReactionKind.Police, now))
    }

    @Test
    fun clear_resets_a_kind_so_a_failed_send_can_retry() {
        val now = 1_000_000L
        val state =
            ConvoyReactionCooldownState()
                .recordSent(ConvoyReactionKind.FollowMe, now)
                .clear(ConvoyReactionKind.FollowMe)
        assertTrue(state.isReady(ConvoyReactionKind.FollowMe, now))
    }

    @Test
    fun payload_carries_convoy_and_wire_kind_and_optional_clientId() {
        val withKey =
            convoySendReactionPayload("convoy-1", ConvoyReactionKind.Police, clientId = "abc123")
        assertEquals("convoy-1", withKey["convoyId"])
        assertEquals("police", withKey["kind"])
        assertEquals("abc123", withKey["clientId"])

        val withoutKey = convoySendReactionPayload("convoy-1", ConvoyReactionKind.FollowMe)
        assertEquals("follow_me", withoutKey["kind"])
        assertFalse(withoutKey.containsKey("clientId"))
    }
}
