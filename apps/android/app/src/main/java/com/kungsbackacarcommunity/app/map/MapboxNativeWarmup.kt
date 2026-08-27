package com.kungsbackacarcommunity.app.map

import android.content.Context
import android.util.Log
import com.mapbox.common.MapboxSDKCommonInitializer
import com.mapbox.maps.loader.MapboxMapsInitializer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loads the Mapbox native libraries (`mapbox-common`, `mapbox-maps`) OFF the
 * main thread, early, so the first [com.mapbox.maps.MapView] the member reaches
 * does not pay the cold `dlopen` cost on the UI thread.
 *
 * ## Why this exists (ANR #1000: `MapboxLibraryLoader.load` on the main thread)
 *
 * The Maps SDK ships two `androidx.startup` initializers, discovered via
 * `<meta-data>` under `androidx.startup.InitializationProvider`:
 *
 *  - `com.mapbox.common.MapboxSDKCommonInitializer`  → loads `mapbox-common`
 *  - `com.mapbox.maps.loader.MapboxMapsInitializer`  → loads `mapbox-maps`
 *
 * `androidx.startup`'s `InitializationProvider.onCreate()` runs on the MAIN
 * thread during process creation — before `Application.onCreate()`. Both
 * initializers' `create()` (via `BaseMapboxInitializer`) call the library
 * loader EAGERLY (the internal init flag is `true`), so each cold start
 * `System.loadLibrary`s a large Mapbox native library synchronously on the main
 * thread. On slow / cold devices the two `dlopen`s (with their relocation and
 * JNI registration) block the main thread long enough to trip an ANR — the exact
 * `com.mapbox.common.loader.MapboxLibraryLoader.load` frame Crashlytics reported.
 *
 * The manifest ([AndroidManifest.xml]) removes those two `<meta-data>` entries
 * with `tools:node="remove"`, so `androidx.startup` no longer loads the native
 * libraries on the main thread at startup. This helper then performs the exact
 * same initialization on a background thread instead. By the time composition
 * reaches the map surface, the libraries are already resident and the
 * main-thread `MapView` construction is a no-op load.
 *
 * ## Safety / idempotence
 *
 * `BaseMapboxInitializer` tracks per-initializer state under a global
 * `ReentrantLock`, so running the initializers here is idempotent: a second call
 * (or the SDK's own lazy path, should a `MapView` somehow be built before this
 * finishes) is a no-op that returns immediately. In the worst case — a `MapView`
 * constructed on the main thread before this background load wins the race — the
 * main thread simply does (or waits on) the load exactly as it would have before
 * this change, so this is strictly an improvement and never a regression. Only
 * the native-library preload runs off-main; no Android View is created here
 * (Views must be built on the main thread), so nothing about the map's own
 * behaviour changes.
 *
 * [warmUp] is called once from [com.kungsbackacarcommunity.app.KccApplication].
 */
internal object MapboxNativeWarmup {

    private const val TAG = "MapboxNativeWarmup"

    private val started = AtomicBoolean(false)

    /**
     * Kicks off the native-library preload on a single low-priority background
     * thread. Cheap and non-blocking to call; safe to call more than once (only
     * the first call does work). Never throws — any failure is swallowed and the
     * SDK's own lazy load remains as the fallback.
     */
    fun warmUp(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        Thread({
            try {
                // Order mirrors androidx.startup's dependency graph: common first
                // (maps depends on it), then maps. Each create() loads its native
                // library via the same loader that used to run on the main thread.
                MapboxSDKCommonInitializer().create(appContext)
                MapboxMapsInitializer().create(appContext)
            } catch (e: Exception) {
                // Preload is a pure optimization: if it fails, the Maps SDK still
                // loads the library lazily the first time a MapView is built. Do
                // not crash the app for a warm-up miss.
                Log.w(TAG, "Mapbox native warm-up failed; falling back to lazy load", e)
            } catch (e: LinkageError) {
                // A native-load failure surfaces as UnsatisfiedLinkError (a
                // LinkageError, NOT an Exception) — still just a warm-up miss the
                // lazy load will retry, so swallow it too. Deliberately does NOT
                // catch other Errors (OutOfMemoryError, etc.): those are fatal VM
                // conditions that must not be suppressed on this thread.
                Log.w(TAG, "Mapbox native warm-up failed to link; falling back to lazy load", e)
            }
        }, "mapbox-native-warmup").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }.start()
    }
}
