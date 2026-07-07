package com.kungsbackacarcommunity.app.media

import kotlinx.coroutines.CancellationException
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

    @Test
    fun `success uploads, persists the stored path, ends Uploaded`() = runTest {
        val uploader = FakeUploader()
        val coordinator = ImageUploadCoordinator(uploader, MediaUpload.PROFILE_IMAGE_MAX_BYTES)
        var persisted: String? = null

        coordinator.upload(image, "profileImages/u1/a.jpg") { stored -> persisted = stored }

        assertEquals(listOf(Triple("profileImages/u1/a.jpg", 1_000, "image/jpeg")), uploader.uploaded)
        assertEquals("profileImages/u1/a.jpg", persisted)
        assertEquals(ImageUploadStatus.Uploaded, coordinator.status.value)
    }

    @Test
    fun `too-large never uploads and surfaces TooLarge`() = runTest {
        val uploader = FakeUploader()
        val coordinator = ImageUploadCoordinator(uploader, maxBytes = 500)
        var persisted = false

        coordinator.upload(image, "profileImages/u1/a.jpg") { persisted = true }

        assertTrue(uploader.uploaded.isEmpty())
        assertEquals(false, persisted)
        assertEquals(ImageUploadStatus.TooLarge, coordinator.status.value)
    }

    @Test
    fun `non-image never uploads and surfaces Failed`() = runTest {
        val uploader = FakeUploader()
        val coordinator = ImageUploadCoordinator(uploader, MediaUpload.PROFILE_IMAGE_MAX_BYTES)

        coordinator.upload(
            PickedImage(ByteArray(10), "application/pdf"),
            "profileImages/u1/a.jpg",
        ) {}

        assertTrue(uploader.uploaded.isEmpty())
        assertEquals(ImageUploadStatus.Failed, coordinator.status.value)
    }

    @Test
    fun `an upload failure surfaces Failed and can reset`() = runTest {
        val uploader = FakeUploader().apply { failWith = IllegalStateException("denied") }
        val coordinator = ImageUploadCoordinator(uploader, MediaUpload.VEHICLE_IMAGE_MAX_BYTES)

        coordinator.upload(image, "vehicleImages/u1/v9/a.jpg") {}

        assertEquals(ImageUploadStatus.Failed, coordinator.status.value)
        coordinator.reset()
        assertEquals(ImageUploadStatus.Idle, coordinator.status.value)
    }

    @Test
    fun `a persist failure surfaces Failed`() = runTest {
        val uploader = FakeUploader()
        val coordinator = ImageUploadCoordinator(uploader, MediaUpload.VEHICLE_IMAGE_MAX_BYTES)

        coordinator.upload(image, "vehicleImages/u1/v9/a.jpg") {
            throw IllegalStateException("callable rejected imagePath")
        }

        assertEquals(ImageUploadStatus.Failed, coordinator.status.value)
        // The bytes DID upload before the persist failed (retry re-persists).
        assertEquals(1, uploader.uploaded.size)
    }

    @Test
    fun `cancellation is rethrown and leaves Idle`() = runTest {
        val uploader = FakeUploader().apply { failWith = CancellationException("c") }
        val coordinator = ImageUploadCoordinator(uploader, MediaUpload.PROFILE_IMAGE_MAX_BYTES)
        var rethrown = false

        try {
            coordinator.upload(image, "profileImages/u1/a.jpg") {}
        } catch (c: CancellationException) {
            rethrown = true
        }

        assertTrue(rethrown)
        assertEquals(ImageUploadStatus.Idle, coordinator.status.value)
    }

    @Test
    fun `uses the vehicle path pattern end to end`() = runTest {
        val uploader = FakeUploader()
        val coordinator = ImageUploadCoordinator(uploader, MediaUpload.VEHICLE_IMAGE_MAX_BYTES)
        val imageId = MediaUpload.newImageId("image/jpeg", uuid = "id-1")
        val path = MediaUpload.vehicleImagePath("u1", "v9", imageId)
        var persisted: String? = null

        coordinator.upload(image, path) { persisted = it }

        assertEquals("vehicleImages/u1/v9/id-1.jpg", persisted)
        assertNull(MediaUpload.precheck("image/jpeg", image.sizeBytes, MediaUpload.VEHICLE_IMAGE_MAX_BYTES))
    }
}
