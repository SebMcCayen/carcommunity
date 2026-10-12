package com.kungsbackacarcommunity.app.feedback

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URI

/**
 * Shared, privileged "open a GitHub issue in the phone browser" helper.
 *
 * LIFTED out of [FeedbackReportRoute] so BOTH the "Report a problem" success
 * window and the "Open tickets" browser share ONE implementation of the
 * github.com-only guard: a single choke point means the two flows cannot drift
 * apart and a future caller cannot accidentally reintroduce a weaker check.
 */

/**
 * Launches [url] in the browser, but ONLY when it is an HTTPS issue URL for
 * this repository on github.com. These are privileged in-app flows, so a malformed or compromised
 * backend/Firestore value must not be able to launch a non-web intent
 * (file:/intent:/javascript: …). Anything that fails validation is silently
 * skipped — the caller's work (the report, the interaction) is already done.
 */
internal fun openGitHubUrl(context: Context, url: String) {
    if (!isGitHubWebUrl(url)) return
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: ActivityNotFoundException) {
        // No browser available — nothing to open; the caller's work already landed.
    }
}

/** True only for an HTTPS issue URL in the canonical carcommunity repository. */
internal fun isGitHubWebUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    if (uri.rawUserInfo != null || uri.port != -1) return false
    if (!uri.host.equals("github.com", ignoreCase = true)) return false
    return ISSUE_PATH.matches(uri.path.orEmpty())
}

private val ISSUE_PATH =
    Regex(
        "^/SebMcCayen/carcommunity/issues/[1-9][0-9]*$",
        RegexOption.IGNORE_CASE,
    )
