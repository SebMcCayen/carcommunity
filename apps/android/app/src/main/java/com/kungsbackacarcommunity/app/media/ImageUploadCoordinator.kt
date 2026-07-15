package com.kungsbackacarcommunity.app.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/** A picked image ready to upload (bytes + its content type + size). */
data class PickedImage(
    val bytes: ByteArray,
    val contentType: String?,
) {
    val sizeBytes: Long get() = bytes.size.toLong()

    // Value-based equality (ByteArray needs it) so this is a well-behaved data
    // class; used only in tests, but avoids a surprising identity comparison.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PickedImage) return false
        return contentType == other.contentType && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * (contentType?.hashCode() ?: 0) + bytes.contentHashCode()
}

/** UI-facing progress of an image upload. */
sealed interface ImageUploadStatus {
    data object Idle : ImageUploadStatus

    data object Uploading : ImageUploadStatus

    data object Uploaded : ImageUploadStatus

    /** The pick exceeded the byte cap (client pre-check). */
    data object TooLarge : ImageUploadStatus

    /** The pick was not an accepted image type, or the upload/persist failed. */
    data object Failed : ImageUploadStatus
}

/**
 * Orchestrates one image upload (Phase 12 media-uploads slice): compress + strip
 * metadata → client pre-check → upload bytes to a caller-built path → invoke
 * [persist] with the stored path.
 *
 * Compression/metadata-stripping is CENTRALISED here (via [compress], defaulting
 * to [ImageCompressor.compress]) so EVERY caller — avatar, vehicle photo, and any
 * future image upload — gets a downscaled, EXIF-free (no GPS) JPEG by
 * construction and cannot accidentally upload a raw pick. [maxDimension] tunes
 * the longest-side cap per upload type (avatars smaller, vehicle photos larger).
 * When [compress] returns null the pick could not be proven metadata-free, so the
 * upload FAILS rather than leak geotagged bytes.
 *
 * Pure Kotlin so both flows are unit-testable with a fake [MediaUploader] and a
 * fake [compress]. The path is built by the caller from the *processed* content
 * type (owner+id are known there); this coordinator enforces the shared size/type
 * rules and drives status.
 */
class ImageUploadCoordinator(
    private val uploader: MediaUploader,
    private val maxBytes: Long,
    private val maxDimension: Int = ImageCompressor.AVATAR_MAX_DIMENSION,
    private val compress: suspend (picked: PickedImage, maxDimension: Int) -> PickedImage? =
        { picked, dimension -> ImageCompressor.compress(picked, dimension) },
) {
    private val state = MutableStateFlow<ImageUploadStatus>(ImageUploadStatus.Idle)
    val status: StateFlow<ImageUploadStatus> = state.asStateFlow()

    // Atomic check-and-set guard: only the coroutine that wins tryLock() runs an
    // upload; a concurrent second call fails the lock and is a no-op. Held for
    // the whole upload+persist and always released in finally.
    private val uploadLock = Mutex()

    /**
     * Compresses + metadata-strips [picked], pre-checks it against the shared
     * rules, uploads to the path built by [pathFor] from the PROCESSED content
     * type (so the stored object's extension matches the re-encoded bytes), then
     * runs [persist] (the domain write that records the path — a Firestore avatar
     * write or the garage-updateVehicle callable). Re-entrant calls while an
     * upload is in flight are rejected atomically (a concurrent call is a no-op).
     */
    suspend fun upload(
        picked: PickedImage,
        pathFor: (contentType: String?) -> String,
        persist: suspend (storedPath: String) -> Unit,
    ) {
        // Atomic guard: if another upload already holds the lock, do nothing.
        if (!uploadLock.tryLock()) return
        try {
            // Centralised compression + EXIF/GPS strip. A null result means the
            // pick could not be proven metadata-free — fail rather than leak it.
            val prepared = compress(picked, maxDimension)
            if (prepared == null) {
                state.value = ImageUploadStatus.Failed
                return
            }

            when (MediaUpload.precheck(prepared.contentType, prepared.sizeBytes, maxBytes)) {
                MediaUpload.PrecheckError.TOO_LARGE -> {
                    state.value = ImageUploadStatus.TooLarge
                    return
                }
                MediaUpload.PrecheckError.NOT_AN_IMAGE -> {
                    state.value = ImageUploadStatus.Failed
                    return
                }
                null -> Unit
            }

            state.value = ImageUploadStatus.Uploading
            try {
                // contentType is guaranteed non-null by the pre-check above.
                val path = pathFor(prepared.contentType)
                val stored = uploader.upload(path, prepared.bytes, prepared.contentType!!)
                persist(stored)
                state.value = ImageUploadStatus.Uploaded
            } catch (cancellation: CancellationException) {
                state.value = ImageUploadStatus.Idle
                throw cancellation
            } catch (failure: Exception) {
                state.value = ImageUploadStatus.Failed
            }
        } finally {
            uploadLock.unlock()
        }
    }

    /** Resets to idle after the UI consumes a terminal state. */
    fun reset() {
        state.value = ImageUploadStatus.Idle
    }
}
