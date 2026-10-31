package com.kungsbackacarcommunity.app.chat

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native Compose-root captures of the real composer/coordinator using local test data only.
 * Optional instrumentation argument: redesignTheme=dark|light (default dark).
 * Writes only externalFilesDir/chat-instant-send/community-<theme>-<state>.png.
 */
@RunWith(AndroidJUnit4::class)
class ChatInstantSendCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun matchProductionImeWindow() {
        compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            val light = InstrumentationRegistry.getArguments().getString("redesignTheme") == "light"
            WindowCompat.getInsetsController(it.window, it.window.decorView).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
    }

    @Test fun capturePendingEmptyComposerAndFailedBubbleWithNextDraft() {
        val theme = InstrumentationRegistry.getArguments().getString("redesignTheme") ?: "dark"
        require(theme == "dark" || theme == "light")
        val f = CaptureFixture()
        compose.setContent {
            KccTheme(darkTheme = theme == "dark") {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().statusBarsPadding()) {
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
            .assertTextEquals(SENT_TEXT).awaitDisplayed()
        status(R.string.channel_statusSending).awaitDisplayed()
        status(R.string.channel_statusFailedRetry).assertDoesNotExist()
        compose.runOnIdle {
            assertFalse("Capture must precede the fake server response", first.response.isCompleted)
            assertEquals(ChannelDeliveryState.Sending, f.coordinator.pendingMessages.value.single().deliveryState)
        }
        screenshotComposeRoot("community-$theme-pending", first.clientId)

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
        status(R.string.channel_statusFailedRetry).awaitDisplayed().assertIsEnabled()
        compose.onAllNodesWithTag("chat-body-${first.clientId}", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag("chat-body-${first.clientId}", useUnmergedTree = true)
            .assertTextEquals(SENT_TEXT).awaitDisplayed()
        compose.runOnIdle { assertEquals(1, f.attempts.size) }
        screenshotComposeRoot("community-$theme-failed-next-draft", first.clientId)
    }

    private fun input() = compose.onNodeWithTag(CHANNEL_INPUT_TEST_TAG)
    private fun assertDraft(expected: String) {
        assertEquals(expected, input().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }
    private fun status(id: Int) = compose.onNodeWithText(compose.activity.getString(id))
    private fun SemanticsNodeInteraction.awaitDisplayed(): SemanticsNodeInteraction {
        // KeepPinnedToNewest and the platform IME resize settle asynchronously.
        // Composer clearing above is immediate; visibility must settle naturally,
        // without scrolling the test to hide a real pinning/layout regression.
        compose.waitUntil(5_000) {
            compose.onAllNodes(isRoot()).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() && isDisplayed()
        }
        return assertIsDisplayed()
    }

    private fun screenshotComposeRoot(name: String, messageId: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val bodyNode = compose.onNodeWithTag("chat-body-$messageId", useUnmergedTree = true)
        val root = compose.onRoot()
        // PixelCopy captures the Compose window itself. UiAutomation's display
        // screenshot can use a different origin while the IME resizes the window,
        // so its coordinates cannot safely be mixed with boundsInWindow here.
        // Keep the rendered-ink guard and use root-relative coordinates throughout.
        var accepted: Bitmap? = null
        compose.waitUntil(5_000) {
            val bounds = bodyNode.fetchSemanticsNode().boundsInRoot
            val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
            val bitmap = root.captureToImage().asAndroidBitmap()
            var ink = 0
            for (y in (bounds.top - origin.y).toInt().coerceAtLeast(0) until (bounds.bottom - origin.y).toInt().coerceAtMost(bitmap.height)) {
                for (x in (bounds.left - origin.x).toInt().coerceAtLeast(0) until (bounds.right - origin.x).toInt().coerceAtMost(bitmap.width)) {
                    val pixel = bitmap.getPixel(x, y)
                    if (android.graphics.Color.red(pixel) < 64 && android.graphics.Color.green(pixel) < 64 &&
                        android.graphics.Color.blue(pixel) < 64) ink++
                }
            }
            // Coverage is density-independent: CI uses 320x640 at 160 dpi,
            // whereas phone previews have roughly nine times as many pixels.
            // A blank gold body still has zero ink at either resolution.
            val requiredInk = maxOf(16, (bounds.width * bounds.height * 0.02f).toInt())
            if (ink >= requiredInk) { accepted = bitmap; true } else { bitmap.recycle(); false }
        }
        val bitmap = checkNotNull(accepted)
        try {
            val output = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "chat-instant-send")
            check(output.mkdirs() || output.isDirectory)
            File(output, "$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
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
