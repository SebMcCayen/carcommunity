import SwiftUI

/// The five-tab, map-first shell. The Map tab is the (stub-backed) map home;
/// History / Social / Garage render as translucent panels over the map per
/// `translucentPanelTabs`; the tab set, default tab, and the map-cover rules
/// all come from the pure ``ShellNavigation`` logic so behaviour stays unit-tested
/// outside SwiftUI.
struct ShellView: View {
    /// The signed-in session, threaded from ``RootView``. The config-less /
    /// unavailable state renders the bare shell with no profile entry —
    /// Android's "unavailable entries are omitted" hub rule.
    @Bindable var session: AuthSession

    @State private var selectedTab: ShellTab = .defaultTab
    /// The full-screen sub-route back-stack, held as the ONE pure value from
    /// ``ShellRouteStack``; every open/Back goes through its ``ShellRouteStack/opening(_:)``
    /// / ``ShellRouteStack/poppingOne()`` reducers rather than ad-hoc state.
    @State private var routes = ShellRouteStack.empty

    /// The shell's SINGLE map surface, composed once for the whole signed-in
    /// shell and never disposed — Android composes the surface once in
    /// `AuthenticatedApp` and covers/uncovers it; `@State` mirrors that by
    /// keeping this one instance for the shell's lifetime. Covered pages only
    /// stand it down via ``MapSurface/setActive(_:)`` (see the map-cover
    /// effect below), never recreate it.
    @State private var mapSurface = StubMapSurface()

    /// The events-list wiring, created once on first shell appearance. Nil in
    /// a config-less build (no GoogleService-Info.plist →
    /// ``FirebaseEventsRepository/createIfAvailable()`` returns nil); the
    /// events route then renders ``EventsScreen``'s placeholder state instead
    /// of crashing.
    @State private var eventsCoordinator: EventsCoordinator?
    /// Whether the one-shot events wiring above ran. A separate flag because
    /// a nil `eventsCoordinator` is also the legitimate steady state of a
    /// config-less build, so nil cannot mean "not attempted yet".
    @State private var hasWiredEvents = false

    /// What is drawn over the shell's map right now — the ONE pure value
    /// every cover-derived decision reads. `navigating` / `navSearchOpen` are
    /// hard false until turn-by-turn and the address search are ported.
    private var mapCover: MapCover {
        ShellNavigation.mapCover(
            tab: selectedTab,
            route: routes.current,
            navigating: false,
            navSearchOpen: false
        )
    }

    var body: some View {
        TabView(selection: $selectedTab) {
            ForEach(ShellTab.allCases, id: \.self) { tab in
                content(for: tab)
                    .tabItem { Label(tab.title, systemImage: tab.systemImage) }
                    .tag(tab)
            }
        }
        // Route host: sub-routes render full-screen OVER the tab shell (the
        // Android shell's route-host pattern), each carrying its own back
        // affordance that pops one level via the pure stack.
        .overlay {
            if let route = routes.current {
                routeHost(for: route)
            }
        }
        // Drive the surface's liveness from the SAME pure cover value the
        // pages derive from — the iOS counterpart of Android's
        // `LaunchedEffect(mapCover) { mapSurface.setActive(...) }`:
        // Transparent/None keep it live, Opaque stands it down.
        .onChange(of: mapCover, initial: true) { _, cover in
            mapSurface.setActive(ShellMapHost.surfaceActive(cover: cover))
        }
        .task {
            guard !hasWiredEvents else { return }
            hasWiredEvents = true
            eventsCoordinator = FirebaseEventsRepository.createIfAvailable()
                .map { EventsCoordinator(repository: $0) }
        }
    }

