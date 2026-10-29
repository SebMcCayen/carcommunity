package com.kungsbackacarcommunity.app.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccPalette
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Contrast of actual quote text and the shared link/mention ink in both app themes. */
@RunWith(AndroidJUnit4::class)
class ChatAccentContrastTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lightChatKeepsDarkInkOnGoldAndLightReadingSurfaces() = verifyTheme(dark = false)

    @Test fun darkChatKeepsDarkInkOnGoldAndGoldOnDarkReadingSurfaces() = verifyTheme(dark = true)

    private fun verifyTheme(dark: Boolean) {
        lateinit var colors: ColorScheme
        var quoteTaps = 0
        compose.setContent {
            KccTheme(darkTheme = dark) {
                colors = MaterialTheme.colorScheme
                Column {
                    Surface(color = colors.primary) {
                        ChatQuoteHeader(
                            authorName = "Own quote author",
                            preview = "Own quote preview",
                            isOwn = true,
                            modifier = Modifier.testTag("own-quote"),
                            onClick = { quoteTaps++ },
                        )
                    }
                    Surface(color = colors.surface) {
                        ChatQuoteHeader(
                            authorName = "Incoming quote author",
                            preview = "Incoming quote preview",
                            isOwn = false,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        // The send/bubble/unread pair must be the existing gold with dark ink.
        assertEquals(KccPalette.crownGold, colors.primary)
        assertEquals(KccPalette.inkBlack, colors.onPrimary)
        assertContrast(colors.onPrimary, colors.primary)
        assertContrast(colors.chatUnreadIndicatorColor(selected = true), colors.primary)
        assertContrast(colors.chatUnreadIndicatorColor(selected = false), colors.background)
        for (surface in listOf(colors.background, colors.surface, colors.surfaceVariant)) {
            val ink = colors.chatAccentTextColor(surface)
            assertContrast(ink, surface)
            assertEquals(if (dark) colors.primary else colors.onSurface, ink)
        }

        for (text in listOf("Own quote author", "Own quote preview")) {
            val ink = renderedTextColor(text)
            assertEquals(colors.onPrimary, ink)
            assertContrast(ink, colors.primary)
        }
        for (text in listOf("Incoming quote author", "Incoming quote preview")) {
            assertContrast(renderedTextColor(text), colors.surface)
        }
        compose.onNodeWithTag("own-quote").assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, quoteTaps) }
    }

    private fun renderedTextColor(text: String): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().layoutInput.style.color
    }

    private fun assertContrast(ink: Color, surface: Color) {
        val light = maxOf(ink.luminance(), surface.luminance())
        val dark = minOf(ink.luminance(), surface.luminance())
        val ratio = (light + 0.05f) / (dark + 0.05f)
        assertTrue("Normal chat text needs 4.5:1 contrast; got $ratio", ratio >= 4.5f)
    }
}
