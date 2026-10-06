import SwiftUI

struct WelcomeGateView: View {
    let uid: String
    @Bindable var session: AuthSession
    let access: AccountAccess
    let featureFlags: FeatureFlags
    let diagnostics: IOSDiagnosticsComposition
    let store: WelcomeStoring
    @State private var seen: Bool
    @State private var initialRoute: ShellRoute?

    init(
        uid: String,
        session: AuthSession,
        access: AccountAccess,
        featureFlags: FeatureFlags,
        diagnostics: IOSDiagnosticsComposition,
        store: WelcomeStoring = WelcomeStore()
    ) {
        self.uid = uid
        self.session = session
        self.access = access
        self.featureFlags = featureFlags
        self.diagnostics = diagnostics
        self.store = store
        _seen = State(initialValue: store.hasSeenWelcome(uid: uid))
        _initialRoute = State(initialValue: nil)
    }

    var body: some View {
        if seen {
            ShellView(
                session: session,
                authenticatedUid: uid,
                access: access,
                featureFlags: featureFlags,
                diagnostics: diagnostics,
                initialRoute: initialRoute,
                initialTab: initialTab
            )
        } else {
            WelcomeScreen(
                onSeeMembership: { finish(route: .subscription) },
                onCompleteProfile: { finish(route: .profile) },
                onAddCar: { finish(tab: .garage) },
                onFinish: { finish() }
            )
            .id(uid)
        }
    }

    private func finish(route: ShellRoute? = nil, tab: ShellTab? = nil) {
        store.markSeen(uid: uid)
        initialRoute = route
        // Garage is a tab, not a route. ShellView currently has a map default;
        // use its garage route-free entry through the dedicated initial tab.
        initialTab = tab
        seen = true
    }

    @State private var initialTab: ShellTab? = nil
}
