// Pure (UI-framework-free) navigation + toggle logic for the map-first shell,
// so the decisions are unit-testable without SwiftUI.
//
// This is the iOS port of the Android app's
// `shell/ShellNav.kt` — the same rules, expressed natively. The two files are
// intentionally parallel: when one platform changes a rule here, port the
// change (and its tests) to the other, per the mobile platform parity
// instructions.

/// The five bottom-navigation tabs. ``map`` is the default, map-first home.
enum ShellTab: CaseIterable, Sendable {
    case map
    case history
    case create
    case social
    case garage

    /// The tab shown on first entry (map-first home). Production and tests both
    /// reference this so the default isn't tied to case declaration order.
    static let defaultTab: ShellTab = .map
}

/// Sub-routes reachable from a tab hub or the map-home profile menu. Rendered
/// full-screen over the tab shell; each carries its own back affordance that
/// returns to the current tab hub.
///
/// Android's `ShellRoute` additionally retains retired constants (`Garage`,
/// `Badges`, `More`) purely so `rememberSaveable` can restore state persisted
/// by older builds. iOS has never shipped, so no such legacy constants exist
/// here — do not add them.
enum ShellRoute: Sendable {
    case profile
    case liveLocation
    /// The real Mapbox map (group-drive "show on map" overlay).
    case map
    case events
    case crownHunt
    /// The social leaderboard (all-time / this-month podiums per category).
    case leaderboard
    case partners
    case notifications
    case notificationSettings
    case friends
    /// Convoy management (list / create / detail / ended summary).
    case convoys
    /// Another member's read-only public profile. The target member's uid is
    /// carried alongside the route (payload-free enum), set when a friend row
    /// is tapped.
    case memberProfile
    /// The DM inbox (conversation list).
    case conversations
    /// A single 1:1 DM thread (target carried alongside the route).
    case chat
    /// The chat hub (Community / Convoys / Friends + Notifications), opened
    /// from the map chat bubble.
    case chatHub
    case blocked
    /// The full Kronpoäng ledger. Opened as a CHILD of the profile route, so
    /// Back returns to the profile.
    case points
    case partnerApplication
    /// Digital billboards. Kept wired on Android but intentionally unreferenced
    /// from the UI (billboards are meant to be map pins); mirrored here for
    /// route parity.
    case billboards
    case accountDeletion
    case partnerStats
    case feedback
    /// The "Open tickets" browser, opened from the Feedback screen.
    case openTickets
    case subscription
    case settings
    /// The standalone "Saved places" management screen, reached from Settings.
    case savedPlaces
    /// The "Vad är nytt" changelog page, reached from Settings.
    case whatsNew
}

/// Outcome of a system-Back gesture in the shell.
enum ShellBackResult: Sendable {
    /// A sub-route is open — close it, returning to the current tab hub.
    case closeRoute
    /// On a non-Map tab with no route open — return to the Map tab.
    case goToMapTab
    /// On the Map tab with no route open — nothing for the shell to do.
    /// (Android maps this to letting the system exit the app; iOS has no
    /// app-exit gesture, so the shell simply stays on the map home.)
    case exit
}

/// What is drawn over the shell's single map surface.
///
/// The map is composed once for the whole signed-in shell and never disposed,
/// so "which page is the user on" is, from the map's point of view, only ever
/// this question: can they see it, and can they touch it? Two different
/// answers hang off that — whether the surface stays live and whether the map
/// home's chrome stands down — and they are NOT the same answer, which is
/// exactly why this is one enum and not a pair of booleans that can drift.
enum MapCover: Sendable {
    /// Nothing over it: the map home. Visible, live, interactive.
    case none
    /// Chrome over a map the user can still see — the address search, and the
    /// translucent History / Social / Garage panels. The surface stays LIVE
    /// but the map home's own chrome stands down.
    case transparent
    /// The map is hidden entirely (a full-screen route, turn-by-turn).
    /// Nothing to see, so the surface is stood down.
    case opaque
}

/// The tabs whose page renders as a TRANSLUCENT panel over the live map
/// (``MapCover/transparent``) rather than as an opaque page that hides it.
let translucentPanelTabs: Set<ShellTab> = [.history, .social, .garage]

