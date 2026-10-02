import SwiftUI

struct RestrictedAccountScreen: View {
    @Environment(\.openURL) private var openURL
    let access: AccountAccess?
    @Bindable var privacyCoordinator: LiveLocationCoordinator
    let onSignOut: () -> Void
    @State private var deletionCoordinator = AccountDeletionCoordinator(
        repository: FirebaseAccountDeletionRepository.createIfAvailable()
    )
    @State private var showsAccountDeletion = false

    var body: some View {
        VStack(spacing: KccSpacing.s4) {
            Image(systemName: "exclamationmark.shield.fill")
                .font(.system(size: 48))
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            Text("accountStatus.suspendedTitle")
                .font(.system(size: KccTypeScale.headingLg, weight: .semibold))
            Text(bodyKey)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)

            Button("accountStatus.supportLink") {
                openURL(URL(string: "https://github.com/SebMcCayen/carcommunity/issues")!)
            }
            .buttonStyle(.borderedProminent)

            HStack {
                policyButton("settings.terms", urlKey: "url.terms")
                policyButton("settings.privacyPolicy", urlKey: "url.privacy")
            }

            Button {
                Task { await privacyCoordinator.hideMeNow() }
            } label: {
                Label("liveLocation.hideNow", systemImage: "location.slash.fill")
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.borderedProminent)
            .disabled(privacyCoordinator.actionStatus == .working)

            if privacyCoordinator.actionStatus == .failed {
                Text("liveLocation.error")
                    .foregroundStyle(KccPalette.errorRed)
            }

            // Subscription management remains a later production-cutover
            // milestone. Account deletion is always reachable while suspended.
            Text("accountStatus.subscriptionManagementPlaceholder")

            if deletionCoordinator.isAvailable {
                Button("settings.accountDeletion", role: .destructive) {
                    showsAccountDeletion = true
                }
                .buttonStyle(.bordered)
            }

            Button("auth.signOut", action: onSignOut)
                .buttonStyle(.bordered)
        }
        .font(.system(size: KccTypeScale.bodyMd))
        .padding(KccSpacing.s6)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(.background, ignoresSafeAreaEdges: .all)
        .task {
            privacyCoordinator.canShare = false
            privacyCoordinator.start()
        }
        .fullScreenCover(isPresented: $showsAccountDeletion) {
            AccountDeletionScreen(
                coordinator: deletionCoordinator,
                onDeleted: onSignOut,
                onReauthenticate: onSignOut,
                onBack: { showsAccountDeletion = false }
            )
        }
    }

    private var bodyKey: LocalizedStringKey {
        access == nil
            ? "accountStatus.verificationFailedBody"
            : "accountStatus.suspendedBody"
    }

    private func policyButton(_ title: LocalizedStringKey, urlKey: String) -> some View {
        Button(title) {
            let value = String(localized: String.LocalizationValue(urlKey))
            if let url = URL(string: value), url.scheme == "https" { openURL(url) }
        }
        .buttonStyle(.bordered)
    }
}
