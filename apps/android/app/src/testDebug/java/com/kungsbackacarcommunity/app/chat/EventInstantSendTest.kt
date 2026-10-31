package com.kungsbackacarcommunity.app.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.design.chatQuickEmojiTestTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h900dp-mdpi")
class EventInstantSendTest {
    @get:Rule val compose = createComposeRule()
    private val rows = mutableStateOf<List<ChatMessage>>(emptyList())
    private val cooldown = mutableStateOf(0)
    private val attempts = mutableListOf<Attempt>()

    @Test fun immediateClearAndRpcAcceptanceThenServerEchoPreserveNewDraft() {
        start()
        send().performClick()
        awaitAttempts(1)
        draft("")
        compose.onNodeWithText(TEXT).assertIsDisplayed()
        status(R.string.channel_statusSending).assertIsDisplayed()
        input().performTextInput("Next draft")
        send().assertIsNotEnabled()
        compose.runOnIdle { attempts.single().ack.complete(true) }
        draft("Next draft")
        status(R.string.channel_statusSending).assertDoesNotExist()
        compose.onNodeWithText(TEXT).assertIsDisplayed()
        compose.runOnIdle { echo(0) }
        status(R.string.channel_statusSending).assertDoesNotExist()
        compose.onAllNodesWithText(TEXT).assertCountEquals(1)
        draft("Next draft")
    }

    @Test fun rejectedUneditedDraftRestoresAndRetryReusesClientId() {
        start()
        send().performClick()
        awaitAttempts(1)
        compose.runOnIdle { attempts.single().ack.complete(false) }
        draft(TEXT)
        send().performClick()
        awaitAttempts(2)
        draft("")
        compose.runOnIdle { assertEquals(attempts[0].id, attempts[1].id) }
    }

    @Test fun lateOwnEchoClearsOnlyUntouchedRestoredDraft() {
        start()
        send().performClick()
        awaitAttempts(1)
        compose.runOnIdle { attempts.single().ack.complete(false) }
        draft(TEXT)
        compose.runOnIdle { echo(0) }
        draft("")
        status(R.string.channel_statusFailedRetry).assertDoesNotExist()
        compose.onAllNodesWithText(TEXT).assertCountEquals(1)
    }

    @Test fun lateOwnEchoPreservesNewerDraftEvenWhenTextMatchesRestoration() {
        start()
        send().performClick()
        awaitAttempts(1)
        compose.runOnIdle { attempts.single().ack.complete(false) }
        draft(TEXT)
        input().performTextReplacement("Another draft")
        input().performTextReplacement(TEXT)
        compose.runOnIdle { echo(0) }
        draft(TEXT)
        status(R.string.channel_statusFailedRetry).assertDoesNotExist()
    }

    @Test fun unrelatedEchoDoesNotConsumeRestoredDraft() {
        start()
        send().performClick()
        awaitAttempts(1)
        compose.runOnIdle { attempts.single().ack.complete(false) }
        draft(TEXT)
        compose.runOnIdle {
            rows.value = listOf(ChatMessage(attempts.single().id, "other", "Other", TEXT, false, createdAtMillis = 0L))
        }
        draft(TEXT)
        compose.runOnIdle {
            rows.value = listOf(ChatMessage("unrelated", "self", "Me", TEXT, false, createdAtMillis = 0L))
        }
        draft(TEXT)
        compose.runOnIdle { echo(0) }
        draft("")
    }

    @Test fun legacyIdleCallbackAllowsAnotherSendAndEmojiKeepsDraft() {
        val sent = mutableListOf<String>()
        compose.setContent { KccTheme {
            EventChatScreen(
                state = ChatMessagesState.Loaded(emptyList()), currentUid = "self", canParticipate = true,
                sendStatus = ChatSendStatus.Idle, reportStatus = ChatReportStatus.Idle,
                onSend = { sent += it }, onReport = { _, _ -> }, onReportDismiss = {}, onBack = {},
            )
        } }
        input().performTextInput(TEXT)
        send().performClick()
        draft("")
        input().performTextInput("Next draft")
        send().assertIsEnabled()
        compose.onNodeWithTag(chatQuickEmojiTestTag("😊")).performClick()
        draft("Next draft")
        send().assertIsEnabled().performClick()
        draft("")
        compose.runOnIdle { assertEquals(listOf(TEXT, "😊", "Next draft"), sent) }
    }

    @Test fun failureAndBubbleRetryPreserveNewDraft() {
        start()
        send().performClick()
        awaitAttempts(1)
        input().performTextInput("Keep this")
        compose.runOnIdle { attempts.single().ack.complete(false) }
        draft("Keep this")
        status(R.string.channel_statusFailedRetry).performClick()
        awaitAttempts(2)
        compose.runOnIdle {
            assertEquals(attempts[0].id, attempts[1].id)
            assertEquals(TEXT, attempts[1].text)
            attempts[1].ack.complete(true)
        }
        draft("Keep this")
    }

