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
    let diagnostics: IOSDiagnosticsComposition

    var body: some View {
        Group {
            switch session.state {
            case .signedOut:
                SignInScreen(coordinator: signInCoordinator)
            case .unavailable:
                ShellView(
                    session: session,
                    authenticatedUid: nil,
                    access: .unrestrictedCommunity,
                    featureFlags: .contractDefaults,
                    diagnostics: diagnostics
                )
            case .signedIn(let uid, let displayName):
                authenticatedContent(uid: uid, displayName: displayName)
            }
        }
        .task(id: signedInUid) { accessSession.bind(uid: signedInUid) }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await accessSession.refreshFlags() } }
        }
    }

    @ViewBuilder
    private func authenticatedContent(uid: String, displayName: String?) -> some View {
        // Fence the asynchronously refreshed access snapshot to the canonical
        // Firebase identity. A deleted previous account must not sign out a
        // replacement account during the frame before `.task(id:)` rebinds.
        switch accessSession.accountState(for: uid) {
        case .loaded(let access) where access.deleted:
            // The callable disables the Auth user before marking the profile.
            // If the profile listener wins the race against the callable
            // response (or the app relaunches with a cached token), clear the
            // local Firebase session here as the lifecycle backstop.
            DeletedAccountSessionEndView {
                session.signOut(ifSignedInAs: uid)
            }
        case .loaded(let access) where access.suspended:
            RestrictedAccountScreen(
                access: access,
                privacyCoordinator: restrictedPrivacyCoordinator,
                onSignOut: { session.signOut(ifSignedInAs: uid) },
                onDeletionSessionEnd: { session.signOut(ifSignedInAs: uid) }
            )
        case .loaded(let access):
            AuthenticatedExperience(
                uid: uid,
                displayName: displayName,
                session: session,
                access: access,
                featureFlags: accessSession.flags,
                diagnostics: diagnostics
            )
            .id(uid)
        case .unavailable:
            AuthenticatedExperience(
                uid: uid,
                displayName: displayName,
                session: session,
                access: .unrestrictedCommunity,
                featureFlags: accessSession.flags,
                diagnostics: diagnostics
            )
            .id(uid)
        case .loading:
            ProgressView().accessibilityLabel(Text("accountStatus.verifying"))
        case .failed:
            // Fail closed in the client until the live listener recovers. The
            // policy/support surface remains reachable instead of exposing
            // feature UI from stale or unknown account status.
            RestrictedAccountScreen(
                access: nil,
                privacyCoordinator: restrictedPrivacyCoordinator,
                onSignOut: { session.signOut(ifSignedInAs: uid) },
                onDeletionSessionEnd: { session.signOut(ifSignedInAs: uid) }
            )
        }
    }

    private var signedInUid: String? {
        if case .signedIn(let uid, _) = session.state { return uid }
        return nil
    }
}

private struct DeletedAccountSessionEndView: View {
    let endSession: () -> Bool
    @State private var failed = false

    var body: some View {
        Group {
            if failed {
                VStack(spacing: KccSpacing.s3) {
                    Text("auth.signOutError")
                        .multilineTextAlignment(.center)
                        .foregroundStyle(KccPalette.errorRed)
                    Button("auth.signOut", action: attempt)
                        .buttonStyle(.borderedProminent)
                }
                .padding(KccSpacing.s6)
            } else {
                ProgressView()
                    .accessibilityLabel(Text("auth.loading"))
            }
        }
        .task { attempt() }
    }

    private func attempt() {
        failed = !endSession()
    }
}
