import SwiftUI

/// The five-tab, map-first shell. Placeholder content for now — each tab fills
/// in as its feature is ported; the tab set, default tab, and (eventually) the
/// map-cover rules come from the pure ``ShellNav`` logic so behaviour stays
/// unit-tested outside SwiftUI.
struct ShellView: View {
    /// The signed-in session, threaded from ``RootView``. The config-less /
    /// unavailable state renders the bare shell with no profile entry —
    /// Android's "unavailable entries are omitted" hub rule.
    @Bindable var session: AuthSession

    @State private var selectedTab: ShellTab = .defaultTab
    /// The full-screen sub-route back-stack, held as the ONE pure value from
    /// ``ShellNav``; every open/Back goes through its ``ShellRouteStack/opening(_:)``
    /// / ``ShellRouteStack/poppingOne()`` reducers rather than ad-hoc state.
    @State private var routes = ShellRouteStack.empty

    var body: some View {
        TabView(selection: $selectedTab) {
            ForEach(ShellTab.allCases, id: \.self) { tab in
                placeholder(for: tab)
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
        // The map-home profile entry (Android: the map-home top-right profile
        // menu button). Only on the Map tab, and only when a session actually
        // exists — the unavailable shell has no one to show or sign out.
        .overlay(alignment: .topTrailing) {
            if tab == .map, case .signedIn = session.state {
                profileButton
            }
        }
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
