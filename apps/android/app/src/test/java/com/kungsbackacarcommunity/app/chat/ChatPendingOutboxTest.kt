package com.kungsbackacarcommunity.app.chat

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import com.kungsbackacarcommunity.app.chatchannels.*
import com.kungsbackacarcommunity.app.dm.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ChatPendingOutboxTest {
    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = when (value) {
            is String, is Long -> true
            is ArrayList<*> -> value.all { it == null || canBeSaved(it) }
            else -> false
        }
    }

    private fun channel(id: String, state: ChannelDeliveryState, error: ChannelSendError? = null) =
        ChannelMessage(id, "self", "Hej @Anna 🚗\n$id", "Self", "avatar", 123L, "iso",
            mentionedUids = listOf("anna", "bob"), clientId = id, deliveryState = state, sendError = error,
            replyTo = ChannelReplyTo("parent", "author", "Anna", "Quoted\ntext"))

    private fun dm(id: String, state: DmDeliveryState, error: DmSendError? = null) =
        DmMessage(id, "self", "Hej 🚗\n$id", 456L, "iso", clientId = id,
            deliveryState = state, sendError = error,
            replyTo = DmReplyTo("parent", "author", null, "Quoted\ntext"))

    @Test fun channelCodecPreservesAllPayloadsErrorsStatesAndQuickEmoji() {
        val rows = listOf(
            channel("sending", ChannelDeliveryState.Sending),
            channel("failed", ChannelDeliveryState.Failed, ChannelSendError.Generic),
            channel("terminal", ChannelDeliveryState.Failed, ChannelSendError.NotMember),
            channel("acked", ChannelDeliveryState.Sent),
            channel("emoji", ChannelDeliveryState.Sending).copy(text = "😊", mentionedUids = emptyList(),
                senderDisplayName = null, senderAvatarPath = null, createdAtMillis = null,
                createdAtIso = null, replyTo = null),
        )
        val restored = roundTrip(ChatPendingOutbox.saver(ChannelPendingMessageSaver), ChatPendingOutbox(rows))
        assertEquals(rows, restored.initialPending)
    }

    @Test fun dmCodecPreservesAllPayloadsErrorsStatesAndNulls() {
        val rows = listOf(
            dm("sending", DmDeliveryState.Sending),
            dm("failed", DmDeliveryState.Failed, DmSendError.Generic),
            dm("terminal", DmDeliveryState.Failed, DmSendError.CannotDeliver),
            dm("acked", DmDeliveryState.Sent),
            dm("emoji", DmDeliveryState.Sending).copy(text = "😊", createdAtMillis = null,
                createdAtIso = null, replyTo = null, clientId = null),
        )
        val restored = roundTrip(ChatPendingOutbox.saver(DmPendingMessageSaver), ChatPendingOutbox(rows))
        assertEquals(rows, restored.initialPending)
    }

    @Test fun saveReadsLatestBoundFlowWithoutAnyCollectorOrRecomposition() {
        val flow = MutableStateFlow(emptyList<ChannelMessage>())
        val outbox = ChatPendingOutbox<ChannelMessage>()
        outbox.bind(flow)
        // No collector/Compose frame: this row must still be saved.
        flow.value = listOf(channel("late", ChannelDeliveryState.Sending))
        val saver = ChatPendingOutbox.saver(ChannelPendingMessageSaver)
        assertEquals(flow.value, roundTrip(saver, outbox).initialPending)
        flow.value = listOf(channel("late", ChannelDeliveryState.Sent))
        assertEquals(ChannelDeliveryState.Sent, roundTrip(saver, outbox).initialPending.single().deliveryState)
        // ACK/live reconciliation removes rows directly on the flow too.
        flow.value = emptyList()
        assertTrue(roundTrip(saver, outbox).initialPending.isEmpty())
    }

    @Test fun rebindingDoesNotSaveAnOldCoordinatorOrReplayAnOldSeed() {
        val outbox = ChatPendingOutbox(listOf(channel("seed", ChannelDeliveryState.Sending)))
        val first = MutableStateFlow(listOf(channel("first", ChannelDeliveryState.Failed, ChannelSendError.Generic)))
        outbox.bind(first)
        val second = MutableStateFlow(listOf(channel("second", ChannelDeliveryState.Sent)))
        outbox.bind(second)
        first.value = emptyList()
        val restored = roundTrip(ChatPendingOutbox.saver(ChannelPendingMessageSaver), outbox)
        assertEquals(second.value, restored.initialPending)
    }

    @Test fun restoredChannelNeedsExplicitSameIdRetryAndLiveEchoRemovesIt() = runTest {
        val rows = listOf(
            channel("unknown", ChannelDeliveryState.Sending),
            channel("failed", ChannelDeliveryState.Failed, ChannelSendError.Generic),
            channel("terminal", ChannelDeliveryState.Failed, ChannelSendError.Invalid),
            channel("acked", ChannelDeliveryState.Sent),
        )
        val restored = roundTrip(ChatPendingOutbox.saver(ChannelPendingMessageSaver), ChatPendingOutbox(rows))
        val sends = mutableListOf<List<Any?>>()
        val coordinator = ChannelChatCoordinator(
            sender = { text, mentions, id, replyId ->
                sends += listOf(text, mentions, id, replyId)
                ChannelSendResult.Sent(id, mentions)
            },
            pager = { ChannelOlderResult.Failed }, selfUid = "self", initialPending = restored.initialPending,
        )
        restored.bind(coordinator.pendingMessages)
        assertTrue(sends.isEmpty())
        assertEquals(rows[0].copy(deliveryState = ChannelDeliveryState.Failed, sendError = ChannelSendError.Generic),
            coordinator.pendingMessages.value[0])
        assertEquals(rows.drop(1), coordinator.pendingMessages.value.drop(1))
        assertFalse(coordinator.retry("acked"))
        assertFalse(coordinator.retry("terminal"))
        assertTrue(coordinator.retry("unknown"))
        assertEquals(listOf(listOf(rows[0].text, rows[0].mentionedUids, "unknown", "parent")), sends)
        val live = listOf(rows[0].copy(deliveryState = ChannelDeliveryState.Sent))
        assertEquals(1, ChannelThread.mergeWithPending(emptyList(), live, coordinator.pendingMessages.value)
            .count { it.id == "unknown" })
        coordinator.onLiveMessages(live)
        assertFalse(coordinator.pendingMessages.value.any { it.id == "unknown" })
        assertEquals(1, sends.size)
    }

    @Test fun restoredDmNeedsExplicitSameIdRetryAndLiveEchoRemovesIt() = runTest {
        val rows = listOf(dm("unknown", DmDeliveryState.Sending), dm("acked", DmDeliveryState.Sent),
            dm("terminal", DmDeliveryState.Failed, DmSendError.CannotDeliver))
        val restored = roundTrip(ChatPendingOutbox.saver(DmPendingMessageSaver), ChatPendingOutbox(rows))
        val sends = mutableListOf<List<String?>>()
        val repo = object : DmRepository {
            override fun observeConversations(uid: String) = flowOf(DmConversationsState.Loaded(emptyList()))
            override fun observeThread(conversationId: String) = flowOf(DmThreadState.Loaded(emptyList()))
            override suspend fun sendMessage(toUid: String, text: String, clientId: String?): DmSendResult {
                error("Reply overload expected")
            }
            override suspend fun sendMessage(toUid: String, text: String, clientId: String?, replyToMessageId: String?): DmSendResult {
                sends += listOf(toUid, text, clientId, replyToMessageId)
                return DmSendResult.Sent("pair", clientId!!)
            }
            override suspend fun loadOlder(conversationId: String, before: String) = DmOlderResult.Failed
            override suspend fun markRead(conversationId: String) = Unit
        }
        val coordinator = DmThreadCoordinator(repo, "self", "peer", "pair", initialPending = restored.initialPending)
        restored.bind(coordinator.pendingMessages)
        assertTrue(sends.isEmpty())
        assertEquals(rows[0].copy(deliveryState = DmDeliveryState.Failed, sendError = DmSendError.Generic),
            coordinator.pendingMessages.value[0])
        assertEquals(rows.drop(1), coordinator.pendingMessages.value.drop(1))
        assertFalse(coordinator.retry("acked"))
        assertFalse(coordinator.retry("terminal"))
        assertTrue(coordinator.retry("unknown"))
        assertEquals(listOf(listOf("peer", rows[0].text, "unknown", "parent")), sends)
        val live = listOf(rows[0].copy(deliveryState = DmDeliveryState.Sent))
        assertEquals(1, DmThread.mergeWithPending(emptyList(), live, coordinator.pendingMessages.value)
            .count { it.id == "unknown" })
        coordinator.onLiveMessages(live)
        assertFalse(coordinator.pendingMessages.value.any { it.id == "unknown" })
        assertEquals(1, sends.size)
    }

    @Test fun independentRoomOutboxesDoNotShareRows() {
        val first = ChatPendingOutbox(listOf(dm("private", DmDeliveryState.Failed, DmSendError.Generic)))
        val second = ChatPendingOutbox<DmMessage>()
        val saver = ChatPendingOutbox.saver(DmPendingMessageSaver)
        assertEquals("private", roundTrip(saver, first).initialPending.single().id)
        assertTrue(roundTrip(saver, second).initialPending.isEmpty())
    }

    private fun <Value, Saved : Any> roundTrip(saver: Saver<Value, Saved>, value: Value): Value {
        val saved = with(saver) { with(scope) { checkNotNull(save(value)) } }
        // Round-trip only primitive/ArrayList saved data, never serializable domain models.
        val bytes = ByteArrayOutputStream().also { output ->
            ObjectOutputStream(output).use { it.writeObject(saved) }
        }.toByteArray()
        @Suppress("UNCHECKED_CAST")
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as Saved }
        return checkNotNull(saver.restore(restored))
    }
}
