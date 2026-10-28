package com.kungsbackacarcommunity.app.chatchannels

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

/** Real production host + local repository: catches stacked chrome consuming the conversation. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h900dp-mdpi")
class ChatHubViewportTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun swedishDarkNormalText() = checkViewport(320, 1f, "sv", true)
    @Test fun swedishDarkEnlargedText() = checkViewport(320, 1.5f, "sv", true)
    @Test fun englishLightNormalText() = checkViewport(390, 1f, "en", false)
    @Test fun englishLightCompactPhoneNormalText() = checkViewport(360, 1f, "en", false)
    @Test fun englishLightEnlargedText() = checkViewport(390, 1.5f, "en", false)

    private fun checkViewport(width: Int, scale: Float, language: String, dark: Boolean) {
        val config = Configuration(compose.activity.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }
        // Keep the Activity in the ContextWrapper chain so permission launchers
        // and other Activity-owned APIs still resolve their real owner.
        val localized = ContextThemeWrapper(compose.activity, compose.activity.theme).apply {
            applyOverrideConfiguration(config)
        }
        var closed = 0
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides localized, LocalConfiguration provides config,
                LocalDensity provides Density(1f, scale),
            ) {
                KccTheme(darkTheme = dark) {
                    Box(Modifier.requiredSize(width.dp, 640.dp)) {
                        ChatHubRoute(
                            uid = "local-self", communityChatRepository = LocalRepository,
                            convoyChatRepository = null, friendsRepository = null, dmRepository = null,
                            notificationsRepository = null, notificationsCoordinator = null,
                            communityUnread = true, convoysUnread = true,
                            friendsUnread = true, notificationsUnread = true,
                            onClose = { closed++ }, showHubBackArrow = true,
                            driverContent = { Text("Local Driver surface") },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val root = compose.onNodeWithTag(CHAT_HUB_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val sources = compose.onNodeWithTag(CHAT_SOURCE_SELECTOR_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val strip = compose.onNodeWithTag(CHAT_CHANNEL_STRIP_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("channel_message_viewport").fetchSemanticsNode().boundsInRoot
        val input = compose.onNodeWithTag(CHANNEL_INPUT_TEST_TAG)
        val inputBounds = input.fetchSemanticsNode().boundsInRoot
        // Check every destination in the real populated host without scrolling;
        // the full-width strip must also preserve the existing reading budget.
        for (tab in ChatTab.entries) {
            val target = compose.onNodeWithTag(chatChannelTabTag(tab))
            target.assertIsDisplayed()
            val bounds = target.fetchSemanticsNode().boundsInRoot
            assertTrue("$tab fully inside strip: $bounds / $strip",
                bounds.left >= strip.left && bounds.right <= strip.right &&
                    bounds.top >= strip.top && bounds.bottom <= strip.bottom)
            assertTrue("$tab minimum hit target: $bounds", bounds.width >= 48f && bounds.height >= 48f)
        }
        if (scale == 1f && width >= 360) {
            assertEquals("Normal-width tab strip stays compact", 48f, strip.height, 0.01f)
            assertTrue("Normal-width chrome leaves extra reading space", strip.bottom - root.top <= 124f)
        }
        // Budget includes the real status inset. The old title/source/back/icon stack exceeds it.
        assertTrue("Chrome must leave room for messages: $strip / $root", strip.bottom - root.top <= 144f)
        assertTrue("Sources must be a compact row: $sources", sources.height <= 64f)
        assertTrue("Conversation viewport: $viewport", viewport.height >= 300f)
        assertTrue("Messages start below chrome", viewport.top >= strip.bottom)
        assertTrue("Composer clears messages", inputBounds.top >= viewport.bottom)
        assertTrue("Composer inside hub", inputBounds.bottom <= root.bottom)
        compose.onNodeWithText("Local latest message").assertIsDisplayed()
        val bodyLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Local latest message", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(bodyLayouts) }
        assertEquals(16.sp, bodyLayouts.single().layoutInput.style.fontSize)
        assertEquals(scale, bodyLayouts.single().layoutInput.density.fontScale, 0.001f)
        for (label in listOf(R.string.chatHub_tabChats, R.string.chatHub_tabDriver)) {
            val target = compose.onNodeWithText(localized.getString(label)).fetchSemanticsNode().boundsInRoot
            assertTrue("Source hit target: $target", target.height >= 48f && target.width >= 48f)
        }
        input.assertIsDisplayed().performTextInput("Local draft")
        // The description belongs to the 24dp icon. Measure/click its actual
        // 48dp button ancestor rather than treating the glyph as the hit target.
        val back = compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button) and
                hasClickAction() and
                hasAnyDescendant(hasContentDescription(localized.getString(R.string.chatHub_back))),
            useUnmergedTree = true,
        )
        val backBounds = back.fetchSemanticsNode().boundsInRoot
        assertTrue("Back shares the channel row", backBounds.top < strip.bottom && backBounds.bottom > strip.top)
        assertTrue("Back hit target: $backBounds", backBounds.height >= 48f && backBounds.width >= 48f)

        // Real conditional route composition must retain the composer draft on source switches.
        compose.onNodeWithText(localized.getString(R.string.chatHub_tabDriver)).performClick()
        compose.onNodeWithText("Local Driver surface").assertIsDisplayed()
        compose.onNodeWithText(localized.getString(R.string.chatHub_tabChats)).performClick()
        compose.onNodeWithTag(CHANNEL_INPUT_TEST_TAG).assertTextContains("Local draft")
        back.performClick()
        compose.runOnIdle { assertEquals(1, closed) }
    }

    private object LocalRepository : CommunityChatRepository {
        override fun observeMessages() = flowOf(ChannelMessagesState.Loaded(
            listOf(
                ChannelMessage("one", "local-member", "Local older message", "Local member", null, 0L, null),
                ChannelMessage("two", "local-self", "Local latest message", "Local self", null, 60_000L, null),
            ),
        ))
        override fun observeUnread(uid: String) = flowOf(true)
        override suspend fun post(text: String, mentionedUids: List<String>, clientId: String?) =
            ChannelSendResult.Failed(ChannelSendError.Generic)
        override suspend fun loadOlder(before: String) = ChannelOlderResult.Failed
        override suspend fun markRead() = Unit
    }
}
