package com.kungsbackacarcommunity.app.chatchannels

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ChannelChatCoordinator], the shared OPTIMISTIC send / paging /
 * mark-read state machine behind both the community and convoy channels. Mirrors
 * dm/DmThreadCoordinatorTest: exercises the instant optimistic bubble, success /
 * failure / retry reconciliation, snapshot reconciliation, mention reconciliation,
 * retryability (Error vs End), trimming/bounds, and (optional) marker invocation
 * on plain fakes.
 */
class ChannelChatCoordinatorTest {

    private class Fakes {
        var sendResult: ChannelSendResult = ChannelSendResult.Sent("m1")
        var olderResult: ChannelOlderResult =
            ChannelOlderResult.Loaded(
                ChannelMessagesPage(emptyList(), nextBefore = null, hasMore = false),
            )
        var sendCalls = 0
        var lastSendText: String? = null
        var lastSendMentions: List<String>? = null
        val sentClientIds = mutableListOf<String>()
        var loadOlderCalls = 0
        var lastBefore: String? = null
        var markReadCalls = 0

        /** When set, [sender] suspends on it until completed — models an in-flight callable. */
        var gate: CompletableDeferred<Unit>? = null

        val sender: suspend (String, List<String>, String) -> ChannelSendResult =
            { text, mentionedUids, clientId ->
                sendCalls++
                lastSendText = text
                lastSendMentions = mentionedUids
                sentClientIds += clientId
                gate?.await()
                sendResult
            }
        val pager: suspend (String) -> ChannelOlderResult = { before ->
            loadOlderCalls++
            lastBefore = before
            olderResult
        }
        val marker: suspend () -> Unit = { markReadCalls++ }
    }

    private fun coordinator(
        f: Fakes,
        withMarker: Boolean = true,
        ids: () -> String = { "cid-1" },
    ) =
        ChannelChatCoordinator(
            sender = f.sender,
            pager = f.pager,
            selfUid = "me",
            marker = if (withMarker) f.marker else null,
            clock = { 1000L },
            idGenerator = ids,
        )

    @Test
    fun `send appends an optimistic bubble immediately with a generated client id`() = runTest {
        val f = Fakes().apply { gate = CompletableDeferred() }
        val c = coordinator(f)

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
        assertEquals("hello", bubble.text)
        assertEquals(ChannelDeliveryState.Sending, bubble.deliveryState)
        // The callable was dispatched with the same key.
        assertEquals(listOf("cid-1"), f.sentClientIds)

        f.gate!!.complete(Unit)
        job.join()
        // Acked: the bubble flips to Sent and sentCount bumps.
        assertEquals(ChannelDeliveryState.Sent, c.pendingMessages.value.single().deliveryState)
        assertEquals(1, c.sentCount.value)
        assertEquals("hello", f.lastSendText)
    }

    @Test
    fun `a failed send flips the bubble to Failed with the mapped error`() = runTest {
        val f = Fakes().apply { sendResult = ChannelSendResult.Failed(ChannelSendError.NotMember) }
        val c = coordinator(f)
        c.send("hi")
        val bubble = c.pendingMessages.value.single()
        assertEquals(ChannelDeliveryState.Failed, bubble.deliveryState)
        assertEquals(ChannelSendError.NotMember, bubble.sendError)
        assertEquals(0, c.sentCount.value)
    }

    @Test
    fun `retry resends the SAME client id and recovers to Sent`() = runTest {
        val f = Fakes().apply { sendResult = ChannelSendResult.Failed(ChannelSendError.Generic) }
        val c = coordinator(f)
        c.send("hi")
        assertEquals(ChannelDeliveryState.Failed, c.pendingMessages.value.single().deliveryState)

        f.sendResult = ChannelSendResult.Sent("m1")
        c.retry("cid-1")
        // Same idempotency key on both attempts (exactly-once on the backend).
        assertEquals(listOf("cid-1", "cid-1"), f.sentClientIds)
        val bubble = c.pendingMessages.value.single()
        assertEquals(ChannelDeliveryState.Sent, bubble.deliveryState)
        assertNull(bubble.sendError)
    }

    @Test
    fun `retry is a no-op for an unknown or non-failed bubble`() = runTest {
        val f = Fakes()
        val c = coordinator(f)
        c.send("hi") // resolves to Sent (default sendResult)
        c.retry("nope")
        c.retry("cid-1") // exists but is Sent, not Failed
        assertEquals(1, f.sendCalls)
    }

    @Test
    fun `onLiveMessages drops a pending bubble once its delivered doc arrives`() = runTest {
        val f = Fakes()
        val c = coordinator(f)
        c.send("hi")
        assertEquals(1, c.pendingMessages.value.size)

        // The delivered document's id equals the bubble's clientId (the backend
        // wrote it at that id), so the reconcile drops the bubble — the message
        // renders exactly once via the live listener.
        c.onLiveMessages(listOf(msg("cid-1", 1000L)))
        assertTrue(c.pendingMessages.value.isEmpty())
    }

