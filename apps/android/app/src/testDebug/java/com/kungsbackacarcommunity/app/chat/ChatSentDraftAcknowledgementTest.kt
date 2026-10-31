package com.kungsbackacarcommunity.app.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.chatchannels.*
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.design.chatQuickEmojiTestTag
import com.kungsbackacarcommunity.app.dm.*
import com.kungsbackacarcommunity.app.moderation.ChatSurface
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Real composers and controlled acknowledgements; no Firebase, network or emulator. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h900dp-mdpi")
class ChatSentDraftAcknowledgementTest {
    @get:Rule val compose = createComposeRule()

    @Test fun communityRpcSuccessClearsDraft() = rpcSuccess(Surface.Community)
    @Test fun convoyRpcSuccessClearsDraft() = rpcSuccess(Surface.Convoy)
    @Test fun dmRpcSuccessClearsDraft() = rpcSuccess(Surface.Dm)
    @Test fun eventRpcSuccessClearsDraft() = rpcSuccess(Surface.Event)

    // Regression: a delivered row renders while the callable response is still suspended.
    // Before this fix the input remains populated even though delivery is already known.
    @Test fun communityDeliveredRowClearsBeforeRpcResponse() = deliveredBeforeRpc(Surface.Community)
    @Test fun convoyDeliveredRowClearsBeforeRpcResponse() = deliveredBeforeRpc(Surface.Convoy)
    @Test fun dmDeliveredRowClearsBeforeRpcResponse() = deliveredBeforeRpc(Surface.Dm)
    @Test fun eventDeliveredRowClearsBeforeRpcResponse() = deliveredBeforeRpc(Surface.Event)

    @Test fun communityLateRpcDoesNotClearNewSameTextDraft() = lateRpc(Surface.Community)
    @Test fun convoyLateRpcDoesNotClearNewSameTextDraft() = lateRpc(Surface.Convoy)
    @Test fun dmLateRpcDoesNotClearNewSameTextDraft() = lateRpc(Surface.Dm)
    @Test fun eventLateRpcDoesNotClearNewSameTextDraft() = lateRpc(Surface.Event)

    @Test fun communityEditAwayAndBackIsANewDraft() = editAwayAndBack(Surface.Community)
    @Test fun convoyEditAwayAndBackIsANewDraft() = editAwayAndBack(Surface.Convoy)
    @Test fun dmEditAwayAndBackIsANewDraft() = editAwayAndBack(Surface.Dm)
    @Test fun eventEditAwayAndBackIsANewDraft() = editAwayAndBack(Surface.Event)

    @Test fun communityFailureAndCooldownRetainDraftAndRetryId() = failureAndCooldown(Surface.Community)
    @Test fun convoyFailureAndCooldownRetainDraftAndRetryId() = failureAndCooldown(Surface.Convoy)
    @Test fun dmFailureAndCooldownRetainDraftAndRetryId() = failureAndCooldown(Surface.Dm)
    @Test fun eventFailureAndCooldownRetainDraftAndRetryId() = failureAndCooldown(Surface.Event)

    @Test fun communityDeliveredQuickEmojiKeepsTypedDraft() = quickEmoji(Surface.Community)
    @Test fun convoyDeliveredQuickEmojiKeepsTypedDraft() = quickEmoji(Surface.Convoy)
    @Test fun dmDeliveredQuickEmojiKeepsTypedDraft() = quickEmoji(Surface.Dm)
    @Test fun eventDeliveredQuickEmojiKeepsTypedDraft() = quickEmoji(Surface.Event)

    @Test fun communityRequiresOwnDeliveredExactId() = unrelatedRows(Surface.Community)
    @Test fun dmRequiresOwnDeliveredExactId() = unrelatedRows(Surface.Dm)
    @Test fun eventRequiresOwnExactId() = unrelatedRows(Surface.Event)

    @Test fun communityRetryPreservesEditsMadeWhileRetrying() = retryEdits(Surface.Community)
    @Test fun dmRetryPreservesEditsMadeWhileRetrying() = retryEdits(Surface.Dm)
    @Test fun communityOldBubbleCannotClearAnotherDraftWithSameText() = oldBubble(Surface.Community)
    @Test fun dmOldBubbleCannotClearAnotherDraftWithSameText() = oldBubble(Surface.Dm)

    private fun rpcSuccess(surface: Surface) {
        val f = start(surface)
        send(f).performClick()
        awaitAttempts(f, 1)
        draft("") // The network response is deliberately still suspended.
        compose.runOnIdle { f.attempts.single().ack.complete(true) }
        draft("")
        compose.runOnIdle { assertEquals(1, f.attempts.size) }
    }

