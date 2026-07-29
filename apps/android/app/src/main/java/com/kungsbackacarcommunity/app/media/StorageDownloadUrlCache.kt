package com.kungsbackacarcommunity.app.media

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Remembers which download URL a Cloud Storage path resolved to, in memory and
 * across process death.
 *
 * ## Why this exists
 * The app stores Storage *paths* and turns them into URLs at render time with
 * `getDownloadUrl()` ([resolveStorageDownloadUrl]) — and that is a NETWORK
 * ROUND-TRIP per image, paid before Coil is even handed a model to load. Without
 * this cache, every entry into My Garage re-paid one round-trip per car, and no
 * amount of image-bytes caching could hide it, because the byte cache is keyed
 * on a URL the app did not have yet. Offline, the round-trip simply failed and
 * the photo never appeared even though its bytes were sitting in Coil's disk
 * cache.
 *
 * ## Why caching the URL is safe
 * The resolved URL is a long-lived tokenized download link, not an expiring
 * signed URL, and the object under a given path is immutable — a new photo is a
 * new `<uuid>.<ext>` object ([MediaUpload.newImageId]), never a rewrite of the
 * old one. The one way a remembered URL can go bad is a token rotation/revoke
 * from the Firebase console, which is rare and handled at the call site: a
 * cached URL that fails to load drops its entry here and re-resolves once (see
 * `rememberStorageImage`). There is deliberately no time-based expiry — an
 * expiry short enough to beat a rotation would re-introduce the round-trip this
 * exists to remove, and a failed load is a precise signal where a clock is a
 * guess.
 *
 * Reads are non-blocking off the in-memory map ([peek]); the persisted layer is
 * read and written from suspending functions on [Dispatchers.IO]. The one
 * exception is [clear], which sign-out calls straight from the main thread and
 * which therefore leans on `apply()`'s own background write.
 */
object StorageDownloadUrlCache {

    private const val PREFS_NAME = "kcc_storage_download_urls"
    private const val KEY_ENTRIES = "entries"

    private val lock = Any()

    /** MRU-first, guarded by [lock]. */
    private var entries: List<StorageUrlEntry> = emptyList()

    @Volatile
    private var loadedFromDisk: Boolean = false

    /**
     * Bumped by [clear]. An in-flight disk read that started before a clear must
     * not merge the cleared member's entries back in when it lands, so it checks
     * this before committing.
     */
    private var generation: Int = 0

    /**
     * The remembered URL for [path] from memory only — never touches disk or the
     * network, so a composable may call it during composition to paint a
     * previously seen image on its FIRST frame.
     */
    fun peek(path: String?): String? {
        if (path.isNullOrBlank()) return null
        synchronized(lock) {
            return entries.firstOrNull { it.path == path }?.url
        }
    }

    /** The remembered URL for [path], falling back to the persisted map. */
    suspend fun cached(context: Context, path: String): String? {
        peek(path)?.let { return it }
        ensureLoaded(context)
        return peek(path)
    }

    /** Remembers that [path] resolves to [url], persisting the updated map. */
    suspend fun put(context: Context, path: String, url: String) {
        if (path.isBlank() || url.isEmpty()) return
        ensureLoaded(context)
        val snapshot = synchronized(lock) {
            StorageUrlCacheCodec.touch(entries, path, url).also { entries = it }
        }
        persist(context, snapshot)
    }

    /** Forgets [path], so the next resolution goes back to `getDownloadUrl()`. */
    suspend fun invalidate(context: Context, path: String) {
        if (path.isBlank()) return
        ensureLoaded(context)
        val snapshot = synchronized(lock) {
            StorageUrlCacheCodec.remove(entries, path).also { entries = it }
        }
        persist(context, snapshot)
    }

    /**
     * Forgets everything, in memory and on disk.
     *
     * Called on SIGN-OUT. A Firebase download URL is a bearer credential — the
     * token in it *is* the authorisation — so leaving the departing member's
     * resolved links on the device would hand whoever signs in next a fetchable
     * URL for every image that member had viewed. Mirrors how the rest of the
     * per-member local state is dropped at sign-out (see `MainActivity.signOut`).
     *
     * Safe to call from the main thread: the in-memory drop is immediate and
     * `apply()` hands the file write to a background thread.
     */
    fun clear(context: Context) {
        synchronized(lock) {
            entries = emptyList()
            generation++
            // Nothing left on disk worth reading back.
            loadedFromDisk = true
        }
        runCatching { prefs(context).edit().remove(KEY_ENTRIES).apply() }
    }

    /**
     * Reads the persisted map into memory once per process.
     *
     * Concurrent callers may all reach [withContext]; the merge under [lock] is
     * idempotent and [StorageUrlCacheCodec.merge] keeps freshly resolved entries
     * ahead of whatever was on disk, so a lost race costs a redundant read, never
     * a stale overwrite. A read overtaken by a [clear] is dropped outright.
     */
    private suspend fun ensureLoaded(context: Context) {
        if (loadedFromDisk) return
        val startedAt = synchronized(lock) { generation }
        withContext(Dispatchers.IO) {
            val stored = StorageUrlCacheCodec.decode(
                runCatching { prefs(context).getString(KEY_ENTRIES, null) }.getOrNull(),
            )
            synchronized(lock) {
                if (generation != startedAt) return@withContext
                entries = StorageUrlCacheCodec.merge(entries, stored)
                loadedFromDisk = true
            }
        }
    }

    private suspend fun persist(context: Context, snapshot: List<StorageUrlEntry>) {
        withContext(Dispatchers.IO) {
            runCatching {
                prefs(context)
                    .edit()
                    .putString(KEY_ENTRIES, StorageUrlCacheCodec.encode(snapshot))
                    .apply()
            }
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
