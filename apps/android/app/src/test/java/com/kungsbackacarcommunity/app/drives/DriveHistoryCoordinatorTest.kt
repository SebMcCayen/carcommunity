package com.kungsbackacarcommunity.app.drives

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the paging + reload + tier-handling of [DriveHistoryCoordinator], the
 * StateFlow that replaced the raw `rides` snapshot listener. Uses a scripted fake
 * [DriveHistoryRepository] so the whole thing runs off-device.
 */
class DriveHistoryCoordinatorTest {

    private fun drive(id: String) =
        SavedDrive(
            rideId = id,
            title = id,
            distanceMeters = 1000.0,
            durationSeconds = 60,
            averageSpeedMetersPerSecond = 10.0,
            startedAtMillis = null,
            endedAtMillis = null,
            createdAtMillis = 1L,
        )

    /** Serves a pre-built queue of pages (or a failure) per call. */
    private class ScriptedRepository(
        private val pages: MutableList<Result<DriveHistoryPage>>,
    ) : DriveHistoryRepository {
        val cursors = mutableListOf<String?>()

        override suspend fun listHistory(cursorRideId: String?, pageSize: Int?): DriveHistoryPage {
            cursors.add(cursorRideId)
            return pages.removeAt(0).getOrThrow()
        }

        override suspend fun fetchStats(monthStartMillis: Long?, monthEndMillis: Long?) =
            throw UnsupportedOperationException("not used here")
    }

    private fun page(
        drives: List<SavedDrive>,
        tier: DriveSubscriptionTier = DriveSubscriptionTier.SUPPORTER,
        hasMore: Boolean = false,
        nextCursor: String? = null,
        hidden: Int? = 0,
    ) = DriveHistoryPage(
        tier = tier,
        drives = drives,
        hasMore = hasMore,
        nextCursorRideId = nextCursor,
        hiddenDriveCount = hidden,
        hasTierRestrictedHistory = hidden?.let { it > 0 },
    )

    @Test
    fun `reload publishes the first page as Loaded with the hidden count`() = runTest {
        val repo =
            ScriptedRepository(
                mutableListOf(
                    Result.success(
                        page(
                            listOf(drive("a"), drive("b")),
                            tier = DriveSubscriptionTier.COMMUNITY,
                            hasMore = false,
                            hidden = 3,
                        ),
                    ),
                ),
            )
        val coordinator = DriveHistoryCoordinator(repo)

        coordinator.reload()

        val loaded = coordinator.state.value as DriveHistoryListState.Loaded
        assertEquals(listOf("a", "b"), loaded.drives.map { it.rideId })
        assertEquals(DriveSubscriptionTier.COMMUNITY, loaded.tier)
        assertEquals(3, loaded.hiddenDriveCount)
        assertFalse(loaded.hasMore)
        assertEquals(listOf<String?>(null), repo.cursors) // first page is cursor-less
    }

    @Test
    fun `a failed first load surfaces Error carrying the callable code`() = runTest {
        val repo =
            ScriptedRepository(
                mutableListOf(Result.failure(DriveHistoryException(code = "PERMISSION_DENIED"))),
            )
        val coordinator = DriveHistoryCoordinator(repo)

        coordinator.reload()

        val error = coordinator.state.value as DriveHistoryListState.Error
        assertEquals("PERMISSION_DENIED", error.code)
    }

    @Test
    fun `loadMore appends the next page, advances the cursor and de-dupes`() = runTest {
        val repo =
            ScriptedRepository(
                mutableListOf(
                    Result.success(page(listOf(drive("a"), drive("b")), hasMore = true, nextCursor = "b")),
                    // Overlapping "b" (a concurrent-write shift) must not duplicate.
                    Result.success(page(listOf(drive("b"), drive("c")), hasMore = false, nextCursor = null, hidden = null)),
                ),
            )
        val coordinator = DriveHistoryCoordinator(repo)

        coordinator.reload()
        coordinator.loadMore()

        val loaded = coordinator.state.value as DriveHistoryListState.Loaded
        assertEquals(listOf("a", "b", "c"), loaded.drives.map { it.rideId })
        assertFalse(loaded.hasMore)
        assertFalse(loaded.loadingMore)
        // The second call carried the first page's nextCursor.
        assertEquals(listOf<String?>(null, "b"), repo.cursors)
    }

