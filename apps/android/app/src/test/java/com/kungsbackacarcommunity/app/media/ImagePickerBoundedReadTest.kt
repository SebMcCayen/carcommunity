package com.kungsbackacarcommunity.app.media

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the bounded read used by the image picker: an unknown-size
 * stream must never materialize an array larger than the cap (OOM guard).
 */
class ImagePickerBoundedReadTest {

    @Test
    fun `reads a stream under the cap fully`() {
        val bytes = ByteArray(1_000) { it.toByte() }
        val result = readBounded(ByteArrayInputStream(bytes), maxBytes = 5_000)
        assertArrayEquals(bytes, result)
    }

    @Test
    fun `reads a stream exactly at the cap`() {
        val bytes = ByteArray(2_048) { (it % 7).toByte() }
        val result = readBounded(ByteArrayInputStream(bytes), maxBytes = 2_048)
        assertArrayEquals(bytes, result)
    }

    @Test
    fun `rejects a stream one byte over the cap`() {
        val bytes = ByteArray(2_049)
        val result = readBounded(ByteArrayInputStream(bytes), maxBytes = 2_048)
        assertNull(result)
    }

    @Test
    fun `rejects a large stream without materializing it`() {
        // Far larger than the cap; must bail early, returning null (not OOM).
        val bytes = ByteArray(1_000_000)
        val result = readBounded(ByteArrayInputStream(bytes), maxBytes = 10_000)
        assertNull(result)
    }

    @Test
    fun `reads an empty stream as empty bytes`() {
        val result = readBounded(ByteArrayInputStream(ByteArray(0)), maxBytes = 100)
        assertEquals(0, result?.size)
    }
}
