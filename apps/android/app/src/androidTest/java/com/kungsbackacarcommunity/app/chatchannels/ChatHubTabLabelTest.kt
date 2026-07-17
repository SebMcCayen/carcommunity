package com.kungsbackacarcommunity.app.chatchannels

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * The chat hub's four tab labels must FIT — Seb reported "Community" rendering as
 * "Commun…" and "Notifications" as "Notificati…" (Issue 2).
 *
 * A [androidx.compose.material3.TabRow] gives each of the four tabs an equal
 * quarter of the card's width. The fix reclaims the 16dp of horizontal padding the
 * Material text+icon `Tab` overload spends on each label, and lets the label
 * auto-shrink (see [TAB_LABEL_MIN_SP]) rather than ellipsize.
 *
 * These tests pin the actual claim — every shipped label, in BOTH locales, fits
 * inside one tab at every width and font scale the app realistically renders at —
 * by measuring the REAL strings from each locale's resources, at the REAL
 * `labelMedium` style from the app's own theme, with the REAL auto-shrink
 * constants. A fix that only fits on the test emulator is not a fix.
 */
@RunWith(AndroidJUnit4::class)
class ChatHubTabLabelTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * Widths (dp) the labels must fit at. 320dp is the narrowest width Android
     * phones ship at (`sw320dp`); 360dp and 411dp are the common modern sizes.
     */
    private val screenWidthsDp = listOf(320, 360, 411)

    /** The chat-hub card insets its sides by KccSpacing.s2 (8dp) each — see ChatHubPopup. */
    private val cardHorizontalPaddingDp = 16

    private val tabCount = 4

    /**
     * Font scales the labels must survive. 1.0 is the default; 1.3 and 1.5 are
     * ordinary Android accessibility font sizes. Measured: at 1.0 the pre-fix
     * labels all fit on a 360dp phone, and BOTH labels Seb named ("Community",
     * "Notifications") overflow at 1.3 — so he was almost certainly running a
     * raised font scale, and the fix must hold there.
     */
    private val fontScales = listOf(1.0f, 1.3f, 1.5f)

    /** Per-font-scale text measurer + the app theme's real labelMedium style. */
    private lateinit var measurers: Map<Float, Pair<TextMeasurer, TextStyle>>
    private var deviceDensity = 0f

    @Test
    fun everyTabLabelFitsItsTabInBothLocalesAtEveryWidthAndFontScale() {
        prepareMeasurers()
        val failures = mutableListOf<String>()
        for ((fontScale, pair) in measurers) {
            val (measurer, style) = pair
            for (language in listOf("en", "sv")) {
                for (widthDp in screenWidthsDp) {
                    val tabWidthPx = tabWidthPx(widthDp)
                    for ((key, label) in labelsFor(language)) {
                        val fitting = largestFittingSp(measurer, style, label, tabWidthPx)
                        Log.w(
                            "ChatHubTabLabel",
                            "fs=$fontScale $language ${widthDp}dp $key=\"$label\" " +
                                "tab=${tabWidthPx}px fits@${fitting ?: "NONE"}sp",
                        )
                        if (fitting == null) {
                            failures +=
                                "$language @${widthDp}dp fontScale=$fontScale: \"$label\" " +
                                    "($key) does not fit a ${tabWidthPx}px tab even at " +
                                    "${TAB_LABEL_MIN_SP.value}sp — it would ellipsize"
                        }
                    }
                }
            }
        }
        assertTrue(
            "Chat-hub tab labels that would truncate:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    /**
     * Teeth: reproduces the REPORTED symptom against the PRE-FIX layout — a fixed
     * `labelMedium` label inside Material's text+icon `Tab` overload, which spends
     * 8dp of each side of the tab on text padding.
     *
     * Pins Seb's report exactly: at font scale 1.3 the pre-fix layout overflows on
     * BOTH "Community" (→ "Commun…") and "Notifications" (→ "Notificati…"), the two
     * labels he named. It does NOT overflow at the default font scale — which is
     * why the report is a font-scale-dependent bug, and why the fix has to hold
     * across font scales rather than just widen the tab a little.
     */
    @Test
    fun preFixFixedSizeLabelsOverflow_reproducingTheReportedTruncation() {
        prepareMeasurers()
        val atDefault = preFixOverflowingLabels(fontScale = 1.0f)
        val atLargeFont = preFixOverflowingLabels(fontScale = 1.3f)

        assertTrue(
            "Expected the pre-fix \"Community\" to overflow at fontScale 1.3 " +
                "(reproducing the reported \"Commun…\"), but it did not. " +
                "Overflowing: $atLargeFont",
            "Community" in atLargeFont,
        )
        assertTrue(
            "Expected the pre-fix \"Notifications\" to overflow at fontScale 1.3 " +
                "(reproducing the reported \"Notificati…\"), but it did not. " +
                "Overflowing: $atLargeFont",
            "Notifications" in atLargeFont,
        )
        // Documents the boundary: at the DEFAULT font scale the pre-fix labels did
        // fit on a 360dp phone, so the report is font-scale dependent. If this ever
        // starts overflowing too, the labels/typography changed under us.
        assertTrue(
            "Expected the pre-fix labels to fit at the default font scale on a " +
                "360dp phone (the reported truncation needs a raised font scale), " +
                "but these overflowed: $atDefault",
            atDefault.isEmpty(),
        )
    }

    /** The labels that overflow a quarter-width tab on a 360dp phone, pre-fix. */
    private fun preFixOverflowingLabels(fontScale: Float): List<String> {
        val (measurer, style) = measurers.getValue(fontScale)
        // A 360dp phone, minus the card's side padding, split four ways, minus the
        // 16dp of horizontal text padding the Material Tab text slot adds.
        val textWidthPx = (tabWidthPx(360) - 16 * deviceDensity).toInt()
        val overflowing =
            (labelsFor("en") + labelsFor("sv"))
                .filter { (_, label) ->
                    measurer.measure(
                        text = label,
                        style = style,
                        softWrap = false,
                        maxLines = 1,
                        constraints = Constraints(maxWidth = Int.MAX_VALUE),
                    ).size.width > textWidthPx
                }
                .map { it.second }
        Log.w(
            "ChatHubTabLabel",
            "PRE-FIX fs=$fontScale overflow @360dp (text width ${textWidthPx}px): $overflowing",
        )
        return overflowing
    }

    private fun tabWidthPx(screenWidthDp: Int): Float =
        (screenWidthDp - cardHorizontalPaddingDp).toFloat() / tabCount * deviceDensity

    /**
     * The largest size in [TAB_LABEL_MIN_SP]..[TAB_LABEL_MAX_SP] at which [text]
     * fits [maxWidthPx] on one line, or null if it does not fit even at the floor
     * (→ the real UI would ellipsize it).
     */
    private fun largestFittingSp(
        measurer: TextMeasurer,
        style: TextStyle,
        text: String,
        maxWidthPx: Float,
    ): Float? {
        var size = TAB_LABEL_MAX_SP.value
        while (size >= TAB_LABEL_MIN_SP.value) {
            val width =
                measurer.measure(
                    text = text,
                    style = style.copy(fontSize = TextUnit(size, TextUnitType.Sp)),
                    softWrap = false,
                    maxLines = 1,
                    constraints = Constraints(maxWidth = Int.MAX_VALUE),
                ).size.width
            if (width <= maxWidthPx) return size
            size -= TAB_LABEL_STEP_SP.value
        }
        return null
    }

    private fun labelsFor(language: String): List<Pair<String, String>> {
        val base: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(language))
        val localized = base.createConfigurationContext(config)
        return listOf(
            "tabCommunity" to localized.getString(R.string.chatHub_tabCommunity),
            "tabConvoys" to localized.getString(R.string.chatHub_tabConvoys),
            "tabFriends" to localized.getString(R.string.chatHub_tabFriends),
            "tabNotifications" to localized.getString(R.string.chatHub_tabNotifications),
        )
    }

    /**
     * Builds one measurer per font scale in a SINGLE composition (the rule allows
     * only one `setContent`), each under the app's own [KccTheme] so the style
     * measured is the real `labelMedium` (KccTypeScale.caption) the tabs use — not
     * the Material default.
     */
    private fun prepareMeasurers() {
        val built = mutableMapOf<Float, Pair<TextMeasurer, TextStyle>>()
        composeTestRule.setContent {
            deviceDensity = LocalDensity.current.density
            KccTheme {
                fontScales.forEach { fontScale ->
                    CompositionLocalProvider(
                        LocalDensity provides Density(deviceDensity, fontScale),
                    ) {
                        built[fontScale] = rememberTextMeasurer() to MaterialTheme.typography.labelMedium
                    }
                }
            }
            // Nothing to draw; this test measures text, it does not render the hub.
            Text("")
        }
        composeTestRule.waitForIdle()
        measurers = built.toMap()
    }
}
