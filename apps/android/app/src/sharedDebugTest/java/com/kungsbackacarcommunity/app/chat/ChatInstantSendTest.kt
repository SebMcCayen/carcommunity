package com.kungsbackacarcommunity.app.chat

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.chatchannels.*
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.design.chatQuickEmojiTestTag
import com.kungsbackacarcommunity.app.dm.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.After
import java.io.File
import android.graphics.Bitmap
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production routes/coordinators with local test data and suspended repository responses.
 * Shared by the JVM and native suites; no Firebase repository or platform-specific runner API.
 */
@RunWith(AndroidJUnit4::class)
class ChatInstantSendTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun matchProductionImeWindow() {
        compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            // This generic test Activity otherwise applies legacy resizing in
            // addition to the composer's explicit IME insets. Production owns
            // IME movement through insets; disable legacy movement in the host.
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
    }

    @After fun captureRequestedNativeWindow() {
        val name = InstrumentationRegistry.getArguments().getString("chatInstantSnapshot") ?: return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "chat-instant-send")
        check(output.mkdirs() || output.isDirectory)
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun communityClearsComposerAndShowsSendingBeforeNetworkReturns() = pending(Surface.Community)
    @Test fun dmClearsComposerAndShowsSendingBeforeNetworkReturns() = pending(Surface.Dm)

    @Test fun communityLateAckPreservesNextDraft() = lateAck(Surface.Community, NEXT_DRAFT)
    @Test fun dmLateAckPreservesNextDraft() = lateAck(Surface.Dm, NEXT_DRAFT)
    @Test fun communityLateAckPreservesNewDraftWithIdenticalText() = lateAck(Surface.Community, FIRST_DRAFT)
    @Test fun dmLateAckPreservesNewDraftWithIdenticalText() = lateAck(Surface.Dm, FIRST_DRAFT)

    @Test fun communityFailureAndBubbleRetryPreserveNextDraftAndReuseClientId() = failedWithNextDraft(Surface.Community)
    @Test fun dmFailureAndBubbleRetryPreserveNextDraftAndReuseClientId() = failedWithNextDraft(Surface.Dm)
    @Test fun communityFailedBubbleRetryPreservesIdenticalNewDraft() = retryWithIdenticalNewDraft(Surface.Community)
    @Test fun dmFailedBubbleRetryPreservesIdenticalNewDraft() = retryWithIdenticalNewDraft(Surface.Dm)
    @Test fun communityFailedBubbleRetryPreservesIdenticalNewDraftAfterRestoration() = retryWithIdenticalNewDraft(Surface.Community, recreate = true)
    @Test fun dmFailedBubbleRetryPreservesIdenticalNewDraftAfterRestoration() = retryWithIdenticalNewDraft(Surface.Dm, recreate = true)
    @Test fun communityFailureRestoresUntouchedDraftAndBubbleRetryClearsImmediately() = failedWithoutEdits(Surface.Community)
    @Test fun dmFailureRestoresUntouchedDraftAndBubbleRetryClearsImmediately() = failedWithoutEdits(Surface.Dm)

    @Test fun communityQuickEmojiKeepsTypedDraftWhilePendingAndAfterAck() = quickEmoji(Surface.Community)
    @Test fun dmQuickEmojiKeepsTypedDraftWhilePendingAndAfterAck() = quickEmoji(Surface.Dm)

    @Test fun communityOwnDeliveredListenerBeatsLateFailureWithoutRestoringText() = deliveredBeforeFailure(Surface.Community, "")
    @Test fun dmOwnDeliveredListenerBeatsLateFailureWithoutRestoringText() = deliveredBeforeFailure(Surface.Dm, "")
    @Test fun communityOwnDeliveredListenerAndLateFailurePreserveNextDraft() = deliveredBeforeFailure(Surface.Community, FIRST_DRAFT)
    @Test fun dmOwnDeliveredListenerAndLateFailurePreserveNextDraft() = deliveredBeforeFailure(Surface.Dm, FIRST_DRAFT)

    @Test fun communityPendingRestorationKeepsComposerEmptyAndRecoversFailedBubble() = restorePending(Surface.Community)
    @Test fun dmPendingRestorationKeepsComposerEmptyAndRecoversFailedBubble() = restorePending(Surface.Dm)
    @Test fun communityPendingRestorationPreservesNextDraftAndRecoversOldFailedBubble() = restorePending(Surface.Community, nextDraft = NEXT_DRAFT)
    @Test fun dmPendingRestorationPreservesNextDraftAndRecoversOldFailedBubble() = restorePending(Surface.Dm, nextDraft = NEXT_DRAFT)
    @Test fun communityPendingEmojiRestorationPreservesTypedDraftAndRetryId() = restorePending(Surface.Community, quickEmoji = true)
    @Test fun dmPendingEmojiRestorationPreservesTypedDraftAndRetryId() = restorePending(Surface.Dm, quickEmoji = true)
    @Test fun communityDeletedNextDraftStaysEmptyAfterPendingRestoration() = restorePending(Surface.Community, deleteNextDraft = true)
    @Test fun dmDeletedNextDraftStaysEmptyAfterPendingRestoration() = restorePending(Surface.Dm, deleteNextDraft = true)

    private fun restorePending(
        surface: Surface,
        nextDraft: String = "",
        quickEmoji: Boolean = false,
        deleteNextDraft: Boolean = false,
    ) {
        val f = Fixture(surface)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { KccTheme { f.Route() } }
        input().performTextInput(FIRST_DRAFT)
        val first = if (quickEmoji) {
            compose.onNodeWithTag(chatQuickEmojiTestTag(EMOJI)).performClick()
            attempt(f, 1).also { compose.runOnIdle { assertEquals(EMOJI, it.text) } }
        } else submit(f)
        var expected = if (quickEmoji) FIRST_DRAFT else ""
        assertDraft(expected)
        sending(f).assertEventuallyDisplayed()
        assertBody(first)
        if (nextDraft.isNotEmpty()) {
            input().performTextInput(nextDraft)
            expected = nextDraft
            assertDraft(expected)
        }
        if (deleteNextDraft) {
            input().performTextInput(NEXT_DRAFT)
            assertDraft(NEXT_DRAFT)
            input().performTextClearance()
            expected = ""
            assertDraft("")
        }
        assertPending(f, first, 1)

        // Disposal cancels the old send. The saved outbox recovers its bubble as
        // failed/unknown without copying outgoing text back into the composer.
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(5_000) { compose.runOnIdle { first.cancelled } }
        assertDraft(expected)
        assertBody(first)
        sending(f).assertDoesNotExist()
        retry(f).assertEventuallyDisplayed().assertIsEnabled()
        compose.onAllNodesWithText(text(
            if (surface == Surface.Community) R.string.channel_statusFailedRetry else R.string.dm_statusFailedRetry,
        )).assertCountEquals(1)
        assertAttemptCount(f, 1)
        compose.runOnIdle { assertFalse(first.response.isCompleted) }
        if (expected.isEmpty()) send(f).assertIsNotEnabled() else send(f).assertIsEnabled()

        // Even a late successful transport response after disposal must leave
        // the restored composer untouched and must not cause an automatic send.
        assertTrue(first.response.complete(true))
        compose.waitForIdle()
        assertDraft(expected)
        assertBody(first)
        retry(f).assertEventuallyDisplayed().assertIsEnabled()
        assertAttemptCount(f, 1)
        // Retry the saved bubble explicitly, preserving its payload/key and the
        // independent composer (including an intentionally deleted next draft).
        retry(f).performClick()
        val retried = attempt(f, 2)
        assertSamePayload(first, retried)
        assertDraft(expected)
        assertBody(retried)
        sending(f).assertEventuallyDisplayed()
        retry(f).assertDoesNotExist()
        assertPending(f, retried, 2)
        complete(retried, true)
        assertDraft(expected)
        compose.runOnIdle { f.publish(first) }
        assertBody(first)
        assertDraft(expected)
        sending(f).assertDoesNotExist()
        retry(f).assertDoesNotExist()
        assertAttemptCount(f, 2)
    }

    private fun pending(surface: Surface) {
        val f = start(surface)
        val attempt = submit(f)
        assertDraft("")
        assertBody(attempt)
        sending(f).assertEventuallyDisplayed()
        retry(f).assertDoesNotExist()
        assertPending(f, attempt, 1)
        // Editing must work even though the callable has not returned.
        input().performTextInput(NEXT_DRAFT)
        assertDraft(NEXT_DRAFT)
        assertPending(f, attempt, 1)
        complete(attempt, true)
        assertDraft(NEXT_DRAFT)
    }

    private fun lateAck(surface: Surface, nextDraft: String) {
        val f = start(surface)
        val attempt = submit(f)
        assertDraft("")
        input().performTextInput(nextDraft)
        assertDraft(nextDraft)
        assertPending(f, attempt, 1)
        // No listener snapshot: the acknowledgement alone must leave the new edit intact.
        complete(attempt, true)
        assertDraft(nextDraft)
        send(f).assertIsEnabled()
        sending(f).assertDoesNotExist()
        assertBody(attempt)
        assertAttemptCount(f, 1)
        val next = submit(f, count = 2)
        compose.runOnIdle { assertNotEquals(attempt.clientId, next.clientId) }
        assertDraft("")
        complete(next, true)
    }

    private fun failedWithNextDraft(surface: Surface) {
        val f = start(surface)
        val first = submit(f)
        assertDraft("")
        input().performTextInput(NEXT_DRAFT)
        complete(first, false)
        assertDraft(NEXT_DRAFT)
        assertBody(first)
        sending(f).assertDoesNotExist()
        retry(f).assertEventuallyDisplayed().assertIsEnabled().performClick()
        val retried = attempt(f, 2)
        assertSamePayload(first, retried)
        assertDraft(NEXT_DRAFT)
        assertBody(first) // A retry transitions the existing bubble; it must not append another.
        sending(f).assertEventuallyDisplayed()
        retry(f).assertDoesNotExist()
        assertPending(f, retried, 2)
        input().performTextReplacement("Edited during retry 🚙")
        complete(retried, true)
        assertDraft("Edited during retry 🚙")
        compose.runOnIdle { f.publish(first) }
        assertBody(first)
        sending(f).assertDoesNotExist()
        retry(f).assertDoesNotExist()
        assertDraft("Edited during retry 🚙")
        assertAttemptCount(f, 2)
    }

    private fun retryWithIdenticalNewDraft(surface: Surface, recreate: Boolean = false) {
        val f = Fixture(surface)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { KccTheme { f.Route() } }
        input().performTextInput(FIRST_DRAFT)
        val first = submit(f)
        assertDraft("")
        // This is a user-authored new draft, despite matching the outgoing text.
        // It must never acquire ownership of the old failed bubble's retry.
        input().performTextInput(FIRST_DRAFT)
        assertDraft(FIRST_DRAFT)
        assertPending(f, first, 1)
        complete(first, false)
        assertDraft(FIRST_DRAFT)
        assertBody(first)
        retry(f).assertEventuallyDisplayed().assertIsEnabled()
        if (recreate) {
            restoration.emulateSavedInstanceStateRestore()
            assertDraft(FIRST_DRAFT)
            assertBody(first)
            retry(f).assertEventuallyDisplayed().assertIsEnabled()
        }
        assertAttemptCount(f, 1)
        retry(f).performClick()
        val retried = attempt(f, 2)
        assertSamePayload(first, retried)
        assertDraft(FIRST_DRAFT)
        assertBody(first)
        sending(f).assertEventuallyDisplayed()
        retry(f).assertDoesNotExist()
        assertPending(f, retried, 2)
        // Leave the identical draft untouched through both ACK and live delivery;
        // a further edit here could hide an incorrect acknowledgement clear.
        complete(retried, true)
        assertDraft(FIRST_DRAFT)
        compose.runOnIdle { f.publish(first) }
        assertDraft(FIRST_DRAFT)
        assertBody(first)
        sending(f).assertDoesNotExist()
        retry(f).assertDoesNotExist()
        assertAttemptCount(f, 2)
    }

    private fun failedWithoutEdits(surface: Surface) {
        val f = start(surface)
        val first = submit(f)
        assertDraft("")
        complete(first, false)
        assertDraft(FIRST_DRAFT)
        assertBody(first) // Both the restored composer and retry bubble retain the content.
        retry(f).assertEventuallyDisplayed().performClick()
        val retried = attempt(f, 2)
        assertSamePayload(first, retried)
        assertDraft("")
        assertBody(first)
        sending(f).assertEventuallyDisplayed()
        assertPending(f, retried, 2)
        complete(retried, true)
        assertDraft("")
        compose.runOnIdle { f.publish(first) }
        assertBody(first)
        retry(f).assertDoesNotExist()
        assertAttemptCount(f, 2)
    }

    private fun quickEmoji(surface: Surface) {
        val f = start(surface)
        compose.onNodeWithTag(chatQuickEmojiTestTag(EMOJI)).performClick()
        val emoji = attempt(f, 1)
        compose.runOnIdle { assertEquals(EMOJI, emoji.text) }
        assertDraft(FIRST_DRAFT)
        assertBody(emoji)
        sending(f).assertEventuallyDisplayed()
        assertPending(f, emoji, 1)
        compose.runOnIdle { f.publish(emoji) }
        assertBody(emoji)
        assertDraft(FIRST_DRAFT)
        complete(emoji, true)
        assertDraft(FIRST_DRAFT)
        assertBody(emoji)
        sending(f).assertDoesNotExist()
        assertAttemptCount(f, 1)
    }

    private fun deliveredBeforeFailure(surface: Surface, nextDraft: String) {
        val f = start(surface)
        val first = submit(f)
        assertDraft("")
        assertPending(f, first, 1)
        compose.runOnIdle { f.publish(first) }
        sending(f).assertDoesNotExist()
        assertBody(first)
        assertDraft("")
        if (nextDraft.isNotEmpty()) input().performTextInput(nextDraft)
        // Own delivered doc with the exact clientId is authoritative even if the
        // suspended callable later reports a generic failure (route callback false).
        complete(first, false)
        assertDraft(nextDraft)
        assertBody(first)
        retry(f).assertDoesNotExist()
        sending(f).assertDoesNotExist()
        assertAttemptCount(f, 1)
    }

    private fun start(surface: Surface): Fixture {
        val f = Fixture(surface)
        compose.setContent { KccTheme { f.Route() } }
        input().performTextInput(FIRST_DRAFT)
        return f
    }

    private fun input() = compose.onNode(hasSetTextAction())
    private fun assertDraft(expected: String) {
        assertEquals(expected, input().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    private fun assertBody(attempt: Attempt) {
        // Select message bodies, not composer EditableText or the emoji button.
        compose.onAllNodes(
            hasTestTag("chat-body-${attempt.clientId}"), useUnmergedTree = true,
        ).assertCountEquals(1)
        compose.onNodeWithTag("chat-body-${attempt.clientId}", useUnmergedTree = true)
            .assertTextEquals(attempt.text).assertEventuallyDisplayed()
    }

    // Native window/IME layout can settle after the fake repository call is
    // observed. Keep the immediate composer assertion, then require the bubble
    // to become visibly laid out; never scroll it into view from the test.
    private fun SemanticsNodeInteraction.assertEventuallyDisplayed(): SemanticsNodeInteraction {
        compose.waitUntil(5_000) { isDisplayed() }
        return assertIsDisplayed()
    }

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun send(f: Fixture) = compose.onNodeWithText(text(
        if (f.surface == Surface.Community) R.string.channel_send else R.string.dm_send,
    ))
    private fun sending(f: Fixture) = compose.onNodeWithText(text(
        if (f.surface == Surface.Community) R.string.channel_statusSending else R.string.dm_statusSending,
    ))
    private fun retry(f: Fixture) = compose.onNodeWithText(text(
        if (f.surface == Surface.Community) R.string.channel_statusFailedRetry else R.string.dm_statusFailedRetry,
    ))

    private fun submit(f: Fixture, count: Int = 1): Attempt {
        send(f).assertIsEnabled().performClick()
        return attempt(f, count)
    }
    private fun attempt(f: Fixture, count: Int): Attempt {
        compose.waitUntil(5_000) { compose.runOnIdle { f.script.attempts.size == count } }
        return compose.runOnIdle { f.script.attempts.last() }
    }
    private fun assertAttemptCount(f: Fixture, count: Int) = compose.runOnIdle {
        assertEquals(count, f.script.attempts.size)
    }
    private fun assertPending(f: Fixture, attempt: Attempt, count: Int) = compose.runOnIdle {
        assertFalse("Network response must remain suspended", attempt.response.isCompleted)
        assertFalse(attempt.returned)
        assertEquals(count, f.script.attempts.size)
    }
    private fun complete(attempt: Attempt, accepted: Boolean) {
        assertTrue(attempt.response.complete(accepted))
        compose.waitUntil(5_000) { compose.runOnIdle { attempt.returned } }
        compose.waitForIdle()
    }
    private fun assertSamePayload(first: Attempt, retried: Attempt) = compose.runOnIdle {
        assertEquals(first.clientId, retried.clientId)
        assertEquals(first.text, retried.text)
    }

    private enum class Surface { Community, Dm }
    private data class Attempt(
        val text: String,
        val clientId: String,
        val response: CompletableDeferred<Boolean> = CompletableDeferred(),
        var returned: Boolean = false,
        var cancelled: Boolean = false,
    )
    private class SendScript {
        val attempts = mutableListOf<Attempt>()
        suspend fun send(text: String, clientId: String?): Boolean {
            val attempt = Attempt(text, checkNotNull(clientId))
            check(attempt.clientId.isNotBlank())
            attempts += attempt
            return try {
                attempt.response.await().also { attempt.returned = true }
            } catch (cancelled: CancellationException) {
                attempt.cancelled = true
                throw cancelled
            }
        }
    }

    private class Fixture(val surface: Surface) {
        // ChatSendCooldowns is process-wide: each fixture owns an isolated account.
        val uid = "local-instant-send-${UUID.randomUUID()}"
        val script = SendScript()
        val communityMessages = MutableStateFlow<ChannelMessagesState>(ChannelMessagesState.Loaded(emptyList()))
        val dmMessages = MutableStateFlow<DmThreadState>(DmThreadState.Loaded(emptyList()))
        private val community = object : CommunityChatRepository {
            override fun observeMessages() = communityMessages
            override fun observeUnread(uid: String) = flowOf(false)
            override suspend fun post(text: String, mentionedUids: List<String>, clientId: String?): ChannelSendResult =
                if (script.send(text, clientId)) ChannelSendResult.Sent(checkNotNull(clientId))
                else ChannelSendResult.Failed(ChannelSendError.Generic)
            override suspend fun loadOlder(before: String) = ChannelOlderResult.Failed
            override suspend fun markRead() = Unit
        }
        private val dm = object : DmRepository {
            override fun observeConversations(uid: String) = flowOf(DmConversationsState.Loaded(emptyList()))
            override fun observeThread(conversationId: String) = dmMessages
            override suspend fun sendMessage(toUid: String, text: String, clientId: String?): DmSendResult =
                if (script.send(text, clientId)) DmSendResult.Sent(dmPairId(uid, PEER), checkNotNull(clientId))
                else DmSendResult.Failed(DmSendError.Generic)
            override suspend fun loadOlder(conversationId: String, before: String) = DmOlderResult.Failed
            override suspend fun markRead(conversationId: String) = Unit
        }

        @Composable fun Route() {
            when (surface) {
                Surface.Community -> CommunityChannelRoute(community, uid)
                Surface.Dm -> ChatRoute(dm, uid, PEER, "Lokal testmedlem")
            }
        }

        fun publish(attempt: Attempt) {
            communityMessages.value = ChannelMessagesState.Loaded(listOf(ChannelMessage(
                id = attempt.clientId, senderUid = uid, text = attempt.text,
                senderDisplayName = "Lokal testmedlem", senderAvatarPath = null,
                createdAtMillis = 1_792_936_800_000L, createdAtIso = null,
                clientId = attempt.clientId, deliveryState = ChannelDeliveryState.Sent,
            )))
            dmMessages.value = DmThreadState.Loaded(listOf(DmMessage(
                id = attempt.clientId, senderUid = uid, text = attempt.text,
                createdAtMillis = 1_792_936_800_000L, createdAtIso = null,
                clientId = attempt.clientId, deliveryState = DmDeliveryState.Sent,
            )))
        }
    }

    private companion object {
        const val FIRST_DRAFT = "Första lokala meddelandet 🚗"
        const val NEXT_DRAFT = "Nästa utkast är mitt 🚙"
        const val EMOJI = "😊"
        const val PEER = "local-instant-send-peer"
    }
}
