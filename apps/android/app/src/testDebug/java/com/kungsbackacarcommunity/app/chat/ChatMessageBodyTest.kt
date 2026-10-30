package com.kungsbackacarcommunity.app.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
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
    private fun show(text: AnnotatedString, scale: Float = 1f, uriHandler: UriHandler? = null) {
        compose.setContent { CompositionLocalProvider(
            LocalDensity provides Density(1f, scale),
            LocalUriHandler provides (uriHandler ?: LocalUriHandler.current),
        ) { KccTheme {
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
    private fun assertRichBodyPreserved(expected: AnnotatedString, actual: AnnotatedString) {
        // Material3 supplies default link styles and Foundation adds their layout
        // spans. Check the content contract rather than equality with unstyled input.
        assertEquals(expected.text, actual.text)
        assertEquals(expected.getStringAnnotations("mention", 0, expected.length),
            actual.getStringAnnotations("mention", 0, actual.length))
        assertEquals(expected.paragraphStyles, actual.paragraphStyles)
        assertTrue("Original visual spans must survive", actual.spanStyles.containsAll(expected.spanStyles))
        val original = expected.getLinkAnnotations(0, expected.length).single()
        val rendered = actual.getLinkAnnotations(0, actual.length).single()
        assertEquals(original.start, rendered.start)
        assertEquals(original.end, rendered.end)
        assertEquals(original.tag, rendered.tag)
        val originalUrl = original.item as LinkAnnotation.Url
        val renderedUrl = rendered.item as LinkAnnotation.Url
        assertEquals(originalUrl.url, renderedUrl.url)
        assertSame(originalUrl.linkInteractionListener, renderedUrl.linkInteractionListener)
    }
    private fun clickBodyLink() {
        compose.onNode(hasAnyAncestor(hasTestTag("chat-body-wall")) and hasClickAction(),
            useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick) { click ->
            assertTrue(click())
        }
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
        val openedUrls = mutableListOf<String>()
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) { openedUrls += uri }
        }
        val text = buildAnnotatedString {
            pushStringAnnotation("mention", "friend")
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("@Testmedlem ") }
            pop()
            withLink(LinkAnnotation.Url("https://example.com")) { append("https://example.com ") }
            append("👨‍👩‍👧‍👦🇸🇪👍🏽🚗".repeat(100))
        }
        show(text, 2f, uriHandler)
        assertEquals(8, layout().lineCount)
        assertRichBodyPreserved(text, layout().layoutInput.text)
        clickBodyLink()
        compose.runOnIdle { assertEquals(listOf("https://example.com"), openedUrls) }
        compose.onNodeWithTag("chat-body-toggle-wall").performClick()
        assertTrue(layout().lineCount > CHAT_COLLAPSED_BODY_LINES)
        val expanded = layout().layoutInput.text
        assertRichBodyPreserved(text, expanded)
        assertEquals(1, expanded.getStringAnnotations("mention", 0, expanded.length).size)
        assertEquals(1, expanded.getLinkAnnotations(0, expanded.length).size)
        clickBodyLink()
        compose.runOnIdle { assertEquals(List(2) { "https://example.com" }, openedUrls) }
        compose.onNodeWithTag("chat-body-toggle-wall").performScrollTo().performClick()
        assertEquals(CHAT_COLLAPSED_BODY_LINES, layout().lineCount)
        assertRichBodyPreserved(text, layout().layoutInput.text)
    }
    @Test fun shortMessagesKeepTheirExistingCompactPresentation() {
        show(AnnotatedString("Hej 🚗"))
        compose.onNodeWithTag("chat-body-toggle-wall").assertDoesNotExist()
        assertEquals(1, layout().lineCount)
    }
}
