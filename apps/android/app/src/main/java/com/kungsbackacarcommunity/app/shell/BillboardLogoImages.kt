package com.kungsbackacarcommunity.app.shell

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.SuccessResult
import com.kungsbackacarcommunity.app.media.LogoImagePolicy
import com.kungsbackacarcommunity.app.media.rememberLogoStorageSession
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Bounded per-version logos. Authenticated bytes use Coil's account-scoped caches. */
@Composable
internal fun rememberBillboardLogoImages(paths: List<String>): Map<String, Bitmap> {
    val context = LocalContext.current
    val uniquePaths = remember(paths) { paths.filter(LogoImagePolicy::allowed).distinct().take(LogoImagePolicy.BILLBOARD_LIMIT) }
    val imageLoader = remember(context) { context.imageLoader }
    val session = rememberLogoStorageSession(context)
    val bitmaps by produceState<Map<String, Bitmap>>(emptyMap(), uniquePaths, imageLoader, session) {
        value = emptyMap()
        if (session == null) return@produceState
        val gate = Semaphore(LogoImagePolicy.PARALLEL_DOWNLOADS)
        coroutineScope {
            uniquePaths.map { path -> async {
                gate.withPermit {
                    val request = session.request(context, path, 128) ?: return@withPermit null
                    val result = imageLoader.execute(request)
                    (result as? SuccessResult)?.drawable?.toBitmap()?.let { path to it }
                }
            } }.awaitAll().filterNotNull().toMap().also { value = it }
        }
    }
    return bitmaps
}
