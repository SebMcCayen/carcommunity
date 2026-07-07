package com.kungsbackacarcommunity.app.media

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [MediaUploader] backed by Cloud Storage (Phase 12 media-uploads slice).
 *
 * Uploads bytes with `putBytes` and a content-type in the object metadata so
 * the Security Rules' `request.resource.contentType` check passes. Construction
 * is guarded ([createIfAvailable] returns null when Firebase is not configured),
 * so the config-less CI build carries no uploader and the UI degrades to a
 * disabled state.
 */
class FirebaseMediaUploader private constructor(
    private val storage: FirebaseStorage,
) : MediaUploader {

    override suspend fun upload(path: String, bytes: ByteArray, contentType: String): String {
        val metadata = StorageMetadata.Builder().setContentType(contentType).build()
        val reference = storage.reference.child(path)
        return suspendCancellableCoroutine { continuation ->
            val task = reference.putBytes(bytes, metadata)
            task.addOnSuccessListener {
                if (continuation.isActive) continuation.resume(path)
            }.addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            continuation.invokeOnCancellation { task.cancel() }
        }
    }

    companion object {
        fun createIfAvailable(context: Context): MediaUploader? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            return FirebaseMediaUploader(FirebaseStorage.getInstance())
        }
    }
}
