package com.kungsbackacarcommunity.app.dm

import com.kungsbackacarcommunity.app.diagnostics.CrashFeatures
import com.kungsbackacarcommunity.app.diagnostics.CrashTelemetry
import com.kungsbackacarcommunity.app.diagnostics.NoopCrashTelemetry
import com.kungsbackacarcommunity.app.diagnostics.RecordingCrashTelemetry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DmThreadCoordinatorTest {

    private class FakeRepo : DmRepository {
        var sendResult: DmSendResult = DmSendResult.Sent("me__friend", "c-1")
        var sendCalls = 0
        var lastSendToUid: String? = null
        var lastSendText: String? = null
        val sentClientIds = mutableListOf<String?>()
        var olderResult: DmOlderResult =
            DmOlderResult.Loaded(DmMessagesPage(emptyList(), nextBefore = null, hasMore = false))
        var markReadCalls = 0
        var loadOlderCalls = 0
        var lastBefore: String? = null

        /** When set, [sendMessage] suspends on this until completed — models an in-flight callable. */
        var gate: CompletableDeferred<Unit>? = null

        override fun observeConversations(uid: String): Flow<DmConversationsState> = emptyFlow()

        override fun observeThread(conversationId: String): Flow<DmThreadState> = emptyFlow()

        override suspend fun sendMessage(toUid: String, text: String, clientId: String?): DmSendResult {
            sendCalls++
            lastSendToUid = toUid
            lastSendText = text
            sentClientIds += clientId
            gate?.await()
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

    private fun coordinator(
        repo: DmRepository,
        ids: () -> String = { "cid-1" },
        crashTelemetry: CrashTelemetry = NoopCrashTelemetry,
    ) =
        DmThreadCoordinator(
            repo,
            selfUid = "me",
            otherUid = "friend",
            conversationId = "me__friend",
            clock = { 1000L },
            idGenerator = ids,
            crashTelemetry = crashTelemetry,
        )

    @Test
    fun `an UNEXPECTED send throw is recorded as a non-fatal`() = runTest {
        // A mapped DmSendResult.Failed is a modelled outcome; a raw throw is not,
        // and the member only ever sees a generic retry — so the stack trace has
        // to go somewhere.
        val boom = IllegalStateException("callable blew up")
        val repo =
            object : DmRepository by FakeRepo() {
                override suspend fun sendMessage(toUid: String, text: String, clientId: String?): DmSendResult =
                    throw boom
            }
        val telemetry = RecordingCrashTelemetry()

        coordinator(repo, crashTelemetry = telemetry).send("hello")

        assertEquals(1, telemetry.nonFatals.size)
        assertEquals(CrashFeatures.DM_SEND, telemetry.nonFatals.single().first)
        assertEquals(boom, telemetry.nonFatals.single().second)
    }

    @Test
    fun `a MAPPED send failure is not recorded as a non-fatal`() = runTest {
        // The app already models this outcome and shows a specific reason; it is
        // the app working, not a fault, so it must not spend the console budget.
        val repo = FakeRepo().apply { sendResult = DmSendResult.Failed(DmSendError.Generic) }
        val telemetry = RecordingCrashTelemetry()

        coordinator(repo, crashTelemetry = telemetry).send("hello")

        assertTrue(telemetry.nonFatals.isEmpty())
    }

    @Test
    fun `send appends an optimistic bubble immediately with a generated client id`() = runTest {
        val repo = FakeRepo().apply { gate = CompletableDeferred() }
        val c = coordinator(repo)

        // Launch the send but hold the callable open, so we observe the state
        // BEFORE the round-trip resolves — the optimistic bubble must already be
        // there (that is the whole point: instant UI).
        val job = launch { c.send("  hello ") }
        runCurrent()

        val pending = c.pendingMessages.value
        assertEquals(1, pending.size)
        val bubble = pending.single()
        assertEquals("cid-1", bubble.id)
        assertEquals("cid-1", bubble.clientId)
        assertEquals("me", bubble.senderUid)
        assertEquals("hello", bubble.text) // trimmed
        assertEquals(DmDeliveryState.Sending, bubble.deliveryState)
        // The callable was fired with the same client idempotency key.
        assertEquals(listOf<String?>("cid-1"), repo.sentClientIds)
        assertEquals("hello", repo.lastSendText)

        repo.gate!!.complete(Unit)
        job.join()
    }

    @Test
    fun `a successful send flips the bubble to Sent and bumps sentCount`() = runTest {
        val repo = FakeRepo()
        val c = coordinator(repo)
        c.send("hi")
        assertEquals(DmDeliveryState.Sent, c.pendingMessages.value.single().deliveryState)
        assertEquals(1, c.sentCount.value)
    }

    @Test
    fun `blank message is not sent and adds no bubble`() = runTest {
        val repo = FakeRepo()
        val c = coordinator(repo)
        c.send("   ")
        assertEquals(0, repo.sendCalls)
        assertTrue(c.pendingMessages.value.isEmpty())
        assertEquals(0, c.sentCount.value)
    }

    @Test
    fun `a failed send marks the bubble Failed with the specific error and does not bump sentCount`() =
        runTest {
            val repo =
                FakeRepo().apply { sendResult = DmSendResult.Failed(DmSendError.CannotDeliver) }
            val c = coordinator(repo)
            c.send("hi")
            val bubble = c.pendingMessages.value.single()
            assertEquals(DmDeliveryState.Failed, bubble.deliveryState)
            // The specific error is retained so the UI can explain why + decide
            // whether a retry is worthwhile (CannotDeliver is terminal).
            assertEquals(DmSendError.CannotDeliver, bubble.sendError)
            assertEquals(0, c.sentCount.value)
        }

    @Test
    fun `onLiveMessages reconciles the delivered doc away so it renders once`() = runTest {
        val repo = FakeRepo().apply { sendResult = DmSendResult.Sent("me__friend", "cid-1") }
        val c = coordinator(repo)
        c.send("hi")
        // The bubble is present until the listener delivers the real doc.
        assertEquals(1, c.pendingMessages.value.size)

        // The live snapshot delivers the message whose doc id == the client id.
        val delivered =
            DmMessage(id = "cid-1", senderUid = "me", text = "hi", createdAtMillis = 1000L, createdAtIso = null)
        c.onLiveMessages(listOf(delivered))

        // Pending is now empty — merged display would show only the server doc.
        assertTrue(c.pendingMessages.value.isEmpty())
    }

    @Test
    fun `retry resends the SAME client id and does not double-count`() = runTest {
        val repo = FakeRepo().apply { sendResult = DmSendResult.Failed(DmSendError.Generic) }
        val c = coordinator(repo)
        c.send("oops")
        assertEquals(DmDeliveryState.Failed, c.pendingMessages.value.single().deliveryState)

        // Second attempt succeeds this time.
        repo.sendResult = DmSendResult.Sent("me__friend", "cid-1")
        c.retry("cid-1")

        // Exactly one bubble throughout (no duplicate), now acked.
        assertEquals(1, c.pendingMessages.value.size)
        assertEquals(DmDeliveryState.Sent, c.pendingMessages.value.single().deliveryState)
        // Both the original send and the retry used the SAME idempotency key, so
        // the backend stays exactly-once.
        assertEquals(listOf<String?>("cid-1", "cid-1"), repo.sentClientIds)
        assertEquals(1, c.sentCount.value)
    }

    @Test
    fun `retry is a no-op for an unknown or non-failed bubble`() = runTest {
        val repo = FakeRepo()
        val c = coordinator(repo)
        c.send("hi") // succeeds → Sent, not Failed
        c.retry("cid-1") // not in Failed state
        c.retry("does-not-exist")
        assertEquals(1, repo.sendCalls) // only the original send
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
        assertEquals(DmPageStatus.Error, c.pageStatus.value)
        assertTrue(c.olderMessages.value.isEmpty())

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
