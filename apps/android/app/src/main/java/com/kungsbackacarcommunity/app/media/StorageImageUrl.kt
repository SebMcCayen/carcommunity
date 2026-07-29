package com.kungsbackacarcommunity.app.media

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.google.firebase.FirebaseApp
import com.google.firebase.storage.FirebaseStorage
import com.kungsbackacarcommunity.app.firebase.await

/**
 * Resolves a Cloud Storage object path (e.g. `profileImages/{uid}/{id}`) to a
 * download URL for Coil to load. The app stores paths, not URLs.
 *
 * `getDownloadUrl()` is a NETWORK CALL, so a resolved URL is remembered in
 * [StorageDownloadUrlCache] (memory + disk) and re-used on the next call: a
 * repeat view of the same image costs no round-trip at all, and works offline.
 * Pass [forceRefresh] to skip and drop the remembered mapping — the caller does
 * that when a remembered URL turned out not to load, which is what a rotated
 * download token looks like from here.
 *
 * The returned URL is a long-lived, tokenized download link: it embeds the
 * object's download token and stays valid until that token is rotated or
 * revoked (e.g. from the Firebase console). It is NOT a short-lived / expiring
 * signed URL — which is exactly why remembering it is safe.
 *
 * Returns null (Coil renders nothing) when Firebase is unavailable, the path is
 * blank, or resolution fails — a config-less build never crashes on rendering.
 */
suspend fun resolveStorageDownloadUrl(
    context: Context,
    path: String?,
    forceRefresh: Boolean = false,
): String? {
    if (path.isNullOrBlank()) return null
    if (forceRefresh) {
        StorageDownloadUrlCache.invalidate(context, path)
    } else {
        StorageDownloadUrlCache.cached(context, path)?.let { return it }
    }
    if (FirebaseApp.getApps(context).isEmpty()) return null
    val resolved = runCatching {
        FirebaseStorage.getInstance().reference.child(path).downloadUrl.await()
    }.getOrNull()?.toString() ?: return null
    StorageDownloadUrlCache.put(context, path, resolved)
    return resolved
}

/**
 * A Storage-backed image, as a screen sees it: the [url] to hand Coil (null
 * while resolving or when resolution is not possible) plus the way to report
 * back that the URL did not load.
 */
@Immutable
class StorageImage internal constructor(
    val url: String?,
    /**
     * Call when the image loader reports that the SERVER rejected [url] — a 4xx,
     * i.e. what a rotated or revoked download token looks like from here. Drops
     * the remembered `path -> url` mapping and re-resolves ONCE.
     *
     * Deliberately once: a second failure is a real failure (the object is
     * gone), and retrying it would spin the resolver against a wall.
     *
     * Deliberately NOT for transport failures. Being offline is the case where
     * the remembered URL is most valuable — it is what lets Coil serve the photo
     * out of its disk cache with no network at all — so a connectivity error
     * must never be allowed to delete it. Callers discriminate on the error's
     * cause; see the `onError` handler in `VehicleCard`.
     */
    val onLoadFailed: () -> Unit,
)

/**
 * Compose helper: resolves [path] to a download URL, re-resolving when [path]
 * changes.
 *
 * Seeds itself synchronously from [StorageDownloadUrlCache.peek], so an image
 * this process has already resolved is handed to Coil on the FIRST composed
 * frame rather than after a round-trip — that, plus Coil's memory/disk cache,
 * is what makes a re-entered screen paint its photos immediately.
 */
@Composable
fun rememberStorageImage(context: Context, path: String?): StorageImage {
    var attempt by remember(path) { mutableIntStateOf(0) }
    var url by remember(path, attempt) {
        mutableStateOf(if (attempt == 0) StorageDownloadUrlCache.peek(path) else null)
    }
    LaunchedEffect(path, attempt) {
        // Non-null only on the seeded first pass, where the work is already done.
        if (url == null) {
            url = resolveStorageDownloadUrl(context, path, forceRefresh = attempt > 0)
        }
    }
    val resolved = url
    return remember(resolved, attempt) {
        StorageImage(url = resolved) {
            if (attempt == 0 && resolved != null) attempt = 1
        }
    }
}

/**
 * Compose helper: the download URL for [path], or null while resolving or when
 * resolution is not possible. Shorthand for [rememberStorageImage] at the many
 * call sites (avatars, list rows) that have no error affordance to wire up.
 */
@Composable
fun rememberStorageImageUrl(context: Context, path: String?): String? =
    rememberStorageImage(context, path).url
