package com.kungsbackacarcommunity.app.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hand-off point between an incoming map-link Intent and the Compose shell —
 * the map-navigation analogue of `PushNavigator`.
 *
 * When the member picks KCC from Android's "Open with" / default-handler chooser
 * for a `geo:` / `google.navigation:` link, the URI arrives on MainActivity as
 * an ACTION_VIEW Intent, which is outside the shell's composition and may arrive
 * before it exists (a cold start straight into the chooser). MainActivity parses
 * the URI (via [GeoUriParser]) and parks the resulting point or text query here.
 * The authenticated shell opens its existing coordinate preview or place search;
 * a query never picks a result until the member explicitly chooses one.
 *
 * Process-scoped on purpose (the Activity may publish before the shell is there
 * to consume), and [consume] clears it so a configuration change cannot replay a
 * navigation the member already saw.
 */
object MapLinkNavigator {
    private val _pending = MutableStateFlow<GeoUriTarget?>(null)

    /** The map target awaiting the authenticated shell, or null. */
    val pending: StateFlow<GeoUriTarget?> = _pending.asStateFlow()

    /** Parks a target for the shell. A newer link replaces an unconsumed one. */
    fun publish(target: GeoUriTarget) {
        _pending.value = target
    }

    /** Takes the pending target (if any), clearing it so it fires exactly once. */
    fun consume(): GeoUriTarget? = _pending.getAndSet(null)

    /**
     * Drops any pending target. Called on sign-out for symmetry with the other
     * process-scoped hand-offs, so nothing decoded before a session change is
     * carried into the next one.
     */
    fun clear() {
        _pending.value = null
    }

    private fun <T> MutableStateFlow<T>.getAndSet(newValue: T): T {
        while (true) {
            val current = value
            if (compareAndSet(current, newValue)) return current
        }
    }
}
