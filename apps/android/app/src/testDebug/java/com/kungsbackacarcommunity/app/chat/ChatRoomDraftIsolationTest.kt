package com.kungsbackacarcommunity.app.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.dm.*
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h900dp-mdpi")
class ChatRoomDraftIsolationTest {
    @get:Rule val compose = createComposeRule()

    /** Real route, same composition slot, same repository; only the recipient changes. */
    @Test fun rejectedDmDraftDoesNotFollowRecipientSwitch() {
        val peer = mutableStateOf("alice")
        val repository = RejectingDmRepository()
        compose.setContent { KccTheme {
            ChatRoute(repository, "rejected-dm-self", peer.value, peer.value)
        } }
        input().performTextInput("Private draft for Alice")
        sendDm()
        await { repository.sends.size == 1 }
        assertDraft("Private draft for Alice")
        compose.runOnIdle { peer.value = "bob" }
        // A push/deep-link can change the peer while ShellRoute.Chat remains mounted.
        assertDraft("")
        compose.runOnIdle { assertEquals(1, repository.sends.size) }
        input().performTextInput("New draft for Bob")
        sendDm()
        await { repository.sends.size == 2 }
        compose.runOnIdle {
            assertEquals(listOf("alice", "bob"), repository.sends.map { it.peer })
            assertEquals(listOf("Private draft for Alice", "New draft for Bob"), repository.sends.map { it.text })
            assertNotEquals(repository.sends[0].id, repository.sends[1].id)
        }
    }

    private fun input() = compose.onNode(hasSetTextAction())
    private fun assertDraft(expected: String) {
        assertEquals(expected, input().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }
    private fun sendDm() {
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.dm_send)
        compose.onNodeWithText(label).performClick()
    }
    // Pump Compose before inspecting fake calls: a plain opaque-list poll never runs the UI work.
    private fun await(predicate: () -> Boolean) = compose.waitUntil(5_000) {
        compose.runOnIdle { predicate() }
    }

    private data class DmSend(val peer: String, val text: String, val id: String?)
    private class RejectingDmRepository : DmRepository {
        val sends = mutableListOf<DmSend>()
        override fun observeConversations(uid: String) = flowOf(DmConversationsState.Loaded(emptyList()))
        override fun observeThread(conversationId: String) = flowOf(DmThreadState.Loaded(emptyList()))
        override suspend fun sendMessage(toUid: String, text: String, clientId: String?): DmSendResult {
            sends += DmSend(toUid, text, clientId)
            return DmSendResult.Failed(DmSendError.Generic)
        }
        override suspend fun loadOlder(conversationId: String, before: String) = DmOlderResult.Failed
        override suspend fun markRead(conversationId: String) = Unit
    }
}
