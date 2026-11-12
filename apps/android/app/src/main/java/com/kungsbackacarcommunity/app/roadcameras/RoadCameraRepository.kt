package com.kungsbackacarcommunity.app.roadcameras

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class RoadCameraType(val wire: String) {
    Speed("speed"), Traffic("traffic"), RoadCondition("road_condition");
    companion object { fun fromWire(value: String?) = entries.find { it.wire == value } }
}

data class RoadCamera(val id: String, val type: RoadCameraType, val name: String,
    val latitude: Double, val longitude: Double, val direction: Double? = null)
data class RoadCameraCatalog(val cameras: List<RoadCamera>, val updatedAt: String?)
data class RoadCameraPhoto(val bytes: ByteArray, val contentType: String, val photoTime: String?)
class RoadCameraRateLimited : Exception()

interface RoadCameraRepository {
    suspend fun catalog(): RoadCameraCatalog
    suspend fun photo(cameraId: String): RoadCameraPhoto
}

/** Public road data only. A single mutex coalesces repeated opens after the first fills the cache. */
class CachedRoadCameraRepository(
    private val directory: File,
    private val source: RoadCameraRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : RoadCameraRepository {
    private val mutex = Mutex()
    private var cooldownUntil = 0L
    init { directory.mkdirs() }

    override suspend fun catalog(): RoadCameraCatalog = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = File(directory, "catalog-v1")
            val cached = readCatalog(file)
            if (cached != null && fresh(file, CATALOG_TTL)) return@withLock cached
            try {
                source.catalog().also { value ->
                    require(value.cameras.size <= 10_000)
                    atomicWrite(file) { output ->
                        output.writeUTF(value.updatedAt.orEmpty())
                        output.writeInt(value.cameras.size)
                        value.cameras.forEach {
                            output.writeUTF(it.id); output.writeUTF(it.type.wire); output.writeUTF(it.name)
                            output.writeDouble(it.latitude); output.writeDouble(it.longitude)
                            output.writeDouble(it.direction ?: Double.NaN)
                        }
                    }
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                cached ?: throw error
            }
        }
    }

    override suspend fun photo(cameraId: String): RoadCameraPhoto = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = File(directory, "photo-${digest(cameraId)}")
            readPhoto(file)?.takeIf { fresh(file, PHOTO_TTL) }?.let {
                file.setLastModified(now()) // LRU is separately recorded; expiry stays in the payload.
                return@withLock it
            }
            val time = now()
            if (time < cooldownUntil) throw RoadCameraRateLimited()
            val attemptsFile = File(directory, "photo-attempts")
            val attempts = runCatching { attemptsFile.readLines().mapNotNull(String::toLongOrNull) }
                .getOrDefault(emptyList()).filter { it <= time && time - it < 60_000 }
            if (attempts.size >= 6) throw RoadCameraRateLimited()
            attemptsFile.writeText((attempts + time).joinToString("\n"))
            try {
                source.photo(cameraId).also { value ->
                    require(value.bytes.isNotEmpty() && value.bytes.size <= MAX_PHOTO_BYTES)
                    require(value.contentType in setOf("image/jpeg", "image/png"))
                    atomicWrite(file) { output ->
                        output.writeUTF(value.contentType); output.writeUTF(value.photoTime.orEmpty())
                        output.writeLong(time); output.writeInt(value.bytes.size); output.write(value.bytes)
                    }
                    prunePhotos()
                }
            } catch (error: RoadCameraRateLimited) {
                cooldownUntil = time + 60_000
                throw error
            }
        }
    }

    private fun readCatalog(file: File): RoadCameraCatalog? = runCatching {
        if (!file.isFile || file.length() > 4 * 1024 * 1024) return null
        DataInputStream(file.inputStream().buffered()).use { input ->
            val updatedAt = input.readUTF().ifEmpty { null }
            val count = input.readInt(); require(count in 0..10_000)
            RoadCameraCatalog(List(count) {
                val id = input.readUTF(); val type = requireNotNull(RoadCameraType.fromWire(input.readUTF()))
                val name = input.readUTF(); val latitude = input.readDouble(); val longitude = input.readDouble()
                val direction = input.readDouble().takeIf(Double::isFinite)
                require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0)
                RoadCamera(id, type, name, latitude, longitude, direction)
            }, updatedAt)
        }
    }.getOrNull()

    private fun readPhoto(file: File): RoadCameraPhoto? = runCatching {
        if (!file.isFile || file.length() > MAX_PHOTO_BYTES + 4096) return null
        DataInputStream(file.inputStream().buffered()).use { input ->
            val type = input.readUTF(); require(type in setOf("image/jpeg", "image/png"))
            val photoTime = input.readUTF().ifEmpty { null }
            val savedAt = input.readLong(); require(now() >= savedAt && now() - savedAt < PHOTO_TTL)
            val length = input.readInt(); require(length in 1..MAX_PHOTO_BYTES)
            RoadCameraPhoto(ByteArray(length).also(input::readFully), type, photoTime)
        }
    }.getOrNull()

    private fun fresh(file: File, ttl: Long) = now() >= file.lastModified() && now() - file.lastModified() < ttl
    private fun atomicWrite(file: File, write: (DataOutputStream) -> Unit) {
        val temporary = File(directory, "${file.name}.tmp")
        DataOutputStream(temporary.outputStream().buffered()).use(write)
        check(temporary.renameTo(file))
        file.setLastModified(now())
    }
    private fun prunePhotos() {
        val files = directory.listFiles()?.filter { it.name.startsWith("photo-") && it.name != "photo-attempts" }
            ?.sortedByDescending(File::lastModified).orEmpty()
        var total = 0L
        files.forEachIndexed { index, file ->
            total += file.length()
            if (index >= 20 || total > 4 * 1024 * 1024) file.delete()
        }
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
    companion object {
        const val CATALOG_TTL = 24 * 60 * 60 * 1000L
        const val PHOTO_TTL = 5 * 60 * 1000L
        const val MAX_PHOTO_BYTES = 128 * 1024
    }
}
