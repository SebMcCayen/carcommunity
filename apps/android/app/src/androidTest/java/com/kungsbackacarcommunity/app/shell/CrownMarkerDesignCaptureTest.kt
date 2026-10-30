package com.kungsbackacarcommunity.app.shell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.crownhunt.CrownMarkerStyle
import com.kungsbackacarcommunity.app.crownhunt.CrownRarity
import com.kungsbackacarcommunity.app.crownhunt.crownGlyphRes
import com.kungsbackacarcommunity.app.design.KccPalette
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Native component sheet; no map, location, account, repositories or network.
 * A real window frame-commit fence precedes screenshot and pixel assertions.
 * Run a config-less build so application startup cannot reach Firebase either.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class CrownMarkerDesignCaptureTest {
    @get:Rule val rule = ActivityScenarioRule(ComponentActivity::class.java)

    @Test
    fun captureCrownMarkerComponentsOnLightAndDarkSurfaces() {
        val panels = mutableListOf<CrownTilePanel>()
        val committed = CountDownLatch(1)
        rule.scenario.onActivity { activity ->
            val density = activity.resources.displayMetrics.density
            fun px(dp: Int) = (dp * density).toInt()
            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(12), px(12), px(12), px(12))
                setBackgroundColor(KccPalette.warmIvory.toArgb())
            }
            root.addView(TextView(activity).apply {
                text = "Förhandsvisning – kronmarkörer"
                textSize = 18f
                setTextColor(KccPalette.inkBlack.toArgb())
                typeface = Typeface.DEFAULT_BOLD
            })
            root.addView(TextView(activity).apply {
                text = "Komponenter i faktisk storlek · ingen karta"
                textSize = 12f
                setTextColor(KccPalette.inkBlack.toArgb())
                setPadding(0, px(4), 0, px(8))
            })
            for (dark in listOf(false, true)) {
                val panel = CrownTilePanel(activity, dark)
                panels += panel
                root.addView(panel, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
                ).apply { topMargin = px(8) })
            }
            activity.setContentView(root)
            activity.window.decorView.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            root.postInvalidateOnAnimation()
        }
        assertTrue("Native window must present its frame", committed.await(10, TimeUnit.SECONDS))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "crown-marker-design")
        check(directory.mkdirs() || directory.isDirectory)
        val file = File(directory, "crown-marker-components.png")
        file.outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        try {
            for ((index, background) in listOf(KccPalette.softSand.toArgb(), KccPalette.inkBlack.toArgb()).withIndex()) {
                val location = IntArray(2)
                var width = 0
                var height = 0
                var density = 1f
                rule.scenario.onActivity {
                    val view = panels[index]
                    assertTrue(view.hasDrawn)
                    view.getLocationOnScreen(location)
                    width = view.width
                    height = view.height
                    density = view.resources.displayMetrics.density
                }
                val panel = Bitmap.createBitmap(screenshot, location[0], location[1], width, height)
                try {
                    assertTrue("Use portrait space of at least 242dp per panel", height >= 242 * density)
                    assertTrue("Use a device at least 300dp wide", width >= 276 * density)
                    assertEquals("panel $index surface", background, panel.getPixel(0, 0))
                    for ((tier, rarity) in CrownRarity.entries.withIndex()) {
                        val side = (26 + 2 * rarity.ordinal) * density
                        val centre = 12 * density + (width - 24 * density) / 4 * (tier + 0.5f)
                        val size = kotlin.math.ceil(side + 2 * density).toInt()
                        val left = (centre - size / 2f).toInt()
                        val sampleX = left + (size / 2f - side / 2 + 3 * density).toInt()
                        val distantFill = if (index == 0) CrownMarkerBitmaps.lightSurface(tier) else KccPalette.nightOverlay.toArgb()
                        assertEquals("$rarity distant fill", distantFill,
                            panel.getPixel(sampleX, (60 * density).toInt() + size / 2))
                        assertEquals("$rarity ready fill", accent(rarity),
                            panel.getPixel(sampleX, (114 * density).toInt() + size / 2))
                    }
                } finally { panel.recycle() }
            }
            assertTrue(file.length() > 1024)
            val saved = checkNotNull(BitmapFactory.decodeFile(file.absolutePath))
            try { assertTrue("PNG preserves the actual window", screenshot.sameAs(saved)) }
            finally { saved.recycle() }
        } finally { screenshot.recycle() }
    }
}

