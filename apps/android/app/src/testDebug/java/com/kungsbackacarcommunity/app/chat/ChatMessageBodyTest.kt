package com.kungsbackacarcommunity.app.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h900dp-mdpi")
class ChatMessageBodyTest {
    @get:Rule val compose = createComposeRule()
    private fun show(text: AnnotatedString, scale: Float = 1f) {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, scale)) { KccTheme {
            Column(Modifier.width(240.dp).verticalScroll(rememberScrollState())) {
                ChatMessageBody("wall", text, MaterialTheme.typography.bodyLarge, MaterialTheme.colorScheme.onSurface)
            }
        } } }
    }
    private fun layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("chat-body-wall").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }
    @Test fun twoThousandEmojiCollapseToEightLinesAndExpandWithoutChangingText() {
        val text = AnnotatedString("🚗".repeat(2000))
        show(text)
        assertEquals(CHAT_COLLAPSED_BODY_LINES, layout().lineCount)
        assertEquals(text, layout().layoutInput.text)
        compose.onNodeWithTag("chat-body-toggle-wall").performClick()
        assertTrue(layout().lineCount > CHAT_COLLAPSED_BODY_LINES)
        assertEquals(text, layout().layoutInput.text)
        compose.onNodeWithTag("chat-body-toggle-wall").performScrollTo().performClick()
        assertEquals(CHAT_COLLAPSED_BODY_LINES, layout().lineCount)
        assertEquals(text, layout().layoutInput.text)
    }
    @Test fun largeFontRetainsEmojiGraphemesMentionAnnotationsAndClickableLinks() {
        val text = buildAnnotatedString {
            pushStringAnnotation("mention", "friend")
            append("@Testmedlem ")
            pop()
            withLink(LinkAnnotation.Url("https://example.com")) { append("https://example.com ") }
            append("👨‍👩‍👧‍👦🇸🇪👍🏽🚗".repeat(100))
        }
        show(text, 2f)
        assertEquals(8, layout().lineCount)
        assertEquals(text, layout().layoutInput.text)
        compose.onNodeWithTag("chat-body-toggle-wall").performClick()
        val expanded = layout().layoutInput.text
        assertEquals(text, expanded)
        assertEquals(1, expanded.getStringAnnotations("mention", 0, expanded.length).size)
        assertEquals(1, expanded.getLinkAnnotations(0, expanded.length).size)
    }
    @Test fun shortMessagesKeepTheirExistingCompactPresentation() {
        show(AnnotatedString("Hej 🚗"))
        compose.onNodeWithTag("chat-body-toggle-wall").assertDoesNotExist()
        assertEquals(1, layout().lineCount)
    }
}
