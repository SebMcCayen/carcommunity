package com.kungsbackacarcommunity.app.testutil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Unit test for [RetryRule]'s Statement logic. It drives the rule with SYNTHETIC
 * [Statement]s (a base that fails a controlled number of times) rather than a
 * real flaky UI test, so it is fully deterministic: no Activity, no emulator GPU,
 * and — crucially — no always-red `@Test` living in the suite. It still exercises
 * the real [RetryRule.apply]/`evaluate` path, including the retry-then-rethrow
 * contract that keeps genuine regressions red.
 */
class RetryRuleTest {

    private val description: Description =
        Description.createTestDescription(RetryRuleTest::class.java, "synthetic")

    /** A base [Statement] that throws for its first [failures] evaluations, then passes. */
    private class FlakyStatement(private val failures: Int) : Statement() {
        var attempts = 0
            private set

        override fun evaluate() {
            attempts += 1
            if (attempts <= failures) {
                throw AssertionError("synthetic flake on attempt $attempts")
            }
        }
    }

    @Test
    fun passesFirstTime_runsExactlyOnce() {
        val base = FlakyStatement(failures = 0)
        RetryRule(maxRetries = 2).apply(base, description).evaluate()
        assertEquals("a passing test must not be retried", 1, base.attempts)
    }

    @Test
    fun failsThenPasses_selfHeals_andReportsGreen() {
        // Fails on attempts 1 and 2, passes on attempt 3 (= 2 retries). evaluate()
        // must return normally — the flake self-healed.
        val base = FlakyStatement(failures = 2)
        RetryRule(maxRetries = 2).apply(base, description).evaluate()
        assertEquals("must retry until the passing attempt", 3, base.attempts)
    }

    @Test
    fun exhaustsRetries_stillFails_soRegressionsAreNotMasked() {
        // Fails every attempt (3 total): the rule must rethrow rather than swallow.
        val base = FlakyStatement(failures = Int.MAX_VALUE)
        val error =
            assertThrows(AssertionError::class.java) {
                RetryRule(maxRetries = 2).apply(base, description).evaluate()
            }
        assertEquals("must exhaust all attempts before giving up", 3, base.attempts)
        assertTrue(
            "the surfaced failure must be the real assertion, not a wrapper",
            error.message?.contains("synthetic flake") == true,
        )
    }

    @Test
    fun rethrowsTheLastFailure_notAnEarlierOne() {
        // Distinct errors per attempt; the caller must see the LAST one.
        val thrown = mutableListOf<Throwable>()
        val base =
            object : Statement() {
                var attempts = 0

                override fun evaluate() {
                    attempts += 1
                    val e = AssertionError("failure #$attempts")
                    thrown += e
                    throw e
                }
            }
        val surfaced =
            assertThrows(AssertionError::class.java) {
                RetryRule(maxRetries = 2).apply(base, description).evaluate()
            }
        assertSame("the LAST failure must be rethrown", thrown.last(), surfaced)
    }

    @Test
    fun zeroRetries_runsOnce_andRethrows() {
        // maxRetries = 0 means no extra attempts: a single run, failure surfaced.
        val base = FlakyStatement(failures = Int.MAX_VALUE)
        assertThrows(AssertionError::class.java) {
            RetryRule(maxRetries = 0).apply(base, description).evaluate()
        }
        assertEquals("zero retries means exactly one attempt", 1, base.attempts)
    }
}
