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

/** Robolectric is intentionally scoped to the Debug test variant in this app. */
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

    @Test fun `a rejected mutation refreshes instead of restoring an obsolete in-flight choice`() = runTest {
        val release = CompletableDeferred<Unit>()
        var reads = 0
        val state = ReactionRoomState(ReactionRoom("community", "global"), "rejected-fixture", RuntimeEnvironment.getApplication(), this,
            sessionIsCurrent = { true }, remoteCall = { name, _ ->
                if (!name.endsWith("getMine")) error("Synthetic rejected mutation")
                val version = if (++reads == 1) { release.await(); 1L } else 2L
                mapOf("items" to listOf(mapOf("messageId" to "message", "emojiId" to "1f44d", "version" to version)))
            })
        state.store.clear(); state.enabled = true
        state.own["message"] = ReactionChoice("1f44d", 1, fetchedAt = 0)
        state.register("message", mapOf("1f44d" to 1L))
        advanceTimeBy(100); runCurrent()
        state.select("message"); state.choose("1f602"); runCurrent()
        assertNull(state.own["message"])
        release.complete(Unit)
        repeat(100) {
            advanceTimeBy(100); runCurrent()
            if (state.own["message"]?.version != 2L) withContext(Dispatchers.IO) { Thread.sleep(10) }
        }
        assertEquals(2, reads)
        assertEquals(2L, state.own["message"]?.version)
        state.invalidate(); state.store.clear()
    }

    @Test fun `reaction switches and removals display immediately before the server responds`() = runTest {
        val release = CompletableDeferred<Unit>()
        var payload: Map<String, Any?>? = null
        val state = ReactionRoomState(ReactionRoom("community", "global"), "optimistic-fixture", RuntimeEnvironment.getApplication(), this,
            sessionIsCurrent = { true }, remoteCall = { _, request ->
                payload = request; release.await()
                mapOf("emojiId" to "1f602", "version" to 2L, "counts" to mapOf("1f602" to 1L))
            })
        state.store.clear(); state.enabled = true
        state.register("message", mapOf("1f44d" to 1L))
        state.own["message"] = ReactionChoice("1f44d", 1)
        state.select("message"); state.choose("1f602")
        assertEquals(mapOf("1f602" to 1L), state.visible["message"])
        assertEquals("1f602", state.own["message"]?.emojiId)
        assertNull("The picker must close before network work runs", state.selectedId)
        assertTrue(state.busy)
        assertNull("Optimistic private state must not be cached", state.store.own(state.room, "message"))
        runCurrent()
        assertEquals(1L, payload?.get("expectedVersion"))
        // A stale snapshot and an old own-state response cannot erase the pending intent.
        state.register("message", mapOf("1f44d" to 2L))
        assertEquals(mapOf("1f602" to 1L), state.visible["message"])
        assertEquals("1f602", state.own["message"]?.emojiId)
        release.complete(Unit); runCurrent()
        assertEquals(2L, state.own["message"]?.version)
        // The listener can coalesce our write with another member's reaction.
        state.register("message", mapOf("1f602" to 2L))
        assertEquals(mapOf("1f602" to 2L), state.visible["message"])
        state.invalidate(); state.store.clear()
    }

    @Test fun `a listener snapshot newer than the mutation response remains visible`() = runTest {
        val release = CompletableDeferred<Unit>()
        val state = ReactionRoomState(ReactionRoom("community", "global"), "newer-snapshot-fixture", RuntimeEnvironment.getApplication(), this,
            sessionIsCurrent = { true }, remoteCall = { _, _ ->
                release.await()
                mapOf("emojiId" to "1f602", "version" to 1L, "counts" to mapOf("1f602" to 1L))
            })
        state.store.clear(); state.enabled = true
        state.register("message", emptyMap()); state.own["message"] = ReactionChoice(null, 0)
        state.select("message"); state.choose("1f602"); runCurrent()
        state.register("message", mapOf("1f602" to 2L, "1f44d" to 1L))
        release.complete(Unit); runCurrent()
        assertEquals(mapOf("1f602" to 2L, "1f44d" to 1L), state.visible["message"])
        assertEquals("1f602", state.own["message"]?.emojiId)
        state.invalidate(); state.store.clear()
    }

    @Test fun `a listener sequence returning to its original counts still supersedes an old response`() = runTest {
        val release = CompletableDeferred<Unit>()
        val state = ReactionRoomState(ReactionRoom("community", "global"), "snapshot-cycle-fixture", RuntimeEnvironment.getApplication(), this,
            sessionIsCurrent = { true }, remoteCall = { _, _ ->
                release.await()
                mapOf("emojiId" to "1f602", "version" to 1L, "counts" to mapOf("1f602" to 2L))
            })
        state.store.clear(); state.enabled = true
        state.register("message", mapOf("1f602" to 1L)); state.own["message"] = ReactionChoice(null, 0)
        state.select("message"); state.choose("1f602"); runCurrent()
        state.register("message", mapOf("1f602" to 2L))
        state.register("message", mapOf("1f602" to 1L))
        release.complete(Unit); runCurrent()
        assertEquals(mapOf("1f602" to 1L), state.visible["message"])
        state.invalidate(); state.store.clear()
    }

    @Test fun `a pending reaction survives its row being scrolled out and remounted`() = runTest {
        val release = CompletableDeferred<Unit>()
        val state = ReactionRoomState(ReactionRoom("community", "global"), "remount-fixture", RuntimeEnvironment.getApplication(), this,
            sessionIsCurrent = { true }, remoteCall = { _, _ ->
                release.await()
                mapOf("emojiId" to "1f602", "version" to 1L, "counts" to mapOf("1f602" to 1L))
            })
        state.store.clear(); state.enabled = true
        state.register("message", emptyMap()); state.own["message"] = ReactionChoice(null, 0)
        state.select("message"); state.choose("1f602"); runCurrent()
        state.unregister("message"); state.register("message", emptyMap())
        assertEquals(mapOf("1f602" to 1L), state.visible["message"])
        assertEquals("1f602", state.own["message"]?.emojiId)
        state.select("message")
        assertEquals("message", state.selectedId)
        release.complete(Unit); runCurrent()
        assertEquals(1L, state.own["message"]?.version)
        assertEquals(mapOf("1f602" to 1L), state.visible["message"])
        state.invalidate(); state.store.clear()
    }

    @Test fun `removing own reaction hides its final chip immediately`() = runTest {
        val release = CompletableDeferred<Unit>()
        val state = ReactionRoomState(ReactionRoom("community", "global"), "remove-fixture", RuntimeEnvironment.getApplication(), this,
            sessionIsCurrent = { true }, remoteCall = { _, _ -> release.await(); emptyMap() })
        state.store.clear(); state.enabled = true
        state.register("message", mapOf("1f44d" to 1L, "1f602" to 2L))
        state.own["message"] = ReactionChoice("1f44d", 1)
        state.select("message"); state.choose("1f44d")
        assertEquals(mapOf("1f602" to 2L), state.visible["message"])
        assertNull(state.own["message"]?.emojiId)
        state.invalidate(); state.store.clear()
    }

    @Test fun `failed optimistic reaction rolls back to the latest snapshot and preserves a newer menu`() = runTest {
        val release = CompletableDeferred<Unit>()
        val state = ReactionRoomState(ReactionRoom("community", "global"), "rollback-fixture", RuntimeEnvironment.getApplication(), this,
            sessionIsCurrent = { true }, remoteCall = { name, _ ->
                if (name.endsWith("getMine")) mapOf("items" to emptyList<Any>())
                else { release.await(); error("Synthetic network failure") }
            })
        state.store.clear(); state.enabled = true
        state.register("message", mapOf("1f44d" to 1L))
        state.own["message"] = ReactionChoice(null, 0)
        state.select("message"); state.choose("1f602"); runCurrent()
        state.register("message", mapOf("1f44d" to 3L))
        state.register("other", emptyMap()); state.select("other")
        release.complete(Unit); runCurrent()
        assertEquals(mapOf("1f44d" to 3L), state.visible["message"])
        assertNull(state.own["message"])
        assertEquals("other", state.selectedId)
        assertFalse(state.busy)
        state.invalidate(); state.store.clear()
    }

    @Test fun `invalidation prevents a pending mutation from restoring cleared state`() = runTest {
        val release = CompletableDeferred<Unit>()
        val state = ReactionRoomState(ReactionRoom("community", "global"), "mutation-invalidate-fixture", RuntimeEnvironment.getApplication(), this,
            sessionIsCurrent = { true }, remoteCall = { _, _ ->
                release.await()
                mapOf("emojiId" to "1f602", "version" to 1L, "counts" to mapOf("1f602" to 1L))
            })
        state.store.clear(); state.enabled = true
        state.register("message", emptyMap()); state.own["message"] = ReactionChoice(null, 0)
        state.select("message"); state.choose("1f602"); runCurrent()
        state.invalidate(); release.complete(Unit); runCurrent()
        assertTrue(state.visible.isEmpty())
        assertTrue(state.own.isEmpty())
        assertFalse(state.busy)
        assertNull(state.store.own(state.room, "message"))
        state.store.clear()
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
