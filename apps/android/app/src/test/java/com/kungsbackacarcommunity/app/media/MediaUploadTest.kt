package com.kungsbackacarcommunity.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUploadTest {

    // --- Path building (both patterns) ---------------------------------------

    @Test
    fun `profile image path follows profileImages uid imageId`() {
        assertEquals(
            "profileImages/u1/abc.jpg",
            MediaUpload.profileImagePath("u1", "abc.jpg"),
        )
    }

    @Test
    fun `vehicle image path follows vehicleImages uid vehicleId imageId`() {
        assertEquals(
            "vehicleImages/u1/v9/abc.png",
            MediaUpload.vehicleImagePath("u1", "v9", "abc.png"),
        )
    }

    @Test
    fun `vehicle image path is a single-segment imageId (matches backend validation)`() {
        val uid = "u1"
        val vehicleId = "v9"
        val path = MediaUpload.vehicleImagePath(uid, vehicleId, MediaUpload.newImageId("image/jpeg"))
        val prefix = "vehicleImages/$uid/$vehicleId/"
        assertTrue(path.startsWith(prefix))
        val imageId = path.removePrefix(prefix)
        assertFalse("imageId must not contain a slash", imageId.contains("/"))
        assertTrue(imageId.isNotEmpty())
    }

    // --- Extension from mime type --------------------------------------------

    @Test
    fun `extension maps accepted image mime types`() {
        assertEquals("jpg", MediaUpload.extensionForMimeType("image/jpeg"))
        assertEquals("png", MediaUpload.extensionForMimeType("image/png"))
        assertEquals("webp", MediaUpload.extensionForMimeType("image/webp"))
        assertEquals("gif", MediaUpload.extensionForMimeType("image/gif"))
    }

    @Test
    fun `extension is case-insensitive and trims`() {
        assertEquals("jpg", MediaUpload.extensionForMimeType("  IMAGE/JPEG "))
    }

    @Test
    fun `extension is null for non-image or unknown types`() {
        assertNull(MediaUpload.extensionForMimeType("application/pdf"))
        assertNull(MediaUpload.extensionForMimeType("image/bmp"))
        assertNull(MediaUpload.extensionForMimeType(null))
    }

    @Test
    fun `newImageId is uuid dot ext`() {
        assertEquals("uuid-1.png", MediaUpload.newImageId("image/png", uuid = "uuid-1"))
    }

    @Test
    fun `newImageId falls back to jpg for an unexpected type`() {
        assertEquals("uuid-1.jpg", MediaUpload.newImageId("application/pdf", uuid = "uuid-1"))
    }

    // --- Type check ----------------------------------------------------------

    @Test
    fun `isAllowedImageType accepts the rules whitelist only`() {
        assertTrue(MediaUpload.isAllowedImageType("image/jpeg"))
        assertTrue(MediaUpload.isAllowedImageType("image/png"))
        assertTrue(MediaUpload.isAllowedImageType("image/webp"))
        assertTrue(MediaUpload.isAllowedImageType("image/gif"))
        assertFalse(MediaUpload.isAllowedImageType("image/bmp"))
        assertFalse(MediaUpload.isAllowedImageType("text/plain"))
        assertFalse(MediaUpload.isAllowedImageType(null))
    }

    // --- Size / type pre-check (mirrors the rules) ---------------------------

    @Test
    fun `precheck accepts an in-bounds image`() {
        assertNull(
            MediaUpload.precheck(
                mimeType = "image/jpeg",
                sizeBytes = 1_000,
                maxBytes = MediaUpload.PROFILE_IMAGE_MAX_BYTES,
            ),
        )
    }

    @Test
    fun `precheck rejects a non-image before size`() {
        assertEquals(
            MediaUpload.PrecheckError.NOT_AN_IMAGE,
            MediaUpload.precheck("application/pdf", 1, MediaUpload.PROFILE_IMAGE_MAX_BYTES),
        )
    }

    @Test
    fun `precheck rejects an over-cap image`() {
        assertEquals(
            MediaUpload.PrecheckError.TOO_LARGE,
            MediaUpload.precheck(
                "image/png",
                MediaUpload.PROFILE_IMAGE_MAX_BYTES + 1,
                MediaUpload.PROFILE_IMAGE_MAX_BYTES,
            ),
        )
    }

    @Test
    fun `precheck accepts exactly the cap (rules use less-than-or-equal)`() {
        assertNull(
            MediaUpload.precheck(
                "image/png",
                MediaUpload.VEHICLE_IMAGE_MAX_BYTES,
                MediaUpload.VEHICLE_IMAGE_MAX_BYTES,
            ),
        )
    }

    @Test
    fun `caps mirror the storage rules (5 and 10 MB)`() {
        assertEquals(5L * 1024 * 1024, MediaUpload.PROFILE_IMAGE_MAX_BYTES)
        assertEquals(10L * 1024 * 1024, MediaUpload.VEHICLE_IMAGE_MAX_BYTES)
    }
}
