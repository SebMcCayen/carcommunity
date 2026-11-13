package com.kungsbackacarcommunity.app.moderation

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class ChatModerationCleanupTest {
    private val payload = mapOf<String, Any?>("idempotencyKey" to "same-operation", "warn" to true,
        "pause" to mapOf("amount" to 2, "unit" to "days"))
    private fun response(more: Boolean) = mapOf("cleanup" to mapOf("hasMore" to more))

    @Test fun followsAllCleanupPagesUsingExactPayloadAndChecksAccountAroundEveryCall() = runBlocking {
        val events = mutableListOf<String>()
        val calls = mutableListOf<Map<String, Any?>>()
        completeChatModerationCleanup(payload, { events += "account" }, { request ->
            events += "call"; calls += request
            response(calls.size < 3)
        })
        assertEquals(3, calls.size)
        calls.forEach { assertSame(payload, it) }
        assertEquals(List(3) { listOf("account", "call", "account") }.flatten(), events)
    }

    @Test fun boundsWorkAndCanResumeWithSameReceiptOnNextAttempt() = runBlocking {
        var calls = 0
        try {
            completeChatModerationCleanup(payload, {}, { calls++; response(true) })
            fail("Bound must report incomplete cleanup")
        } catch (_: ChatModerationCleanupIncompleteException) { }
        assertEquals(10, calls)
        completeChatModerationCleanup(payload, {}, { request ->
            assertSame(payload, request); calls++; response(false)
        })
        assertEquals(11, calls)
    }

    @Test fun accountSwitchStopsBeforeAnotherRpc() = runBlocking {
        var current = true
        var calls = 0
        try {
            completeChatModerationCleanup(payload, { check(current) }, {
                calls++; current = false; response(true)
            })
            fail("Changed account must abort")
        } catch (_: IllegalStateException) { }
        assertEquals(1, calls)
    }

    @Test fun malformedResponseCannotBeReportedAsComplete() = runBlocking {
        for (invalid in listOf(null, emptyMap<String, Any>(), mapOf("cleanup" to emptyMap<String, Any>()),
            mapOf("cleanup" to mapOf("hasMore" to "false")))) {
            var calls = 0
            try {
                completeChatModerationCleanup(payload, {}, { calls++; invalid })
                fail("Invalid response must fail closed")
            } catch (_: IllegalStateException) { }
            assertEquals(1, calls)
        }
    }

    @Test fun alreadySubmittedCleanupSurvivesOriginalSheetCancellation() = runTest {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        val owner = launch {
            finishSubmittedChatModeration {
                completeChatModerationCleanup(payload, {}, {
                    calls++
                    if (calls == 1) { entered.complete(Unit); finish.await() }
                    response(calls == 1)
                })
            }
        }
        entered.await()
        owner.cancel()
        finish.complete(Unit)
        owner.join()
        assertEquals(2, calls)
    }

    @Test fun completionDeadlineIsBoundedAndRetryableRatherThanLifecycleCancellation() = runTest {
        try {
            finishSubmittedChatModeration(timeoutMillis = 1000L) { delay(2000) }
            fail("The completion deadline must apply even after UI cancellation")
        } catch (error: IOException) {
            // Coroutine stacktrace recovery may copy the IOException and retain
            // the original exception as its cause in Gradle's debug test runtime.
            assertTrue(generateSequence(error as Throwable) { it.cause }
                .any { it is kotlinx.coroutines.TimeoutCancellationException })
        }
    }
}
