package com.kungsbackacarcommunity.app.home

/**
 * Pure home-shell presentation logic (Phase 12, slice 1).
 *
 * Kept free of Android/Compose types so it is unit-testable on the JVM
 * (the `./gradlew test` gate). The Compose screen in
 * [com.kungsbackacarcommunity.app.home.HomeScreen] renders the result.
 */
object HomeContent {

    /**
     * The name to greet the signed-in user by, or `null` to fall back to a
     * generic greeting.
     *
     * A Firebase user's `displayName` may be absent or whitespace-only, so
     * blank values collapse to `null`; surrounding whitespace is trimmed.
     */
    fun greetingName(displayName: String?): String? =
        displayName?.trim()?.takeIf { it.isNotEmpty() }
}
