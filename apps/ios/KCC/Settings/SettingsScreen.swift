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
        var destinations: Set<SettingsDestination> = []
        if onManageSubscription != nil { destinations.insert(.subscription) }
        if onSavedPlaces != nil { destinations.insert(.savedPlaces) }
        if onNotificationSettings != nil { destinations.insert(.notificationSettings) }
        if onBlockedUsers != nil { destinations.insert(.blockedUsers) }
        if onPartnerStats != nil { destinations.insert(.partnerStats) }
        if onFeedback != nil { destinations.insert(.feedback) }
        if onDeleteAccount != nil { destinations.insert(.accountDeletion) }
        if onWhatsNew != nil { destinations.insert(.whatsNew) }
        return destinations
    }
}

enum SettingsDestination: Hashable, Sendable {
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
                        key: "settingsMenu.manageSubscription",
                        icon: "creditcard",
                        identifier: "settings.subscription",
                        action: actions.onManageSubscription
                    )
                    destinationRow(
                        key: "settingsMenu.savedPlaces",
                        icon: "bookmark",
                        identifier: "settings.savedPlaces",
                        action: actions.onSavedPlaces
                    )
                    destinationRow(
                        key: "settingsMenu.notificationSettings",
                        icon: "bell.badge",
                        identifier: "settings.notificationSettings",
                        action: actions.onNotificationSettings
                    )
                    destinationRow(
                        key: "settings.blockedUsers",
                        icon: "person.crop.circle.badge.xmark",
                        identifier: "settings.blockedUsers",
                        action: actions.onBlockedUsers
                    )
                    destinationRow(
                        key: "settingsMenu.partnerStats",
                        icon: "chart.bar",
                        identifier: "settings.partnerStats",
                        action: actions.onPartnerStats
                    )
                    destinationRow(
                        key: "settingsMenu.feedback",
                        icon: "exclamationmark.bubble",
                        identifier: "settings.feedback",
                        action: actions.onFeedback
                    )
                    destinationRow(
                        key: "settingsMenu.deleteAccount",
                        icon: "trash",
                        identifier: "settings.accountDeletion",
                        role: .destructive,
                        action: actions.onDeleteAccount
                    )
                }
            }

            if actions.onWhatsNew != nil {
                Section("settingsMenu.aboutSection") {
                    destinationRow(
                        key: "settingsMenu.whatsNew",
                        icon: "sparkles",
                        identifier: "settings.whatsNew",
                        action: actions.onWhatsNew
                    )
                }
            }

            Section("settingsMenu.legalSection") {
                legalLink(
                    title: "settingsMenu.privacy",
                    urlKey: "url.privacy",
                    icon: "lock.shield",
                    identifier: "settings.privacyPolicy"
                )
                legalLink(
                    title: "settingsMenu.terms",
                    urlKey: "url.terms",
                    icon: "doc.text",
                    identifier: "settings.terms"
                )
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
        key: LocalizedStringKey,
        icon: String,
        identifier: String,
        role: ButtonRole? = nil,
        action: (() -> Void)?
    ) -> some View {
        if let action {
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
        icon: String,
        identifier: String
    ) -> some View {
        if let url = URL(string: String(localized: urlKey)),
           let scheme = url.scheme?.lowercased(), ["https", "http"].contains(scheme) {
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

#Preview {
    NavigationStack {
        SettingsScreen(actions: SettingsActions(
            onNotificationSettings: {},
            onBlockedUsers: {}
        ))
    }
}
