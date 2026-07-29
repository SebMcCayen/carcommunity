package com.kungsbackacarcommunity.app.media

import android.content.Context
import coil.ImageLoader

/**
 * The app's single Coil [ImageLoader], installed by `KccApplication` via
 * `coil.ImageLoaderFactory` so every `AsyncImage` in the app gets it.
 *
 * ## Why we don't take Coil's default loader
 * Coil enables its memory and disk caches by default, but it also defaults to
 * `respectCacheHeaders = true`, and on a disk-cache HIT that flag is the
 * difference between painting immediately and painting after a network
 * round-trip: `HttpUriFetcher` only short-circuits to the cached bytes without
 * consulting `CacheStrategy` when the flag is off; with it on, a cached response
 * that is not provably fresh is revalidated over the network first.
 *
 * Our images never come back provably fresh. Firebase Storage objects are
 * uploaded with content type only ([FirebaseMediaUploader]) — no `Cache-Control`
 * metadata — so they are served with the storage backend's conservative default
 * for a non-public object, which does not license reuse without revalidation.
 * The result was a revalidation round-trip per photo per screen entry, and no
 * photo at all offline, for bytes already on disk.
 *
 * Turning the flag off is safe here because a Storage URL is IMMUTABLE content:
 * every upload mints a fresh `<uuid>.<ext>` object id
 * ([MediaUpload.newImageId]), so a changed photo is a different path and a
 * different URL, never new bytes behind an old one. There is nothing for a
 * revalidation to discover.
 *
 * Cache sizes are left at Coil's defaults (a share of available memory, 2% of
 * the cache volume on disk) — the app shows tens of small images, not thousands,
 * and a hand-picked number here would be a guess with no measurement behind it.
 */
object KccImageLoader {

    /**
     * Fade-in for an image that had to be fetched. Matches the shell's own tab
     * crossfade so the app has one fade duration rather than two nearly-equal
     * ones. Coil skips the transition entirely on a memory-cache hit, so a
     * revisited photo still appears instantly rather than fading in again.
     */
    const val CROSSFADE_MILLIS: Int = 200

    fun create(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .respectCacheHeaders(false)
            .crossfade(CROSSFADE_MILLIS)
            .build()
}
