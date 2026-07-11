package com.kungsbackacarcommunity.app.shell

/**
 * Pure (Android-free) navigation + toggle logic for the map-first shell, so the
 * decisions are JVM-unit-testable without Compose.
 */

/** The five bottom-navigation tabs. [Map] is the default, map-first home. */
enum class ShellTab {
    Map,
    History,
    Create,
    Social,
    Garage,
    ;

    companion object {
        /**
         * The tab shown on first entry (map-first home). Production and tests both
         * reference this so the default isn't tied to enum declaration order.
         */
        val DEFAULT = Map
    }
}

/**
 * Sub-routes reachable from a tab hub or the top-bar "More" menu. Rendered
 * full-screen over the tab shell; each carries its own back affordance that
 * returns to the current tab hub. Enum (not a class) so it survives
 * `rememberSaveable` without a custom Saver.
 */
enum class ShellRoute {
    Profile,
    LiveLocation,
    /** The real Mapbox map (group-drive "show on map" overlay). */
    Map,
    Events,
    CrownHunt,
    Partners,
    Notifications,
    NotificationSettings,
    Garage,
    Friends,
    /** The DM inbox (conversation list). */
    Conversations,
    /** A single 1:1 DM thread (target carried alongside the route). */
    Chat,
    Badges,
    Blocked,
    Points,
    PartnerApplication,
    Billboards,
    AccountDeletion,
    PartnerStats,
    Feedback,
    Subscription,
    More,
}

/** Outcome of a system-Back press in the shell. */
sealed interface ShellBackResult {
    /** A sub-route is open — close it, returning to the current tab hub. */
    data object CloseRoute : ShellBackResult

    /** On a non-Map tab with no route open — return to the Map tab. */
    data object GoToMapTab : ShellBackResult

    /** On the Map tab with no route open — let the system exit the app. */
    data object Exit : ShellBackResult
}

object ShellNavigation {
    /**
     * Resolves a system-Back press given the current [tab] and open [route].
     * An open route always closes first; otherwise a non-Map tab returns to
     * Map; and Map with nothing open exits.
     */
    fun onBack(tab: ShellTab, route: ShellRoute?): ShellBackResult =
        when {
            route != null -> ShellBackResult.CloseRoute
            tab != ShellTab.Map -> ShellBackResult.GoToMapTab
            else -> ShellBackResult.Exit
        }
}

/** What tapping the floating live-location-share toggle should do. */
enum class LiveShareAction {
    /** Start a fresh session (member, wired, not already sharing). */
    Start,

    /** Stop the current session (already sharing). */
    Stop,

    /** Open the live-location screen (not wired, or start not permitted). */
    OpenScreen,
}

object LiveShareToggle {
    /**
     * Decides the toggle action from the observed live-location state.
     *
     * @param isSharing whether an active session is currently sharing.
     * @param canShare whether the caller may start (flag on + active member).
     * @param wired whether the live-location coordinator is available (Firebase
     *   configured). When false the toggle can only open the (informational)
     *   screen.
     */
    fun action(isSharing: Boolean, canShare: Boolean, wired: Boolean): LiveShareAction =
        when {
            !wired -> LiveShareAction.OpenScreen
            isSharing -> LiveShareAction.Stop
            canShare -> LiveShareAction.Start
            else -> LiveShareAction.OpenScreen
        }
}
