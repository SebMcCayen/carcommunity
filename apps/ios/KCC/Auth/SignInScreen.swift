import AuthenticationServices
import SwiftUI

/// The signed-out screen: brand title + Sign in with Apple, per the parity
/// instructions (Apple-only on iOS, ADR-002). Mirrors Android's
/// `SignInScreen.kt` in behavior: button → coordinator, progress while the
/// flow runs, a dismissible generic error, and an unavailable notice for
/// config-less builds.
struct SignInScreen: View {
    @Bindable var coordinator: SignInCoordinator
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        ScrollView {
            VStack(spacing: KccSpacing.s4) {

                Text("auth.loginTitle")
                    .font(.title2.bold())
                Text("auth.loginSubtitle")
                    .font(.body)
                    .foregroundStyle(KccTheme.secondary)

                switch coordinator.status {
                case .inProgress:
                    ProgressView("auth.loading")
                case .failed(.unavailable):
                    Text("auth.platformUnsupported")
                        .font(.subheadline)
                        .foregroundStyle(KccTheme.error)
                        .multilineTextAlignment(.center)
                case .failed(.generic):
                    VStack(spacing: KccSpacing.s2) {
                        Text("auth.errorGeneric")
                            .font(.subheadline)
                            .foregroundStyle(KccTheme.error)
                            .multilineTextAlignment(.center)
                        signInButton
                    }
                case .idle:
                    signInButton
                }

                Text("iosStore.signedOutPrivacy", tableName: "AppStore")
                    .font(.footnote)
                    .foregroundStyle(KccTheme.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.top, KccSpacing.s4)
                AppStoreLegalLinks()
            }
            .frame(maxWidth: .infinity)
            .kccContentCard()
            .padding(KccSpacing.s4)
        }.kccReadingSurface(pageBackground: true)
    }

    private var signInButton: some View {
        ApprovedAppleSignInButton(dark: colorScheme == .dark) {
            coordinator.resetFailure()
            Task { await coordinator.signIn() }
        }
        .id(colorScheme)
        .frame(maxWidth: .infinity)
        .frame(height: 64)
        .accessibilityIdentifier("auth.appleSignIn")
    }
}

/// Apple's native artwork/proportions; activation retains the existing nonce
/// provider rather than creating a second authorization request.
private struct ApprovedAppleSignInButton: UIViewRepresentable {
    let dark: Bool
    let onTap: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onTap: onTap) }
    func makeUIView(context: Context) -> ASAuthorizationAppleIDButton {
        let button = ASAuthorizationAppleIDButton(type: .signIn, style: dark ? .white : .black)
        button.cornerRadius = KccRadius.md
        button.addTarget(context.coordinator, action: #selector(Coordinator.activate), for: .touchUpInside)
        return button
    }
    func updateUIView(_ view: ASAuthorizationAppleIDButton, context: Context) {
        context.coordinator.onTap = onTap
    }
    @MainActor final class Coordinator: NSObject {
        var onTap: () -> Void
        init(onTap: @escaping () -> Void) { self.onTap = onTap }
        @objc func activate() { onTap() }
    }
}
