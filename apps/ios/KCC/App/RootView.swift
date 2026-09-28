import SwiftUI

/// The auth-state switch — the iOS port of Android's `AppRoot.kt`:
/// signed out → ``SignInScreen``; signed in → the tab shell; unavailable
/// (config-less build) → the bare shell so CI and clone-and-run builds still
/// render.
struct RootView: View {
    @Environment(\.scenePhase) private var scenePhase
    @Bindable var session: AuthSession
    let signInCoordinator: SignInCoordinator
    @Bindable var accessSession: AppAccessSession
    @Bindable var restrictedPrivacyCoordinator: LiveLocationCoordinator

    var body: some View {
        Group {
            switch session.state {
            case .signedOut:
                SignInScreen(coordinator: signInCoordinator)
            case .unavailable:
                ShellView(
                    session: session,
                    access: .unrestrictedCommunity,
                    featureFlags: .contractDefaults
                )
            case .signedIn:
                authenticatedContent
            }
        }
        .task(id: signedInUid) { accessSession.bind(uid: signedInUid) }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await accessSession.refreshFlags() } }
        }
    }

    @ViewBuilder
    private var authenticatedContent: some View {
        switch accessSession.accountState {
        case .loaded(let access) where access.isRestricted:
            RestrictedAccountScreen(
                access: access,
                privacyCoordinator: restrictedPrivacyCoordinator,
                onSignOut: session.signOut
            )
        case .loaded(let access):
            ShellView(session: session, access: access, featureFlags: accessSession.flags)
        case .unavailable:
            ShellView(
                session: session,
                access: .unrestrictedCommunity,
                featureFlags: accessSession.flags
            )
        case .loading:
            ProgressView().accessibilityLabel(Text("accountStatus.verifying"))
        case .failed:
            // Fail closed in the client until the live listener recovers. The
            // policy/support surface remains reachable instead of exposing
            // feature UI from stale or unknown account status.
            RestrictedAccountScreen(
                access: nil,
                privacyCoordinator: restrictedPrivacyCoordinator,
                onSignOut: session.signOut
            )
        }
    }

    private var signedInUid: String? {
        if case .signedIn(let uid, _) = session.state { return uid }
        return nil
    }
}
