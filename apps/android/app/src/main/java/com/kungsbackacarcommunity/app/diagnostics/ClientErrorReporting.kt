package com.kungsbackacarcommunity.app.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * THE entry point for auto-reporting a user-facing error from a composable.
 *
 * ## Why this exists
 *
 * Every user-visible failure surface (an error dialog, an error snackbar, an
 * error state in a list) should file a GitHub issue automatically — a user should
 * never have to report a bug the app already knows about. The transport for that
 * already exists ([FirebaseClientErrorReporter] → the `errors-reportClientError`
 * callable → a deduplicated `auto-error` GitHub issue); this is just the small,
 * shared way to reach it, so each new surface is a two-line change rather than a
 * copy of the wiring.
 *
 * ## How to wire a new surface
 *
 * Report when the surface APPEARS, keyed so it fires once per entry into the
 * error state rather than once per recomposition:
 *
 * ```kotlin
 * val errorReporter = rememberClientErrorReporter()
 * LaunchedEffect(error != null, retryKey) {
 *     if (error != null) {
 *         errorReporter?.report(
 *             feature = "drives.saveDrive",   // stable dot-path, screen.action
 *             message = "Saving a live-session drive failed",
 *             code = error.code,              // a stable status name if you have one
 *         )
 *     }
 * }
 * ```
 *
 * A null reporter means reporting is unavailable (a config-less build) — the
 * `?.` is the whole handling, never a fallback path.
 *
 * ## The rules a call site must honour
 *
 * - **Never break the UX.** [ClientErrorReporter.report] returns immediately and
 *   swallows every outcome; it must never be awaited, retried, or allowed to
 *   change what the user sees. Reporting a failure must not itself fail anything.
 * - **PII is forbidden in [ClientErrorReporter.report].** `message` is
 *   APP-GENERATED text only. No GPS coordinates, no route data, no message or
 *   chat contents, no emails, no uids, no display names, and never the user's own
 *   free text. The public GitHub issue is world-readable; the uid is recorded
 *   only in the admin-only `clientErrorReports` document, by the backend.
 * - **Prefer a `code`.** The backend fingerprint is `feature + (code ?: normalized
 *   message)`, so a stable code is what keeps one fault to one issue. Plumb the
 *   status name out of the repository if the state does not carry one yet.
 * - **Only report genuine FAULTS.** Not empty states, not validation messages,
 *   and not expected business outcomes (e.g. "not a member", "recipient blocked")
 *   — those are the app working correctly, and filing them buys noise plus the
 *   30/hour per-user server budget.
 *
 * ## What you do not have to build
 *
 * Dedup and rate limiting are the BACKEND's job and are already done: one issue
 * per fingerprint (recurrences bump an occurrence tally instead of filing again)
 * plus a 30/hour per-user limit. Do not add client-side throttling on top; just
 * key the effect so a single failure reports once.
 *
 * Pre-authentication failures (sign-in) do NOT belong here — this callable
 * requires a signed-in caller. Use the public `diagnostics-submitReport` path
 * ([DiagnosticsSignInFailureReporter]) for those, and [CrashReporter] for
 * uncaught exceptions.
 *
 * @return the reporter, or null when Firebase is not configured.
 */
@Composable
fun rememberClientErrorReporter(): ClientErrorReporter? {
    val context = LocalContext.current
    return remember(context) { FirebaseClientErrorReporter.createIfAvailable(context) }
}
