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
        ScrollView {
        VStack(spacing: KccSpacing.s4) {

            Text("auth.loginTitle")
                .font(.title2.bold())
            Text("auth.loginSubtitle")
                .font(.body)
                .foregroundStyle(.secondary)


            switch coordinator.status {
            case .inProgress:
                ProgressView("auth.loading")
            case .failed(.unavailable):
                Text("auth.platformUnsupported")
                    .font(.subheadline)
                    .foregroundStyle(KccPalette.errorRed)
                    .multilineTextAlignment(.center)
            case .failed(.generic):
                VStack(spacing: KccSpacing.s2) {
                    Text("auth.errorGeneric")
                        .font(.subheadline)
                        .foregroundStyle(KccPalette.errorRed)
                        .multilineTextAlignment(.center)
                    signInButton
                }
            case .idle:
                signInButton
            }

            Text("auth.deleteAccountNote")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.top, KccSpacing.s4)
        }
        .frame(maxWidth: .infinity)
        .kccContentCard()
        .padding(KccSpacing.s4)
        }.kccReadingSurface(pageBackground: true)
    }

    private var signInButton: some View {
        Button {
            coordinator.resetFailure()
            Task { await coordinator.signIn() }
        } label: {
            Label("auth.appleLoginButton", systemImage: "apple.logo")
                .font(.body.weight(.semibold))
                .frame(maxWidth: .infinity, minHeight: 48)
        }
        .buttonStyle(.borderedProminent)
        .tint(KccTheme.text)
        .foregroundStyle(KccTheme.page)
    }
}
