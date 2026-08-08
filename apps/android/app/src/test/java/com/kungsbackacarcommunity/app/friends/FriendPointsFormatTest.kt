package com.kungsbackacarcommunity.app.friends

import org.junit.Assert.assertEquals
import org.junit.Test

class FriendPointsFormatTest {

    @Test
    fun `small balances are printed as-is`() {
        assertEquals("0", FriendPointsFormat.grouped(0))
        assertEquals("7", FriendPointsFormat.grouped(7))
        assertEquals("999", FriendPointsFormat.grouped(999))
    }

    @Test
    fun `thousands are grouped with a space`() {
        assertEquals("1 000", FriendPointsFormat.grouped(1_000))
        assertEquals("1 240", FriendPointsFormat.grouped(1_240))
        assertEquals("12 000", FriendPointsFormat.grouped(12_000))
        assertEquals("123 456", FriendPointsFormat.grouped(123_456))
    }

    @Test
    fun `millions get a group boundary every three digits`() {
        assertEquals("1 000 000", FriendPointsFormat.grouped(1_000_000))
        assertEquals("12 345 678", FriendPointsFormat.grouped(12_345_678))
    }

    @Test
    fun `a negative balance keeps its sign`() {
        assertEquals("-1 240", FriendPointsFormat.grouped(-1_240))
    }

    @Test
    fun `Long extremes format without overflow`() {
        assertEquals("9 223 372 036 854 775 807", FriendPointsFormat.grouped(Long.MAX_VALUE))
        assertEquals("-9 223 372 036 854 775 808", FriendPointsFormat.grouped(Long.MIN_VALUE))
    }
}
