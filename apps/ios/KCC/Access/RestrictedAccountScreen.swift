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
                    .foregroundStyle(KccTheme.secondary)
                    .accessibilityHidden(true)
                Text(access?.blacklisted == true ? "carCommunityAccess.blacklistedTitle" : "carCommunityAccess.suspendedTitle")
                    .font(.title2.bold())
                Text(bodyKey)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(KccTheme.secondary)

                if let reason = access?.restrictionReason {
                    Text(verbatim: reason).font(.body).fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
                }
                if let uid { AccountWarningsSection(uid: uid).id(uid) }

                Button {
                    openURL(URL(string: "https://github.com/SebMcCayen/carcommunity/issues")!)
                } label: { Text("accountStatus.supportLink").frame(maxWidth: .infinity, minHeight: 48) }
                .kccPrimaryAction()

                SocialFlow {
                    policyButton("settings.terms", urlKey: "url.terms")
                    policyButton("settings.privacyPolicy", urlKey: "url.privacy")
                }

                Button {
                    Task { await privacyCoordinator.hideMeNow() }
                } label: {
                    Label("liveLocation.hideNow", systemImage: "location.slash.fill")
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .kccPrimaryAction()
                .disabled(privacyCoordinator.actionStatus == .working)

                if privacyCoordinator.actionStatus == .failed {
                    Text("liveLocation.error")
                        .foregroundStyle(KccTheme.error)
                }

                // Subscription management remains a later production-cutover
                // milestone. Account deletion is always reachable while suspended.
                Text("accountStatus.subscriptionManagementPlaceholder")

                if deletionCoordinator.isAvailable {
                    Button(role: .destructive) {
                        showsAccountDeletion = true
                    } label: { Text("settings.accountDeletion").frame(maxWidth: .infinity, minHeight: 48) }
                    .buttonStyle(.bordered)
                }

                if signOutFailed {
                    Text("auth.signOutError")
                        .foregroundStyle(KccTheme.error)
                        .multilineTextAlignment(.center)
                }

                Button(action: attemptSignOut) { Text("auth.signOut").frame(maxWidth: .infinity, minHeight: 48) }
                    .buttonStyle(.bordered)
            }
            .font(.body)
            .padding(KccSpacing.s4)
            .frame(maxWidth: .infinity)
        }
        .kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
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
        Button {
            let value = String(localized: String.LocalizationValue(urlKey))
            if let url = URL(string: value), url.scheme == "https" { openURL(url) }
        } label: { Text(title).frame(minHeight: 48) }
        .buttonStyle(.bordered)
    }
}