/** Test-only layout: the tiles themselves always come from the production renderer. */
private class CrownTilePanel(context: Context, private val dark: Boolean) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textColor = if (dark) KccPalette.warmIvory.toArgb() else KccPalette.inkBlack.toArgb()
    private val surfaceColor = if (dark) KccPalette.inkBlack.toArgb() else KccPalette.softSand.toArgb()
    private val tiles = CrownRarity.entries.associateWith { rarity ->
        listOf(false, true).associateWith { inRange ->
            val appearance = CrownMarkerStyle.spawnMarkerAppearance(rarity, inRange, false)
            checkNotNull(CrownMarkerBitmaps.create(
                context, crownGlyphRes(rarity), appearance.discColorArgb,
                appearance.glyphColorArgb, appearance.glowColorArgb,
                rarity = rarity,
                mapMode = if (dark) MapMode.Night else MapMode.Day,
            ))
        }
    }

    init {
        contentDescription = if (dark) "Mörk yta, kronmarkörer som komponenter" else "Ljus yta, kronmarkörer som komponenter"
    }

    @Volatile var hasDrawn = false
        private set

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(surfaceColor)
        label(canvas, if (dark) "Mörk yta · faktisk storlek" else "Ljus yta · faktisk storlek", 12f, 20f, 14f, bold = true)
        val columnWidth = (width - 24 * density) / 4
        val names = listOf("Vanlig", "Ovanlig", "Sällsynt", "Legendarisk")
        CrownRarity.entries.forEachIndexed { index, rarity ->
            val centre = 12 * density + columnWidth * (index + 0.5f)
            label(canvas, names[index], centre / density, 38f, 11f, centred = true)
            drawTile(canvas, rarity, false, centre, 60f)
            drawTile(canvas, rarity, true, centre, 114f)
        }
        label(canvas, "På avstånd", 12f, 55f, 11f)
        label(canvas, "Inom räckhåll", 12f, 109f, 11f)
        label(canvas, "Tätt blandat · båda avståndslägen", 12f, 167f, 12f, bold = true)
        // Abstract component packing, deliberately without roads, geography or users.
        // Two close rows allow visual inspection of nearby tiles on both surfaces.
        for (row in 0..1) for (column in 0..6) {
            val rarity = CrownRarity.entries[(column + row) % CrownRarity.entries.size]
            val centre = width / 2f + (column - 3) * 34 * density
            drawTile(canvas, rarity, (column + row) % 2 == 0, centre, 174f + row * 34f)
        }
        hasDrawn = true
    }

    private fun drawTile(canvas: Canvas, rarity: CrownRarity, inRange: Boolean, centre: Float, topDp: Float) {
        val bitmap = tiles.getValue(rarity).getValue(inRange)
        canvas.drawBitmap(bitmap, (centre - bitmap.width / 2f).toInt().toFloat(), (topDp * density).toInt().toFloat(), null)
    }

    private fun label(canvas: Canvas, text: String, x: Float, y: Float, size: Float, bold: Boolean = false, centred: Boolean = false) {
        paint.color = textColor
        paint.textSize = size * density
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.textAlign = if (centred) Paint.Align.CENTER else Paint.Align.LEFT
        canvas.drawText(text, x * density, y * density, paint)
    }

    // Bitmap lifetime follows this small, single-activity test fixture. Do not
    // recycle on detach: Compose may temporarily detach and reattach AndroidView.
}

private fun accent(rarity: CrownRarity): Int = when (rarity) {
    CrownRarity.COMMON -> KccPalette.crownCommon
    CrownRarity.UNCOMMON -> KccPalette.crownUncommon
    CrownRarity.RARE -> KccPalette.crownRare
    CrownRarity.LEGENDARY -> KccPalette.crownGold
}.toArgb()
