import Foundation
import SwiftUI

/// Optional in-app destinations exposed by the Settings hub. A missing
/// callback means the feature is not composed in this build and its row is
/// omitted, matching the shell's existing unavailable-entry policy.
struct SettingsActions {
    let onManageSubscription: (() -> Void)?
    let onSavedPlaces: (() -> Void)?
    let onNotificationSettings: (() -> Void)?
    let onBlockedUsers: (() -> Void)?
    let onPartnerStats: (() -> Void)?
    let onFeedback: (() -> Void)?
    let onDeleteAccount: (() -> Void)?
    let onWhatsNew: (() -> Void)?

    init(
        onManageSubscription: (() -> Void)? = nil,
        onSavedPlaces: (() -> Void)? = nil,
        onNotificationSettings: (() -> Void)? = nil,
        onBlockedUsers: (() -> Void)? = nil,
        onPartnerStats: (() -> Void)? = nil,
        onFeedback: (() -> Void)? = nil,
        onDeleteAccount: (() -> Void)? = nil,
        onWhatsNew: (() -> Void)? = nil
    ) {
        self.onManageSubscription = onManageSubscription
        self.onSavedPlaces = onSavedPlaces
        self.onNotificationSettings = onNotificationSettings
        self.onBlockedUsers = onBlockedUsers
        self.onPartnerStats = onPartnerStats
        self.onFeedback = onFeedback
        self.onDeleteAccount = onDeleteAccount
        self.onWhatsNew = onWhatsNew
    }

    var availableDestinations: Set<SettingsDestination> {
        Set(SettingsDestination.allCases.filter { action(for: $0) != nil })
    }

    func action(for destination: SettingsDestination) -> (() -> Void)? {
        switch destination {
        case .subscription: return onManageSubscription
        case .savedPlaces: return onSavedPlaces
        case .notificationSettings: return onNotificationSettings
        case .blockedUsers: return onBlockedUsers
        case .partnerStats: return onPartnerStats
        case .feedback: return onFeedback
        case .accountDeletion: return onDeleteAccount
        case .whatsNew: return onWhatsNew
        }
    }
}

enum SettingsDestination: CaseIterable, Hashable, Sendable {
    case subscription
    case savedPlaces
    case notificationSettings
    case blockedUsers
    case partnerStats
    case feedback
    case accountDeletion
    case whatsNew
}

/// Native iOS Settings hub. The shell owns navigation; this view only renders
/// callbacks that were actually supplied and keeps legal links available in
/// config-less builds.
struct SettingsScreen: View {
    let actions: SettingsActions

    var body: some View {
        List {
            if !actions.availableDestinations.isDisjoint(with: accountDestinations) {
                Section("settingsMenu.accountSection") {
                    destinationRow(
                        destination: .subscription,
                        key: "settingsMenu.manageSubscription",
                        icon: "creditcard",
                        identifier: "settings.subscription"
                    )
                    destinationRow(
                        destination: .savedPlaces,
                        key: "settingsMenu.savedPlaces",
                        icon: "bookmark",
                        identifier: "settings.savedPlaces"
                    )
                    destinationRow(
                        destination: .notificationSettings,
                        key: "settingsMenu.notificationSettings",
                        icon: "bell.badge",
                        identifier: "settings.notificationSettings"
                    )
                    destinationRow(
                        destination: .blockedUsers,
                        key: "settings.blockedUsers",
                        icon: "person.crop.circle.badge.xmark",
                        identifier: "settings.blockedUsers"
                    )
                    destinationRow(
                        destination: .partnerStats,
                        key: "settingsMenu.partnerStats",
                        icon: "chart.bar",
                        identifier: "settings.partnerStats"
                    )
                    destinationRow(
                        destination: .feedback,
                        key: "settingsMenu.feedback",
                        icon: "exclamationmark.bubble",
                        identifier: "settings.feedback"
                    )
                    destinationRow(
                        destination: .accountDeletion,
                        key: "settingsMenu.deleteAccount",
                        icon: "trash",
                        identifier: "settings.accountDeletion",
                        role: .destructive
                    )
                }
            }

            if actions.availableDestinations.contains(.whatsNew) {
                Section("settingsMenu.aboutSection") {
                    destinationRow(
                        destination: .whatsNew,
                        key: "settingsMenu.whatsNew",
                        icon: "sparkles",
                        identifier: "settings.whatsNew"
                    )
                }
            }

            Section {
                legalLink(
                    title: "settingsMenu.privacy",
                    urlKey: "url.privacy",
                    expectedPath: "/privacy",
                    icon: "lock.shield",
                    identifier: "settings.privacyPolicy"
                )
                legalLink(
                    title: "settingsMenu.terms",
                    urlKey: "url.terms",
                    expectedPath: "/terms",
                    icon: "doc.text",
                    identifier: "settings.terms"
                )
            } header: {
                Text("settingsMenu.legalSection")
            } footer: {
                Text(versionText)
                    .frame(maxWidth: .infinity)
                    .accessibilityIdentifier("settings.version")
            }
        }
        .navigationTitle("settingsMenu.title")
        .accessibilityIdentifier("settings.screen")
    }

    private let accountDestinations: Set<SettingsDestination> = [
        .subscription, .savedPlaces, .notificationSettings, .blockedUsers,
        .partnerStats, .feedback, .accountDeletion,
    ]

    @ViewBuilder
    private func destinationRow(
        destination: SettingsDestination,
        key: LocalizedStringKey,
        icon: String,
        identifier: String,
        role: ButtonRole? = nil
    ) -> some View {
        if let action = actions.action(for: destination) {
            Button(role: role, action: action) {
                settingsLabel(key, icon: icon)
            }
            .accessibilityIdentifier(identifier)
        }
    }

    @ViewBuilder
    private func legalLink(
        title: LocalizedStringKey,
        urlKey: String.LocalizationValue,
        expectedPath: String,
        icon: String,
        identifier: String
    ) -> some View {
        if let url = SettingsLegalLinkPolicy.validatedURL(
            String(localized: urlKey),
            expectedPath: expectedPath
        ) {
            Link(destination: url) {
                settingsLabel(title, icon: icon)
            }
            .accessibilityIdentifier(identifier)
        }
    }

    private func settingsLabel(_ key: LocalizedStringKey, icon: String) -> some View {
        Label {
            Text(key)
        } icon: {
            Image(systemName: icon)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
    }

    private var versionText: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString")
            as? String ?? "–"
        return String(format: String(localized: "settingsMenu.version"), version)
    }
}

enum SettingsLegalLinkPolicy {
    private static let host = "kungsbacka-car-community.web.app"

    /// Legal destinations are bundled localization data, but still cross an
    /// app-to-browser trust boundary. Keep them on the canonical HTTPS origin
    /// and exact document path so a malformed translation cannot create an
    /// insecure or lookalike link.
    static func validatedURL(_ raw: String, expectedPath: String) -> URL? {
        guard let url = URL(string: raw),
              url.scheme?.lowercased() == "https",
              url.host?.lowercased() == host,
              url.port == nil,
              url.user == nil,
              url.password == nil,
              url.path == expectedPath,
              url.query == nil,
              url.fragment == nil
        else { return nil }
        return url
    }
}

#Preview {
    NavigationStack {
        SettingsScreen(actions: SettingsActions(
            onNotificationSettings: {},
            onBlockedUsers: {}
        ))
    }
}