    @Test
    fun `mergeWithPending renders an in-flight bubble and reconciles the delivered doc as one`() =
        runTest {
            val f = Fakes().apply { gate = CompletableDeferred() }
            val c = coordinator(f)
            val job = launch { c.send("hi") }
            runCurrent()

            // In flight: the bubble appears at the end of the display list.
            val displayInFlight =
                ChannelThread.mergeWithPending(emptyList(), emptyList(), c.pendingMessages.value)
            assertEquals(listOf("cid-1"), displayInFlight.map { it.id })
            assertEquals(ChannelDeliveryState.Sending, displayInFlight.single().deliveryState)

            f.gate!!.complete(Unit)
            job.join()
            // The delivered doc arrives; reconcile drops the bubble, one message.
            c.onLiveMessages(listOf(msg("cid-1", 1000L)))
            val display =
                ChannelThread.mergeWithPending(
                    emptyList(),
                    listOf(msg("cid-1", 1000L)),
                    c.pendingMessages.value,
                )
            assertEquals(listOf("cid-1"), display.map { it.id })
        }

    @Test
    fun `send forwards the picked mention uids and reports none dropped when all accepted`() =
        runTest {
            val f =
                Fakes().apply { sendResult = ChannelSendResult.Sent("m1", listOf("uid-a", "uid-b")) }
            val c = coordinator(f)
            c.send("hi @Alice @Bob", listOf("uid-a", "uid-b"))
            assertEquals(listOf("uid-a", "uid-b"), f.lastSendMentions)
            assertEquals(0, c.droppedMentionCount.value)
            // The optimistic bubble carries the picked mentions for highlighting.
            assertEquals(listOf("uid-a", "uid-b"), c.pendingMessages.value.single().mentionedUids)
        }

    @Test
    fun `send reconciles against the smaller ACCEPTED set the server echoes`() = runTest {
        val f = Fakes().apply { sendResult = ChannelSendResult.Sent("m1", listOf("uid-a")) }
        val c = coordinator(f)
        c.send("hi @Alice @Bob", listOf("uid-a", "uid-b"))
        assertEquals(1, c.sentCount.value)
        assertEquals(1, c.droppedMentionCount.value)

        c.dismissDroppedMentions()
        assertEquals(0, c.droppedMentionCount.value)
    }

    @Test
    fun `a failed send reports no dropped mentions`() = runTest {
        val f = Fakes().apply { sendResult = ChannelSendResult.Failed(ChannelSendError.Generic) }
        val c = coordinator(f)
        c.send("hi @Alice", listOf("uid-a"))
        assertEquals(0, c.droppedMentionCount.value)
    }

    // Optimistic send frees the composer the moment a message is queued, so the
    // next send can start (and finish) while an earlier one's note is still on
    // screen. The dropped-mention note must therefore survive until the USER
    // clears it — nothing about a later send may retract it.

    @Test
    fun `a later mention-free send does not erase an earlier send's dropped note`() = runTest {
        val f = Fakes().apply { sendResult = ChannelSendResult.Sent("m1", emptyList()) }
        val ids = listOf("cid-1", "cid-2").iterator()
        val c = coordinator(f, ids = { ids.next() })

        c.send("hi @Alice", listOf("uid-a"))
        assertEquals(1, c.droppedMentionCount.value)

        // A perfectly ordinary follow-up message that mentions nobody.
        f.sendResult = ChannelSendResult.Sent("m2", emptyList())
        c.send("och hej igen")

        // Still 1: the user has not acknowledged the drop, so it stays put.
        assertEquals(1, c.droppedMentionCount.value)
        c.dismissDroppedMentions()
        assertEquals(0, c.droppedMentionCount.value)
    }

    @Test
    fun `a later fully-accepted send does not erase an earlier send's dropped note`() = runTest {
        val f = Fakes().apply { sendResult = ChannelSendResult.Sent("m1", emptyList()) }
        val ids = listOf("cid-1", "cid-2").iterator()
        val c = coordinator(f, ids = { ids.next() })

        c.send("hi @Alice", listOf("uid-a"))
        assertEquals(1, c.droppedMentionCount.value)

        // The second send's mention IS accepted — its own drop count is zero, and
        // assigning that would wipe the first send's note.
        f.sendResult = ChannelSendResult.Sent("m2", listOf("uid-b"))
        c.send("hi @Bob", listOf("uid-b"))
        assertEquals(1, c.droppedMentionCount.value)
    }

    @Test
    fun `concurrent sends accumulate their drops instead of racing to overwrite`() = runTest {
        val f = Fakes().apply { sendResult = ChannelSendResult.Sent("m1", emptyList()) }
        val ids = listOf("cid-1", "cid-2").iterator()
        val c = coordinator(f, ids = { ids.next() })
        f.gate = CompletableDeferred()

        // Both in flight at once — exactly what the composer now permits.
        val first = launch { c.send("hi @Alice", listOf("uid-a")) }
        val second = launch { c.send("hi @Bob", listOf("uid-b")) }
        runCurrent()
        assertEquals(2, c.pendingMessages.value.size)

        f.gate!!.complete(Unit)
        first.join()
        second.join()

        // Two separate mentions went undelivered, so the count is 2 — an
        // assignment would leave whichever ack resolved last showing 1.
        assertEquals(2, c.droppedMentionCount.value)
    }

