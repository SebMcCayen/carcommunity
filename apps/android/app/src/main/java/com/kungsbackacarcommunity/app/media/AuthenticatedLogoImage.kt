package com.kungsbackacarcommunity.app.media

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.disk.DiskCache
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.ImageRequest
import coil.request.Options
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.kungsbackacarcommunity.app.firebase.await
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.Buffer

/** Authenticated logos never require (or mint) a public Storage download token. */
internal object LogoImagePolicy {
    const val MAX_BYTES = 1024L * 1024
    const val MAX_PIXELS = 16_000_000L
    const val MAX_DIMENSION = 4096
    const val BILLBOARD_LIMIT = 150
    const val PARALLEL_DOWNLOADS = 4
    private val pathPattern = Regex("^(companyImages|billboardImages)/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$")

    fun allowed(path: String?): Boolean = path != null && pathPattern.matches(path) &&
        path.split('/').none { it == "." || it == ".." }

    // Stable across profile/list/map requests and process restarts, scoped to the
    // Firebase app, bucket and signed-in account. A new logo path is a new version.
    fun cacheKey(owner: String, path: String): String = "kcc-auth-logo-" +
        MessageDigest.getInstance("SHA-256").digest("$owner\n$path".toByteArray())
            .joinToString("") { "%02x".format(it) }
}

internal data class AuthenticatedLogoSource(val path: String, val cacheKey: String)

/** One session snapshot. Requests are rejected after sign-out or an account switch. */
internal class LogoStorageSession(
    private val auth: FirebaseAuth,
    private val storage: FirebaseStorage,
    private val uid: String,
) {
    fun request(context: Context, path: String?, size: Int): ImageRequest? {
        if (!LogoImagePolicy.allowed(path) || auth.currentUser?.uid != uid) return null
        val owner = "${storage.app.name}:${storage.reference.bucket}:$uid"
        val source = AuthenticatedLogoSource(path!!, LogoImagePolicy.cacheKey(owner, path))
        return ImageRequest.Builder(context)
            .data(source)
            .memoryCacheKey(source.cacheKey)
            .diskCacheKey(source.cacheKey)
            .size(size.coerceIn(1, 512))
            .allowHardware(false)
            .fetcherFactory(object : Fetcher.Factory<AuthenticatedLogoSource> {
                override fun create(data: AuthenticatedLogoSource, options: Options, imageLoader: ImageLoader): Fetcher =
                    AuthenticatedLogoFetcher(context, data, imageLoader.diskCache,
                        isCurrentOwner = { auth.currentUser?.uid == uid },
                        readBytes = { objectPath, limit -> storage.reference.child(objectPath).getBytes(limit).await() })
            })
            .build()
    }
}

/** Updates both image and map requests immediately when Firebase auth changes. */
@Composable
internal fun rememberLogoStorageSession(context: Context): LogoStorageSession? {
    val app = remember(context) {
        try { FirebaseApp.getInstance() } catch (_: IllegalStateException) { null }
    }
    val auth = remember(app) { app?.let(FirebaseAuth::getInstance) }
    var uid by remember(auth) { mutableStateOf(auth?.currentUser?.uid) }
    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { uid = it.currentUser?.uid }
        auth?.addAuthStateListener(listener)
        onDispose { auth?.removeAuthStateListener(listener) }
    }
    return remember(app, auth, uid) {
        if (app == null || auth == null || uid == null) null
        else LogoStorageSession(auth, FirebaseStorage.getInstance(app), uid!!)
    }
}

/**
 * Coil's normal memory cache precedes this fetcher. Its bounded disk cache stores
 * authenticated bytes under the same account/path key, so a repeat/offline load
 * needs no Storage request. A miss uses the Firebase SDK with a 1 MiB limit.
 */
@OptIn(coil.annotation.ExperimentalCoilApi::class)
internal class AuthenticatedLogoFetcher(
    private val context: Context,
    private val source: AuthenticatedLogoSource,
    private val diskCache: DiskCache?,
    private val isCurrentOwner: () -> Boolean,
    private val readBytes: suspend (String, Long) -> ByteArray,
) : Fetcher {
    override suspend fun fetch(): SourceResult? = withContext(Dispatchers.IO) {
        if (!LogoImagePolicy.allowed(source.path) || !isCurrentOwner()) return@withContext null
        val cached = cacheAttempt {
            diskCache?.openSnapshot(source.cacheKey)?.use { snapshot ->
                val file = snapshot.data.toFile()
                if (file.length() in 1..LogoImagePolicy.MAX_BYTES) file.readBytes() else null
            }
        }
        if (cached != null && validImage(cached)) {
            return@withContext result(cached, DataSource.DISK)
        }
        downloads.withPermit {
            if (!isCurrentOwner()) return@withPermit null
            val bytes = try {
                readBytes(source.path, LogoImagePolicy.MAX_BYTES)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { return@withPermit null }
            if (!validImage(bytes) || !isCurrentOwner()) return@withPermit null
            cacheAttempt {
                diskCache?.openEditor(source.cacheKey)?.let { editor ->
                    try {
                        editor.metadata.toFile().writeText("kcc-auth-logo-v1")
                        editor.data.toFile().writeBytes(bytes)
                        editor.commit()
                    } catch (error: Exception) {
                        editor.abort()
                        throw error
                    }
                }
            }
            result(bytes, DataSource.NETWORK)
        }
    }

    private fun result(bytes: ByteArray, dataSource: DataSource): SourceResult? =
        if (!isCurrentOwner()) null else SourceResult(ImageSource(Buffer().write(bytes), context), null, dataSource)

    private fun validImage(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > LogoImagePolicy.MAX_BYTES) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth in 1..LogoImagePolicy.MAX_DIMENSION &&
            bounds.outHeight in 1..LogoImagePolicy.MAX_DIMENSION &&
            bounds.outWidth.toLong() * bounds.outHeight <= LogoImagePolicy.MAX_PIXELS
    }

    private fun <T> cacheAttempt(block: () -> T): T? = try { block()
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) { null }

    private companion object {
        val downloads = Semaphore(LogoImagePolicy.PARALLEL_DOWNLOADS)
    }
}
