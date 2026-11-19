package com.kungsbackacarcommunity.app.media

import android.graphics.Bitmap
import coil.decode.DataSource
import coil.disk.DiskCache
import coil.fetch.SourceResult
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(coil.annotation.ExperimentalCoilApi::class)
class AuthenticatedLogoImageTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var disk: DiskCache
    private val path = "companyImages/partner/logo-v1.png"
    private val key get() = LogoImagePolicy.cacheKey("app:bucket:member-a", path)

    @Before fun setup() {
        disk = DiskCache.Builder().directory(context.cacheDir.resolve("logo-test-${System.nanoTime()}"))
            .maxSizeBytes(4L * 1024 * 1024).build()
    }
    @After fun cleanup() { disk.clear() }

    private fun png(width: Int = 8, height: Int = 8): ByteArray {
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() } }
        finally { image.recycle() }
    }
    private fun fetcher(owner: () -> Boolean = { true }, read: suspend (String, Long) -> ByteArray) =
        AuthenticatedLogoFetcher(context, AuthenticatedLogoSource(path, key), disk, owner, read)
    private fun SourceResult.bytes() = source.use { it.source().readByteArray() }

    @Test fun `only canonical company and billboard objects are allowed`() {
        assertTrue(LogoImagePolicy.allowed(path))
        assertTrue(LogoImagePolicy.allowed("billboardImages/board/logo-v2.png"))
        for (invalid in listOf(null, "", "https://example.test/logo.png", "gs://bucket/$path", "companyImages/p/../logo.png",
            "companyImages/../logo.png", "companyImages/./logo.png", "companyImages/p/%2E%2E", "companyImages/p/logo.png?token=x",
            "profileImages/p/logo.png", "/$path", "$path/extra")) assertFalse(invalid, LogoImagePolicy.allowed(invalid))
    }
    @Test fun `profile map cache key is stable and separates account bucket and logo versions`() {
        assertEquals(key, LogoImagePolicy.cacheKey("app:bucket:member-a", path))
        assertNotEquals(key, LogoImagePolicy.cacheKey("app:bucket:member-b", path))
        assertNotEquals(key, LogoImagePolicy.cacheKey("app:other-bucket:member-a", path))
        assertNotEquals(key, LogoImagePolicy.cacheKey("app:bucket:member-a", "$path-v2"))
        assertFalse(key.contains("member-a"))
    }
    @Test fun `tokenless bytes load within SDK cap then reuse the disk cache offline`() = runBlocking {
        val expected = png()
        var reads = 0
        val first = fetcher { objectPath, limit ->
            assertEquals(path, objectPath)
            assertEquals(1024L * 1024, limit)
            reads++; expected
        }.fetch()!!
        assertEquals(DataSource.NETWORK, first.dataSource)
        assertArrayEquals(expected, first.bytes())
        val second = fetcher { _, _ -> error("A cached logo must not require any Storage or URL request") }.fetch()!!
        assertEquals(DataSource.DISK, second.dataSource)
        assertArrayEquals(expected, second.bytes())
        assertEquals(1, reads)
    }
    @Test fun `signed out session cannot read an existing cache entry`() = runBlocking {
        fetcher { _, _ -> png() }.fetch()!!.source.close()
        assertNull(fetcher(owner = { false }) { _, _ -> error("Signed-out fetch") }.fetch())
    }
    @Test fun `account switch during transfer cannot display or cache departing account bytes`() = runBlocking {
        var current = true
        assertNull(fetcher(owner = { current }) { _, _ -> current = false; png() }.fetch())
        assertNull(disk.openSnapshot(key))
    }
    @Test fun `oversized empty and invalid images do not enter the cache`() = runBlocking {
        for (bytes in listOf(ByteArray(LogoImagePolicy.MAX_BYTES.toInt() + 1), byteArrayOf(), "not an image".toByteArray(), png(5000, 1))) {
            assertNull(fetcher { _, _ -> bytes }.fetch())
            assertNull(disk.openSnapshot(key))
        }
    }
    @Test fun `a failed transfer is not cached and a later retry can succeed`() = runBlocking {
        assertNull(fetcher { _, _ -> throw IOException("Local fixture unavailable") }.fetch())
        assertEquals(DataSource.NETWORK, fetcher { _, _ -> png() }.fetch()!!.also { it.source.close() }.dataSource)
    }
    @Test(expected = CancellationException::class)
    fun `cancellation propagates rather than becoming a fallback image`() = runBlocking {
        fetcher { _, _ -> throw CancellationException("Cancelled fixture") }.fetch()
        Unit
    }
}
