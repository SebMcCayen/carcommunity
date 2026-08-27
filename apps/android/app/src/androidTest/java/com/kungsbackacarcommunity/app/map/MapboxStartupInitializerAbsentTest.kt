package com.kungsbackacarcommunity.app.map

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression guard for #1000 (ANR: `MapboxLibraryLoader.load` on the main
 * thread). The Maps SDK ships two `androidx.startup` initializers —
 * [MAPBOX_COMMON_INITIALIZER] and [MAPBOX_MAPS_INITIALIZER] — whose `create()`
 * eagerly `System.loadLibrary(...)` the native libs. `androidx.startup`'s
 * `InitializationProvider` runs on the MAIN thread during process creation
 * (before `Application.onCreate`), so leaving them in place `dlopen`s two large
 * libraries on the UI thread and ANRs on slow cold starts.
 *
 * The fix removes both `<meta-data>` entries from the merged manifest via
 * `tools:node="remove"` and re-runs the identical initialization OFF the main
 * thread from `KccApplication#onCreate` (see `MapboxNativeWarmup`). This test
 * reads the merged manifest at runtime through the [PackageManager] and fails if
 * either initializer ever reappears (an accidental manifest edit, a refactor, or
 * a dependency bump re-merging the SDK's provider entries), which would silently
 * reintroduce the eager main-thread load.
 */
@RunWith(AndroidJUnit4::class)
class MapboxStartupInitializerAbsentTest {

    @Suppress("DEPRECATION") // int-flag overload; the ComponentInfoFlags overload is API 33+ only.
    @Test
    fun mapboxStartupInitializersAreRemovedFromMergedManifest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val authority = "${context.packageName}.androidx-startup"

        val providerInfo =
            context.packageManager.resolveContentProvider(
                authority,
                PackageManager.GET_META_DATA,
            )
        assertNotNull(
            "androidx.startup InitializationProvider not found for authority $authority",
            providerInfo,
        )

        // A provider with NO <meta-data> at all means every initializer (Mapbox
        // included) is absent — that is the desired end state, so treat a null/
        // empty bundle as a pass. Only when the provider still carries initializer
        // meta-data do we assert the two Mapbox keys are not among them.
        val metaData = providerInfo!!.metaData
        if (metaData != null && !metaData.isEmpty) {
            assertFalse(
                "$MAPBOX_COMMON_INITIALIZER must stay removed from the manifest (ANR #1000) — " +
                    "the Mapbox native load must not run on the main thread at startup.",
                metaData.containsKey(MAPBOX_COMMON_INITIALIZER),
            )
            assertFalse(
                "$MAPBOX_MAPS_INITIALIZER must stay removed from the manifest (ANR #1000) — " +
                    "the Mapbox native load must not run on the main thread at startup.",
                metaData.containsKey(MAPBOX_MAPS_INITIALIZER),
            )
        }
    }

    /**
     * The other half of the fix: with the startup initializers removed, the Maps
     * SDK must still initialize when the SAME work is run from [MapboxNativeWarmup]
     * instead. This exercises the real warm-up code path on-device — loading both
     * native libraries via the same loader the removed initializers used — and
     * asserts it succeeds. It directly answers the "does disabling the startup
     * initializers break SDK init?" risk at runtime (and re-runs in CI), rather
     * than relying on manifest inspection alone.
     */
    @Test
    fun mapboxNativeLibrariesInitializeViaWarmUpWithStartupInitializersDisabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        assertTrue(
            "Maps SDK native init must succeed via the off-main warm-up path when the " +
                "androidx.startup initializers are removed (ANR #1000).",
            MapboxNativeWarmup.loadNativeLibrariesBlocking(context),
        )
        // Idempotent: running it again (as the lazy-load fallback or Application's
        // own warm-up would) is a no-op that still reports success.
        assertTrue(
            "Maps SDK native init must be idempotent under repeated warm-up.",
            MapboxNativeWarmup.loadNativeLibrariesBlocking(context),
        )
    }

    private companion object {
        const val MAPBOX_COMMON_INITIALIZER = "com.mapbox.common.MapboxSDKCommonInitializer"
        const val MAPBOX_MAPS_INITIALIZER = "com.mapbox.maps.loader.MapboxMapsInitializer"
    }
}
