import SwiftUI

/// No credentials, redirects, or external payment routes are constructed here.
struct AppStoreSubscriptionManagementLink: View {
    var body: some View {
        Link(destination: URL(string: "https://apps.apple.com/account/subscriptions")!) {
            Text("iosStore.manage").frame(minHeight: 48)
        }.buttonStyle(.bordered)
    }
}

struct AppStoreLegalLinks: View {
    var body: some View {
        SocialFlow {
            Link(destination: URL(string: String(localized: "url.terms"))!) {
                Text("settingsMenu.terms").frame(minHeight: 48)
            }
            Link(destination: URL(string: String(localized: "url.privacy"))!) {
                Text("settingsMenu.privacy").frame(minHeight: 48)
            }
        }.buttonStyle(.bordered)
    }
}