    @Test
    fun `loadMore is a no-op when the tier cannot page`() = runTest {
        val repo =
            ScriptedRepository(
                mutableListOf(
                    Result.success(
                        page(listOf(drive("a")), tier = DriveSubscriptionTier.COMMUNITY, hasMore = false),
                    ),
                ),
            )
        val coordinator = DriveHistoryCoordinator(repo)

        coordinator.reload()
        coordinator.loadMore() // hasMore == false → must not call listHistory again

        assertEquals(listOf<String?>(null), repo.cursors)
    }

    @Test
    fun `a failed loadMore keeps the drives and flags loadMoreFailed`() = runTest {
        val repo =
            ScriptedRepository(
                mutableListOf(
                    Result.success(page(listOf(drive("a")), hasMore = true, nextCursor = "a")),
                    Result.failure(DriveHistoryException(code = "UNAVAILABLE")),
                ),
            )
        val coordinator = DriveHistoryCoordinator(repo)

        coordinator.reload()
        coordinator.loadMore()

        val loaded = coordinator.state.value as DriveHistoryListState.Loaded
        assertEquals(listOf("a"), loaded.drives.map { it.rideId }) // list not blanked
        assertFalse(loaded.loadingMore)
        assertTrue(loaded.loadMoreFailed)
        assertTrue(loaded.hasMore) // still offers a retry
    }

    /**
     * The race Copilot flagged: a reload() (tier-change / post-delete) that lands
     * WHILE a loadMore() page request is in flight must win. The stale page must be
     * dropped — never merged onto the pre-reload list, and never allowed to clobber
     * the cursor the reload set.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a reload during an in-flight loadMore discards the stale page`() = runTest {
        // First page (reload #1) resolves immediately; the load-more page (cursor
        // "b") is gated so we can slip a second reload in front of it; reload #2
        // resolves immediately with a DIFFERENT, post-downgrade list.
        val loadMoreGate = CompletableDeferred<Unit>()
        val repo =
            object : DriveHistoryRepository {
                val cursors = mutableListOf<String?>()

                override suspend fun listHistory(
                    cursorRideId: String?,
                    pageSize: Int?,
                ): DriveHistoryPage {
                    cursors.add(cursorRideId)
                    if (cursorRideId != null) {
                        // The load-more append — hold it open until a reload has run.
                        loadMoreGate.await()
                        return page(listOf(drive("b"), drive("c")), hasMore = false, nextCursor = null)
                    }
                    // cursor == null → a (re)load of the first page.
                    return if (cursors.count { it == null } == 1) {
                        page(listOf(drive("a"), drive("b")), hasMore = true, nextCursor = "b")
                    } else {
                        // Post-downgrade: a shorter list and a fresh cursor.
                        page(
                            listOf(drive("x")),
                            tier = DriveSubscriptionTier.COMMUNITY,
                            hasMore = false,
                            nextCursor = null,
                            hidden = 4,
                        )
                    }
                }

                override suspend fun fetchStats(monthStartMillis: Long?, monthEndMillis: Long?) =
                    throw UnsupportedOperationException("not used here")
            }
        val coordinator = DriveHistoryCoordinator(repo)

        coordinator.reload() // [a, b], hasMore, cursor "b"
        val job = launch { coordinator.loadMore() } // suspends on the gate
        runCurrent()
        assertTrue((coordinator.state.value as DriveHistoryListState.Loaded).loadingMore)

        // A reload wins the race while the append is still parked on the gate.
        coordinator.reload() // -> [x], Community, hidden 4

        // Release the stale append; it must be dropped, not merged.
        loadMoreGate.complete(Unit)
        job.join()

        val loaded = coordinator.state.value as DriveHistoryListState.Loaded
        assertEquals(listOf("x"), loaded.drives.map { it.rideId }) // reload result, not [a,b,c]
        assertEquals(DriveSubscriptionTier.COMMUNITY, loaded.tier)
        assertEquals(4, loaded.hiddenDriveCount)
        assertFalse(loaded.loadingMore)
        assertFalse(loaded.hasMore)

        // The dropped page must not have advanced the cursor: a subsequent loadMore
        // is a no-op (hasMore is false after the reload) and issues no new request.
        val requestsBefore = repo.cursors.size
        coordinator.loadMore()
        assertEquals(requestsBefore, repo.cursors.size)
    }
}