/// The shell's full-screen sub-routes form a back-stack: the ``current`` route
/// on top, with its ancestors in ``parents`` (nearest parent last). Modelled as
/// pure data — no SwiftUI — so the "Back pops ONE level to the parent, not to
/// the map" rule is assertable in a unit test.
struct ShellRouteStack: Equatable, Sendable {
    /// The routes stacked below ``current`` (its ancestors). Empty whenever
    /// ``current`` is nil.
    let parents: [ShellRoute]
    /// The route now on top, or nil when nothing is open (the shell then falls
    /// through to its tab Back rules).
    let current: ShellRoute?
}

extension ShellRouteStack {
    /// The closed stack: nothing open, the shell shows its tab hubs.
    static let empty = ShellRouteStack(parents: [], current: nil)

    /// The stack after opening `route` one level deeper. Thin composition of
    /// ``ShellNavigation/pushRoute(parents:current:)`` with the new top, so
    /// the view layer holds ONE value and never assembles parents/current by
    /// hand (where the two could drift apart).
    func opening(_ route: ShellRoute) -> ShellRouteStack {
        ShellRouteStack(
            parents: ShellNavigation.pushRoute(parents: parents, current: current),
            current: route
        )
    }

    /// The stack after a Back gesture pops one level — the counterpart of
    /// ``opening(_:)``, delegating to ``ShellNavigation/popRoute(parents:)``.
    func poppingOne() -> ShellRouteStack {
        ShellNavigation.popRoute(parents: parents)
    }
}

enum ShellNavigation {
    /// Resolves a system-Back gesture given the current `tab` and open `route`.
    /// An open route always closes first — which, with a route back-stack,
    /// means popping ONE level (see ``popRoute(parents:)``); otherwise a
    /// non-Map tab returns to Map; and Map with nothing open resolves to
    /// ``ShellBackResult/exit``.
    static func onBack(tab: ShellTab, route: ShellRoute?) -> ShellBackResult {
        if route != nil { return .closeRoute }
        if tab != .map { return .goToMapTab }
        return .exit
    }

    /// The parent stack after opening a new route one level deeper. The
    /// currently-open `current` route (if any) becomes the new route's parent,
    /// so a later Back pops back to it — this is what makes hub → child
    /// (Settings → Blocked users) and list → detail (a conversation, a member
    /// profile) hierarchical. Opening a route with nothing already open (a
    /// top-level entry from the map home or a push tap) adds no parent, so
    /// Back from it returns to the map.
    static func pushRoute(parents: [ShellRoute], current: ShellRoute?) -> [ShellRoute] {
        guard let current else { return parents }
        return parents + [current]
    }

    /// Pops one level off the route back-stack on a Back gesture. Backing out
    /// of a child route (Settings → Blocked users) must return to its PARENT
    /// hub (Settings), which is the top of `parents` — NOT nil. Only an empty
    /// parent stack yields a nil ``ShellRouteStack/current`` (nothing left
    /// open), letting the shell then apply its tab Back rules.
    static func popRoute(parents: [ShellRoute]) -> ShellRouteStack {
        guard let last = parents.last else {
            return ShellRouteStack(parents: [], current: nil)
        }
        return ShellRouteStack(parents: Array(parents.dropLast()), current: last)
    }

    /// Resolves what is currently drawn over the map — the SINGLE source of
    /// truth for every "the map isn't the thing on screen" decision in the
    /// shell. Everything downstream derives from this one value rather than
    /// re-deriving its own condition, so they cannot drift apart as pages are
    /// added.
    ///
    /// - Parameters:
    ///   - navigating: true while full-screen turn-by-turn is running; it
    ///     brings its own map, so the shell's is hidden outright. (Turn-by-turn
    ///     is not in iOS v1 — see ADR-002 — but the rule is ported whole so the
    ///     two platforms share one decision table.)
    ///   - navSearchOpen: true while the address search draws its chrome over a
    ///     map the user is still looking at.
    static func mapCover(
        tab: ShellTab,
        route: ShellRoute?,
        navigating: Bool,
        navSearchOpen: Bool
    ) -> MapCover {
        if navigating { return .opaque }
        if navSearchOpen { return .transparent }
        if route != nil { return .opaque }
        // History / Social / Garage are translucent PANELS over the map: the
        // map behind them is visible, so it has to keep rendering its puck and
        // its GPS fixes. Standing it down here would leave a puck-less map
        // showing through the card and in the uncovered strip above it.
        if translucentPanelTabs.contains(tab) { return .transparent }
        if tab != .map { return .opaque }
        return .none
    }

