package com.kungsbackacarcommunity.app.chatchannels

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ChannelChatCoordinator], the shared send/paging/mark-read state
 * machine behind both the community and convoy channels. Mirrors
 * dm/DmThreadCoordinatorTest; exercises retryability (Error vs End),
 * trimming/bounds, and (optional) marker invocation on plain fakes.
 */
class ChannelChatCoordinatorTest {

    private class Fakes {
        var sendResult: ChannelSendResult = ChannelSendResult.Sent("m1")
        var olderResult: ChannelOlderResult =
            ChannelOlderResult.Loaded(ChannelMessagesPage(emptyList(), nextBefore = null, hasMore = false))
        var sendCalls = 0
        var lastSendText: String? = null
        var loadOlderCalls = 0
        var lastBefore: String? = null
        var markReadCalls = 0

        val sender: suspend (String) -> ChannelSendResult = { text ->
            sendCalls++
            lastSendText = text
            sendResult
        }
        val pager: suspend (String) -> ChannelOlderResult = { before ->
            loadOlderCalls++
            lastBefore = before
            olderResult
        }
        val marker: suspend () -> Unit = { markReadCalls++ }
    }

    private fun coordinator(f: Fakes, withMarker: Boolean = true) =
        ChannelChatCoordinator(
            sender = f.sender,
            pager = f.pager,
            marker = if (withMarker) f.marker else null,
        )

    @Test
    fun `send trims, succeeds, and increments sentCount`() = runTest {
        val f = Fakes()
        val c = coordinator(f)
        c.send("  hello ")
        assertEquals("hello", f.lastSendText)
        assertEquals(ChannelSendStatus.Idle, c.sendStatus.value)
        assertEquals(1, c.sentCount.value)
    }

    @Test
    fun `blank message is not sent`() = runTest {
        val f = Fakes()
        val c = coordinator(f)
        c.send("   ")
        assertEquals(0, f.sendCalls)
        assertEquals(ChannelSendStatus.Idle, c.sendStatus.value)
        assertEquals(0, c.sentCount.value)
    }

    @Test
    fun `an over-long message is not sent`() = runTest {
        val f = Fakes()
        val c = coordinator(f)
        c.send("x".repeat(CHANNEL_MESSAGE_MAX_LENGTH + 1))
        assertEquals(0, f.sendCalls)
        assertEquals(ChannelSendStatus.Idle, c.sendStatus.value)
    }

    @Test
    fun `a failed send surfaces the mapped error and does not bump sentCount`() = runTest {
        val f = Fakes().apply { sendResult = ChannelSendResult.Failed(ChannelSendError.NotMember) }
        val c = coordinator(f)
        c.send("hi")
        assertEquals(ChannelSendStatus.Failed(ChannelSendError.NotMember), c.sendStatus.value)
        assertEquals(0, c.sentCount.value)
    }

    @Test
    fun `resetSendError clears a failure`() = runTest {
        val f = Fakes().apply { sendResult = ChannelSendResult.Failed(ChannelSendError.Generic) }
        val c = coordinator(f)
        c.send("hi")
        assertTrue(c.sendStatus.value is ChannelSendStatus.Failed)
        c.resetSendError()
        assertEquals(ChannelSendStatus.Idle, c.sendStatus.value)
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
        // A failure must NOT permanently end pagination.
        assertEquals(ChannelPageStatus.Error, c.pageStatus.value)
        assertTrue(c.olderMessages.value.isEmpty())

        // The Error state is retryable: a second attempt actually calls through,
        // and a now-successful page recovers to Idle (more remain).
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
        // End is terminal: no second call.
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
