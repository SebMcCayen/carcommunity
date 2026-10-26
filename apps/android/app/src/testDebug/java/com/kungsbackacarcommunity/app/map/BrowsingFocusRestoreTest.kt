package com.kungsbackacarcommunity.app.map

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.kungsbackacarcommunity.app.location.LastKnownLocation
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Recreate the store as a new process would, before any fresh location arrives. */
@RunWith(AndroidJUnit4::class)
class BrowsingFocusRestoreTest {
    @Test fun recreatedStoreSeedsTheVeryFirstBrowsingCamera() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("app_map_zoom_preference", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            MapZoomPreferenceStore(context).set(13.5)
            val restored = MapZoomPreferenceStore(context).browsingZoom.value
            val cached = LastKnownLocation(latitude = 57.49, longitude = 12.08)
            assertEquals(13.5, BrowsingInitialCamera.resolve(cached, restored).zoom, 0.0)
            assertEquals(13.5, BrowsingInitialCamera.resolve(null, restored).zoom, 0.0)
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
