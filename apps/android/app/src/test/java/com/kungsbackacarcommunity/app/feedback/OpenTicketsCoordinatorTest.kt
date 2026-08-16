package com.kungsbackacarcommunity.app.feedback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenTicketsCoordinatorTest {

    /** Records interact calls and returns a scripted outcome. */
    private class FakeRepo(
        private val outcome: TicketInteractOutcome = TicketInteractOutcome.POSTED,
        private val throwOnInteract: Exception? = null,
    ) : OpenTicketsRepository {
        val calls = mutableListOf<Triple<Int, TicketInteractionType, String?>>()

        override fun observe(): Flow<OpenTicketsListState> =
            flowOf(OpenTicketsListState.Loaded(emptyList()))

        override suspend fun interact(
            issueNumber: Int,
            type: TicketInteractionType,
            text: String?,
            clientId: String,
        ): TicketInteractOutcome {
            calls.add(Triple(issueNumber, type, text))
            throwOnInteract?.let { throw it }
            return outcome
        }
    }

    @Test
    fun `plus one posted marks done and disables the control`() = runTest {
        val repo = FakeRepo(TicketInteractOutcome.POSTED)
        val coordinator = OpenTicketsCoordinator(repo)

        coordinator.plusOne(7)

        val state = coordinator.stateFor(7)
        assertTrue(state.plusOneDone)
        assertFalse(state.canPlusOne)
        assertNull(state.submitting)
        assertNull(state.error)
        assertEquals(1, repo.calls.size)
        assertEquals(TicketInteractionType.PLUS_ONE, repo.calls.single().second)
    }

    @Test
    fun `plus one already-done also disables and notes it`() = runTest {
        val coordinator = OpenTicketsCoordinator(FakeRepo(TicketInteractOutcome.ALREADY_DONE))

        coordinator.plusOne(7)

        val state = coordinator.stateFor(7)
        assertTrue(state.plusOneDone)
        assertEquals(TicketInteractionError.ALREADY_DONE, state.error)
    }

    @Test
    fun `a rate-limited plus one is retryable`() = runTest {
        val repo = FakeRepo(TicketInteractOutcome.RATE_LIMITED)
        val coordinator = OpenTicketsCoordinator(repo)

        coordinator.plusOne(7)

        val state = coordinator.stateFor(7)
        assertFalse(state.plusOneDone)
        assertTrue(state.canPlusOne) // not done → can try again
        assertEquals(TicketInteractionError.RATE_LIMITED, state.error)
    }

    @Test
    fun `a failed interaction surfaces a generic error and stays retryable`() = runTest {
        val coordinator = OpenTicketsCoordinator(FakeRepo(TicketInteractOutcome.FAILED))

        coordinator.plusOne(7)

        val state = coordinator.stateFor(7)
        assertFalse(state.plusOneDone)
        assertEquals(TicketInteractionError.UNKNOWN, state.error)
    }

    @Test
    fun `a repository exception is treated as a failure`() = runTest {
        val coordinator =
            OpenTicketsCoordinator(FakeRepo(throwOnInteract = IllegalStateException("boom")))

        coordinator.plusOne(7)

        assertEquals(TicketInteractionError.UNKNOWN, coordinator.stateFor(7).error)
    }

    @Test
    fun `a second plus one after done makes no further call`() = runTest {
        val repo = FakeRepo(TicketInteractOutcome.POSTED)
        val coordinator = OpenTicketsCoordinator(repo)

        coordinator.plusOne(7)
        coordinator.plusOne(7)

        assertEquals(1, repo.calls.size)
    }

    @Test
    fun `an empty comment is rejected locally without a call`() = runTest {
        val repo = FakeRepo(TicketInteractOutcome.POSTED)
        val coordinator = OpenTicketsCoordinator(repo)

        coordinator.comment(7, "   ")

        assertEquals(0, repo.calls.size)
        assertEquals(TicketInteractionError.EMPTY_COMMENT, coordinator.stateFor(7).error)
        assertFalse(coordinator.stateFor(7).commentDone)
    }

    @Test
    fun `a comment is bounded and posts the trimmed text`() = runTest {
        val repo = FakeRepo(TicketInteractOutcome.POSTED)
        val coordinator = OpenTicketsCoordinator(repo)

        coordinator.comment(7, "  " + "x".repeat(TicketComments.MAX_COMMENT_LENGTH + 50) + "  ")

        val posted = repo.calls.single()
        assertEquals(TicketInteractionType.COMMENT, posted.second)
        assertEquals(TicketComments.MAX_COMMENT_LENGTH, posted.third!!.length)
        assertTrue(coordinator.stateFor(7).commentDone)
    }

    @Test
    fun `plus one and comment are independent on one ticket`() = runTest {
        val repo = FakeRepo(TicketInteractOutcome.POSTED)
        val coordinator = OpenTicketsCoordinator(repo)

        coordinator.plusOne(7)
        coordinator.comment(7, "Me too, with detail")

        val state = coordinator.stateFor(7)
        assertTrue(state.plusOneDone)
        assertTrue(state.commentDone)
        assertEquals(2, repo.calls.size)
    }

    @Test
    fun `clearError removes an inline error`() = runTest {
        val coordinator = OpenTicketsCoordinator(FakeRepo(TicketInteractOutcome.RATE_LIMITED))

        coordinator.plusOne(7)
        assertEquals(TicketInteractionError.RATE_LIMITED, coordinator.stateFor(7).error)

        coordinator.clearError(7)
        assertNull(coordinator.stateFor(7).error)
    }

    @Test
    fun `client id matches the callable schema`() {
        val id = randomTicketClientId()
        assertTrue(Regex("^[A-Za-z0-9_-]{1,64}$").matches(id))
    }
}
