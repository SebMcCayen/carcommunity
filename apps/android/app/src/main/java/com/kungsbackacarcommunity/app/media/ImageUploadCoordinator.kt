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
 * Orchestrates one image upload (Phase 12 media-uploads slice): client pre-check
 * → upload bytes to a caller-built path → invoke [persist] with the stored path.
 * Pure Kotlin so both the avatar and vehicle flows are unit-testable with a fake
 * [MediaUploader]. The path is built by the caller (owner+id are known there);
 * this coordinator only enforces the shared size/type rules and drives status.
 */
class ImageUploadCoordinator(
    private val uploader: MediaUploader,
    private val maxBytes: Long,
) {
    private val state = MutableStateFlow<ImageUploadStatus>(ImageUploadStatus.Idle)
    val status: StateFlow<ImageUploadStatus> = state.asStateFlow()

    // Atomic check-and-set guard: only the coroutine that wins tryLock() runs an
    // upload; a concurrent second call fails the lock and is a no-op. Held for
    // the whole upload+persist and always released in finally.
    private val uploadLock = Mutex()

    /**
     * Pre-checks [picked] against the shared rules, uploads to [path], then runs
     * [persist] (the domain write that records the path — a Firestore avatar
     * write or the garage-updateVehicle callable). Re-entrant calls while an
     * upload is in flight are rejected atomically (a concurrent call is a no-op).
     */
    suspend fun upload(
        picked: PickedImage,
        path: String,
        persist: suspend (storedPath: String) -> Unit,
    ) {
        // Atomic guard: if another upload already holds the lock, do nothing.
        if (!uploadLock.tryLock()) return
        try {
            when (MediaUpload.precheck(picked.contentType, picked.sizeBytes, maxBytes)) {
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
                val stored = uploader.upload(path, picked.bytes, picked.contentType!!)
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
