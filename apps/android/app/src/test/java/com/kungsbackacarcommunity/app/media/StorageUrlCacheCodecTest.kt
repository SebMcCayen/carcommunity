package com.kungsbackacarcommunity.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persisted `storage path -> download URL` map is the whole reason a
 * revisited Garage paints its photos without a `getDownloadUrl()` round-trip, so
 * its serialisation and its LRU bookkeeping are pinned here: a map that fails to
 * survive process death silently reinstates the latency it exists to remove, and
 * a map that grows without bound is a leak nobody notices.
 */
class StorageUrlCacheCodecTest {

    private fun entry(n: Int) = StorageUrlEntry(
        path = "vehicleImages/uid/vehicle$n/$n.jpg",
        url = "https://firebasestorage.googleapis.com/v0/b/b/o/$n.jpg?alt=media&token=t$n",
    )

    @Test
    fun `encode then decode round-trips entries in order`() {
        val entries = listOf(entry(1), entry(2), entry(3))

        assertEquals(entries, StorageUrlCacheCodec.decode(StorageUrlCacheCodec.encode(entries)))
    }

    @Test
    fun `decode of nothing is empty`() {
        assertEquals(emptyList<StorageUrlEntry>(), StorageUrlCacheCodec.decode(null))
        assertEquals(emptyList<StorageUrlEntry>(), StorageUrlCacheCodec.decode(""))
    }

    @Test
    fun `decode keeps a real download URL intact including its query`() {
        val decoded = StorageUrlCacheCodec.decode(StorageUrlCacheCodec.encode(listOf(entry(7))))

        // The URL half must survive verbatim: the token IS the authorisation, so
        // a URL truncated at the first '?' or '&' would silently 403.
        assertEquals(entry(7).url, decoded.single().url)
    }

    @Test
    fun `decode skips malformed records instead of failing the whole read`() {
        val raw = "no-separator-here\n\tmissing-path\n${entry(1).path}\t${entry(1).url}\n${entry(2).path}\t"

        assertEquals(listOf(entry(1)), StorageUrlCacheCodec.decode(raw))
    }

    @Test
    fun `decode keeps the first mapping when a path repeats`() {
        val raw = "p\tfirst\np\tsecond"

        assertEquals(listOf(StorageUrlEntry("p", "first")), StorageUrlCacheCodec.decode(raw))
    }

    @Test
    fun `entries containing a separator are not storable and never encoded`() {
        val tabbed = StorageUrlEntry("pa\tth", "https://example.test/x")
        val newlined = StorageUrlEntry("path", "https://example.test/\nx")

        assertFalse(StorageUrlCacheCodec.isStorable(tabbed))
        assertFalse(StorageUrlCacheCodec.isStorable(newlined))
        assertTrue(StorageUrlCacheCodec.isStorable(entry(1)))
        assertEquals(
            listOf(entry(1)),
            StorageUrlCacheCodec.decode(StorageUrlCacheCodec.encode(listOf(tabbed, entry(1), newlined))),
        )
    }

    @Test
    fun `touch promotes an existing path to the head without duplicating it`() {
        val entries = listOf(entry(1), entry(2), entry(3))

        val touched = StorageUrlCacheCodec.touch(entries, entry(3).path, entry(3).url)

        assertEquals(listOf(entry(3), entry(1), entry(2)), touched)
    }

    @Test
    fun `touch replaces the URL a path maps to`() {
        val entries = listOf(entry(1))

        val touched = StorageUrlCacheCodec.touch(entries, entry(1).path, "https://example.test/fresh")

        assertEquals(listOf(StorageUrlEntry(entry(1).path, "https://example.test/fresh")), touched)
    }

    @Test
    fun `touch caps the map and evicts the least recently used entry`() {
        val full = (1..StorageUrlCacheCodec.MAX_ENTRIES).map(::entry)
        val leastRecentlyUsed = full.last()

        val touched = StorageUrlCacheCodec.touch(full, "fresh/path.jpg", "https://example.test/fresh")

        assertEquals(StorageUrlCacheCodec.MAX_ENTRIES, touched.size)
        assertEquals(StorageUrlEntry("fresh/path.jpg", "https://example.test/fresh"), touched.first())
        assertFalse(leastRecentlyUsed in touched)
    }

    @Test
    fun `encode and decode both respect the cap`() {
        val oversized = (1..(StorageUrlCacheCodec.MAX_ENTRIES + 10)).map(::entry)

        val encoded = StorageUrlCacheCodec.encode(oversized)

        assertEquals(oversized.take(StorageUrlCacheCodec.MAX_ENTRIES), StorageUrlCacheCodec.decode(encoded))
    }

    @Test
    fun `remove drops exactly the named path`() {
        val entries = listOf(entry(1), entry(2))

        assertEquals(listOf(entry(1)), StorageUrlCacheCodec.remove(entries, entry(2).path))
        assertEquals(entries, StorageUrlCacheCodec.remove(entries, "not/in/the/map.jpg"))
    }

    @Test
    fun `merge keeps freshly resolved entries ahead of and instead of stored ones`() {
        val live = listOf(StorageUrlEntry(entry(1).path, "https://example.test/just-resolved"))
        val stored = listOf(entry(1), entry(2))

        val merged = StorageUrlCacheCodec.merge(live, stored)

        assertEquals(listOf(live.single(), entry(2)), merged)
    }

    @Test
    fun `merge caps the result`() {
        val live = listOf(StorageUrlEntry("fresh/path.jpg", "https://example.test/fresh"))
        val stored = (1..StorageUrlCacheCodec.MAX_ENTRIES).map(::entry)

        val merged = StorageUrlCacheCodec.merge(live, stored)

        assertEquals(StorageUrlCacheCodec.MAX_ENTRIES, merged.size)
        assertEquals(live.single(), merged.first())
    }
}
