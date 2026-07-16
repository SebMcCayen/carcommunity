package com.kungsbackacarcommunity.app.media

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.google.firebase.FirebaseApp
import com.google.firebase.storage.FirebaseStorage
import com.kungsbackacarcommunity.app.firebase.await

/**
 * Resolves a Cloud Storage object path (e.g. `profileImages/{uid}/{id}`) to a
 * download URL for Coil to load. The app stores paths, not URLs, and resolves
 * the URL at render time via `getDownloadUrl()`.
 *
 * The returned URL is a long-lived, tokenized download link: it embeds the
 * object's download token and stays valid until that token is rotated or
 * revoked (e.g. from the Firebase console). It is NOT a short-lived / expiring
 * signed URL — do not persist or cache it as if it will expire on its own.
 *
 * Returns null (Coil renders nothing) when Firebase is unavailable, the path is
 * blank, or resolution fails — a config-less build never crashes on rendering.
 */
suspend fun resolveStorageDownloadUrl(context: Context, path: String?): String? {
    if (path.isNullOrBlank()) return null
    if (FirebaseApp.getApps(context).isEmpty()) return null
    return runCatching {
        FirebaseStorage.getInstance().reference.child(path).downloadUrl.await()
    }.getOrNull()?.toString()
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
