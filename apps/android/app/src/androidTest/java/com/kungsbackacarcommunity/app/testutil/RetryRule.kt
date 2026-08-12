package com.kungsbackacarcommunity.app.testutil

import android.util.Log
import org.junit.internal.AssumptionViolatedException
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Re-runs a failing instrumented test up to [maxRetries] EXTRA times, passing as
 * soon as any attempt passes. If every attempt fails, the LAST failure is
 * rethrown, so a genuine regression still turns the job red — this heals
 * transient flakes, it does not mask real failures.
 *
 * Why this instead of the Gradle `org.gradle.test-retry` plugin: that plugin only
 * retries JVM `Test` tasks, not `connectedDebugAndroidTest` (a
 * `DeviceProviderInstrumentTestTask`), so it cannot touch the residual on-device
 * Compose UI suite at all.
 *
 * ORDERING MATTERS. For a Compose UI test this rule must sit OUTSIDE the
 * `ComposeTestRule` so that a retry gets a fresh Activity + compose hierarchy —
 * the exact flake we are healing is "the Activity did not launch / No compose
 * hierarchies found", surrounded by emulator GPU errors ("Failed to find
 * ColorBuffer"). Wrap the compose rule with [around]:
 *
 * ```
 * val composeTestRule = createComposeRule()
 *
 * @get:Rule
 * val rules = RetryRule.around(composeTestRule)
 * ```
 *
 * `RuleChain.outerRule(RetryRule()).around(composeTestRule)` applies RetryRule as
 * the outermost rule, so each retry re-evaluates the compose rule's statement —
 * which relaunches its `ActivityScenario` and rebuilds the compose hierarchy from
 * scratch. A retry therefore starts from a clean Activity, not a half-torn-down
 * one. For a non-Compose device test (no compose rule to wrap) declare the rule
 * on its own: `@get:Rule val retry = RetryRule()`.
 */
class RetryRule(private val maxRetries: Int = DEFAULT_MAX_RETRIES) : TestRule {

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val totalAttempts = maxRetries + 1
                var lastError: Throwable? = null
                for (attempt in 1..totalAttempts) {
                    try {
                        base.evaluate()
                        if (attempt > 1) {
                            Log.w(
                                TAG,
                                "${description.displayName} PASSED on attempt " +
                                    "$attempt/$totalAttempts (self-healed a flake).",
                            )
                        }
                        return
                    } catch (assumption: AssumptionViolatedException) {
                        // An assumption failure means "skip", not "flake" — never
                        // retry it and never convert it into a hard failure.
                        throw assumption
                    } catch (t: Throwable) {
                        lastError = t
                        if (attempt < totalAttempts) {
                            Log.w(
                                TAG,
                                "${description.displayName} FAILED attempt " +
                                    "$attempt/$totalAttempts — retrying. Cause: $t",
                                t,
                            )
                        } else {
                            Log.e(
                                TAG,
                                "${description.displayName} FAILED all " +
                                    "$totalAttempts attempts — reporting as a real failure.",
                                t,
                            )
                        }
                    }
                }
                // All attempts failed: rethrow the last failure so a genuine
                // regression stays red (never swallow it).
                throw lastError
                    ?: IllegalStateException(
                        "RetryRule ran no attempts for ${description.displayName}",
                    )
            }
        }

    companion object {
        private const val TAG = "RetryRule"

        /** Two extra attempts (three total) — one flaky attempt self-heals. */
        const val DEFAULT_MAX_RETRIES = 2

        /**
         * A [RuleChain] with a [RetryRule] OUTSIDE [inner], so a retry re-applies
         * [inner] (e.g. a `ComposeTestRule`) and thus relaunches the Activity /
         * rebuilds the compose hierarchy on each attempt.
         */
        fun around(inner: TestRule, maxRetries: Int = DEFAULT_MAX_RETRIES): RuleChain =
            RuleChain.outerRule(RetryRule(maxRetries)).around(inner)
    }
}
