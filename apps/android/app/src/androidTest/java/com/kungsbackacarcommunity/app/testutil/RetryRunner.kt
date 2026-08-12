package com.kungsbackacarcommunity.app.testutil

import android.util.Log
import org.junit.AssumptionViolatedException
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunNotifier
import org.junit.runners.BlockJUnit4ClassRunner
import org.junit.runners.model.FrameworkMethod
import org.junit.runners.model.InitializationError

/**
 * On-device JUnit4 runner that re-runs a failing test method up to [MAX_RETRIES]
 * EXTRA times (three attempts total), passing as soon as any attempt passes. If
 * every attempt fails the LAST failure is reported, so a genuine regression still
 * turns the job red — this heals transient emulator flakes, it does not mask real
 * failures.
 *
 * Why a Runner and not a `TestRule`: a `TestRule` re-evaluates its `Statement`
 * against the SAME test instance, so a retry re-invokes the `@Test` method body on
 * that instance. Tests that use `kotlinx.coroutines.test.runTest {}` then fail with
 * `IllegalStateException: Only a single call to runTest can be performed during one
 * test`. Retrying at the Runner level runs [methodBlock] per attempt, and
 * `methodBlock` builds its statement over a FRESH test instance (via `createTest()`),
 * so `runTest`, coroutine scopes AND every `@Rule` field — including the
 * `ComposeTestRule` / `ActivityScenario` — are rebuilt from scratch each attempt.
 * That is exactly what heals the "Activity did not launch / No compose hierarchies
 * found" flake (surrounded by emulator GPU "Failed to find ColorBuffer" noise)
 * without the single-`runTest` problem.
 *
 * Applies to the residual on-device Compose UI suite (`connectedDebugAndroidTest`),
 * which the Gradle `org.gradle.test-retry` plugin cannot touch (that plugin only
 * retries JVM `Test` tasks, not a `DeviceProviderInstrumentTestTask`). Use it via
 * `@RunWith(RetryRunner::class)`. It extends [BlockJUnit4ClassRunner] rather than
 * the AndroidX class runner because none of these tests use `@UiThreadTest`; the
 * `InstrumentationRegistry` the Compose rules rely on is populated by the
 * `AndroidJUnitRunner` instrumentation, not by the per-class runner.
 */
class RetryRunner
@Throws(InitializationError::class)
constructor(klass: Class<*>) : BlockJUnit4ClassRunner(klass) {

    override fun runChild(method: FrameworkMethod, notifier: RunNotifier) {
        val description = describeChild(method)
        if (isIgnored(method)) {
            notifier.fireTestIgnored(description)
            return
        }

        val totalAttempts = MAX_RETRIES + 1
        var lastFailure: Throwable? = null
        notifier.fireTestStarted(description)
        try {
            for (attempt in 1..totalAttempts) {
                try {
                    // Fresh test instance per attempt (see class KDoc): rebuilds
                    // runTest state, coroutine scopes and every @Rule (the compose
                    // rule relaunches its Activity), so a retry starts clean.
                    methodBlock(method).evaluate()
                    if (attempt > 1) {
                        Log.w(
                            TAG,
                            "${description.displayName} PASSED on attempt " +
                                "$attempt/$totalAttempts (self-healed a flake).",
                        )
                    }
                    lastFailure = null
                    break
                } catch (assumption: AssumptionViolatedException) {
                    // An assumption failure means "skip", not "flake". Honour it as
                    // a skip ONLY when no earlier attempt hard-failed — otherwise a
                    // late, non-deterministic assumption would mask a real failure,
                    // so stop retrying and report that earlier failure below.
                    if (lastFailure == null) {
                        notifier.fireTestAssumptionFailed(Failure(description, assumption))
                        return
                    }
                    break
                } catch (t: Throwable) {
                    lastFailure = t
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
                            "${description.displayName} FAILED all $totalAttempts " +
                                "attempts — reporting as a real failure.",
                            t,
                        )
                    }
                }
            }
            // All attempts failed: report the last failure so a genuine regression
            // stays red (never swallowed).
            lastFailure?.let { notifier.fireTestFailure(Failure(description, it)) }
        } finally {
            notifier.fireTestFinished(description)
        }
    }

    companion object {
        private const val TAG = "RetryRunner"

        /** Two extra attempts (three total) — one flaky attempt self-heals. */
        const val MAX_RETRIES = 2
    }
}
