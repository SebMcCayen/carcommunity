package com.kungsbackacarcommunity.app.roadcameras

import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RoadCameraCacheTest {
    private class Source : RoadCameraRepository {
        var catalogs = 0
        var photos = 0
        var fail = false
        override suspend fun catalog(): RoadCameraCatalog {
            catalogs++; if (fail) error("Offline")
            return RoadCameraCatalog(listOf(RoadCamera("a", RoadCameraType.Traffic, "E6", 57.0, 12.0)), null)
        }
        override suspend fun photo(cameraId: String): RoadCameraPhoto {
            photos++; delay(5)
            return RoadCameraPhoto(byteArrayOf(1, 2, 3), "image/jpeg", null)
        }
    }

    @Test fun `catalog survives restart and never downloads a photo`() = runBlocking {
        val directory = Files.createTempDirectory("road-camera-test").toFile()
        try {
            val source = Source(); var time = System.currentTimeMillis()
            val first = CachedRoadCameraRepository(directory, source) { time }
            first.catalog()
            val restarted = CachedRoadCameraRepository(directory, source) { time }
            restarted.catalog()
            assertEquals(1, source.catalogs); assertEquals(0, source.photos)
            time += CachedRoadCameraRepository.CATALOG_TTL + 1
            source.fail = true
            assertEquals("a", restarted.catalog().cameras.single().id)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `parallel image opens coalesce and expire from original fetch time`() = runBlocking {
        val directory = Files.createTempDirectory("road-camera-test").toFile()
        try {
            val source = Source(); var time = System.currentTimeMillis()
            val repository = CachedRoadCameraRepository(directory, source) { time }
            (1..5).map { async { repository.photo("same") } }.awaitAll()
            assertEquals(1, source.photos)
            time += 240_000
            repository.photo("same")
            time += 61_000
            repository.photo("same")
            assertEquals(2, source.photos)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `remote opens are throttled across cache instance restarts`() = runBlocking {
        val directory = Files.createTempDirectory("road-camera-test").toFile()
        try {
            val source = Source(); var time = System.currentTimeMillis()
            val repository = CachedRoadCameraRepository(directory, source) { time }
            repeat(6) { repository.photo("camera-$it") }
            val restarted = CachedRoadCameraRepository(directory, source) { time }
            try { restarted.photo("another"); fail("Expected limit") } catch (_: RoadCameraRateLimited) { }
            assertEquals(6, source.photos)
            repository.photo("camera-0") // Cached image remains available during the limit.
            time += 60_001
            restarted.photo("another")
            assertEquals(7, source.photos)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `photo cache has a bounded entry count`() = runBlocking {
        val directory = Files.createTempDirectory("road-camera-test").toFile()
        try {
            val source = Source(); var time = System.currentTimeMillis()
            val repository = CachedRoadCameraRepository(directory, source) { time }
            repeat(24) { repository.photo("camera-$it"); time += 60_001 }
            assertTrue(directory.listFiles().orEmpty().count { it.name.startsWith("photo-") && it.name != "photo-attempts" } <= 20)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `parser rejects unknown type and invalid coordinates`() {
        val base = mapOf("id" to "a", "name" to "E6", "type" to "traffic", "latitude" to 57.0, "longitude" to 12.0)
        assertNotNull(RoadCameraParser.parse(base))
        assertNull(RoadCameraParser.parse(base + ("latitude" to Double.NaN)))
        assertNull(RoadCameraParser.parse(base + ("longitude" to 181.0)))
        assertNull(RoadCameraParser.parse(base + ("type" to "unrecognized")))
    }
}
