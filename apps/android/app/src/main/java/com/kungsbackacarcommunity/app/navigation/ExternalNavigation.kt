package com.kungsbackacarcommunity.app.navigation

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Locale

/**
 * Handoff to the device's maps app for turn-by-turn driving directions.
 *
 * Used as the "Start navigation" behavior in builds that do NOT bundle the Mapbox
 * Navigation SDK (the token-less `noNav` build — see app/build.gradle.kts
 * `navSdkEnabled`), so the route preview's Start button still gives the user real
 * turn-by-turn guidance instead of a dead "navigation unavailable" panel. Builds
 * that DO bundle the SDK use the in-app [turnbyturn.TurnByTurnNavScreen] instead.
 *
 * The URI builders are pure/locale-independent so they are JVM-unit-testable; the
 * [launch] glue (Intent + startActivity) is verified on device.
 */
object ExternalNavigation {
    /**
     * A `google.navigation:` URI that opens Google Maps directly in driving
     * turn-by-turn mode to [destination]. Coordinates use a locale-independent
     * dot decimal separator.
     */
    fun navigationUri(destination: LatLng): String {
        val lat = fmt(destination.latitude)
        val lng = fmt(destination.longitude)
        return "google.navigation:q=$lat,$lng&mode=d"
    }

    /**
     * A generic `geo:` URI (a labelled pin) for any maps app, used as the
     * fallback when no app handles the Google-Maps navigation URI.
     */
    fun geoUri(destination: LatLng, label: String): String {
        val lat = fmt(destination.latitude)
        val lng = fmt(destination.longitude)
        val encodedLabel = Uri.encode(label)
        return "geo:0,0?q=$lat,$lng($encodedLabel)"
    }

    /**
     * Launches the device's maps app for driving directions to [destination],
     * preferring Google Maps turn-by-turn and falling back to a generic `geo:`
     * pin. Invokes [onUnavailable] if neither can be handled (no maps app).
     */
    fun launch(
        context: Context,
        destination: LatLng,
        label: String,
        onUnavailable: () -> Unit,
    ) {
        // A non-Activity context (application/service) has no task to launch into,
        // so starting an Activity from it without FLAG_ACTIVITY_NEW_TASK throws an
        // AndroidRuntimeException. Add the flag in that case so this helper is safe
        // for any Context; an Activity context keeps the default same-task launch.
        val newTask = context !is Activity

        val navIntent = Intent(Intent.ACTION_VIEW, Uri.parse(navigationUri(destination)))
        if (newTask) navIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(navIntent)
            return
        } catch (_: ActivityNotFoundException) {
            // No Google-Maps navigation handler — fall through to the geo pin.
        }
        val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse(geoUri(destination, label)))
        if (newTask) geoIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(geoIntent)
        } catch (_: ActivityNotFoundException) {
            onUnavailable()
        }
    }

    /** Fixed 6-dp coordinate formatting, locale-independent (always a dot). */
    private fun fmt(value: Double): String = String.format(Locale.US, "%.6f", value)
}
