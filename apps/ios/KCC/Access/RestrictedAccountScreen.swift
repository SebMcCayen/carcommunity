import SwiftUI

struct RestrictedAccountScreen: View {
    @Environment(\.openURL) private var openURL
    let access: AccountAccess?
    let onSignOut: () -> Void

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

            // These remain visible but disabled until their dedicated iOS
            // slices land, matching Android's existing status placeholders.
            Text("accountStatus.subscriptionManagementPlaceholder")
            Text("accountStatus.accountDeletionPlaceholder")

            Button("auth.signOut", action: onSignOut)
                .buttonStyle(.bordered)
        }
        .font(.system(size: KccTypeScale.bodyMd))
        .padding(KccSpacing.s6)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(.background, ignoresSafeAreaEdges: .all)
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
