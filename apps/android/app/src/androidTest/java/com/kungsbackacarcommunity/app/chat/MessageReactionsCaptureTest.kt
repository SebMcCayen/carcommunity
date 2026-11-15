package com.kungsbackacarcommunity.app.chat

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.chatchannels.*
import com.kungsbackacarcommunity.app.design.CHAT_QUICK_EMOJI_ROW_TEST_TAG
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.moderation.ChatSurface
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real native chat widgets with an injected in-memory transport. Never signs in or contacts production. */
@RunWith(AndroidJUnit4::class)
class MessageReactionsCaptureTest {
    private lateinit var state: ReactionRoomState
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun personalSingleRowAndOfflinePickerDoNotWriteHistoryToBackend() {
        val theme = InstrumentationRegistry.getArguments().getString("redesignTheme") ?: "dark"
        val calls = mutableListOf<Pair<String, Map<String, Any?>>>()
        val initial = mapOf("1f44d" to 3L, "2764-fe0f" to 2L)
        val messages = mutableStateOf(listOf(
            ChannelMessage("incoming", "fixture-author", "Ses vid stationen kl 19?", "Testmedlem A", null, System.currentTimeMillis() - 120_000, null, reactionCounts = initial),
            ChannelMessage("incoming-two", "fixture-author-two", "Låter bra! Jag är också med.", "Testmedlem B", null, System.currentTimeMillis() - 60_000, null),
            ChannelMessage("own", "fixture-viewer", "Jag kommer!", null, null, System.currentTimeMillis(), null),
        ))
        var chosen: String? = null
        var version = 0L
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        compose.setContent {
            val factory = remember {
                { room: ReactionRoom, uid: String, context: android.content.Context, scope: kotlinx.coroutines.CoroutineScope ->
                    ReactionRoomState(room, uid, context, scope, sessionIsCurrent = { true }, remoteCall = { name, payload ->
                        calls += name to payload
                        if (name.endsWith("getMine")) mapOf("items" to (payload["messageIds"] as List<*>).map { id -> mapOf("messageId" to id, "emojiId" to chosen.takeIf { id == "incoming" }, "version" to if (id == "incoming") version else 0L) })
                        else {
                            val desired = payload["emojiId"] as? String
                            val counts = messages.value.first().reactionCounts.toMutableMap()
                            chosen?.let { key -> counts[key]?.let { value -> if (value == 1L) counts.remove(key) else counts[key] = value - 1 } }
                            desired?.let { key -> counts[key] = (counts[key] ?: 0) + 1 }
                            chosen = desired; version++
                            messages.value = messages.value.map { if (it.id == "incoming") it.copy(reactionCounts = counts) else it }
                            mapOf("messageId" to "incoming", "emojiId" to desired, "version" to version, "counts" to counts)
                        }
                    }).also {
                        state = it; it.store.clear(); it.enabled = true
                        listOf("incoming", "incoming-two", "own").forEach { id -> it.own[id] = ReactionChoice(null, 0) }
                    }
                }
            }
            CompositionLocalProvider(LocalReactionRoomFactory provides factory) {
                KccTheme(darkTheme = theme == "dark") {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                            ChatFeedSelector(false, {})
                            ChatChannelStrip(ChatTab.Community, listOf(false, false, false, false), {}, {})
                            HorizontalDivider()
                            Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                ChannelChatContent(messages.value, "fixture-viewer", false, "", false, false,
                                    onSend = { _, _, _ -> }, onRetry = {}, onLoadOlder = {}, surface = ChatSurface.CommunityChannel,
                                    chatRepliesEnabled = true, onBlock = {}, onReport = { _, _ -> })
                            }
                        }
                    }
                }
            }
        }
        compose.waitUntil(15_000) { ::state.isInitialized && state.catalog.size == 3963 }
        compose.onNodeWithTag(CHAT_QUICK_EMOJI_ROW_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithText("Ses vid stationen kl 19?").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("reaction-quick-row").assertIsDisplayed()
        screenshot("$theme-selected")
        val before = calls.count { it.first.endsWith("-set") }
        compose.onNodeWithContentDescription(InstrumentationRegistry.getInstrumentation().targetContext.getString(com.kungsbackacarcommunity.app.R.string.reactions_all)).performClick()
        compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(com.kungsbackacarcommunity.app.R.string.reactions_search)).performTextInput("raket")
        compose.onNodeWithContentDescription(state.catalog.first { it.id == "1f680" }.name).assertIsDisplayed()
        assertEquals(before, calls.count { it.first.endsWith("-set") })
        screenshot("$theme-picker")
        compose.onNodeWithContentDescription(state.catalog.first { it.id == "1f680" }.name).performClick()
        compose.waitUntil(10_000) { !state.busy && state.selectedId == null }
        compose.onNodeWithText("🚀 1 ✓").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, calls.count { it.first.endsWith("-set") })
            assertEquals(1L, state.store.usage["1f680"]?.first)
        }
        compose.onNodeWithText("Ses vid stationen kl 19?").performTouchInput { longClick() }
        compose.onNodeWithContentDescription(state.catalog.first { it.id == "1f680" }.name).assertIsDisplayed()
        screenshot("$theme-personal-history")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "chat-reactions/$name.png")
        file.parentFile!!.mkdirs()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
