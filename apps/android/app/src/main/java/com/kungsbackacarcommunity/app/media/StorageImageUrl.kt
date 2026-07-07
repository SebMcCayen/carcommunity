package com.kungsbackacarcommunity.app.media

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.storage.FirebaseStorage
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Resolves a Cloud Storage object path (e.g. `profileImages/{uid}/{id}`) to a
 * download URL for Coil to load. The app stores paths, not URLs; the URL is a
 * short-lived, authenticated handle resolved at render time.
 *
 * Returns null (Coil renders nothing) when Firebase is unavailable, the path is
 * blank, or resolution fails — a config-less build never crashes on rendering.
 */
suspend fun resolveStorageDownloadUrl(context: Context, path: String?): String? {
    if (path.isNullOrBlank()) return null
    if (FirebaseApp.getApps(context).isEmpty()) return null
    return runCatching {
        FirebaseStorage.getInstance().reference.child(path).downloadUrl.awaitUrl()
    }.getOrNull()?.toString()
}

/** Minimal Task -> suspend bridge (no kotlinx-coroutines-play-services dep). */
private suspend fun <T> Task<T>.awaitUrl(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result ->
            if (continuation.isActive) continuation.resume(result)
        }.addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

/**
 * Compose helper: resolves [path] to a download URL, re-resolving when [path]
 * changes. Emits null while resolving or when resolution is not possible.
 */
@Composable
fun rememberStorageImageUrl(context: Context, path: String?): String? {
    var url by remember(path) { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        url = resolveStorageDownloadUrl(context, path)
    }
    return url
}
