package com.kungsbackacarcommunity.app.shell

import android.graphics.Color
import android.graphics.Bitmap
import java.io.File
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.crownhunt.CrownMarkerStyle
import com.kungsbackacarcommunity.app.crownhunt.CrownRarity
import com.kungsbackacarcommunity.app.crownhunt.crownGlyphRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Pixel regression: the basemap must remain visible inside the crown ring. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CrownMarkerBitmapTest {
    @Test
    fun allRarities_haveTransparentInteriors_andCollectedBadgesRemainDistinct() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val density = context.resources.displayMetrics.density
        for (rarity in CrownRarity.entries) {
            val appearance = CrownMarkerStyle.spawnMarkerAppearance(rarity, true, false)
            val bitmap = requireNotNull(CrownMarkerBitmaps.create(context, crownGlyphRes(rarity),
                appearance.discColorArgb, appearance.glyphColorArgb, appearance.glowColorArgb))
            val folder = File("build/reports/design-markers").apply { mkdirs() }
            File(folder, "${rarity.name.lowercase()}.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            val centre = bitmap.width / 2f
            // Inspect the whole former disc interior, including the spaces
            // around the silhouette. Its solid centre is legitimately opaque.
            val innerRadius = (CrownMarkerStyle.DISC_DIAMETER_DP / 2f - 0.5f) * density
            var transparent = 0
            var total = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                if ((x - centre) * (x - centre) + (y - centre) * (y - centre) > innerRadius * innerRadius) continue
                total++
                if (Color.alpha(bitmap.getPixel(x, y)) == 0) transparent++
            }
            assertTrue("$rarity still fills its ring like an incident marker", transparent > total / 10)
            assertTrue("$rarity lost its crown silhouette", transparent < total)
        }
        val collected = CrownMarkerStyle.spawnMarkerAppearance(CrownRarity.COMMON, true, true)
        val bitmap = requireNotNull(CrownMarkerBitmaps.create(context, crownGlyphRes(CrownRarity.COMMON),
            collected.discColorArgb, collected.glyphColorArgb, collected.glowColorArgb, collected.collectedBadge))
        val centre = bitmap.width / 2f
        val badgeCentre = centre + (CrownMarkerStyle.DISC_DIAMETER_DP / 2f + 3.5f) * density * 0.70710677f
        // A point away from the check strokes still has the success fill, not slate.
        val x = (badgeCentre + 3f * density).toInt()
        val y = (badgeCentre + 3f * density).toInt()
        assertEquals(CrownMarkerStyle.COLLECTED_BADGE_FILL, bitmap.getPixel(x, y))
    }
}
