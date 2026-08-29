import SwiftUI

/// The auth-state switch — the iOS port of Android's `AppRoot.kt`:
/// signed out → ``SignInScreen``; signed in → the tab shell; unavailable
/// (config-less build) → the bare shell so CI and clone-and-run builds still
/// render.
struct RootView: View {
    let session: AuthSession
    let signInCoordinator: SignInCoordinator

    var body: some View {
        switch session.state {
        case .signedOut:
            SignInScreen(coordinator: signInCoordinator)
        case .signedIn, .unavailable:
            // The session rides along so the shell can show who is signed in
            // and offer sign-out; in the unavailable case the shell renders
            // bare (no profile entry) off the same state.
            ShellView(session: session)
        }
    }
}
