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
 * Sub-routes reachable from a tab hub or the map-home profile menu. Rendered
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
    /** Convoy management (list / create / detail / ended summary). */
    Convoys,
    /**
     * Another member's read-only public profile (name/avatar/bio + their garage
     * cars and, when readable, awards). The target member's uid is carried
     * alongside the route (payload-free enum), set when a friend row is tapped.
     */
    MemberProfile,
    /** The DM inbox (conversation list). */
    Conversations,
    /** A single 1:1 DM thread (target carried alongside the route). */
    Chat,
    /** The 3-channel chat hub (Community / Convoys / Friends + Notifications), opened from the map chat bubble. */
    ChatHub,
    Badges,
    Blocked,
    Points,
    PartnerApplication,
    Billboards,
    AccountDeletion,
    PartnerStats,
    Feedback,
    Subscription,
    Settings,
    /** The "Vad är nytt" changelog page, reached from Settings. */
    WhatsNew,

    /**
     * Retired: the old full-screen "More"/profile hub that the top-bar avatar
     * used to open. The map-home profile menu (a transparent popup) replaced it,
     * so nothing in the new UI navigates here and it is deliberately absent from
     * the profile menu. The constant is retained ONLY for backward-compatible
     * state restore: `rememberSaveable` persists `ShellRoute` by name, so an
     * older build could have saved `route = More`; dropping the constant would
     * throw during restore. The route host in AuthenticatedApp handles it with a
     * migration-safe branch that returns to the home hub instead of rendering
     * blank. Do not reuse.
     */
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