    private fun deliveredBeforeRpc(surface: Surface) {
        val f = start(surface)
        send(f).performClick()
        awaitAttempts(f, 1)
        compose.runOnIdle { f.publish(f.attempts.single()) }
        draft("")
        compose.runOnIdle {
            assertFalse(f.attempts.single().ack.isCompleted)
            assertEquals(1, f.attempts.size)
        }
    }

    private fun lateRpc(surface: Surface) {
        val f = start(surface)
        send(f).performClick()
        awaitAttempts(f, 1)
        compose.runOnIdle { f.publish(f.attempts.single()) }
        draft("")
        input().performTextInput(TEXT) // A new draft may intentionally repeat the same text.
        compose.runOnIdle { f.attempts.single().ack.complete(true) }
        draft(TEXT)
        send(f).performClick()
        awaitAttempts(f, 2)
        compose.runOnIdle { assertNotEquals(f.attempts[0].id, f.attempts[1].id) }
    }

    private fun editAwayAndBack(surface: Surface) {
        val f = start(surface)
        send(f).performClick()
        awaitAttempts(f, 1)
        input().performTextReplacement("Another draft")
        input().performTextReplacement(TEXT)
        compose.runOnIdle { f.attempts.single().ack.complete(true) }
        draft(TEXT) // String equality alone incorrectly clears this newer edit.
        send(f).performClick()
        awaitAttempts(f, 2)
        compose.runOnIdle { assertNotEquals(f.attempts[0].id, f.attempts[1].id) }
    }

    private fun failureAndCooldown(surface: Surface) {
        val f = start(surface)
        send(f).performClick()
        awaitAttempts(f, 1)
        compose.runOnIdle { f.seconds.value = 30; f.attempts.single().ack.complete(false) }
        draft(TEXT)
        send(f).assertIsNotEnabled()
        compose.runOnIdle { f.seconds.value = 0 }
        send(f).assertIsEnabled()
        compose.runOnIdle { assertEquals(1, f.attempts.size) } // Expiry is not auto-send.
        send(f).performClick()
        awaitAttempts(f, 2)
        compose.runOnIdle {
            assertEquals(f.attempts[0].id, f.attempts[1].id)
            f.attempts[1].ack.complete(true)
        }
        draft("")
    }

    private fun quickEmoji(surface: Surface) {
        val f = start(surface)
        compose.onNodeWithTag(chatQuickEmojiTestTag("😊")).performClick()
        awaitAttempts(f, 1)
        compose.runOnIdle { assertEquals("😊", f.attempts.single().text); f.publish(f.attempts.single()) }
        draft(TEXT)
        compose.runOnIdle { f.attempts.single().ack.complete(true) }
        draft(TEXT)
    }

    private fun unrelatedRows(surface: Surface) {
        val f = start(surface)
        send(f).performClick()
        awaitAttempts(f, 1)
        compose.runOnIdle { f.publish(f.attempts.single(), author = "other") }
        draft("")
        compose.runOnIdle { f.publish(f.attempts.single().copy(id = "unrelated-own-row")) }
        draft("")
        if (surface != Surface.Event) {
            compose.runOnIdle { f.publish(f.attempts.single(), delivered = false) }
            draft("")
        }
        compose.runOnIdle { f.publish(f.attempts.single()) }
        draft("")
        // The exact own delivered row beats a later failed callable response.
        compose.runOnIdle { f.attempts.single().ack.complete(false) }
        draft("")
    }

    private fun retryEdits(surface: Surface) {
        val f = start(surface)
        send(f).performClick()
        awaitAttempts(f, 1)
        compose.runOnIdle { f.attempts[0].ack.complete(false) }
        send(f).assertIsEnabled()
        compose.runOnIdle { f.publish(f.attempts[0], failed = true) }
        retry(f).performClick()
        awaitAttempts(f, 2)
        input().performTextReplacement("New draft during retry")
        input().performTextReplacement(TEXT)
        compose.runOnIdle {
            assertEquals(f.attempts[0].id, f.attempts[1].id)
            f.attempts[1].ack.complete(true)
        }
        draft(TEXT)
    }

    private fun oldBubble(surface: Surface) {
        val f = start(surface)
        send(f).performClick()
        awaitAttempts(f, 1)
        compose.runOnIdle { f.attempts[0].ack.complete(false) }
        send(f).assertIsEnabled()
        input().performTextReplacement("Another attempted message")
        send(f).performClick()
        awaitAttempts(f, 2)
        compose.runOnIdle { f.attempts[1].ack.complete(false) }
        send(f).assertIsEnabled()
        input().performTextReplacement(TEXT)
        compose.runOnIdle { f.publish(f.attempts[0], failed = true) }
        retry(f).performClick()
        awaitAttempts(f, 3)
        compose.runOnIdle {
            assertEquals(f.attempts[0].id, f.attempts[2].id)
            assertNotEquals(f.attempts[0].id, f.attempts[1].id)
            f.attempts[2].ack.complete(true)
        }
        draft(TEXT)
    }

