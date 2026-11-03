package com.kungsbackacarcommunity.app.chatchannels

import android.Manifest
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.kungsbackacarcommunity.app.design.KccTheme
import java.io.File
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Real platform IME, production hub and local messages; no Firebase access. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class ChatHubKeyboardNavigationTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS),
    ).around(compose)

    @Test fun darkHubFoldsNavigationAndBackRestoresItWithoutLosingDraft() = exercise(dark = true)
    @Test fun lightHubFoldsNavigationAndBackRestoresItWithoutLosingDraft() = exercise(dark = false)
    @Test fun chatWithoutDriverKeepsItsStatusInsetAndDraft() = exercise(dark = true, driver = false)

    private fun exercise(dark: Boolean, driver: Boolean = true) {
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        var closed = 0
        val suffix = "${if (dark) "dark" else "light"}-${if (driver) "driver" else "chat-only"}"
        compose.setContent {
            KccTheme(darkTheme = dark) {
                ChatHubRoute(
                    uid = "local-keyboard-member", communityChatRepository = LocalRepository,
                    convoyChatRepository = null, friendsRepository = null, dmRepository = null,
                    communityUnread = false, convoysUnread = false, friendsUnread = false,
                    onClose = { closed++ }, showHubBackArrow = true,
                    driverContent = if (driver) ({ Text("Local Driver fixture") }) else null,
                )
            }
        }
        compose.onNodeWithTag(CHAT_CHANNEL_STRIP_TEST_TAG).assertIsDisplayed()
        if (driver) compose.onNodeWithTag(CHAT_SOURCE_SELECTOR_TEST_TAG).assertIsDisplayed()
        val before = viewportTop()
        capture("$suffix-before")
        val input = compose.onNodeWithTag(CHANNEL_INPUT_TEST_TAG)
        input.performClick().performTextInput("Lokalt utkast med åäö 🚗")
        awaitKeyboard(shown = true)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(CHAT_CHANNEL_STRIP_TEST_TAG).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag(CHAT_SOURCE_SELECTOR_TEST_TAG).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag(CHAT_CHANNEL_STRIP_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithTag(CHAT_SOURCE_SELECTOR_TEST_TAG).assertDoesNotExist()
        input.assertIsDisplayed().assertTextContains("Lokalt utkast med åäö 🚗")
        val after = viewportTop()
        assertTrue("Folding navigation must reclaim conversation space: $before -> $after", after < before)
        val safeTop = compose.runOnIdle {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        }
        assertTrue("Messages must remain below the status bar: $after / $safeTop", after >= safeTop)
        capture("$suffix-keyboard")

        // Actual Android Back dismisses the IME before leaving the current room.
        // Focus may remain, so it must not be the source of header visibility.
        Espresso.pressBack()
        awaitKeyboard(shown = false)
        compose.onNodeWithTag(CHAT_CHANNEL_STRIP_TEST_TAG).assertIsDisplayed()
        if (driver) compose.onNodeWithTag(CHAT_SOURCE_SELECTOR_TEST_TAG).assertIsDisplayed()
        input.assertIsFocused().assertTextContains("Lokalt utkast med åäö 🚗")
        compose.onNodeWithTag(chatChannelTabTag(ChatTab.Community)).assertIsSelected()
        compose.runOnIdle { assertEquals(0, closed) }
        capture("$suffix-restored")
    }

    private fun viewportTop() = compose.onNodeWithTag("channel_message_viewport")
        .fetchSemanticsNode().boundsInRoot.top

    private fun awaitKeyboard(shown: Boolean) {
        compose.waitUntil(8_000) {
            compose.runOnIdle {
                val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                val visible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true &&
                    (insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0)
                visible == shown
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = File(checkNotNull(context.getExternalFilesDir(null)), "chat-keyboard-navigation")
            check(directory.mkdirs() || directory.isDirectory)
            File(directory, "$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private object LocalRepository : CommunityChatRepository {
        override fun observeMessages() = flowOf(ChannelMessagesState.Loaded(listOf(
            ChannelMessage("local-message", "local-peer", "Lokala testdata – ingen server är ansluten.",
                "Lokal testmedlem", null, 1_792_936_800_000L, null),
        )))
        override fun observeUnread(uid: String) = flowOf(false)
        override suspend fun post(text: String, mentionedUids: List<String>, clientId: String?) =
            ChannelSendResult.Failed(ChannelSendError.Generic)
        override suspend fun loadOlder(before: String) = ChannelOlderResult.Failed
        override suspend fun markRead() = Unit
    }
}
