package com.kungsbackacarcommunity.app.driverfeed

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DriverFeedTest {
    private val cursor = DriverCursor(123, 987654321, "last")
    private fun post(id: String) = DriverPost(id, "author", "Author", "member", "Original text", 123L, emptyList(), 2, 1)

    @Test fun `decode preserves original text and clamps counts without fixtures`() {
        val value = DriverPost.decode("post", mapOf("authorUid" to "author", "text" to "Original\ntext",
            "likeCount" to -3, "replyCount" to 7L, "createdAtMillis" to 123L))!!
        assertEquals("Original\ntext", value.text)
        assertEquals("", value.author)
        assertEquals(0, value.likes)
        assertEquals(7, value.replies)
        assertEquals(123L, value.createdAtMillis)
    }

    @Test fun `decode rejects invalid target and author IDs`() {
        assertNull(DriverPost.decode("../post", mapOf("authorUid" to "author")))
        assertNull(DriverPost.decode("post", emptyMap<String, Any>()))
        assertNull(DriverPost.decode("post", mapOf("authorUid" to "")))
    }

    @Test fun `media accepts HTTPS without credentials and at most four images`() {
        val media = listOf("http://example.com/plain", "file:///private/image", "https://user:pass@example.com/image") +
            (1..6).map { "https://example.com/$it.jpg" }
        assertEquals((1..4).map { "https://example.com/$it.jpg" },
            DriverPost.decode("post", mapOf("authorUid" to "author", "mediaUrls" to media))!!.mediaUrls)
    }

    @Test fun `empty filtered page retains cursor and can load more`() = runTest {
        val seen = mutableListOf<DriverCursor?>()
        val repo = Fake(feedRead = { after ->
            seen += after
            if (after == null) DriverPage(emptyList(), cursor) else DriverPage(listOf(post("visible")), null)
        })
        val coordinator = DriverFeedCoordinator(repo, "uid", this)
        coordinator.refresh(); runCurrent()
        assertTrue(coordinator.state.value.posts.isEmpty())
        assertEquals(cursor, coordinator.state.value.next)
        coordinator.more(); runCurrent()
        assertEquals(listOf(null, cursor), seen)
        assertEquals("visible", coordinator.state.value.posts.single().id)
    }

    @Test fun `pagination deduplicates and serializes taps`() = runTest {
        var calls = 0
        val repo = Fake(feedRead = {
            calls++
            DriverPage(listOf(post("same")), if (calls == 1) cursor else null)
        })
        val coordinator = DriverFeedCoordinator(repo, "uid", this)
        coordinator.refresh(); runCurrent()
        coordinator.more(); coordinator.more(); runCurrent()
        assertEquals(2, calls)
        assertEquals(1, coordinator.state.value.posts.size)
    }

    @Test fun `detail uses independent cursor and original thread ID`() = runTest {
        val seen = mutableListOf<Pair<String, DriverCursor?>>()
        val repo = Fake(feedRead = { DriverPage(listOf(post("thread")), cursor) },
            postRead = { post(it) }, commentsRead = { id, after ->
                seen += id to after
                DriverPage(emptyList(), if (after == null) cursor else null)
            })
        val coordinator = DriverFeedCoordinator(repo, "uid", this)
        coordinator.refresh(); runCurrent()
        coordinator.open("thread"); runCurrent()
        assertEquals("thread", coordinator.state.value.post?.id)
        coordinator.more(); runCurrent()
        assertEquals(listOf("thread" to null, "thread" to cursor), seen)
        coordinator.back(); runCurrent()
        assertNull(coordinator.state.value.selectedId)
    }

    @Test fun `access error clears already displayed original content and retry recovers`() = runTest {
        var denied = false
        val repo = Fake(feedRead = {
            if (denied) throw DriverFeedException(DriverFeedFailure.ONBOARDING)
            DriverPage(listOf(post("old")), cursor)
        })
        val coordinator = DriverFeedCoordinator(repo, "uid", this)
        coordinator.refresh(); runCurrent()
        denied = true
        coordinator.more(); runCurrent()
        assertTrue(coordinator.state.value.posts.isEmpty())
        assertEquals(DriverFeedFailure.ONBOARDING, coordinator.state.value.failure)
        denied = false
        coordinator.refresh(); runCurrent()
        assertEquals("old", coordinator.state.value.posts.single().id)
    }

    @Test fun `account stop rejects response even if backend ignores cancellation`() = runTest {
        val gate = CompletableDeferred<DriverPage<DriverPost>>()
        val repo = Fake(feedRead = { withContext(NonCancellable) { gate.await() } })
        val coordinator = DriverFeedCoordinator(repo, "old-account", this)
        coordinator.refresh(); runCurrent()
        coordinator.stop()
        gate.complete(DriverPage(listOf(post("private-old")), null)); runCurrent()
        assertEquals(DriverFeedState(), coordinator.state.value)
    }

    @Test fun `refresh rejects superseded response`() = runTest {
        val gate = CompletableDeferred<DriverPage<DriverPost>>()
        var calls = 0
        val repo = Fake(feedRead = {
            calls++
            if (calls == 1) withContext(NonCancellable) { gate.await() }
            else DriverPage(listOf(post("fresh")), null)
        })
        val coordinator = DriverFeedCoordinator(repo, "uid", this)
        coordinator.refresh(); runCurrent()
        coordinator.refresh(); runCurrent()
        gate.complete(DriverPage(listOf(post("stale")), null)); runCurrent()
        assertEquals("fresh", coordinator.state.value.posts.single().id)
    }

    @Test fun `missing configuration is restricted and never reads`() = runTest {
        val coordinator = DriverFeedCoordinator(null, "uid", this)
        coordinator.refresh()
        assertEquals(DriverFeedFailure.RESTRICTED, coordinator.state.value.failure)
    }

    @Test fun `localization keys stay within Driver and source is explicit`() {
        assertEquals("driver.feed.source", DriverFeedStrings { it }["source"])
    }

    @Test fun `wire cursor retains nanos and payload uses limit twenty`() {
        val value = DriverCursor(123, 987654321, "lastpost1")
        val payload = pagePayload(value)
        assertEquals(20L, payload["limit"])
        assertEquals(value, driverWireCursor(mapOf("nextCursor" to payload["cursor"])))
        assertNull(driverWireCursor(mapOf("nextCursor" to null)))
        assertFalse(pagePayload(null).containsKey("cursor"))
    }

    @Test fun `wire rejects missing and malformed cursors instead of repeating pages`() {
        for (fields in listOf(emptyMap(), mapOf("nextCursor" to "bad"),
            mapOf("nextCursor" to mapOf("seconds" to 123.5, "nanoseconds" to 0, "id" to "lastpost1")),
            mapOf("nextCursor" to mapOf("seconds" to 123, "nanoseconds" to 1000000000, "id" to "lastpost1")))) {
            assertThrows(DriverFeedException::class.java) { driverWireCursor(fields) }
        }
    }

    @Test fun `wire rejects oversized response and wrong list shape`() {
        assertThrows(DriverFeedException::class.java) { driverWireList(mapOf("posts" to List(21) { emptyMap<String, Any>() }), "posts") }
        assertThrows(DriverFeedException::class.java) { driverWireList(mapOf("posts" to listOf("bad")), "posts") }
    }

    @Test fun `bridge reasons distinguish onboarding restrictions and unavailable thread`() {
        assertEquals(DriverFeedFailure.CANONICAL_ONBOARDING, driverReadFailure("canonical-onboarding-required", "FAILED_PRECONDITION"))
        assertEquals(DriverFeedFailure.ONBOARDING, driverReadFailure("driver-onboarding-required", "FAILED_PRECONDITION"))
        assertEquals(DriverFeedFailure.RESTRICTED, driverReadFailure("account-restricted", "PERMISSION_DENIED"))
        assertEquals(DriverFeedFailure.UNAVAILABLE, driverReadFailure("thread-unavailable", "NOT_FOUND"))
        assertEquals(DriverFeedFailure.ERROR, driverReadFailure(null, "RESOURCE_EXHAUSTED"))
        assertEquals(DriverFeedFailure.ERROR, driverReadFailure(null, "NOT_FOUND"))
    }

    @Test fun `access invalidation clears content before reload`() = runTest {
        val repo = Fake(feedRead = { DriverPage(listOf(post("original")), null) })
        val model = DriverFeedCoordinator(repo, "uid", this)
        model.refresh(); runCurrent()
        model.invalidate()
        assertTrue(model.state.value.posts.isEmpty())
        assertTrue(model.state.value.loading)
        model.refresh(); runCurrent()
        assertEquals("original", model.state.value.posts.single().id)
    }

    @Test fun `pause clears content but retains detail target for reauthorization`() = runTest {
        val repo = Fake(postRead = { post(it) })
        val model = DriverFeedCoordinator(repo, "uid", this)
        model.open("original"); runCurrent()
        model.pause()
        assertEquals("original", model.state.value.selectedId)
        assertNull(model.state.value.post)
        assertTrue(model.state.value.comments.isEmpty())
        model.refresh(); runCurrent()
        assertEquals("original", model.state.value.post?.id)
    }

    @Test fun `same account host retains selected post comments window and viewport across tab disposal`() = runTest {
        val seen = mutableListOf<DriverCursor?>()
        val repo = Fake(postRead = { post(it) }, commentsRead = { _, after ->
            seen += after
            DriverPage(listOf(DriverComment(if (after == null) "first" else "second", "author", "Author", "Text", 123L)),
                if (after == null) cursor else null)
        })
        val host = DriverFeedSessionOwner("uid", this)
        var creations = 0
        val source = host.defaultRepository { creations++; repo }
        val model = host.coordinator("uid", source)
        model.open("selected"); runCurrent()
        model.more(); runCurrent()
        model.recordViewport("selected", 3, 12)
        model.pause()
        assertNull(model.state.value.post)
        val remountedSource = host.defaultRepository { creations++; Fake() }
        assertEquals(1, creations)
        assertSame(source, remountedSource)
        assertSame(model, host.coordinator("uid", remountedSource))
        model.resume(); runCurrent()
        assertEquals("selected", model.state.value.selectedId)
        assertEquals(listOf("first", "second"), model.state.value.comments.map { it.id })
        assertEquals(DriverFeedViewport(3, 12), model.viewport())
        assertEquals(listOf(null, cursor, null, cursor), seen)
        host.close()
        assertEquals(DriverFeedState(), model.state.value)
        assertEquals(DriverFeedViewport(), model.viewport("selected"))
    }

    @Test fun `repeated hide resume restores at most two pages even after deep explicit paging`() = runTest {
        var reads = 0
        val repo = Fake(feedRead = { after ->
            reads++
            val page = (after?.seconds ?: 0) + 1
            DriverPage(listOf(post("post-$page")), DriverCursor(page, 0, "post-$page"))
        })
        val model = DriverFeedCoordinator(repo, "uid", this)
        model.refresh(); runCurrent()
        repeat(6) { model.more(); runCurrent() }
        assertEquals(7, model.state.value.posts.size)
        repeat(8) {
            val before = reads
            model.pause()
            assertTrue(model.state.value.posts.isEmpty())
            model.resume(); runCurrent()
            assertEquals(DRIVER_RESTORE_MAX_PAGES, reads - before)
            assertEquals(2, model.state.value.posts.size)
            assertNotNull(model.state.value.next)
        }
    }

    @Test fun `bounded restore clamps deep viewport and keeps valid offset`() {
        val state = DriverFeedState(posts = listOf(post("first"), post("second")))
        assertEquals(DriverFeedViewport(1, 0), clampedDriverViewport(state, DriverFeedViewport(100, 37)))
        assertEquals(DriverFeedViewport(1, 37), clampedDriverViewport(state, DriverFeedViewport(1, 37)))
        assertEquals(DriverFeedViewport(2, 0), clampedDriverViewport(
            DriverFeedState(selectedId = "post", post = post("post")), DriverFeedViewport(100, 37)))
    }

    @Test fun `back restores feed pages independently from selected detail`() = runTest {
        val repo = Fake(feedRead = { after -> DriverPage(listOf(post(if (after == null) "first" else "second")),
            if (after == null) cursor else null) }, postRead = { post(it) })
        val model = DriverFeedCoordinator(repo, "uid", this)
        model.refresh(); runCurrent()
        model.more(); runCurrent()
        model.recordViewport(null, 1, 11)
        model.open("first"); runCurrent()
        model.pause(); model.resume(); runCurrent()
        model.back(); runCurrent()
        assertEquals(listOf("first", "second"), model.state.value.posts.map { it.id })
        assertEquals(DriverFeedViewport(1, 11), model.viewport())
    }

    @Test fun `resume rechecks access and never restores blocked content`() = runTest {
        var blocked = false
        val repo = Fake(feedRead = {
            if (blocked) throw DriverFeedException(DriverFeedFailure.RESTRICTED)
            DriverPage(listOf(post("formerly-visible")), cursor)
        })
        val model = DriverFeedCoordinator(repo, "uid", this)
        model.refresh(); runCurrent()
        model.pause()
        blocked = true
        model.resume()
        assertTrue(model.state.value.posts.isEmpty())
        runCurrent()
        assertEquals(DriverFeedFailure.RESTRICTED, model.state.value.failure)
        assertTrue(model.state.value.posts.isEmpty())
    }

    @Test fun `late reply after hide cannot replace reauthorized content`() = runTest {
        val gate = CompletableDeferred<DriverPage<DriverPost>>()
        var reads = 0
        val repo = Fake(feedRead = {
            if (++reads == 1) withContext(NonCancellable) { gate.await() }
            else DriverPage(listOf(post("authorized")), null)
        })
        val model = DriverFeedCoordinator(repo, "uid", this)
        model.refresh(); runCurrent()
        model.pause(); model.resume(); runCurrent()
        gate.complete(DriverPage(listOf(post("old")), cursor)); runCurrent()
        assertEquals("authorized", model.state.value.posts.single().id)
        assertEquals(2, reads)
    }

    @Test fun `account change and unmount retire old owner including uncancellable reply`() = runTest {
        val gate = CompletableDeferred<DriverPage<DriverPost>>()
        val oldRepo = Fake(feedRead = { withContext(NonCancellable) { gate.await() } })
        val oldHost = DriverFeedSessionOwner("old", this)
        val oldModel = oldHost.coordinator("old", oldRepo)
        oldModel.refresh(); runCurrent()
        assertThrows(IllegalArgumentException::class.java) { oldHost.coordinator("new", oldRepo) }
        oldHost.close()
        assertThrows(IllegalStateException::class.java) { oldHost.coordinator("old", oldRepo) }
        val newHost = DriverFeedSessionOwner("new", this)
        val newRepo = Fake(feedRead = { DriverPage(listOf(post("new-account")), null) })
        val newModel = newHost.coordinator("new", newRepo)
        newModel.refresh(); runCurrent()
        gate.complete(DriverPage(listOf(post("private-old")), cursor)); runCurrent()
        assertEquals(DriverFeedState(), oldModel.state.value)
        assertEquals("new-account", newModel.state.value.posts.single().id)
        newHost.close()
        val returningHost = DriverFeedSessionOwner("old", this)
        assertEquals(DriverFeedState(), returningHost.coordinator("old", oldRepo).state.value)
        returningHost.close()
    }

    private class Fake(
        val feedRead: suspend (DriverCursor?) -> DriverPage<DriverPost> = { DriverPage(emptyList(), null) },
        val postRead: suspend (String) -> DriverPost = { throw DriverFeedException(DriverFeedFailure.UNAVAILABLE) },
        val commentsRead: suspend (String, DriverCursor?) -> DriverPage<DriverComment> = { _, _ -> DriverPage(emptyList(), null) },
    ) : DriverFeedRepository {
        override suspend fun feed(uid: String, after: DriverCursor?) = feedRead(after)
        override suspend fun detail(uid: String, id: String, after: DriverCursor?) =
            DriverDetailPage(postRead(id), commentsRead(id, after))
        override fun invalidations(uid: String, threadId: String?) = emptyFlow<Unit>()
    }
}
