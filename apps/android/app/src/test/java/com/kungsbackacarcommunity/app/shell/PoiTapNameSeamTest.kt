package com.kungsbackacarcommunity.app.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers [resolvePoiTapName], the crash-safe seam behind the `standardPoi` tap
 * handler in `MapboxMapSurface.Content`.
 *
 * The regression this exists to catch: a tapped basemap POI whose `name`
 * property is a LITERAL JSON null. Mapbox's `Feature.getStringProperty("name")`
 * (which `poi.name` calls) does `properties().get("name")?.getAsString()`, and
 * `get()` returns a non-null Gson `JsonNull` for a literal null — so
 * `getAsString()` throws `UnsupportedOperationException` and the app crashed
 * (Crashlytics `…JsonNull`). The seam must swallow that into the same
 * `null`/dropped-pin fallback used for a blank or absent name.
 */
class PoiTapNameSeamTest {

    @Test
    fun `a JsonNull-getAsString throw collapses to null instead of crashing`() {
        // Exactly what poi.name does on a literal-null name property.
        assertNull(
            resolvePoiTapName { throw UnsupportedOperationException("JsonNull") },
        )
    }

    @Test
    fun `an absent name (Kotlin null) is null`() {
        assertNull(resolvePoiTapName { null })
    }

    @Test
    fun `a blank name falls back to null`() {
        assertNull(resolvePoiTapName { "   " })
    }

    @Test
    fun `a real name is trimmed and kept`() {
        assertEquals("Circle K", resolvePoiTapName { "  Circle K  " })
    }
}