    /// Per-tab content. The panel tabs draw the SAME single surface behind
    /// their card — several lightweight SwiftUI readers of the one
    /// ``StubMapSurface`` instance, which is the stub-era equivalent of
    /// Android's one composable behind every panel (the stub has no render
    /// surface to duplicate; the real map-UI PR hosts exactly one view).
    @ViewBuilder
    private func content(for tab: ShellTab) -> some View {
        switch tab {
        case .map:
            MapHomeView(surface: mapSurface)
                // The map-home profile entry (Android: the map-home top-right
                // profile menu button). Only when a session actually exists —
                // the unavailable shell has no one to show or sign out.
                .overlay(alignment: .topTrailing) {
                    if case .signedIn = session.state {
                        profileButton
                    }
                }
        case .history:
            panelTab { ComingSoonPanel(title: ShellTab.history.title) }
        case .social:
            panelTab {
                SocialHubPanel(onOpenEvents: { routes = routes.opening(.events) })
            }
        case .garage:
            panelTab { GaragePanel() }
        case .create:
            // Not a panel tab: an opaque page (the map-cover rule stands the
            // surface down here). The Create chooser fills in with its slice.
            placeholder(for: tab)
        }
    }

    /// A translucent panel tab: the live (stub) map behind, the
    /// bottom-anchored card over it — what makes ``MapCover/transparent``
    /// real. Dismissing the panel returns to the Map tab, matching Android's
    /// panel dismiss.
    private func panelTab(@ViewBuilder content: @escaping () -> some View) -> some View {
        ZStack {
            MapHomeView(surface: mapSurface)
            TranslucentShellPanel(
                onDismiss: { selectedTab = .map },
                content: content
            )
        }
    }

    @ViewBuilder
    private func placeholder(for tab: ShellTab) -> some View {
        VStack(spacing: 12) {
            Image(systemName: tab.systemImage)
                .font(.system(size: 44))
                .foregroundStyle(.secondary)
            Text(tab.title)
                .font(.title2)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var profileButton: some View {
        Button {
            routes = routes.opening(.profile)
        } label: {
            Label("shell.moreProfile", systemImage: "person.circle")
                .labelStyle(.iconOnly)
                .font(.system(size: 28))
        }
        .padding(KccSpacing.s4)
    }

    @ViewBuilder
    private func routeHost(for route: ShellRoute) -> some View {
        switch route {
        case .profile:
            ProfileScreen(
                displayName: signedInDisplayName,
                onSignOut: { session.signOut() },
                onBack: { routes = routes.poppingOne() }
            )
        case .events:
            // The read-only events list, opened from the Social hub. The
            // NavigationStack hosts the screen's `navigationTitle`; Back pops
            // one level via the pure stack, like every route.
            NavigationStack {
                EventsScreen(coordinator: eventsCoordinator)
                    .toolbar {
                        ToolbarItem(placement: .topBarLeading) {
                            Button {
                                routes = routes.poppingOne()
                            } label: {
                                Label("shell.back", systemImage: "chevron.backward")
                            }
                        }
                    }
            }
        default:
            // No other route is reachable yet — each renders here as its
            // feature is ported. Falling back to nothing (rather than
            // trapping) keeps an unexpected value harmless.
            EmptyView()
        }
    }

    private var signedInDisplayName: String? {
        if case .signedIn(_, let displayName) = session.state {
            return displayName
        }
        return nil
    }
}

extension ShellTab {
    /// Localized tab title. Keys live in the generated `Localizable.xcstrings`
    /// and are the same semantic names as `contracts/localization`
    /// (`shell.tabMap` …) — see `apps/ios/scripts/generate-strings.mjs`.
    var title: LocalizedStringKey {
        switch self {
        case .map: "shell.tabMap"
        case .history: "shell.tabHistory"
        case .create: "shell.tabCreate"
        case .social: "shell.tabSocial"
        case .garage: "shell.tabGarage"
        }
    }

    var systemImage: String {
        switch self {
        case .map: "map"
        case .history: "clock"
        case .create: "plus.circle"
        case .social: "person.2"
        case .garage: "car"
        }
    }
}

#Preview {
    // Config-less session: the bare shell, no profile entry.
    ShellView(session: AuthSession(repository: nil))
}