    private fun retry(f: Fixture) = compose.onNodeWithText(
        InstrumentationRegistry.getInstrumentation().targetContext.getString(
            if (f.surface == Surface.Dm) R.string.dm_statusFailedRetry else R.string.channel_statusFailedRetry),
    )

    private fun start(surface: Surface): Fixture {
        val f = Fixture(surface)
        compose.setContent { KccTheme { f.Content() } }
        input().performTextInput(TEXT)
        return f
    }
    private fun input() = compose.onNode(hasSetTextAction())
    private fun draft(text: String) {
        assertEquals(text, input().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }
    private fun send(f: Fixture) = compose.onNodeWithText(
        InstrumentationRegistry.getInstrumentation().targetContext.getString(when (f.surface) {
            Surface.Dm -> R.string.dm_send
            Surface.Event -> R.string.chat_sendButton
            else -> R.string.channel_send
        }),
    )
    private fun awaitAttempts(f: Fixture, count: Int) = compose.waitUntil(5_000) {
        compose.runOnIdle { f.attempts.size == count }
    }

    private enum class Surface { Community, Convoy, Dm, Event }
    private data class Attempt(val text: String, val id: String, val ack: CompletableDeferred<Boolean> = CompletableDeferred())
    private class Fixture(val surface: Surface) {
        val attempts = mutableListOf<Attempt>()
        val seconds = mutableStateOf(0)
        val channels = mutableStateOf(emptyList<ChannelMessage>())
        val dms = mutableStateOf(emptyList<DmMessage>())
        val events = mutableStateOf<ChatMessagesState>(ChatMessagesState.Loaded(emptyList()))
        suspend fun confirm(text: String, id: String): Boolean {
            val attempt = Attempt(text, id)
            attempts += attempt
            return attempt.ack.await()
        }
        fun publish(attempt: Attempt, author: String = "self", delivered: Boolean = true, failed: Boolean = false) {
            channels.value = listOf(ChannelMessage(attempt.id, author, attempt.text, null, null, 1L, null,
                clientId = attempt.id,
                deliveryState = if (failed) ChannelDeliveryState.Failed else if (delivered) ChannelDeliveryState.Sent else ChannelDeliveryState.Sending,
                sendError = if (failed) ChannelSendError.Generic else null))
            dms.value = listOf(DmMessage(attempt.id, author, attempt.text, 1L, null, clientId = attempt.id,
                deliveryState = if (failed) DmDeliveryState.Failed else if (delivered) DmDeliveryState.Sent else DmDeliveryState.Sending,
                sendError = if (failed) DmSendError.Generic else null))
            events.value = ChatMessagesState.Loaded(listOf(ChatMessage(attempt.id, author, null, attempt.text,
                isRemoved = false, createdAtMillis = 1L)))
        }
        @Composable fun Content() {
            when (surface) {
                Surface.Community, Surface.Convoy -> ChannelChatContent(
                    channels.value, "self", false, "Empty", false, false,
                    onSend = { _, _, _ -> error("No legacy/duplicate send") }, onRetry = {}, onLoadOlder = {},
                    surface = if (surface == Surface.Community) ChatSurface.CommunityChannel else ChatSurface.ConvoyChannel,
                    cooldownSeconds = seconds.value,
                    onSendConfirmed = { text, _, _, id -> confirm(text, id) },
                    onRetryConfirmed = { message -> confirm(message.text, checkNotNull(message.clientId)) },
                )
                Surface.Dm -> ChatScreen("Other", dms.value, "self", false, false, false,
                    onSend = { _, _ -> error("No legacy/duplicate send") }, onRetry = {}, onLoadOlder = {},
                    cooldownSeconds = seconds.value, onSendConfirmed = { text, _, id -> confirm(text, id) },
                    onRetryConfirmed = { message -> confirm(message.text, checkNotNull(message.clientId)) })
                Surface.Event -> EventChatScreen(events.value, "self", true, ChatSendStatus.Idle,
                    ChatReportStatus.Idle, onSend = { error("No legacy/duplicate send") }, onReport = { _, _ -> },
                    onReportDismiss = {}, onBack = {}, cooldownSeconds = seconds.value,
                    onSendConfirmed = { text, id -> confirm(text, id) })
            }
        }
    }
    private companion object { const val TEXT = "First draft 🚗" }
}
