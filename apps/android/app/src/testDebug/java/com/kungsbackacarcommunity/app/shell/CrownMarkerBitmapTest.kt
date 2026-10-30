package com.kungsbackacarcommunity.app.shell

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.crownhunt.CrownMarkerStyle
import com.kungsbackacarcommunity.app.crownhunt.CrownRarity
import com.kungsbackacarcommunity.app.crownhunt.crownGlyphRes
import com.kungsbackacarcommunity.app.design.KccPalette
import kotlin.math.abs
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native raster checks for the compact tiles; cache identity has its own pure JVM suite. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CrownMarkerBitmapTest {
    private val densityDpis = listOf(160, 213, 240, 320, 480)
    private val ink = KccPalette.nightOverlay.toArgb()

    @Test
    fun everyTier_hasCompactBounds_clearPaddingAndCorners_andAnOpaqueBody() {
        for (dpi in densityDpis) {
            val context = contextAt(dpi)
            val density = context.resources.displayMetrics.density
            assertEquals(dpi / 160f, density, 0.001f)
            for (rarity in CrownRarity.entries) for (inRange in listOf(false, true)) {
                val bitmap = render(context, rarity, inRange)
                try {
                    val sideDp = 26 + 2 * rarity.ordinal
                    val label = "$rarity inRange=$inRange at $dpi dpi"
                    val expectedSize = ceil((sideDp + 2) * density).toInt()
                    assertEquals(label, expectedSize, bitmap.width)
                    assertEquals(label, expectedSize, bitmap.height)
                    for (i in 0 until bitmap.width) {
                        assertEquals("$label top padding", 0, Color.alpha(bitmap.getPixel(i, 0)))
                        assertEquals("$label bottom padding", 0, Color.alpha(bitmap.getPixel(i, bitmap.height - 1)))
                        assertEquals("$label left padding", 0, Color.alpha(bitmap.getPixel(0, i)))
                        assertEquals("$label right padding", 0, Color.alpha(bitmap.getPixel(bitmap.width - 1, i)))
                    }
                    val corner = (2 * density).toInt()
                    for (x in listOf(corner, bitmap.width - 1 - corner)) {
                        for (y in listOf(corner, bitmap.height - 1 - corner)) {
                            assertEquals("$label rounded corner", 0, Color.alpha(bitmap.getPixel(x, y)))
                        }
                    }
                    // This whole inset rectangle is inside the rounded tile, including
                    // the gaps between crown points: it must be a calm opaque surface.
                    val inset = ceil(6 * density).toInt()
                    for (y in inset until bitmap.height - inset) {
                        for (x in inset until bitmap.width - inset) {
                            assertEquals("$label body at $x,$y", 255, Color.alpha(bitmap.getPixel(x, y)))
                        }
                    }
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val xs = pixels.indices.filter { Color.alpha(pixels[it]) > 0 }.map { it % bitmap.width }
                    val ys = pixels.indices.filter { Color.alpha(pixels[it]) > 0 }.map { it / bitmap.width }
                    assertTrue("$label occupied width", abs((xs.max() - xs.min() + 1) - sideDp * density) <= 2f)
                    assertTrue("$label occupied height", abs((ys.max() - ys.min() + 1) - sideDp * density) <= 2f)
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    @Test
    fun rangeChangesSolidFillAndGlyphInk_usingAllFourGeneratedAccents() {
        val accents = CrownRarity.entries.map(::accent)
        assertEquals("Rarities must not collapse to the same colour", 4, accents.toSet().size)
        for (dpi in densityDpis) for (rarity in CrownRarity.entries) {
            val context = contextAt(dpi)
            val density = context.resources.displayMetrics.density
            val distant = render(context, rarity, false)
            val ready = render(context, rarity, true)
            try {
                val x = (distant.width / 2f - (26 + 2 * rarity.ordinal) * density / 2 + 3 * density).toInt()
                val y = distant.height / 2
                assertEquals("$rarity distant body", ink, distant.getPixel(x, y))
                assertEquals("$rarity ready body", accent(rarity), ready.getPixel(x, y))
                // Sample only the glyph's central region, excluding its frame and bars.
                val centre = distant.width / 2f
                val inset = (centre - 8 * density).toInt()
                val end = (centre + 8 * density).toInt()
                var accentGlyph = 0
                var navyGlyph = 0
                for (py in (centre - 9 * density).toInt() until (centre + 4 * density).toInt()) {
                    for (px in inset until end) {
                        if (distant.getPixel(px, py) == accent(rarity)) accentGlyph++
                        if (ready.getPixel(px, py) == ink) navyGlyph++
                    }
                }
                assertTrue("$rarity distant source glyph missing", accentGlyph > 10 * density * density)
                assertTrue("$rarity ready source glyph missing", navyGlyph > 10 * density * density)
                assertFalse("Range must visibly change the raster", distant.sameAs(ready))
            } finally {
                distant.recycle()
                ready.recycle()
            }
        }
    }

    @Test
    fun rankBars_haveOneToFourSeparateEqualWidthMarks_atEveryDensity() {
        for (dpi in densityDpis) for (rarity in CrownRarity.entries) {
            val context = contextAt(dpi)
            val density = context.resources.displayMetrics.density
            val bitmap = render(context, rarity, true)
            try {
                val bottom = bitmap.height / 2f + (26 + 2 * rarity.ordinal) * density / 2
                val y = (bottom - 5 * density).toInt()
                val inset = ceil(4 * density).toInt()
                val runs = mutableListOf<IntRange>()
                var start: Int? = null
                for (x in inset until bitmap.width - inset) {
                    val isBar = bitmap.getPixel(x, y) == ink
                    if (isBar && start == null) start = x
                    if (!isBar && start != null) {
                        runs += start until x
                        start = null
                    }
                }
                start?.let { runs += it until bitmap.width - inset }
                assertEquals("$rarity rank at $dpi dpi", rarity.ordinal + 1, runs.size)
                for (run in runs) {
                    // Exact-colour cores lose up to two edge pixels to antialiasing.
                    assertTrue("$rarity bar width $run", abs(run.count() - 3 * density) <= 2f)
                }
                runs.zipWithNext().forEach { (left, right) ->
                    assertTrue("$rarity bar gap", abs((right.first - left.last - 1) - 2 * density) <= 2f)
                }
                assertTrue("$rarity centred bars", abs((runs.first().first + runs.last().last + 1) / 2f - bitmap.width / 2f) <= 1f)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun glowInputDoesNotAddAHalo_andLegacyGlyphInferenceMatchesExplicitRarity() {
        val context = contextAt(320)
        for (rarity in CrownRarity.entries) {
            val appearance = CrownMarkerStyle.spawnMarkerAppearance(rarity, true, false)
            val explicit = render(context, rarity, true)
            val legacy = requireNotNull(CrownMarkerBitmaps.create(
                context, crownGlyphRes(rarity), appearance.discColorArgb,
                appearance.glyphColorArgb, appearance.glowColorArgb,
            ))
            val glow = requireNotNull(CrownMarkerBitmaps.create(
                context, crownGlyphRes(rarity), appearance.discColorArgb,
                appearance.glyphColorArgb, Color.MAGENTA, rarity = rarity,
            ))
            try {
                assertTrue("$rarity legacy glyph inference", explicit.sameAs(legacy))
                assertTrue("$rarity must never gain a halo", explicit.sameAs(glow))
            } finally {
                explicit.recycle()
                legacy.recycle()
                glow.recycle()
            }
        }
    }

    @Test
    fun curatedRewardMetadataDeterminesTileRank_evenWithTheSameRoyalSourceGlyph() {
        val context = contextAt(320)
        for (rarity in CrownRarity.entries) {
            val bitmap = requireNotNull(CrownMarkerBitmaps.create(
                context = context,
                iconRes = crownGlyphRes(CrownRarity.LEGENDARY),
                discColorArgb = CrownMarkerStyle.ADMIN_POINT_DISC,
                glyphColorArgb = CrownMarkerStyle.adminPointGlyphColorArgb(),
                glowColorArgb = null,
                rewardPoints = rarity.rewardPoints,
            ))
            try {
                assertEquals((28 + 2 * rarity.ordinal) * 2, bitmap.width)
                assertEquals(accent(rarity), bitmap.getPixel(8, bitmap.height / 2))
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun legacyCollectedBadgeRemainsDistinct_withoutExpandingTheTile() {
        val context = contextAt(320)
        for (rarity in CrownRarity.entries) {
            val normal = render(context, rarity, true)
            val collected = render(context, rarity, true, collected = true)
            try {
                assertEquals(normal.width, collected.width)
                assertEquals(normal.height, collected.height)
                assertFalse("$rarity legacy badge", normal.sameAs(collected))
                val bottom = collected.height / 2f + (26 + 2 * rarity.ordinal)
                val badgeSample = (bottom - 4).toInt() // badge centre + 2dp, clear of tick strokes
                assertEquals(CrownMarkerStyle.COLLECTED_BADGE_FILL, collected.getPixel(badgeSample, badgeSample))
                assertEquals("Collected stays inactive", ink, collected.getPixel(8, collected.height / 2))
            } finally {
                normal.recycle()
                collected.recycle()
            }
        }
    }

    @Test
    fun dayChangesOnlyDistantPalette_andPreservesGeometryReadyAndCollectedRasters() {
        for (dpi in densityDpis) for (rarity in CrownRarity.entries) {
            val context = contextAt(dpi)
            val density = context.resources.displayMetrics.density
            val night = render(context, rarity, false, mapMode = MapMode.Night)
            val day = render(context, rarity, false, mapMode = MapMode.Day)
            val readyNight = render(context, rarity, true, mapMode = MapMode.Night)
            val readyDay = render(context, rarity, true, mapMode = MapMode.Day)
            val collectedNight = render(context, rarity, false, collected = true, mapMode = MapMode.Night)
            val collectedDay = render(context, rarity, false, collected = true, mapMode = MapMode.Day)
            try {
                assertEquals(night.width, day.width)
                assertEquals(night.height, day.height)
                for (y in 0 until day.height) for (x in 0 until day.width) {
                    assertEquals("$rarity silhouette at $dpi dpi ($x,$y)",
                        Color.alpha(night.getPixel(x, y)), Color.alpha(day.getPixel(x, y)))
                }
                val x = (day.width / 2f - (26 + 2 * rarity.ordinal) * density / 2 + 3 * density).toInt()
                assertEquals(CrownMarkerBitmaps.lightSurface(rarity.ordinal), day.getPixel(x, day.height / 2))
                val pixels = IntArray(day.width * day.height)
                day.getPixels(pixels, 0, day.width, 0, 0, day.width, day.height)
                assertTrue("$rarity dark foreground", pixels.count { it == CrownMarkerBitmaps.lightAccent(rarity.ordinal) } > 10 * density * density)
                assertFalse("Distant palette changes", day.sameAs(night))
                assertTrue("Ready pixels stay identical in day/night", readyDay.sameAs(readyNight))
                assertTrue("Legacy collected pixels stay identical in day/night", collectedDay.sameAs(collectedNight))
            } finally {
                listOf(night, day, readyNight, readyDay, collectedNight, collectedDay).forEach(Bitmap::recycle)
            }
        }
    }

    private fun contextAt(dpi: Int): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { densityDpi = dpi })
    }

    private fun render(context: Context, rarity: CrownRarity, inRange: Boolean, collected: Boolean = false, mapMode: MapMode = MapMode.Night): Bitmap {
        val appearance = CrownMarkerStyle.spawnMarkerAppearance(rarity, inRange, collected)
        return requireNotNull(CrownMarkerBitmaps.create(
            context, crownGlyphRes(rarity), appearance.discColorArgb,
            appearance.glyphColorArgb, appearance.glowColorArgb,
            collectedBadge = appearance.collectedBadge, rarity = rarity, mapMode = mapMode,
        ))
    }

    private fun accent(rarity: CrownRarity): Int = when (rarity) {
        CrownRarity.COMMON -> KccPalette.crownCommon
        CrownRarity.UNCOMMON -> KccPalette.crownUncommon
        CrownRarity.RARE -> KccPalette.crownRare
        CrownRarity.LEGENDARY -> KccPalette.crownGold
    }.toArgb()
}
