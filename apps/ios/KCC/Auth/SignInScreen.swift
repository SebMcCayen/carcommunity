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

                Text("auth.deleteAccountNote")
                    .font(.footnote)
                    .foregroundStyle(KccTheme.secondary)
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
            HStack(spacing: KccSpacing.s3) {
                Image(systemName: "apple.logo").font(.system(size: 22, weight: .semibold)).accessibilityHidden(true)
                Text("auth.appleLoginButton").font(.body.weight(.semibold))
                    .fixedSize(horizontal: false, vertical: true)
            }
                .frame(maxWidth: .infinity, minHeight: 48)
        }
        .accessibilityLabel(Text("auth.appleLoginButton"))
        .buttonStyle(.borderedProminent)
        .buttonBorderShape(.roundedRectangle(radius: KccRadius.md))
        .tint(KccTheme.text)
        .foregroundStyle(KccTheme.page)
    }
}
