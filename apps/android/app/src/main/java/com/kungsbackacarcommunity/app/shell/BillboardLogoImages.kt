package com.kungsbackacarcommunity.app.shell

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.kungsbackacarcommunity.app.media.resolveStorageDownloadUrl
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Bounded per-version logos. Coil and the Storage URL cache reuse prior downloads. */
@Composable
internal fun rememberBillboardLogoImages(paths: List<String>): Map<String, Bitmap> {
    val context = LocalContext.current
    val uniquePaths = remember(paths) { paths.distinct().take(150) }
    val imageLoader = remember(context) { context.imageLoader }
    val bitmaps by produceState<Map<String, Bitmap>>(emptyMap(), uniquePaths, imageLoader) {
        value = emptyMap()
        val gate = Semaphore(4)
        coroutineScope {
            uniquePaths.map { path -> async {
                gate.withPermit {
                    val url = resolveStorageDownloadUrl(context, path) ?: return@withPermit null
                    val result = imageLoader.execute(ImageRequest.Builder(context).data(url).size(128).allowHardware(false).build())
                    (result as? SuccessResult)?.drawable?.toBitmap()?.let { path to it }
                }
            } }.awaitAll().filterNotNull().toMap().also { value = it }
        }
    }
    return bitmaps
}
