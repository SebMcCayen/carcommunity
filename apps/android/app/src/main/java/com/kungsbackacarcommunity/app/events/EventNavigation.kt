package com.kungsbackacarcommunity.app.events

import com.kungsbackacarcommunity.app.navigation.LatLng

/**
 * Decides the event detail's Navigate action.
 *
 * The event Navigate button routes through the app's OWN in-app navigate-to-point
 * handoff ([onNavigateToPoint]) — the same "Navigate here" preview a tapped map
 * place or a chat geo-link uses — rather than firing an external ACTION_VIEW at
 * the device's maps app. The external launcher is kept ONLY as a fallback for a
 * build that wires no in-app handoff (a config-less shell), so the button still
 * gives real directions there instead of doing nothing.
 *
 * Pure / Android-free so the routing decision (in-app vs. external, and the
 * coordinates it forwards) is JVM-unit-testable without a Context or a device.
 */
object EventNavigation {
    /**
     * Builds the tap handler for the event detail's Navigate button, or null
     * when there is nothing to navigate to / no way to navigate.
     *
     * @param point the event's pin, or null when it carries no valid coordinate
     *   (Navigate is then not offered at all).
     * @param label a human-readable destination name (the event's place name, or
     *   its title) forwarded to whichever navigation path is used.
     * @param onNavigateToPoint the app's in-app navigate-to-point handoff
     *   ((latitude, longitude, name) -> Unit). The PREFERRED path; non-null in the
     *   real app, where the shell always supplies it.
     * @param onExternalFallback the external-maps handoff, used only when
     *   [onNavigateToPoint] is null. Null hides Navigate entirely in that build.
     */
    fun navigateAction(
        point: LatLng?,
        label: String,
        onNavigateToPoint: ((latitude: Double, longitude: Double, name: String?) -> Unit)?,
        onExternalFallback: (() -> Unit)?,
    ): (() -> Unit)? {
        if (point == null) return null
        return when {
            onNavigateToPoint != null -> {
                { onNavigateToPoint(point.latitude, point.longitude, label) }
            }
            else -> onExternalFallback
        }
    }
}