    @Test
    fun `blank message is not sent and adds no bubble`() = runTest {
        val f = Fakes()
        val c = coordinator(f)
        c.send("   ")
        assertEquals(0, f.sendCalls)
        assertTrue(c.pendingMessages.value.isEmpty())
        assertEquals(0, c.sentCount.value)
    }

    @Test
    fun `an over-long message is not sent`() = runTest {
        val f = Fakes()
        val c = coordinator(f)
        c.send("x".repeat(CHANNEL_MESSAGE_MAX_LENGTH + 1))
        assertEquals(0, f.sendCalls)
        assertTrue(c.pendingMessages.value.isEmpty())
    }

    @Test
    fun `loadOlder accumulates messages and marks End when there is no more`() = runTest {
        val f =
            Fakes().apply {
                olderResult =
                    ChannelOlderResult.Loaded(
                        ChannelMessagesPage(
                            messages = listOf(msg("m1", 100L), msg("m2", 200L)),
                            nextBefore = null,
                            hasMore = false,
                        ),
                    )
            }
        val c = coordinator(f)
        c.loadOlder("2026-07-11T00:00:03Z")
        assertEquals("2026-07-11T00:00:03Z", f.lastBefore)
        assertEquals(listOf("m1", "m2"), c.olderMessages.value.map { it.id })
        assertEquals(ChannelPageStatus.End, c.pageStatus.value)
    }

    @Test
    fun `loadOlder keeps paging Idle when more remain`() = runTest {
        val f =
            Fakes().apply {
                olderResult =
                    ChannelOlderResult.Loaded(
                        ChannelMessagesPage(listOf(msg("m1", 100L)), nextBefore = "cursor", hasMore = true),
                    )
            }
        val c = coordinator(f)
        c.loadOlder("2026-07-11T00:00:03Z")
        assertEquals(ChannelPageStatus.Idle, c.pageStatus.value)
    }

    @Test
    fun `a transient loadOlder failure surfaces Error, not End, and stays retryable`() = runTest {
        val f = Fakes().apply { olderResult = ChannelOlderResult.Failed }
        val c = coordinator(f)
        c.loadOlder("2026-07-11T00:00:03Z")
        assertEquals(ChannelPageStatus.Error, c.pageStatus.value)
        assertTrue(c.olderMessages.value.isEmpty())

        f.olderResult =
            ChannelOlderResult.Loaded(
                ChannelMessagesPage(listOf(msg("m1", 100L)), nextBefore = "cursor", hasMore = true),
            )
        c.loadOlder("2026-07-11T00:00:03Z")
        assertEquals(2, f.loadOlderCalls)
        assertEquals(listOf("m1"), c.olderMessages.value.map { it.id })
        assertEquals(ChannelPageStatus.Idle, c.pageStatus.value)
    }

    @Test
    fun `loadOlder with a null cursor is a no-op`() = runTest {
        val f = Fakes()
        val c = coordinator(f)
        c.loadOlder(null)
        assertEquals(0, f.loadOlderCalls)
        assertEquals(ChannelPageStatus.Idle, c.pageStatus.value)
        assertTrue(c.olderMessages.value.isEmpty())
    }

    @Test
    fun `loadOlder does not page again once End is reached`() = runTest {
        val f =
            Fakes().apply {
                olderResult =
                    ChannelOlderResult.Loaded(
                        ChannelMessagesPage(listOf(msg("m1", 100L)), nextBefore = null, hasMore = false),
                    )
            }
        val c = coordinator(f)
        c.loadOlder("2026-07-11T00:00:03Z")
        assertEquals(ChannelPageStatus.End, c.pageStatus.value)
        c.loadOlder("2026-07-11T00:00:02Z")
        assertEquals(1, f.loadOlderCalls)
    }

    @Test
    fun `markRead delegates to the marker when present`() = runTest {
        val f = Fakes()
        val c = coordinator(f)
        c.markRead()
        assertEquals(1, f.markReadCalls)
    }

    @Test
    fun `markRead is a no-op when there is no marker (convoy channel)`() = runTest {
        val f = Fakes()
        val c = coordinator(f, withMarker = false)
        c.markRead()
        assertEquals(0, f.markReadCalls)
    }

    private fun msg(id: String, millis: Long) =
        ChannelMessage(
            id = id,
            senderUid = "u",
            text = id,
            senderDisplayName = null,
            senderAvatarPath = null,
            createdAtMillis = millis,
            createdAtIso = java.time.Instant.ofEpochMilli(millis).toString(),
        )
}
