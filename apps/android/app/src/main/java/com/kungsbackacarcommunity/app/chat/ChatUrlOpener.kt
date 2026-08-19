package com.kungsbackacarcommunity.app.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Opens a chat-embedded web link in the phone's DEFAULT browser.
 *
 * A URL detected by [WebLinks] (http/https only) is opened with a plain
 * `ACTION_VIEW` intent and NO explicit package, so the OS resolves the user's
 * default browser (or shows the app chooser) — the app never bundles or picks a
 * browser itself. This runs ONLY from an explicit user tap on the styled link span;
 * nothing here is ever invoked automatically.
 *
 * As a defence-in-depth second gate behind [WebLinks]' scheme allowlist, the URL is
 * re-checked here: a scheme that is not `http`/`https` is refused, so even a caller
 * that somehow passes a `tel:`/`intent:`/`javascript:`/`file:` string can never fire
 * an intent for it. When no activity can handle the intent (no browser installed)
 * the [ActivityNotFoundException] is caught and the tap is a no-op rather than a
 * crash.
 */
object ChatUrlOpener {
    private const val TAG = "ChatUrlOpener"

    fun open(context: Context, url: String) {
        // Defence in depth: only ever open http/https, matching WebLinks' allowlist.
        val scheme = Uri.parse(url).scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            Log.w(TAG, "Refusing to open non-web scheme: $scheme")
            return
        }
        // FLAG_ACTIVITY_NEW_TASK so the launch is safe even from a non-Activity
        // Context (application/service): startActivity() throws without it outside an
        // Activity, and it is harmless when the caller IS an Activity (the resolved
        // browser owns its own task regardless).
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // No browser resolves the intent — swallow rather than crash.
            Log.w(TAG, "No activity found to open URL", e)
        }
    }
}
