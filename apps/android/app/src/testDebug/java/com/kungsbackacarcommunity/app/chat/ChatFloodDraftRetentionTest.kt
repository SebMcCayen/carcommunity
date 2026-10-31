package com.kungsbackacarcommunity.app.chat

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.chatchannels.ChannelChatContent
import com.kungsbackacarcommunity.app.chatchannels.CHANNEL_INPUT_TEST_TAG
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.design.chatQuickEmojiTestTag
import com.kungsbackacarcommunity.app.dm.ChatScreen
import com.kungsbackacarcommunity.app.moderation.ChatSurface
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h900dp-mdpi")
class ChatFloodDraftRetentionTest {
    @get:Rule val compose = createComposeRule()
    private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun communityRejectKeepsDraftAndNeverDispatchesAtExpiry() {
        val seconds = mutableStateOf(0)
        var calls = 0
        compose.setContent { KccTheme {
            ChannelChatContent(emptyList(), "self", false, "empty", false, false,
                onSend = { _, _, _ -> fail("Legacy callback must not dispatch too") }, onRetry = {}, onLoadOlder = {},
                surface = ChatSurface.CommunityChannel, cooldownSeconds = seconds.value,
                onSendConfirmed = { text, _, _, _ -> assertEquals("Draft 🚗", text); calls++; seconds.value = 30; false })
        } }
        compose.onNodeWithTag(CHANNEL_INPUT_TEST_TAG).performTextInput("Draft 🚗")
        compose.onNodeWithText(str(R.string.channel_send)).performClick()
        compose.onNodeWithText("Draft 🚗").assertExists()
        compose.onNodeWithText(str(R.string.channel_send)).assertIsNotEnabled()
        compose.onNodeWithTag(chatQuickEmojiTestTag("😊")).assertIsNotEnabled()
        compose.runOnIdle { seconds.value = 0 }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText(str(R.string.channel_send)).assertIsEnabled()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun successfulAcknowledgementNeverOverwritesANewerChannelDraft() {
        val ack = CompletableDeferred<Boolean>()
        compose.setContent { KccTheme {
            ChannelChatContent(emptyList(), "self", false, "empty", false, false,
                onSend = { _, _, _ -> }, onRetry = {}, onLoadOlder = {}, surface = ChatSurface.ConvoyChannel,
                onSendConfirmed = { _, _, _, _ -> ack.await() })
        } }
        compose.onNodeWithTag(CHANNEL_INPUT_TEST_TAG).performTextInput("First draft")
        compose.onNodeWithText(str(R.string.channel_send)).performClick()
        compose.onNodeWithTag(CHANNEL_INPUT_TEST_TAG).performTextInput(" with a newer edit")
        compose.runOnIdle { ack.complete(true) }
        compose.onNodeWithText(" with a newer edit").assertExists()
    }

    @Test fun dmRejectKeepsDraftAndGatesQuickEmoji() {
        val seconds = mutableStateOf(0)
        compose.setContent { KccTheme {
            ChatScreen("Other", emptyList(), "self", false, false, false,
                onSend = { _, _ -> fail("Unconfirmed path") }, onRetry = {}, onLoadOlder = {},
                cooldownSeconds = seconds.value, onSendConfirmed = { _, _, _ -> seconds.value = 5; false })
        } }
        compose.onNodeWithText(str(R.string.dm_inputPlaceholder)).performTextInput("DM draft 🚗")
        compose.onNodeWithText(str(R.string.dm_send)).performClick()
        compose.onNodeWithText("DM draft 🚗").assertExists()
        compose.onNodeWithTag(chatQuickEmojiTestTag("😊")).assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.dm_send)).assertIsNotEnabled()
    }

    @Test fun eventRejectKeepsDraftAndGatesQuickEmoji() {
        val seconds = mutableStateOf(0)
        compose.setContent { KccTheme {
            EventChatScreen(ChatMessagesState.Loaded(emptyList()), "self", true,
                ChatSendStatus.Idle, ChatReportStatus.Idle, onSend = { fail("Unconfirmed path") },
                onReport = { _, _ -> }, onReportDismiss = {}, onBack = {},
                cooldownSeconds = seconds.value, onSendConfirmed = { _, _ -> seconds.value = 5; false })
        } }
        compose.onNodeWithText(str(R.string.chat_inputPlaceholder)).performTextInput("Event draft 🚗")
        compose.onNodeWithText(str(R.string.chat_sendButton)).performClick()
        compose.onNodeWithTag("event-chat-input").assertTextEquals("Event draft 🚗")
        compose.onNodeWithTag(chatQuickEmojiTestTag("😊")).assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.chat_sendButton)).assertIsNotEnabled()
    }

    @Test fun rejectedAttemptRestoresSameIdWithoutSendingOnScreenRecreation() {
        val ids = mutableListOf<String>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { KccTheme {
            ChannelChatContent(emptyList(), "self", false, "empty", false, false,
                onSend = { _, _, _ -> }, onRetry = {}, onLoadOlder = {}, surface = ChatSurface.CommunityChannel,
                onSendConfirmed = { _, _, _, id -> ids += id; false })
        } }
        compose.onNodeWithTag(CHANNEL_INPUT_TEST_TAG).performTextInput("Retained draft 🚗")
        compose.onNodeWithText(str(R.string.channel_send)).performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Retained draft 🚗").assertExists()
        compose.runOnIdle { assertEquals(1, ids.size) }
        compose.onNodeWithText(str(R.string.channel_send)).performClick()
        compose.runOnIdle { assertEquals(2, ids.size); assertEquals(ids[0], ids[1]) }
    }
}
