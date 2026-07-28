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

    /**
     * Retired: the garage is no longer a sub-route — it renders directly on the
     * [ShellTab.Garage] tab, so the user's cars and "Add vehicle" are visible on
     * landing instead of behind a hub's "Cars" button.
     *
     * The constant is retained because two things still produce it: the welcome
     * flow's "Add a car" CTA, and `rememberSaveable` state persisted by an older
     * build (dropping the constant would throw on restore). The route host
     * handles both by switching to the Garage tab. Do not reuse.
     */
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
    /** The chat hub (Community / Convoys / Friends + Notifications), opened from the map chat bubble. */
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
    /** The standalone "Saved places" management screen, reached from Settings. */
    SavedPlaces,
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

/**
 * What is drawn over the shell's single map surface.
 *
 * The map is composed once for the whole signed-in shell and never disposed, so
 * "which page is the user on" is, from the map's point of view, only ever this
 * question: can they see it, and can they touch it? Two different answers hang
 * off that — whether the surface stays live (`MapSurface.setActive`) and whether
 * the map home's chrome stands down — and they are NOT the same answer, which is
 * exactly why this is one enum and not a pair of booleans that can drift.
 */
enum class MapCover {
    /** Nothing over it: the map home. Visible, live, interactive. */
    None,

    /**
     * Chrome over a map the user can still see — the address search, and the
     * translucent History / Social / Garage panels. The surface stays LIVE
     * (they are all looking at the map through it) but the map home's own chrome
     * stands down, because it is not the page in front any more.
     */
    Transparent,

    /**
     * The map is hidden entirely (a full-screen route, turn-by-turn). Nothing to
     * see, so the surface is stood down.
     */
    Opaque,
}

/**
 * The tabs whose page renders as a TRANSLUCENT panel over the live map
 * ([MapCover.Transparent]) rather than as an opaque page that hides it.
 *
 * History, Social and Garage are overlays now — a bottom-anchored card with a
 * strip of live map above it and a drag handle to pull it away — so the map
 * behind them is genuinely on screen and must keep rendering.
 */
val TRANSLUCENT_PANEL_TABS: Set<ShellTab> =
    setOf(ShellTab.History, ShellTab.Social, ShellTab.Garage)

/**
 * The shell's full-screen sub-routes form a back-stack: the [current] route on
 * top, with its ancestors in [parents] (nearest parent last). Modelled as pure
 * data — no Compose — so the "Back pops ONE level to the parent, not to the
 * map" rule is assertable in a JVM unit test.
 *
 * @property parents the routes stacked below [current] (its ancestors). Empty
 *   whenever [current] is null.
 * @property current the route now on top, or null when nothing is open (the
 *   shell then falls through to its tab/exit Back rules).
 */
data class ShellRouteStack(
    val parents: List<ShellRoute>,
    val current: ShellRoute?,
)

object ShellNavigation {
    /**
     * Resolves a system-Back press given the current [tab] and open [route].
     * An open route always closes first — which, with a route back-stack, means
     * popping ONE level (see [popRoute]); otherwise a non-Map tab returns to
     * Map; and Map with nothing open exits.
     */
    fun onBack(tab: ShellTab, route: ShellRoute?): ShellBackResult =
        when {
            route != null -> ShellBackResult.CloseRoute
            tab != ShellTab.Map -> ShellBackResult.GoToMapTab
            else -> ShellBackResult.Exit
        }

    /**
     * The parent stack after opening a new route one level deeper. The
     * currently-open [current] route (if any) becomes the new route's parent, so
     * a later Back pops back to it — this is what makes hub → child
     * (Settings → Blocked users) and list → detail (a conversation, a member
     * profile) hierarchical. Opening a route with nothing already open (a
     * top-level entry from the map home or a push tap) adds no parent, so Back
     * from it returns to the map.
     */
    fun pushRoute(parents: List<ShellRoute>, current: ShellRoute?): List<ShellRoute> =
        if (current == null) parents else parents + current

    /**
     * Pops one level off the route back-stack on a Back press. This is the fix
     * for "Back from a sub-menu jumps to the map": backing out of a child route
     * (Settings → Blocked users) must return to its PARENT hub (Settings), which
     * is the top of [parents] — NOT null. Only an empty parent stack yields a
     * null [ShellRouteStack.current] (nothing left open), letting the shell then
     * apply its tab/exit Back rules.
     */
    fun popRoute(parents: List<ShellRoute>): ShellRouteStack =
        if (parents.isEmpty()) {
            ShellRouteStack(parents = emptyList(), current = null)
        } else {
            ShellRouteStack(parents = parents.dropLast(1), current = parents.last())
        }