    /// Whether the chat-hub popup may be shown.
    ///
    /// The hub is a transparent popup with no dimming scrim: it is designed to
    /// float over a live map. So it needs A MAP in front — which is either the
    /// map home (``MapCover/none``) or turn-by-turn, the only other
    /// full-screen map in the app. It is admitted by `navigating` rather than
    /// by ``MapCover/opaque`` alone, because opaque also covers every
    /// full-screen route and non-map tab, where there is no map.
    static func chatHubAllowed(cover: MapCover, navigating: Bool) -> Bool {
        cover == .none || (navigating && cover == .opaque)
    }
}

/// What tapping the floating live-location-share toggle should do.
enum LiveShareAction: Sendable {
    /// Start a fresh session (member, wired, not already sharing).
    case start
    /// Stop the current session (already sharing).
    case stop
    /// Open the live-location screen (not wired, or start not permitted).
    case openScreen
}

enum LiveShareToggle {
    /// Decides the toggle action from the observed live-location state.
    ///
    /// - Parameters:
    ///   - isSharing: whether an active session is currently sharing.
    ///   - canShare: whether the caller may start (flag on + active member).
    ///   - wired: whether the live-location coordinator is available (Firebase
    ///     configured). When false the toggle can only open the
    ///     (informational) screen.
    static func action(isSharing: Bool, canShare: Bool, wired: Bool) -> LiveShareAction {
        if !wired { return .openScreen }
        if isSharing { return .stop }
        if canShare { return .start }
        return .openScreen
    }
}

/// Which rows the live-share manage sheet shows for a given session state.
/// Pulled out as pure data so the "everything still reachable" guarantee can
/// be unit-tested without SwiftUI.
struct LiveManageRows: Equatable, Sendable {
    /// The prominent Stop-sharing action (only when a session is running AND a
    /// stop handler is wired — see ``LiveManageSheet/actions(isSharing:canShareLive:hasStop:)``).
    let showStop: Bool
    /// The immediate privacy escape hatch ("Hide me now").
    let showHideNow: Bool
    /// The one-tap start action (flag on, not sharing).
    let showStart: Bool
    /// The "live location sharing isn't available right now" fallback text,
    /// shown when starting is blocked by the LIVE_LOCATION feature flag being
    /// OFF. This is a FLAG gate, not a membership gate — sharing your own
    /// position is free — so the notice must not claim a membership is
    /// required.
    let showUnavailableNotice: Bool
    /// The "More options" / "Who can see me" row into the full live-location
    /// screen.
    let showAudienceEntry: Bool
}

/// The single source of truth for what the live-share sheet exposes.
///
/// The same sheet is raised from two places, and `hasStop` is what tells them
/// apart: the bottom bar's STOP control (`hasStop` = true — stopping is its
/// only action), and turn-by-turn navigation (`hasStop` = false — no Stop, but
/// the privacy escape hatch and the audience screen stay).
enum LiveManageSheet {
    /// - Parameters:
    ///   - isSharing: an active session is running.
    ///   - canShareLive: the caller may START (the LIVE_LOCATION flag is on).
    ///   - hasStop: a Stop handler is wired — i.e. this is the bottom bar's
    ///     STOP sheet, whose only action is stopping.
    static func actions(
        isSharing: Bool,
        canShareLive: Bool,
        hasStop: Bool
    ) -> LiveManageRows {
        LiveManageRows(
            // Stop only while a session runs and the caller wired a stop
            // handler.
            showStop: isSharing && hasStop,
            // Hide-me-now is the never-gated privacy escape, offered while
            // sharing — but NOT on the stop sheet, which stops and nothing
            // else.
            showHideNow: isSharing && !hasStop,
            // One-tap start only when idle and the LIVE_LOCATION flag is on.
            showStart: !isSharing && canShareLive,
            // Idle + flag off → explain it's unavailable instead of offering
            // start. This is flag-gated, NOT member-gated (own sharing is
            // free).
            showUnavailableNotice: !isSharing && !canShareLive,
            // "Who can see me" stays reachable in every state of the CONTROLS
            // sheet; the stop sheet drops it along with Hide me now.
            showAudienceEntry: !hasStop
        )
    }
}
