package com.kungsbackacarcommunity.app.usersearch

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Timing and staleness behaviour of the typeahead, on VIRTUAL time — the debounce
 * and the "results match the latest keystrokes" guarantee are the two properties
 * a user notices when they break, and neither is observable from a screenshot.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserSearchCoordinatorTest {

    private val debounce = UserSearchCoordinator.DEFAULT_DEBOUNCE_MILLIS

    /** Records every query it is asked for and answers them immediately. */
    private class RecordingRepo(
        private val answer: (String) -> UserSearchOutcome = {
            UserSearchOutcome.Loaded(listOf(MemberSearchResult("uid-$it", it, null)))
        },
    ) : UserSearchRepository {
        val queries = mutableListOf<String>()

        override suspend fun search(query: String): UserSearchOutcome {
            queries += query
            return answer(query)
        }
    }

    @Test
    fun `a burst of keystrokes issues exactly one search`() = runTest {
        val repo = RecordingRepo()
        val coordinator = UserSearchCoordinator(repo, TestScope(testScheduler), debounce)

        // Typing "gust" one character at a time, faster than the debounce.
        listOf("g", "gu", "gus", "gust").forEach {
            coordinator.onQueryChanged(it)
            advanceTimeBy(debounce / 4)
        }
        advanceUntilIdle()

        assertEquals(listOf("gust"), repo.queries)
    }

    @Test
    fun `nothing is sent before the debounce elapses`() = runTest {
        val repo = RecordingRepo()
        val coordinator = UserSearchCoordinator(repo, TestScope(testScheduler), debounce)

        coordinator.onQueryChanged("gt")
        advanceTimeBy(debounce - 1)
        assertTrue("no call may go out inside the debounce window", repo.queries.isEmpty())

        advanceUntilIdle()
        assertEquals(listOf("gt"), repo.queries)
    }

    @Test
    fun `a query below the minimum never reaches the backend`() = runTest {
        val repo = RecordingRepo()
        val coordinator = UserSearchCoordinator(repo, TestScope(testScheduler), debounce)

        coordinator.onQueryChanged("g")
        advanceUntilIdle()

        assertTrue(repo.queries.isEmpty())
        assertEquals(UserSearchState.TooShort, coordinator.state.value)
    }

    @Test
    fun `clearing the field returns to Idle without searching`() = runTest {
        val repo = RecordingRepo()
        val coordinator = UserSearchCoordinator(repo, TestScope(testScheduler), debounce)

        coordinator.onQueryChanged("gt")
        advanceUntilIdle()
        coordinator.onQueryChanged("")
        advanceUntilIdle()

        assertEquals(UserSearchState.Idle, coordinator.state.value)
        assertEquals(listOf("gt"), repo.queries)
    }

    @Test
    fun `an edit that cannot change the result does not refire`() = runTest {
        val repo = RecordingRepo()
        val coordinator = UserSearchCoordinator(repo, TestScope(testScheduler), debounce)

        coordinator.onQueryChanged("gt")
        advanceUntilIdle()
        // Same key in normalized space — trailing space, then a case flip.
        coordinator.onQueryChanged("gt ")
        coordinator.onQueryChanged("GT")
        advanceUntilIdle()

        assertEquals(listOf("gt"), repo.queries)
        // Crucially the earlier results are still on screen: a no-op edit must
        // not cancel the settled state into a permanent spinner.
        assertTrue(coordinator.state.value is UserSearchState.Results)
    }

    @Test
    fun `a slow earlier search can never overwrite a newer one`() = runTest {
        // THE typeahead bug this class exists to prevent: "gt" is slow, "gt86"
        // is fast, and the stale answer lands last and repaints the list with
        // suggestions for a query the user has already moved past.
        val slow = CompletableDeferred<UserSearchOutcome>()
        val repo =
            object : UserSearchRepository {
                val queries = mutableListOf<String>()

                override suspend fun search(query: String): UserSearchOutcome {
                    queries += query
                    return if (query == "gt") {
                        slow.await()
                    } else {
                        UserSearchOutcome.Loaded(listOf(MemberSearchResult("fresh", query, null)))
                    }
                }
            }
        val coordinator = UserSearchCoordinator(repo, TestScope(testScheduler), debounce)

        coordinator.onQueryChanged("gt")
        advanceTimeBy(debounce + 1)
        runCurrent()
        // "gt" is now in flight and hanging. The user keeps typing.
        coordinator.onQueryChanged("gt86")
        advanceUntilIdle()

        // The fresh answer is on screen…
        assertEquals(
            listOf(MemberSearchResult("fresh", "gt86", null)),
            (coordinator.state.value as UserSearchState.Results).members,
        )

        // …and the stale one, resolving late, must change nothing.
        slow.complete(UserSearchOutcome.Loaded(listOf(MemberSearchResult("stale", "gt", null))))
        advanceUntilIdle()
        assertEquals(
            listOf(MemberSearchResult("fresh", "gt86", null)),
            (coordinator.state.value as UserSearchState.Results).members,
        )
    }

    @Test
    fun `previous results stay visible while the next search runs`() = runTest {
        val repo = RecordingRepo()
        val coordinator = UserSearchCoordinator(repo, TestScope(testScheduler), debounce)

        coordinator.onQueryChanged("gt")
        advanceUntilIdle()
        val settled = (coordinator.state.value as UserSearchState.Results).members

        coordinator.onQueryChanged("gt8")
        // Inside the debounce: still Searching, still showing the old rows, so
        // the list does not blank out under a finger already moving to tap one.
        val searching = coordinator.state.value as UserSearchState.Searching
        assertEquals(settled, searching.previous)
    }

    @Test
    fun `an empty result set is an Empty state, not an error`() = runTest {
        val repo = RecordingRepo { UserSearchOutcome.Loaded(emptyList()) }
        val coordinator = UserSearchCoordinator(repo, TestScope(testScheduler), debounce)

        coordinator.onQueryChanged("nobody")
        advanceUntilIdle()

        assertEquals(UserSearchState.Empty, coordinator.state.value)
    }

    @Test
    fun `a backend failure surfaces its mapped category`() = runTest {
        val repo = RecordingRepo { UserSearchOutcome.Failed(UserSearchError.RateLimited) }
        val coordinator = UserSearchCoordinator(repo, TestScope(testScheduler), debounce)

        coordinator.onQueryChanged("gt")
        advanceUntilIdle()

        assertEquals(UserSearchState.Failed(UserSearchError.RateLimited), coordinator.state.value)
    }

    @Test
    fun `a backend TooShort verdict shows the hint rather than an error`() = runTest {
        // Reachable when an older client's minimum disagrees with the backend's.
        val repo = RecordingRepo { UserSearchOutcome.TooShort }
        val coordinator = UserSearchCoordinator(repo, TestScope(testScheduler), debounce)

        coordinator.onQueryChanged("gt")
        advanceUntilIdle()

        assertEquals(UserSearchState.TooShort, coordinator.state.value)
    }

    @Test
    fun `a thrown repository failure degrades to Generic instead of crashing`() = runTest {
        val repo =
            object : UserSearchRepository {
                override suspend fun search(query: String): UserSearchOutcome =
                    throw IllegalStateException("boom")
            }
        val coordinator = UserSearchCoordinator(repo, TestScope(testScheduler), debounce)

        coordinator.onQueryChanged("gt")
        advanceUntilIdle()

        assertEquals(UserSearchState.Failed(UserSearchError.Generic), coordinator.state.value)
    }

    @Test
    fun `clear cancels a pending search and resets to Idle`() = runTest {
        val repo = RecordingRepo()
        val coordinator = UserSearchCoordinator(repo, TestScope(testScheduler), debounce)

        coordinator.onQueryChanged("gt")
        coordinator.clear()
        advanceUntilIdle()

        assertEquals(UserSearchState.Idle, coordinator.state.value)
        assertTrue("a cleared field must not still fire its debounced call", repo.queries.isEmpty())
    }
}
