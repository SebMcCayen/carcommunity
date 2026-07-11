package com.kungsbackacarcommunity.app.dm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DmThreadCoordinatorTest {

    private class FakeRepo : DmRepository {
        var sendResult: DmSendResult = DmSendResult.Sent("me__friend", "m1")
        var olderResult: DmOlderResult =
            DmOlderResult.Loaded(DmMessagesPage(emptyList(), nextBefore = null, hasMore = false))
        var sendCalls = 0
        var lastSendToUid: String? = null
        var lastSendText: String? = null
        var markReadCalls = 0
        var loadOlderCalls = 0
        var lastBefore: String? = null

        override fun observeConversations(uid: String): Flow<DmConversationsState> = emptyFlow()

        override fun observeThread(conversationId: String): Flow<DmThreadState> = emptyFlow()

        override suspend fun sendMessage(toUid: String, text: String): DmSendResult {
            sendCalls++
            lastSendToUid = toUid
            lastSendText = text
            return sendResult
        }

        override suspend fun loadOlder(conversationId: String, before: String): DmOlderResult {
            loadOlderCalls++
            lastBefore = before
            return olderResult
        }

        override suspend fun markRead(conversationId: String) {
            markReadCalls++
        }
    }

    private fun coordinator(repo: DmRepository) =
        DmThreadCoordinator(repo, otherUid = "friend", conversationId = "me__friend")

    @Test
    fun `send trims, succeeds, and increments sentCount`() = runTest {
        val repo = FakeRepo()
        val c = coordinator(repo)
        c.send("  hello ")
        assertEquals("friend", repo.lastSendToUid)
        assertEquals("hello", repo.lastSendText)
        assertEquals(DmSendStatus.Idle, c.sendStatus.value)
        assertEquals(1, c.sentCount.value)
    }

    @Test
    fun `blank message is not sent`() = runTest {
        val repo = FakeRepo()
        val c = coordinator(repo)
        c.send("   ")
        assertEquals(0, repo.sendCalls)
        assertEquals(DmSendStatus.Idle, c.sendStatus.value)
        assertEquals(0, c.sentCount.value)
    }

    @Test
    fun `a failed send surfaces the mapped error and does not bump sentCount`() = runTest {
        val repo = FakeRepo().apply { sendResult = DmSendResult.Failed(DmSendError.CannotDeliver) }
        val c = coordinator(repo)
        c.send("hi")
        assertEquals(DmSendStatus.Failed(DmSendError.CannotDeliver), c.sendStatus.value)
        assertEquals(0, c.sentCount.value)
    }

    @Test
    fun `resetSendError clears a failure`() = runTest {
        val repo = FakeRepo().apply { sendResult = DmSendResult.Failed(DmSendError.Generic) }
        val c = coordinator(repo)
        c.send("hi")
        assertTrue(c.sendStatus.value is DmSendStatus.Failed)
        c.resetSendError()
        assertEquals(DmSendStatus.Idle, c.sendStatus.value)
    }

    @Test
    fun `loadOlder accumulates messages and marks End when there is no more`() = runTest {
        val repo =
            FakeRepo().apply {
                olderResult =
                    DmOlderResult.Loaded(
                        DmMessagesPage(
                            messages = listOf(msg("m1", 100L), msg("m2", 200L)),
                            nextBefore = null,
                            hasMore = false,
                        ),
                    )
            }
        val c = coordinator(repo)
        c.loadOlder("2026-07-11T00:00:03Z")
        assertEquals("2026-07-11T00:00:03Z", repo.lastBefore)
        assertEquals(listOf("m1", "m2"), c.olderMessages.value.map { it.id })
        assertEquals(DmPageStatus.End, c.pageStatus.value)
    }

    @Test
    fun `loadOlder keeps paging Idle when more remain`() = runTest {
        val repo =
            FakeRepo().apply {
                olderResult =
                    DmOlderResult.Loaded(
                        DmMessagesPage(listOf(msg("m1", 100L)), nextBefore = "cursor", hasMore = true),
                    )
            }
        val c = coordinator(repo)
        c.loadOlder("2026-07-11T00:00:03Z")
        assertEquals(DmPageStatus.Idle, c.pageStatus.value)
    }

    @Test
    fun `a transient loadOlder failure surfaces Error, not End, and stays retryable`() = runTest {
        val repo = FakeRepo().apply { olderResult = DmOlderResult.Failed }
        val c = coordinator(repo)
        c.loadOlder("2026-07-11T00:00:03Z")
        // A failure must NOT permanently end pagination.
        assertEquals(DmPageStatus.Error, c.pageStatus.value)
        assertTrue(c.olderMessages.value.isEmpty())

        // The Error state is retryable: a second attempt actually calls through,
        // and a now-successful page recovers to Idle (more remain).
        repo.olderResult =
            DmOlderResult.Loaded(
                DmMessagesPage(listOf(msg("m1", 100L)), nextBefore = "cursor", hasMore = true),
            )
        c.loadOlder("2026-07-11T00:00:03Z")
        assertEquals(2, repo.loadOlderCalls)
        assertEquals(listOf("m1"), c.olderMessages.value.map { it.id })
        assertEquals(DmPageStatus.Idle, c.pageStatus.value)
    }

    @Test
    fun `loadOlder with a null cursor is a no-op`() = runTest {
        val repo = FakeRepo()
        val c = coordinator(repo)
        c.loadOlder(null)
        assertEquals(DmPageStatus.Idle, c.pageStatus.value)
        assertTrue(c.olderMessages.value.isEmpty())
    }

    @Test
    fun `markRead delegates to the repository`() = runTest {
        val repo = FakeRepo()
        val c = coordinator(repo)
        c.markRead()
        assertEquals(1, repo.markReadCalls)
    }

    @Test
    fun `markReadIfIncoming marks read for a message from the other party`() = runTest {
        val repo = FakeRepo()
        val c = coordinator(repo)
        c.markReadIfIncoming(msg("m1", 100L, sender = "friend"))
        assertEquals(1, repo.markReadCalls)
    }

    @Test
    fun `markReadIfIncoming does NOT mark read for the caller's own message`() = runTest {
        val repo = FakeRepo()
        val c = coordinator(repo)
        // "me" is the caller here (otherUid is "friend"); an own send must not
        // trigger a needless markRead callable.
        c.markReadIfIncoming(msg("m1", 100L, sender = "me"))
        assertEquals(0, repo.markReadCalls)
    }

    @Test
    fun `markReadIfIncoming is a no-op for a null newest message`() = runTest {
        val repo = FakeRepo()
        val c = coordinator(repo)
        c.markReadIfIncoming(null)
        assertEquals(0, repo.markReadCalls)
    }

    private fun msg(id: String, millis: Long, sender: String = "friend") =
        DmMessage(id = id, senderUid = sender, text = id, createdAtMillis = millis, createdAtIso = null)
}
