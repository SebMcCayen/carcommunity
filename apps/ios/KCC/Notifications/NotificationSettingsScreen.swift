import SwiftUI
import UIKit

/// The notification-settings screen: per-category in-app / push opt-out toggles
/// that persist to `userPrivate/{uid}.notificationPreferences` — the iOS slice
/// of Android's `NotificationSettingsScreen`.
///
/// The essential account notices (`account_warning`, `account_suspension`)
/// render locked-on and reject toggles, mirroring the backend delivery-time
/// rule. The Android runtime push-permission REQUEST flow is out of this slice
/// (push delivery needs the end-of-MVP APNs setup); an informational push
/// section explains it and offers a deep link to system settings instead.
///
/// A dumb view over ``NotificationSettingsUiState``: the toggle recompute +
/// save lives in the pure ``NotificationSettingsCoordinator``.
///
/// Reached via ``ShellRoute/notificationSettings`` — the shell wraps this in a
/// `NavigationStack` and supplies Back (future wiring PR).
struct NotificationSettingsScreen: View {
    let coordinator: NotificationSettingsCoordinator?

    var body: some View {
        content
            .navigationTitle(Text("notifications.settingsTitle"))
            .task { coordinator?.start() }
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
            }
        } else {
            placeholder
        }
    }

    /// Config-less build / no session: the shared cross-feature unavailable
    /// copy, not "notifications.permissionOptional" ("Notifications are
    /// optional.") — that string describes a CHOICE the member has, which
    /// misdescribes a build that cannot observe preferences at all (Copilot
    /// review on PR #1055).
    private var placeholder: some View {
        VStack(spacing: KccSpacing.s2) {
            Text("notifications.settingsTitle")
                .font(.system(size: KccTypeScale.titleMd, weight: KccTypeScale.semibold))
            Text("shell.unavailable")
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(KccSpacing.s4)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func form(
        preferences: NotificationPreferences,
        coordinator: NotificationSettingsCoordinator
    ) -> some View {
        List {
            pushSection

            Section {
                ForEach(NotificationCategories.active, id: \.self) { category in
                    CategoryRow(
                        category: category,
                        preference: preferences.effective(category),
                        isEssential: NotificationCategories.isEssential(category),
                        onToggle: { channel, enabled in
                            Task { await coordinator.toggle(category, channel: channel, enabled: enabled) }
                        }
                    )
                }
            } header: {
                Text("notifications.settingsCategoriesTitle")
            } footer: {
                // The notifications.* vocabulary has no settings-save-error
                // key (see PR notes: notifications.settingsSaveError is
                // MISSING and must be generated, never hand-added); the
                // generic privacySettings.error — "Something went wrong.
                // Please try again." — is the closest accurate existing
                // string for a settings write failure.
                if case .failed = coordinator.saveStatus {
                    Text("privacySettings.error")
                        .foregroundStyle(KccPalette.errorRed)
                } else if coordinator.saveStatus == .saving {
                    Text("notifications.settingsSaving")
                        .foregroundStyle(.secondary)
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    private var pushSection: some View {
        Section {
            Button {
                openSystemSettings()
            } label: {
                Text("notifications.settingsOpenSystemSettings")
            }
        } header: {
            Text("notifications.settingsPushTitle")
        } footer: {
            Text("notifications.permissionRationale")
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
private struct CategoryRow: View {
    let category: NotificationCategory
    let preference: CategoryPreference
    let isEssential: Bool
    let onToggle: (NotificationChannel, Bool) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            Text(LocalizedStringKey(category.labelKey))
                .font(.system(size: KccTypeScale.bodyMd, weight: KccTypeScale.semibold))

            Toggle(isOn: channelBinding(.inApp)) {
                Text("notifications.settingsInApp")
                    .font(.system(size: KccTypeScale.bodySm))
            }
            .disabled(isEssential)

            Toggle(isOn: channelBinding(.push)) {
                Text("notifications.settingsPush")
                    .font(.system(size: KccTypeScale.bodySm))
            }
            .disabled(isEssential)

            if isEssential {
                Text("notifications.settingsEssential")
                    .font(.system(size: KccTypeScale.caption))
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, KccSpacing.s1)
    }

    private func channelBinding(_ channel: NotificationChannel) -> Binding<Bool> {
        Binding(
            get: {
                switch channel {
                case .inApp: return preference.inApp
                case .push: return preference.push
                }
            },
            set: { onToggle(channel, $0) }
        )
    }
}

#Preview {
    NavigationStack {
        NotificationSettingsScreen(coordinator: nil)
    }
}
