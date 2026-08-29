import AuthenticationServices
import SwiftUI

/// The signed-out screen: brand title + Sign in with Apple, per the parity
/// instructions (Apple-only on iOS, ADR-002). Mirrors Android's
/// `SignInScreen.kt` in behavior: button → coordinator, progress while the
/// flow runs, a dismissible generic error, and an unavailable notice for
/// config-less builds.
struct SignInScreen: View {
    @Bindable var coordinator: SignInCoordinator

    var body: some View {
        VStack(spacing: KccSpacing.s4) {
            Spacer()

            Text("auth.loginTitle")
                .font(.system(size: KccTypeScale.headingLg, weight: .semibold))
            Text("auth.loginSubtitle")
                .font(.system(size: KccTypeScale.bodyMd))
                .foregroundStyle(.secondary)

            Spacer()

            switch coordinator.status {
            case .inProgress:
                ProgressView("auth.loading")
            case .failed(.unavailable):
                Text("auth.platformUnsupported")
                    .font(.system(size: KccTypeScale.bodySm))
                    .foregroundStyle(KccPalette.errorRed)
                    .multilineTextAlignment(.center)
            case .failed(.generic):
                VStack(spacing: KccSpacing.s2) {
                    Text("auth.errorGeneric")
                        .font(.system(size: KccTypeScale.bodySm))
                        .foregroundStyle(KccPalette.errorRed)
                        .multilineTextAlignment(.center)
                    signInButton
                }
            case .idle:
                signInButton
            }

            Text("auth.deleteAccountNote")
                .font(.system(size: KccTypeScale.caption))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.top, KccSpacing.s6)
        }
        .padding(KccSpacing.s6)
    }

    private var signInButton: some View {
        Button {
            coordinator.resetFailure()
            Task { await coordinator.signIn() }
        } label: {
            Label("auth.appleLoginButton", systemImage: "apple.logo")
                .font(.system(size: KccTypeScale.bodyMd, weight: .semibold))
                .frame(maxWidth: .infinity, minHeight: 44)
        }
        .buttonStyle(.borderedProminent)
        .tint(.primary)
    }
}
