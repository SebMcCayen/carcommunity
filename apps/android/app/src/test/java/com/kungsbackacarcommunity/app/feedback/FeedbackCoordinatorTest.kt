package com.kungsbackacarcommunity.app.feedback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackCoordinatorTest {

    private class FakeRepo(
        private val result: FeedbackSubmitResult =
            FeedbackSubmitResult("rep_1", null, issueNumber = null, created = false),
        private val failWith: Exception? = null,
    ) : FeedbackRepository {
        var submits = 0

        override suspend fun report(input: FeedbackReportInput): FeedbackSubmitResult {
            failWith?.let { throw it }
            submits++
            return result
        }
    }

    private val input = FeedbackReportInput("Map", "It broke", "1.2.3", "Android 14", "Pixel 8")

    @Test
    fun `success captured without an issue reports Done with null url`() = runTest {
        val repo = FakeRepo(FeedbackSubmitResult("rep_1", null, issueNumber = null, created = false))
        val coordinator = FeedbackCoordinator(repo)
        coordinator.submit(input)
        assertEquals(1, repo.submits)
        // The submitted summary ("Map") is carried into Done for the thank-you window.
        assertEquals(
            FeedbackStatus.Done(issueUrl = null, issueNumber = null, summary = "Map"),
            coordinator.status.value,
        )
    }

    @Test
    fun `success with a created issue surfaces the url and number`() = runTest {
        val url = "https://github.com/SebMcCayen/carcommunity/issues/42"
        val coordinator =
            FeedbackCoordinator(
                FakeRepo(FeedbackSubmitResult("rep_1", url, issueNumber = 42, created = true)),
            )
        coordinator.submit(input)
        assertEquals(
            FeedbackStatus.Done(issueUrl = url, issueNumber = 42, summary = "Map"),
            coordinator.status.value,
        )
    }

    @Test
    fun `a rate-limited failure is distinguished`() = runTest {
        val coordinator = FeedbackCoordinator(FakeRepo(failWith = FeedbackRateLimitedException()))
        coordinator.submit(input)
        assertEquals(FeedbackStatus.Failed(FeedbackFailureReason.RATE_LIMITED), coordinator.status.value)
    }

    @Test
    fun `a generic failure is Failed and can reset`() = runTest {
        val coordinator = FeedbackCoordinator(FakeRepo(failWith = IllegalStateException("boom")))
        coordinator.submit(input)
        assertEquals(FeedbackStatus.Failed(FeedbackFailureReason.UNKNOWN), coordinator.status.value)
        coordinator.reset()
        assertEquals(FeedbackStatus.Idle, coordinator.status.value)
    }

    @Test
    fun `an unauthenticated failure keeps its diagnosed reason`() = runTest {
        val coordinator =
            FeedbackCoordinator(
                FakeRepo(
                    failWith =
                        FeedbackUnauthenticatedException(
                            FeedbackFailureReason.SERVICE_NOT_INVOCABLE,
                            cause = null,
                        ),
                ),
            )
        coordinator.submit(input)
        assertEquals(
            FeedbackStatus.Failed(FeedbackFailureReason.SERVICE_NOT_INVOCABLE),
            coordinator.status.value,
        )
    }

    @Test
    fun `cancellation is rethrown and leaves Idle`() = runTest {
        val coordinator = FeedbackCoordinator(FakeRepo(failWith = CancellationException("c")))
        var rethrown = false
        try {
            coordinator.submit(input)
        } catch (c: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
        assertEquals(FeedbackStatus.Idle, coordinator.status.value)
    }
}
