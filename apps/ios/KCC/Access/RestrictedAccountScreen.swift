import SwiftUI

struct RestrictedAccountScreen: View {
    @Environment(\.openURL) private var openURL
    let access: AccountAccess?
    var uid: String? = nil
    @Bindable var privacyCoordinator: LiveLocationCoordinator
    let onSignOut: () -> Bool
    let onDeleted: () -> Bool
    let onReauthenticate: () -> Bool
    @State private var deletionCoordinator = AccountDeletionCoordinator(
        repository: FirebaseAccountDeletionRepository.createIfAvailable()
    )
    @State private var showsAccountDeletion = false
    @State private var signOutFailed = false

    var body: some View {
        ScrollView {
        VStack(spacing: KccSpacing.s4) {
            Image(systemName: "exclamationmark.shield.fill")
                .font(.system(size: 48))
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            Text(access?.blacklisted == true ? "carCommunityAccess.blacklistedTitle" : "carCommunityAccess.suspendedTitle")
                .font(.system(size: KccTypeScale.headingLg, weight: .semibold))
            Text(bodyKey)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)

            if let reason = access?.restrictionReason { Text(reason).textSelection(.enabled) }
            if let uid { AccountWarningsSection(uid: uid).id(uid) }

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

            if signOutFailed {
                Text("auth.signOutError")
                    .foregroundStyle(KccPalette.errorRed)
                    .multilineTextAlignment(.center)
            }

            Button("auth.signOut", action: attemptSignOut)
                .buttonStyle(.bordered)
        }
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
                onDeleted: onDeleted,
                onReauthenticate: onReauthenticate,
                onBack: { showsAccountDeletion = false }
            )
        }
    }

    private func attemptSignOut() {
        signOutFailed = !onSignOut()
    }

    private var bodyKey: LocalizedStringKey {
        access == nil
            ? "accountStatus.verificationFailedBody"
            : "carCommunityAccess.body"
    }

    private func policyButton(_ title: LocalizedStringKey, urlKey: String) -> some View {
        Button(title) {
            let value = String(localized: String.LocalizationValue(urlKey))
            if let url = URL(string: value), url.scheme == "https" { openURL(url) }
        }
        .buttonStyle(.bordered)
    }
}
