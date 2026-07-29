package com.kungsbackacarcommunity.app.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Handoff to this app's Google Play listing, used by the update prompt.
 *
 * Mirrors [com.kungsbackacarcommunity.app.navigation.ExternalNavigation]:
 * the URI builders are pure so they are JVM-unit-testable, and the
 * Intent/startActivity glue prefers the Play app and falls back before it
 * gives up. A device with no Play Store at all (an AOSP build, a sideloaded
 * install, an emulator without Play services) must get a message, never a
 * crash — `startActivity` with no handler throws [ActivityNotFoundException],
 * so both attempts are guarded.
 */
object PlayStoreLink {

    /** `market://` deep link — opens the Play app directly when installed. */
    fun marketUri(applicationId: String): String = "market://details?id=$applicationId"

    /** Web fallback for devices without the Play app (opens a browser). */
    fun webUri(applicationId: String): String =
        "https://play.google.com/store/apps/details?id=$applicationId"

    /**
     * Opens this app's Play listing. Invokes [onUnavailable] if neither the
     * Play app nor a browser can handle it.
     */
    fun open(context: Context, applicationId: String, onUnavailable: () -> Unit) {
        // A non-Activity context (application/service) has no task to launch
        // into, so starting an Activity from it without FLAG_ACTIVITY_NEW_TASK
        // throws. Add the flag in that case so this helper is safe for any
        // Context; an Activity context keeps the default same-task launch.
        val newTask = context !is Activity

        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse(marketUri(applicationId)))
        if (newTask) marketIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(marketIntent)
            return
        } catch (_: ActivityNotFoundException) {
            // No Play app — fall through to the browser link.
        }

        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUri(applicationId)))
        if (newTask) webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(webIntent)
        } catch (_: ActivityNotFoundException) {
            onUnavailable()
        }
    }
}
