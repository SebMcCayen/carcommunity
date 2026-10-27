import SwiftUI
import UIKit

/// The notification-settings screen: per-category in-app / push opt-out toggles
/// that persist to `userPrivate/{uid}.notificationPreferences` — the iOS slice
/// of Android's `NotificationSettingsScreen`.
///
/// The essential account notices (`account_warning`, `account_suspension`)
/// render locked-on and reject toggles, mirroring the backend delivery-time
/// rule. The push section reflects the native authorization state, requests
/// permission once, and directs denied members to system settings.
///
/// A dumb view over ``NotificationSettingsUiState``: the toggle recompute +
/// save lives in the pure ``NotificationSettingsCoordinator``.
///
/// Reached via ``ShellRoute/notificationSettings`` — the shell wraps this in a
/// `NavigationStack` and supplies Back (future wiring PR).
struct NotificationSettingsScreen: View {
    let coordinator: NotificationSettingsCoordinator?
    @Bindable var pushNotifications: PushNotificationCoordinator

    init(
        coordinator: NotificationSettingsCoordinator?,
        pushNotifications: PushNotificationCoordinator = .shared
    ) {
        self.coordinator = coordinator
        self.pushNotifications = pushNotifications
    }

    var body: some View {
        content
            .navigationTitle(Text("notifications.settingsTitle"))
            .kccReadingSurface(pageBackground: true)
            .task { coordinator?.start() }
            .task { await pushNotifications.refreshPermissionState() }
    }

    @ViewBuilder
    private var content: some View {
        if let coordinator {
            switch coordinator.state {
            case .loading:
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            case .unavailable:
                placeholder
            case .loaded(let preferences):
                form(preferences: preferences, coordinator: coordinator)
            case .failed:
                loadErrorState
            }
        } else {
            placeholder
        }
    }

    private var loadErrorState: some View {
        ScrollView {
            SettingsStateCard(title: "notifications.settingsTitle", message: "notifications.settingsLoadError", symbol: "bell.badge",
                retryTitle: "notifications.retry", onRetry: { coordinator?.reload() }).padding(KccSpacing.s4)
        }
    }

    /// Config-less build / no session: the shared cross-feature unavailable
    /// copy, not "notifications.permissionOptional" ("Notifications are
    /// optional.") — that string describes a CHOICE the member has, which
    /// misdescribes a build that cannot observe preferences at all (Copilot
    /// review on PR #1055).
    private var placeholder: some View {
        ScrollView {
            SettingsStateCard(title: "notifications.settingsTitle", message: "shell.unavailable", symbol: "bell.badge")
                .padding(KccSpacing.s4)
        }
    }

    private func form(
        preferences: NotificationPreferences,
        coordinator: NotificationSettingsCoordinator
    ) -> some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: KccSpacing.s4) {
                NotificationPermissionCard(state: pushNotifications.permissionState,
                    requesting: pushNotifications.isRequestingPermission,
                    onRequest: { Task { await pushNotifications.requestPermission() } },
                    onOpenSettings: openSystemSettings)
                if case .failed = coordinator.saveStatus {
                    Text("privacySettings.error").font(.body).foregroundStyle(KccPalette.errorRed)
                } else if coordinator.saveStatus == .saving {
                    ProgressView("notifications.settingsSaving").font(.body)
                }
                Text("notifications.settingsCategoriesTitle").font(.title3.bold()).accessibilityAddTraits(.isHeader)
                ForEach(NotificationCategories.active, id: \.self) { category in
                    NotificationPreferenceRow(category: category, preference: preferences.effective(category),
                        isEssential: NotificationCategories.isEssential(category),
                        onToggle: { channel, enabled in
                            Task { await coordinator.toggle(category, channel: channel, enabled: enabled) }
                        })
                        .kccContentCard()
                }
            }.padding(KccSpacing.s4)
        }
    }

    private func openSystemSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}

/// One category's toggles: the label, the in-app + push switches, and (for the
/// essential account notices) the "always on" note. Essential switches are
/// on and disabled — Android's `CategoryRow`.
struct NotificationPreferenceRow: View {
    let category: NotificationCategory
    let preference: CategoryPreference
    let isEssential: Bool
    let onToggle: (NotificationChannel, Bool) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            Text(LocalizedStringKey(category.labelKey))
                .font(.headline)
                .accessibilityAddTraits(.isHeader)

            Toggle(isOn: channelBinding(.inApp)) {
                Text("notifications.settingsInApp")
                    .font(.body)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .tint(KccTheme.driver)
            .frame(minHeight: 48)
            .disabled(isEssential)

            Toggle(isOn: channelBinding(.push)) {
                Text("notifications.settingsPush")
                    .font(.body)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .tint(KccTheme.driver)
            .frame(minHeight: 48)
            .disabled(isEssential)

            if isEssential {
                Label("notifications.settingsEssential", systemImage: "lock.fill")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, KccSpacing.s1)
    }

    func channelBinding(_ channel: NotificationChannel) -> Binding<Bool> {
        Binding(
            get: {
                switch channel {
                case .inApp: return preference.inApp
                case .push: return preference.push
                }
            },
            set: { enabled in
                guard !isEssential else { return }
                onToggle(channel, enabled)
            }
        )
    }
}

#Preview {
    NavigationStack {
        NotificationSettingsScreen(coordinator: nil)
    }
}
