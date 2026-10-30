package com.kungsbackacarcommunity.app.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.chatchannels.*
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.dm.*
import com.kungsbackacarcommunity.app.events.EventStatus
import com.kungsbackacarcommunity.app.events.RsvpStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h900dp-mdpi")
class ChatRouteLifecycleIsolationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rejectedConvoyDraftDoesNotFollowRoomSwitch() = rejectedSwitch(Surface.Convoy, account = false)
    @Test fun rejectedEventDraftDoesNotFollowRoomSwitch() = rejectedSwitch(Surface.Event, account = false)
    @Test fun rejectedDmDraftDoesNotFollowAccountSwitch() = rejectedSwitch(Surface.Dm, account = true)
    @Test fun rejectedConvoyDraftDoesNotFollowAccountSwitch() = rejectedSwitch(Surface.Convoy, account = true)
    @Test fun rejectedCommunityDraftDoesNotFollowAccountSwitch() = rejectedSwitch(Surface.Community, account = true)
    @Test fun rejectedEventDraftDoesNotFollowAccountSwitch() = rejectedSwitch(Surface.Event, account = true)

    @Test fun pendingDmSendIsCancelledOnRecipientSwitch() = pendingSwitch(Surface.Dm, account = false)
    @Test fun pendingConvoySendIsCancelledOnRoomSwitch() = pendingSwitch(Surface.Convoy, account = false)
    @Test fun pendingEventSendIsCancelledOnRoomSwitch() = pendingSwitch(Surface.Event, account = false)
    @Test fun pendingDmSendIsCancelledOnAccountSwitch() = pendingSwitch(Surface.Dm, account = true)
    @Test fun pendingConvoySendIsCancelledOnAccountSwitch() = pendingSwitch(Surface.Convoy, account = true)
    @Test fun pendingCommunitySendIsCancelledOnAccountSwitch() = pendingSwitch(Surface.Community, account = true)
    @Test fun pendingEventSendIsCancelledOnAccountSwitch() = pendingSwitch(Surface.Event, account = true)

    @Test fun sameDmRoomPreservesRejectedAttemptAcrossRecompositionAndRestore() = sameRoom(Surface.Dm)
    @Test fun sameConvoyRoomPreservesRejectedAttemptAcrossRecompositionAndRestore() = sameRoom(Surface.Convoy)
    @Test fun sameCommunityPreservesRejectedAttemptAcrossRecompositionAndRestore() = sameRoom(Surface.Community)
    @Test fun sameEventRoomPreservesRejectedAttemptAcrossRecompositionAndRestore() = sameRoom(Surface.Event)

    @Test fun roomSwitchDoesNotResetAccountCooldownOrAutomaticallySend() {
        val f = Fixture(Surface.Dm, "cooldown")
        compose.setContent { KccTheme { f.Route() } }
        input().performTextInput("Private paused draft")
        compose.runOnIdle { ChatSendCooldowns.forUser(f.uid.value).apply(ChatRateLimit(60_000)) }
        send(f).assertIsNotEnabled()
        f.switchContext(account = false)
        assertDraft("")
        input().performTextInput("New room draft")
        send(f).assertIsNotEnabled()
        compose.runOnIdle { assertTrue(f.script.attempts.isEmpty()) }
        // A different account owns a different gate, as well as a different draft.
        f.switchContext(account = true)
        assertDraft("")
        input().performTextInput("Other account draft")
        send(f).assertIsEnabled()
        compose.runOnIdle { assertTrue(f.script.attempts.isEmpty()) }
    }

    private fun rejectedSwitch(surface: Surface, account: Boolean) {
        val f = Fixture(surface, "rejected-$account")
        compose.setContent { KccTheme { f.Route() } }
        input().performTextInput("Private rejected draft")
        send(f).performClick()
        await { f.script.attempts.size == 1 }
        send(f).assertIsEnabled()
        assertDraft("Private rejected draft")
        val old = f.script.attempts.single()
        f.switchContext(account)
        assertDraft("")
        send(f).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, f.script.attempts.size) }
        input().performTextInput("New context draft")
        send(f).performClick()
        await { f.script.attempts.size == 2 }
        assertNewAttempt(f, old)
    }

    private fun pendingSwitch(surface: Surface, account: Boolean) {
        val f = Fixture(surface, "pending-$account")
        val oldAck = CompletableDeferred<Boolean>()
        f.script.firstReply = oldAck
        compose.setContent { KccTheme { f.Route() } }
        input().performTextInput("Private pending draft")
        send(f).performClick()
        await { f.script.attempts.size == 1 }
        send(f).assertIsNotEnabled()
        val old = f.script.attempts.single()
        f.switchContext(account)
        await { old.cancelled }
        assertDraft("")
        input().performTextInput("New context draft")
        send(f).assertIsEnabled()
        // Completion of the old transport after disposal must not acknowledge the
        // new room's draft, change its busy state or cause another dispatch.
        oldAck.complete(true)
        compose.waitForIdle()
        assertDraft("New context draft")
        compose.runOnIdle { assertEquals(1, f.script.attempts.size) }
        send(f).performClick()
        await { f.script.attempts.size == 2 }
        assertNewAttempt(f, old)
    }

    private fun sameRoom(surface: Surface) {
        val f = Fixture(surface, "same-room")
        val restoration = StateRestorationTester(compose)
        restoration.setContent { KccTheme { f.Route() } }
        input().performTextInput("Same room retained draft")
        send(f).performClick()
        await { f.script.attempts.size == 1 }
        send(f).assertIsEnabled()
        val id = f.script.attempts.single().id
        assertNotNull(id)
        compose.runOnIdle { f.revision.value++ }
        assertDraft("Same room retained draft")
        restoration.emulateSavedInstanceStateRestore()
        assertDraft("Same room retained draft")
        compose.runOnIdle { assertEquals(1, f.script.attempts.size) }
        send(f).performClick()
        await { f.script.attempts.size == 2 }
        compose.runOnIdle { assertEquals(id, f.script.attempts.last().id) }
    }

    private fun assertNewAttempt(f: Fixture, old: Attempt) = compose.runOnIdle {
        val next = f.script.attempts.last()
        assertEquals(f.uid.value, next.uid)
        assertEquals(if (f.surface == Surface.Community) "global" else f.room.value, next.room)
        assertEquals("New context draft", next.text)
        assertNotNull(old.id)
        assertNotNull(next.id)
        assertNotEquals(old.id, next.id)
        assertEquals(2, f.script.attempts.size)
    }

    private fun input() = compose.onNode(hasSetTextAction())
    private fun assertDraft(expected: String) {
        assertEquals(expected, input().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }
    private fun send(f: Fixture): SemanticsNodeInteraction {
        val id = when (f.surface) {
            Surface.Dm -> R.string.dm_send
            Surface.Convoy, Surface.Community -> R.string.channel_send
            Surface.Event -> R.string.chat_sendButton
        }
        return compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(id))
    }
    private fun await(predicate: () -> Boolean) = compose.waitUntil(5_000) {
        compose.runOnIdle { predicate() }
    }

    private enum class Surface { Dm, Convoy, Community, Event }

    /** No outer key and no alternate UI: these are the production route entry points. */
    private inner class Fixture(val surface: Surface, scenario: String) {
        val uid = mutableStateOf("self-$surface-$scenario")
        val room = mutableStateOf("room-a")
        val revision = mutableStateOf(0)
        val script = SendScript { uid.value }
        private val dm = ScriptDmRepository(script)
        private val convoy = ScriptConvoyRepository(script)
        private val community = ScriptCommunityRepository(script)
        private val event = ScriptEventRepository(script)
        // Production shares this coordinator across events: keep it stable here too.
        private val eventCoordinator = ChatCoordinator(event)

        @Composable fun Route() {
            when (surface) {
                Surface.Dm -> ChatRoute(dm, uid.value, room.value, "Peer ${revision.value}")
                Surface.Convoy -> ConvoyChannelRoute(convoy, uid.value, room.value,
                    modifier = Modifier.testTag("revision-${revision.value}"))
                Surface.Community -> CommunityChannelRoute(community, uid.value,
                    modifier = Modifier.testTag("revision-${revision.value}"))
                Surface.Event -> EventChatRoute(event, eventCoordinator, room.value, uid.value,
                    passesMemberGate = true, eventStatus = EventStatus.PUBLISHED, myRsvp = RsvpStatus.GOING,
                    onBack = {}, eventTitle = "Event ${revision.value}")
            }
        }

        fun switchContext(account: Boolean) = compose.runOnIdle {
            if (account) uid.value += "-new" else room.value = "room-b"
        }
    }

    private data class Attempt(val uid: String, val room: String, val text: String, val id: String?, var cancelled: Boolean = false)
    private class SendScript(private val currentUid: () -> String) {
        val attempts = mutableListOf<Attempt>()
        var firstReply: CompletableDeferred<Boolean>? = null
        suspend fun send(room: String, text: String, id: String?): Boolean {
            val reply = if (attempts.isEmpty()) firstReply else null
            val attempt = Attempt(currentUid(), room, text, id)
            attempts += attempt
            return try { reply?.await() ?: false } catch (cancelled: CancellationException) {
                attempt.cancelled = true
                throw cancelled
            }
        }
    }

    private class ScriptDmRepository(private val script: SendScript) : DmRepository {
        override fun observeConversations(uid: String) = flowOf(DmConversationsState.Loaded(emptyList()))
        override fun observeThread(conversationId: String) = flowOf(DmThreadState.Loaded(emptyList()))
        override suspend fun sendMessage(toUid: String, text: String, clientId: String?): DmSendResult =
            if (script.send(toUid, text, clientId)) DmSendResult.Sent("pair", clientId!!)
            else DmSendResult.Failed(DmSendError.Generic)
        override suspend fun loadOlder(conversationId: String, before: String) = DmOlderResult.Failed
        override suspend fun markRead(conversationId: String) = Unit
    }

    private class ScriptConvoyRepository(private val script: SendScript) : ConvoyChatRepository {
        override suspend fun listConvoys() = ConvoyListState.Loaded(emptyList())
        override fun observeMessages(convoyId: String) = flowOf(ChannelMessagesState.Loaded(emptyList()))
        override fun observeUnread(convoyId: String, uid: String) = flowOf(0)
        override fun observeAnyUnread(uid: String) = flowOf(false)
        override suspend fun markRead(convoyId: String) = Unit
        override suspend fun post(convoyId: String, text: String, clientId: String?): ChannelSendResult =
            if (script.send(convoyId, text, clientId)) ChannelSendResult.Sent(clientId!!)
            else ChannelSendResult.Failed(ChannelSendError.Generic)
        override suspend fun loadOlder(convoyId: String, before: String) = ChannelOlderResult.Failed
    }

    private class ScriptCommunityRepository(private val script: SendScript) : CommunityChatRepository {
        override fun observeMessages() = flowOf(ChannelMessagesState.Loaded(emptyList()))
        override fun observeUnread(uid: String) = flowOf(false)
        override suspend fun post(text: String, mentionedUids: List<String>, clientId: String?): ChannelSendResult =
            if (script.send("global", text, clientId)) ChannelSendResult.Sent(clientId!!)
            else ChannelSendResult.Failed(ChannelSendError.Generic)
        override suspend fun loadOlder(before: String) = ChannelOlderResult.Failed
        override suspend fun markRead() = Unit
    }

    private class ScriptEventRepository(private val script: SendScript) : EventChatRepository {
        override fun observeMessages(eventId: String) = flowOf(ChatMessagesState.Loaded(emptyList()))
        override suspend fun postMessage(eventId: String, message: String) {
            error("Expected idempotent production send")
        }
        override suspend fun postMessage(eventId: String, message: String, clientId: String?) {
            if (!script.send(eventId, message, clientId)) throw IllegalStateException("Fixture rejection")
        }
        override suspend fun reportMessage(eventId: String, messageId: String, reason: ChatReportReason, details: String?) = Unit
    }
}
