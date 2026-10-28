package com.kungsbackacarcommunity.app.chatchannels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Pixel readback stays native: Robolectric captureToImage can hang waiting for forceRedraw. */
@RunWith(AndroidJUnit4::class)
class ChatFeedSelectorTransparencyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun bothTabsLeaveTheirParentVisibleInEitherSelectionAndTheme() {
        val dark = mutableStateOf(true)
        val selected = mutableStateOf(false)
        val backdrop = Color(0xFF29516F)
        compose.setContent {
            KccTheme(darkTheme = dark.value) {
                Box(Modifier.fillMaxWidth().background(backdrop)) {
                    ChatFeedSelector(selected.value, onSelect = { selected.value = it })
                }
            }
        }
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val tabRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        for (isDark in listOf(true, false)) for (driverSelected in listOf(false, true)) {
            // Change the selected input without a pressed ripple obscuring the
            // steady-state background. Real touch callbacks are covered on JVM.
            compose.runOnIdle { dark.value = isDark; selected.value = driverSelected }
            for ((driver, labelId) in listOf(false to R.string.chatHub_tabChats, true to R.string.chatHub_tabDriver)) {
                val target = compose.onNode(hasText(resources.getString(labelId)) and tabRole)
                if (driver == driverSelected) target.assertIsSelected() else target.assertIsNotSelected()
                val pixels = target.captureToImage().toPixelMap()
                val samples = listOf(1 to pixels.height / 2, pixels.width - 2 to pixels.height / 2,
                    pixels.width / 2 to 1, pixels.width / 2 to pixels.height - 2)
                for ((x, y) in samples) {
                    val pixel = pixels[x, y]
                    val tolerance = 2f / 255f
                    assertTrue("Plain text tab must not paint a pill fill/border at $x,$y",
                        abs(pixel.red - backdrop.red) <= tolerance &&
                            abs(pixel.green - backdrop.green) <= tolerance &&
                            abs(pixel.blue - backdrop.blue) <= tolerance &&
                            abs(pixel.alpha - backdrop.alpha) <= tolerance)
                }
            }
        }
    }
}
