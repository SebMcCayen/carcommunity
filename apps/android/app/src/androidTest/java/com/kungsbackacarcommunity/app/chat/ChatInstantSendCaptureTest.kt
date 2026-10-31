package com.kungsbackacarcommunity.app.chat

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.chatchannels.*
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.moderation.ChatSurface
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native-window captures of the real composer/coordinator using local test data only.
 * Optional instrumentation argument: redesignTheme=dark|light (default dark).
 * Writes only externalFilesDir/chat-instant-send/community-<theme>-<state>.png.
 */
@RunWith(AndroidJUnit4::class)
class ChatInstantSendCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun capturePendingEmptyComposerAndFailedBubbleWithNextDraft() {
        val theme = InstrumentationRegistry.getArguments().getString("redesignTheme") ?: "dark"
        require(theme == "dark" || theme == "light")
        val f = CaptureFixture()
        compose.setContent {
            KccTheme(darkTheme = theme == "dark") {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        Text(
                            "REGRESSIONSPREVIEW · LOKALA TESTDATA",
                            Modifier.padding(8.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Box(Modifier.weight(1f)) {
                            val pending by f.coordinator.pendingMessages.collectAsState()
                            ChannelChatContent(
                                messages = ChannelThread.mergeWithPending(emptyList(), f.live, pending),
                                currentUid = SELF,
                                loading = false,
                                emptyText = "Lokalt test av direkt skickning",
                                canLoadOlder = false,
                                isLoadingOlder = false,
                                onSend = { _, _, _ -> error("Expected the confirmed coordinator path") },
                                onRetry = { error("Expected the confirmed retry path") },
                                onLoadOlder = {},
                                surface = ChatSurface.CommunityChannel,
                                onSendConfirmed = { text, mentions, reply, id ->
                                    f.coordinator.send(text, mentions, reply, id)
                                },
                                onRetryConfirmed = { message ->
                                    f.coordinator.retry(checkNotNull(message.clientId))
                                },
                            )
                        }
                    }
                }
            }
        }

        input().performTextInput(SENT_TEXT)
        compose.onNodeWithText(compose.activity.getString(R.string.channel_send)).performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { f.attempts.size == 1 } }
        val first = compose.runOnIdle { f.attempts.single() }
        assertDraft("")
        compose.onNodeWithTag("chat-body-${first.clientId}", useUnmergedTree = true)
            .assertTextEquals(SENT_TEXT).assertIsDisplayed()
        status(R.string.channel_statusSending).assertIsDisplayed()
        status(R.string.channel_statusFailedRetry).assertDoesNotExist()
        compose.runOnIdle {
            assertFalse("Capture must precede the fake server response", first.response.isCompleted)
            assertEquals(ChannelDeliveryState.Sending, f.coordinator.pendingMessages.value.single().deliveryState)
        }
        screenshotUiAutomation("community-$theme-pending")

        // Start a different draft while the first callable is still suspended,
        // then reject that callable. Its failed bubble must retain the old text.
        input().performTextInput(NEXT_DRAFT)
        assertTrue(first.response.complete(false))
        compose.waitUntil(5_000) {
            compose.runOnIdle {
                f.coordinator.pendingMessages.value.singleOrNull()?.deliveryState == ChannelDeliveryState.Failed
            }
        }
        assertDraft(NEXT_DRAFT)
        status(R.string.channel_statusSending).assertDoesNotExist()
        status(R.string.channel_statusFailedRetry).assertIsDisplayed().assertIsEnabled()
        compose.onAllNodesWithTag("chat-body-${first.clientId}", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag("chat-body-${first.clientId}", useUnmergedTree = true)
            .assertTextEquals(SENT_TEXT).assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, f.attempts.size) }
        screenshotUiAutomation("community-$theme-failed-next-draft")
    }

    private fun input() = compose.onNodeWithTag(CHANNEL_INPUT_TEST_TAG)
    private fun assertDraft(expected: String) {
        assertEquals(expected, input().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }
    private fun status(id: Int) = compose.onNodeWithText(compose.activity.getString(id))

    private fun screenshotUiAutomation(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Let Android present the settled Compose frame before taking the native window.
        SystemClock.sleep(250)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val output = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "chat-instant-send")
            check(output.mkdirs() || output.isDirectory)
            File(output, "$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private data class CaptureAttempt(
        val clientId: String,
        val response: CompletableDeferred<Boolean> = CompletableDeferred(),
    )

    private class CaptureFixture {
        val attempts = mutableListOf<CaptureAttempt>()
        val live = listOf(ChannelMessage(
            id = "local-context", senderUid = "local-test-peer",
            text = "Lokalt testmeddelande – ingen server är ansluten.",
            senderDisplayName = "Lokal testmedlem", senderAvatarPath = null,
            createdAtMillis = FIXTURE_TIME - 60_000, createdAtIso = null,
        ))
        val coordinator = ChannelChatCoordinator(
            selfUid = SELF,
            clock = { FIXTURE_TIME },
            pager = { ChannelOlderResult.Failed },
            sender = { text, _, clientId, _ ->
                check(text == SENT_TEXT)
                val attempt = CaptureAttempt(clientId)
                attempts += attempt
                if (attempt.response.await()) ChannelSendResult.Sent(clientId)
                else ChannelSendResult.Failed(ChannelSendError.Generic)
            },
        )
    }

    private companion object {
        const val SELF = "local-instant-send-capture"
        const val SENT_TEXT = "Vi ses vid parkeringen 🚗"
        const val NEXT_DRAFT = "Nästa utkast ska vara kvar 🚙"
        const val FIXTURE_TIME = 1_792_936_800_000L
    }
}