    /**
     * Resolves what is currently drawn over the map — the SINGLE source of truth
     * for every "the map isn't the thing on screen" decision in the shell
     * (standing the surface down, clearing its semantics, standing the map
     * home's chrome down, gating the chat hub). Everything downstream derives
     * from this one value rather than re-deriving its own condition, so they
     * cannot drift apart as pages are added.
     *
     * Pure and here rather than inline in the shell composable precisely so the
     * "a translucent panel must NOT stand the map down" rule is assertable in a
     * JVM unit test.
     *
     * @param navigating true while full-screen turn-by-turn is running; it
     *   brings its own map, so the shell's is hidden outright.
     * @param navSearchOpen true while the address search draws its chrome over a
     *   map the user is still looking at (it shows the route it just drew, and
     *   the puck).
     */
    fun mapCover(
        tab: ShellTab,
        route: ShellRoute?,
        navigating: Boolean,
        navSearchOpen: Boolean,
    ): MapCover =
        when {
            navigating -> MapCover.Opaque
            navSearchOpen -> MapCover.Transparent
            route != null -> MapCover.Opaque
            // History / Social / Garage are translucent PANELS over the map: the
            // map behind them is visible, so it has to keep rendering its puck
            // and its GPS fixes. Standing it down here would leave a puck-less
            // map showing through the card and in the uncovered strip above it.
            tab in TRANSLUCENT_PANEL_TABS -> MapCover.Transparent
            tab != ShellTab.Map -> MapCover.Opaque
            else -> MapCover.None
        }

    /**
     * Whether the chat-hub popup may be shown.
     *
     * The hub is a transparent popup with no dimming scrim: it is designed to
     * float over a live map, and over anything else it is just a card hanging in
     * space. So it needs A MAP in front — which is either the map home
     * ([MapCover.None]) or turn-by-turn, the only other full-screen map in the
     * app.
     *
     * Turn-by-turn was excluded until the two right-side control stacks were made
     * identical; navigation now carries the map home's chat control, and a chat
     * button that cannot open the hub is not the same button. It is admitted by
     * [navigating] rather than by [MapCover.Opaque] alone, because Opaque also
     * covers every full-screen route and non-map tab, where there is no map.
     *
     * Pure, so the rule is one unit-testable expression rather than a condition
     * restated at the render site and again in the auto-close effect.
     */
    fun chatHubAllowed(cover: MapCover, navigating: Boolean): Boolean =
        cover == MapCover.None || (navigating && cover == MapCover.Opaque)
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

/**
 * Which rows the live-share manage sheet ([com.kungsbackacarcommunity.app.shell.LiveSharePopup])
 * shows for a given session state. Pulled out as pure data so the "everything
 * still reachable" guarantee can be unit-tested without Compose.
 *
 * @property showStop the prominent Stop-sharing action (only when a session is
 *   running AND a stop handler is wired — see [LiveManageSheet.actions]).
 * @property showHideNow the immediate privacy escape hatch ("Hide me now").
 * @property showStart the one-tap start action (flag on, not sharing).
 * @property showUnavailableNotice the "live location sharing isn't available
 *   right now" fallback text, shown when starting is blocked by the
 *   LIVE_LOCATION feature flag being OFF. This is a FLAG gate, not a membership
 *   gate — sharing your own position is free — so the notice must not claim a
 *   membership is required.
 * @property showAudienceEntry the "More options" / "Who can see me" row into the
 *   full live-location screen, where the caller sees and manages who can see
 *   them.
 */
data class LiveManageRows(
    val showStop: Boolean,
    val showHideNow: Boolean,
    val showStart: Boolean,
    val showUnavailableNotice: Boolean,
    val showAudienceEntry: Boolean,
)

/**
 * The single source of truth for what the live-share sheet exposes.
 *
 * The same sheet is raised from two places, and [hasStop] is what tells them
 * apart:
 *
 *  - **The bottom bar's STOP control** ([hasStop] = true). This is a STOP sheet:
 *    ending the session is its only action. "Hide me now" and "More options" were
 *    removed from it (Seb, 2026-07) — pressing a stop sign should stop, not open
 *    a menu.
 *  - **Turn-by-turn navigation** ([hasStop] = false), which deliberately wires no
 *    Stop (stopping stays with the map's single stop affordance). It keeps the
 *    privacy escape hatch and the audience screen exactly as before, so neither
 *    capability was lost from the app — only from the stop sheet. They also
 *    remain on the full
 *    [com.kungsbackacarcommunity.app.live.LiveLocationScreen].
 *
 * Expressing it here rather than at the two call sites is what keeps the sheet's
 * contents unit-testable and stops the two surfaces from drifting apart.
 */
object LiveManageSheet {
    /**
     * @param isSharing an active session is running.
     * @param canShareLive the caller may START (the LIVE_LOCATION flag is on).
     * @param hasStop a Stop handler is wired — i.e. this is the bottom bar's STOP
     *   sheet, whose only action is stopping (see the class KDoc). False is
     *   turn-by-turn's reuse: no Stop, but the privacy/audience rows stay.
     */
    fun actions(
        isSharing: Boolean,
        canShareLive: Boolean,
        hasStop: Boolean,
    ): LiveManageRows =
        LiveManageRows(
            // Stop only while a session runs and the caller wired a stop handler.
            showStop = isSharing && hasStop,
            // Hide-me-now is the never-gated privacy escape, offered while sharing
            // — but NOT on the stop sheet, which stops and nothing else.
            showHideNow = isSharing && !hasStop,
            // One-tap start only when idle and the LIVE_LOCATION flag is on.
            showStart = !isSharing && canShareLive,
            // Idle + flag off → explain it's unavailable instead of offering
            // start. This is flag-gated, NOT member-gated (own sharing is free).
            showUnavailableNotice = !isSharing && !canShareLive,
            // "Who can see me" (the full live-location screen) stays reachable in
            // every state of the CONTROLS sheet; the stop sheet drops it along
            // with Hide me now.
            showAudienceEntry = !hasStop,
        )
}
