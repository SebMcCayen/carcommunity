package com.kungsbackacarcommunity.app.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReactionRoomRefreshTest {
    @Test fun `selecting during a read preserves its response and queues a new visible message`() = runTest {
        val release = CompletableDeferred<Unit>()
        val batches = mutableListOf<List<String>>()
        var cancelled = false
        val state = ReactionRoomState(ReactionRoom("community", "global"), "refresh-fixture", RuntimeEnvironment.getApplication(), this,
            sessionIsCurrent = { true }, remoteCall = { _, payload ->
                val ids = (payload["messageIds"] as List<*>).map { it as String }
                batches += ids
                if (batches.size == 1) {
                    try { release.await() } finally { cancelled = !release.isCompleted }
                }
                mapOf("items" to ids.map { mapOf("messageId" to it, "emojiId" to "1f44d", "version" to 2L) })
            })
        state.store.clear()
        state.enabled = true
        state.register("first", emptyMap())
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf(listOf("first")), batches)
        state.select("first")
        state.register("second", emptyMap())
        state.select("second")
        advanceTimeBy(100); runCurrent()
        assertFalse("Long-press must not cancel an active own-state read", cancelled)
        assertEquals(1, batches.size)
        release.complete(Unit)
        // Persisting the response uses real disk IO before the queued batch can run.
        repeat(100) {
            advanceTimeBy(100); runCurrent()
            if (state.own["second"] == null) withContext(Dispatchers.IO) { Thread.sleep(10) }
        }
        assertEquals("1f44d", state.own["first"]?.emojiId)
        assertEquals(2L, state.own["second"]?.version)
        assertEquals(listOf(listOf("first"), listOf("second")), batches)
        assertEquals("second", state.selectedId)
        state.invalidate(); state.store.clear()
    }

    @Test fun `a delayed read cannot overwrite a confirmed newer selection`() = runTest {
        val release = CompletableDeferred<Unit>()
        val state = ReactionRoomState(ReactionRoom("community", "global"), "mutation-fixture", RuntimeEnvironment.getApplication(), this,
            sessionIsCurrent = { true }, remoteCall = { name, _ ->
                if (name.endsWith("getMine")) {
                    release.await()
                    mapOf("items" to listOf(mapOf("messageId" to "message", "emojiId" to "1f44d", "version" to 1L)))
                } else mapOf("emojiId" to "1f602", "version" to 2L, "counts" to mapOf("1f602" to 1L))
            })
        state.store.clear(); state.enabled = true
        state.own["message"] = ReactionChoice("1f44d", 1, fetchedAt = 0)
        state.register("message", mapOf("1f44d" to 1L))
        advanceTimeBy(100); runCurrent()
        state.select("message"); state.choose("1f602"); runCurrent()
        assertEquals(2L, state.own["message"]?.version)
        release.complete(Unit)
        repeat(100) {
            advanceTimeBy(100); runCurrent()
            if (state.busy) withContext(Dispatchers.IO) { Thread.sleep(10) }
        }
        assertEquals("1f602", state.own["message"]?.emojiId)
        assertEquals(2L, state.store.own(state.room, "message")?.version)
        state.invalidate(); state.store.clear()
    }

    @Test fun `invalidation still cancels an active read and prevents cached private state`() = runTest {
        val release = CompletableDeferred<Unit>()
        var cancelled = false
        val state = ReactionRoomState(ReactionRoom("community", "global"), "invalidate-fixture", RuntimeEnvironment.getApplication(), this,
            sessionIsCurrent = { true }, remoteCall = { _, _ ->
                try { release.await() } finally { cancelled = !release.isCompleted }
                mapOf("items" to listOf(mapOf("messageId" to "message", "emojiId" to "1f44d", "version" to 1L)))
            })
        state.store.clear(); state.enabled = true
        state.register("message", emptyMap())
        advanceTimeBy(100); runCurrent()
        state.invalidate(); runCurrent()
        assertTrue(cancelled)
        assertTrue(state.own.isEmpty())
        assertNull(state.store.own(state.room, "message"))
        state.store.clear()
    }
}