    @Test fun changedThenEmptiedComposerIsNotRestored() {
        start()
        send().performClick()
        awaitAttempts(1)
        input().performTextInput("Changed")
        input().performTextClearance()
        compose.runOnIdle { attempts.single().ack.complete(false) }
        draft("")
        status(R.string.channel_statusFailedRetry).assertIsDisplayed()
    }

    @Test fun quickEmojiKeepsTypedDraftAndOriginalIdOnRetry() {
        start()
        compose.onNodeWithTag(chatQuickEmojiTestTag("😊")).performClick()
        awaitAttempts(1)
        draft(TEXT)
        compose.runOnIdle { attempts.single().ack.complete(false) }
        draft(TEXT)
        status(R.string.channel_statusFailedRetry).performClick()
        awaitAttempts(2)
        compose.runOnIdle {
            assertEquals("😊", attempts[0].text)
            assertEquals(attempts[0].id, attempts[1].id)
            attempts[1].ack.complete(true)
        }
        draft(TEXT)
    }

    @Test fun ownServerEchoBeforeFalseResponseWinsAndDoesNotRestore() {
        start()
        send().performClick()
        awaitAttempts(1)
        compose.runOnIdle { echo(0) }
        compose.waitForIdle()
        compose.runOnIdle { attempts.single().ack.complete(false) }
        draft("")
        status(R.string.channel_statusFailedRetry).assertDoesNotExist()
        compose.onAllNodesWithText(TEXT).assertCountEquals(1)
    }

    @Test fun nextAcceptedIdenticalMessageUsesNewIdAndCooldownBlocksRetries() {
        start()
        send().performClick()
        awaitAttempts(1)
        compose.runOnIdle { echo(0); attempts[0].ack.complete(true) }
        input().performTextInput(TEXT)
        send().performClick()
        awaitAttempts(2)
        compose.runOnIdle {
            assertNotEquals(attempts[0].id, attempts[1].id)
            attempts[1].ack.complete(false)
            cooldown.value = 15
        }
        send().assertIsNotEnabled()
        status(R.string.channel_statusFailedRetry).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(2, attempts.size) }
    }

    @Test fun cancellationLeavesExplicitSameIdRetryAndPreservesNewDraft() {
        start()
        send().performClick()
        awaitAttempts(1)
        input().performTextInput("Keep this")
        compose.runOnIdle { attempts.single().ack.completeExceptionally(CancellationException("cancel")) }
        draft("Keep this")
        status(R.string.channel_statusFailedRetry).performClick()
        awaitAttempts(2)
        compose.runOnIdle { assertEquals(attempts[0].id, attempts[1].id) }
    }

    @Test fun rotationRestoresPendingContentAndNewDraftWithoutAutoSend() {
        val restoration = StateRestorationTester(compose)
        start(restoration)
        send().performClick()
        awaitAttempts(1)
        input().performTextInput("Next draft")
        restoration.emulateSavedInstanceStateRestore()
        draft("Next draft")
        status(R.string.channel_statusFailedRetry).assertIsDisplayed().performClick()
        awaitAttempts(2)
        compose.runOnIdle { assertEquals(attempts[0].id, attempts[1].id) }
    }

    private fun start(restoration: StateRestorationTester? = null) {
        val content: @androidx.compose.runtime.Composable () -> Unit = { KccTheme {
            EventChatScreen(
                state = ChatMessagesState.Loaded(rows.value), currentUid = "self", canParticipate = true,
                sendStatus = ChatSendStatus.Idle, reportStatus = ChatReportStatus.Idle,
                onSend = {}, onReport = { _, _ -> }, onReportDismiss = {}, onBack = {},
                cooldownSeconds = cooldown.value,
                onSendConfirmed = { text, id ->
                    val attempt = Attempt(text, id)
                    attempts += attempt
                    attempt.ack.await()
                },
            )
        } }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
        input().performTextInput(TEXT)
    }
    private fun echo(index: Int) {
        val attempt = attempts[index]
        rows.value = rows.value + ChatMessage(attempt.id, "self", "Me", attempt.text, false, createdAtMillis = 0L)
    }
    private fun input() = compose.onNodeWithTag("event-chat-input")
    private fun draft(text: String) = assertEquals(text, input().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun send() = compose.onNodeWithText(str(R.string.chat_sendButton))
    private fun status(id: Int) = compose.onNodeWithText(str(id))
    private fun awaitAttempts(count: Int) = compose.waitUntil(5_000) { compose.runOnIdle { attempts.size == count } }
    private data class Attempt(val text: String, val id: String, val ack: CompletableDeferred<Boolean> = CompletableDeferred())
    private companion object { const val TEXT = "Event draft 🚗" }
}
