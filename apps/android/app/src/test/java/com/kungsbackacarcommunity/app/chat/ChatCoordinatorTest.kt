package com.kungsbackacarcommunity.app.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCoordinatorTest {

    private class FakeRepo : EventChatRepository {
        val posts = mutableListOf<Pair<String, String>>()
        val reports = mutableListOf<Triple<String, String, ChatReportReason>>()
        var failPost: Exception? = null
        var failReport: Exception? = null

        override fun observeMessages(eventId: String): Flow<ChatMessagesState> =
            flowOf(ChatMessagesState.Loaded(emptyList()))

        override suspend fun postMessage(eventId: String, message: String) {
            failPost?.let { throw it }
            posts += eventId to message
        }

        override suspend fun reportMessage(
            eventId: String,
            messageId: String,
            reason: ChatReportReason,
            details: String?,
        ) {
            failReport?.let { throw it }
            reports += Triple(eventId, messageId, reason)
        }
    }

    @Test
    fun `post sends and returns to Idle`() = runTest {
        val repo = FakeRepo()
        val coordinator = ChatCoordinator(repo)
        coordinator.post("e1", "hello")
        assertEquals(listOf("e1" to "hello"), repo.posts)
        assertEquals(ChatSendStatus.Idle, coordinator.sendStatus.value)
    }

    @Test
    fun `post ignores an unsendable draft`() = runTest {
        val repo = FakeRepo()
        val coordinator = ChatCoordinator(repo)
        coordinator.post("e1", "   ")
        assertTrue(repo.posts.isEmpty())
        assertEquals(ChatSendStatus.Idle, coordinator.sendStatus.value)
    }

    @Test
    fun `a failed post surfaces Failed and can reset`() = runTest {
        val repo = FakeRepo().apply { failPost = IllegalStateException("nope") }
        val coordinator = ChatCoordinator(repo)
        coordinator.post("e1", "hi")
        assertEquals(ChatSendStatus.Failed, coordinator.sendStatus.value)
        coordinator.resetSend()
        assertEquals(ChatSendStatus.Idle, coordinator.sendStatus.value)
    }

    @Test
    fun `post cancellation is rethrown and leaves Idle`() = runTest {
        val repo = FakeRepo().apply { failPost = CancellationException("cancel") }
        val coordinator = ChatCoordinator(repo)
        var rethrown = false
        try {
            coordinator.post("e1", "hi")
        } catch (c: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
        assertEquals(ChatSendStatus.Idle, coordinator.sendStatus.value)
    }

    @Test
    fun `report succeeds to Done and resets`() = runTest {
        val repo = FakeRepo()
        val coordinator = ChatCoordinator(repo)
        coordinator.submitReport("e1", "m1", ChatReportReason.SPAM)
        assertEquals(listOf(Triple("e1", "m1", ChatReportReason.SPAM)), repo.reports)
        assertEquals(ChatReportStatus.Done, coordinator.reportStatus.value)
        coordinator.resetReport()
        assertEquals(ChatReportStatus.Idle, coordinator.reportStatus.value)
    }

    @Test
    fun `a failed report surfaces Failed`() = runTest {
        val repo = FakeRepo().apply { failReport = IllegalStateException("denied") }
        val coordinator = ChatCoordinator(repo)
        coordinator.submitReport("e1", "m1", ChatReportReason.OTHER)
        assertEquals(ChatReportStatus.Failed, coordinator.reportStatus.value)
    }
}
