package com.kungsbackacarcommunity.app.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageUploadCoordinatorTest {

    private class FakeUploader : MediaUploader {
        val uploaded = mutableListOf<Triple<String, Int, String>>()
        var failWith: Exception? = null

        override suspend fun upload(path: String, bytes: ByteArray, contentType: String): String {
            failWith?.let { throw it }
            uploaded += Triple(path, bytes.size, contentType)
            return path
        }
    }

    private val image = PickedImage(bytes = ByteArray(1_000), contentType = "image/jpeg")

    // Identity compressor: the real ImageCompressor needs Android's Bitmap/EXIF
    // (instrumentation), so unit tests inject a fake here. Passing the pick
    // straight through lets us exercise the coordinator's own logic on the JVM.
    private fun identityCompress(): suspend (PickedImage, Int) -> PickedImage? =
        { picked, _ -> picked }

    private fun coordinator(
        uploader: MediaUploader,
        maxBytes: Long = MediaUpload.PROFILE_IMAGE_MAX_BYTES,
        compress: suspend (PickedImage, Int) -> PickedImage? = identityCompress(),
    ) = ImageUploadCoordinator(uploader, maxBytes, compress = compress)

    @Test
    fun `success uploads, persists the stored path, ends Uploaded`() = runTest {
        val uploader = FakeUploader()
        val coordinator = coordinator(uploader)
        var persisted: String? = null

        coordinator.upload(image, { "profileImages/u1/a.jpg" }) { stored -> persisted = stored }

        assertEquals(listOf(Triple("profileImages/u1/a.jpg", 1_000, "image/jpeg")), uploader.uploaded)
        assertEquals("profileImages/u1/a.jpg", persisted)
        assertEquals(ImageUploadStatus.Uploaded, coordinator.status.value)
    }

    @Test
    fun `compression runs and the path is built from the processed content type`() = runTest {
        val uploader = FakeUploader()
        // Fake compressor that re-encodes a PNG pick to a smaller JPEG, mirroring
        // the real strip+downscale: the coordinator must upload the JPEG bytes and
        // build the path from the PROCESSED (jpeg) content type, not the raw png.
        val compressed = PickedImage(bytes = ByteArray(200), contentType = "image/jpeg")
        val coordinator = coordinator(uploader, compress = { _, _ -> compressed })
        var persisted: String? = null

        coordinator.upload(
            PickedImage(ByteArray(4_000), "image/png"),
            pathFor = { ct -> "profileImages/u1/${MediaUpload.newImageId(ct, uuid = "id")}" },
        ) { persisted = it }

        assertEquals(listOf(Triple("profileImages/u1/id.jpg", 200, "image/jpeg")), uploader.uploaded)
        assertEquals("profileImages/u1/id.jpg", persisted)
        assertEquals(ImageUploadStatus.Uploaded, coordinator.status.value)
    }

    @Test
    fun `a null compression result fails closed and never uploads`() = runTest {
        // Null models a pick that could not be proven metadata-free (e.g. an
        // undecodable, geotagged image): the coordinator must fail, not leak it.
        val uploader = FakeUploader()
        val coordinator = coordinator(uploader, compress = { _, _ -> null })
        var persisted = false

        coordinator.upload(image, { "profileImages/u1/a.jpg" }) { persisted = true }

        assertTrue(uploader.uploaded.isEmpty())
        assertEquals(false, persisted)
        assertEquals(ImageUploadStatus.Failed, coordinator.status.value)
    }

    @Test
    fun `too-large is checked on the compressed result and surfaces TooLarge`() = runTest {
        val uploader = FakeUploader()
        val coordinator = coordinator(uploader, maxBytes = 500)
        var persisted = false

        coordinator.upload(image, { "profileImages/u1/a.jpg" }) { persisted = true }

        assertTrue(uploader.uploaded.isEmpty())
        assertEquals(false, persisted)
        assertEquals(ImageUploadStatus.TooLarge, coordinator.status.value)
    }

    @Test
    fun `non-image never uploads and surfaces Failed`() = runTest {
        val uploader = FakeUploader()
        val coordinator = coordinator(uploader)

        coordinator.upload(
            PickedImage(ByteArray(10), "application/pdf"),
            { "profileImages/u1/a.jpg" },
        ) {}

        assertTrue(uploader.uploaded.isEmpty())
        assertEquals(ImageUploadStatus.Failed, coordinator.status.value)
    }

    @Test
    fun `an upload failure surfaces Failed and can reset`() = runTest {
        val uploader = FakeUploader().apply { failWith = IllegalStateException("denied") }
        val coordinator = coordinator(uploader, MediaUpload.VEHICLE_IMAGE_MAX_BYTES)

        coordinator.upload(image, { "vehicleImages/u1/v9/a.jpg" }) {}

        assertEquals(ImageUploadStatus.Failed, coordinator.status.value)
        coordinator.reset()
        assertEquals(ImageUploadStatus.Idle, coordinator.status.value)
    }

    @Test
    fun `a persist failure surfaces Failed`() = runTest {
        val uploader = FakeUploader()
        val coordinator = coordinator(uploader, MediaUpload.VEHICLE_IMAGE_MAX_BYTES)

        coordinator.upload(image, { "vehicleImages/u1/v9/a.jpg" }) {
            throw IllegalStateException("callable rejected imagePath")
        }

        assertEquals(ImageUploadStatus.Failed, coordinator.status.value)
        // The bytes DID upload before the persist failed (retry re-persists).
        assertEquals(1, uploader.uploaded.size)
    }

    @Test
    fun `cancellation is rethrown and leaves Idle`() = runTest {
        val uploader = FakeUploader().apply { failWith = CancellationException("c") }
        val coordinator = coordinator(uploader)
        var rethrown = false

        try {
            coordinator.upload(image, { "profileImages/u1/a.jpg" }) {}
        } catch (c: CancellationException) {
            rethrown = true
        }

        assertTrue(rethrown)
        assertEquals(ImageUploadStatus.Idle, coordinator.status.value)
    }

    @Test
    fun `a concurrent second upload is a no-op while one is in flight`() = runTest {
        // Uploader that blocks until released, so two coroutines race the guard.
        val gate = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val blockingUploader =
            object : MediaUploader {
                override suspend fun upload(
                    path: String,
                    bytes: ByteArray,
                    contentType: String,
                ): String {
                    started += path
                    gate.await()
                    return path
                }
            }
        val coordinator = coordinator(blockingUploader)
        var firstPersists = 0
        var secondPersists = 0

        // First upload grabs the lock and parks inside the uploader.
        val first = launch { coordinator.upload(image, { "p/first.jpg" }) { firstPersists++ } }
        // Let the first upload reach the blocking uploader.
        testScheduler.advanceUntilIdle()
        assertEquals(ImageUploadStatus.Uploading, coordinator.status.value)

        // Second concurrent call must be rejected atomically (no-op).
        coordinator.upload(image, { "p/second.jpg" }) { secondPersists++ }
        assertEquals(listOf("p/first.jpg"), started)
        assertEquals(0, secondPersists)

        // Release the first upload and let it finish.
        gate.complete(Unit)
        first.join()
        assertEquals(1, firstPersists)
        assertEquals(0, secondPersists)
        assertEquals(listOf("p/first.jpg"), started)
        assertEquals(ImageUploadStatus.Uploaded, coordinator.status.value)
    }

    @Test
    fun `uses the vehicle path pattern end to end`() = runTest {
        val uploader = FakeUploader()
        val coordinator = coordinator(uploader, MediaUpload.VEHICLE_IMAGE_MAX_BYTES)
        var persisted: String? = null

        coordinator.upload(
            image,
            pathFor = { ct ->
                MediaUpload.vehicleImagePath("u1", "v9", MediaUpload.newImageId(ct, uuid = "id-1"))
            },
        ) { persisted = it }

        assertEquals("vehicleImages/u1/v9/id-1.jpg", persisted)
        assertNull(MediaUpload.precheck("image/jpeg", image.sizeBytes, MediaUpload.VEHICLE_IMAGE_MAX_BYTES))
    }
}
