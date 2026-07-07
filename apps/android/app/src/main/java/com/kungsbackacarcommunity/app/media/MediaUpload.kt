package com.kungsbackacarcommunity.app.media

import java.util.UUID

/**
 * Pure media-upload helpers (Phase 12 media-uploads slice).
 *
 * The client mirrors the Cloud Storage Security Rules BEFORE hitting them so a
 * too-large or non-image pick fails fast with a clear message instead of a
 * generic permission-denied. These functions are Firebase-free and JVM-testable.
 *
 * Rules mirrored (firebase/storage.rules):
 *  - profileImages/{userId}/{imageId}         image, up to 5 MB, owner write.
 *  - vehicleImages/{userId}/{vehicleId}/{id}  image, up to 10 MB, member owner.
 */
object MediaUpload {

    /** Cloud Storage byte caps (mirrors isUnderMB(5) / isUnderMB(10)). */
    const val PROFILE_IMAGE_MAX_BYTES: Long = 5L * 1024 * 1024
    const val VEHICLE_IMAGE_MAX_BYTES: Long = 10L * 1024 * 1024

    /**
     * Content types the Storage rules accept
     * (`image/(jpeg|png|webp|gif)`). Anything else is rejected client-side so
     * the write never reaches the bucket.
     */
    private val ALLOWED_CONTENT_TYPES = setOf(
        "image/jpeg",
        "image/png",
        "image/webp",
        "image/gif",
    )

    /**
     * File extension (no leading dot) for an accepted image MIME type, or null
     * when the type is not an accepted image. Used to build the imageId so the
     * stored object keeps a sensible suffix.
     */
    fun extensionForMimeType(mimeType: String?): String? =
        when (mimeType?.trim()?.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> null
        }

    /** True when [mimeType] is an image type the rules accept. */
    fun isAllowedImageType(mimeType: String?): Boolean =
        mimeType?.trim()?.lowercase() in ALLOWED_CONTENT_TYPES

    /**
     * Fresh imageId of the form `<uuid>.<ext>`. [mimeType] must be an accepted
     * image type; falls back to `jpg` only if the caller passed an unexpected
     * type (callers pre-check with [precheck] first).
     */
    fun newImageId(mimeType: String?, uuid: String = UUID.randomUUID().toString()): String {
        val ext = extensionForMimeType(mimeType) ?: "jpg"
        return "$uuid.$ext"
    }

    /** Object path for a profile avatar: `profileImages/{uid}/{imageId}`. */
    fun profileImagePath(uid: String, imageId: String): String =
        "profileImages/$uid/$imageId"

    /**
     * Object path for a vehicle photo:
     * `vehicleImages/{uid}/{vehicleId}/{imageId}` — matches the storage rules
     * AND the garage-updateVehicle imagePath validation (single-segment id).
     */
    fun vehicleImagePath(uid: String, vehicleId: String, imageId: String): String =
        "vehicleImages/$uid/$vehicleId/$imageId"

    /** Why a client-side pre-check rejected a pick. */
    enum class PrecheckError { NOT_AN_IMAGE, TOO_LARGE }

    /**
     * Validates a picked image before upload, mirroring the rules so the write
     * never round-trips to a rejection. Returns the rejecting error, or null
     * when the pick is acceptable.
     */
    fun precheck(mimeType: String?, sizeBytes: Long, maxBytes: Long): PrecheckError? =
        when {
            !isAllowedImageType(mimeType) -> PrecheckError.NOT_AN_IMAGE
            sizeBytes > maxBytes -> PrecheckError.TOO_LARGE
            else -> null
        }
}
