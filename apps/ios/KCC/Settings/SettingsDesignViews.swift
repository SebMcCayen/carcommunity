import SwiftUI

/// Labels share one reading style; decorative glyphs never cap the user's text size.
struct SettingsRowLabel: View {
    let title: LocalizedStringKey
    let symbol: String
    var destructive = false
    var external = false

    var body: some View {
        HStack(spacing: KccSpacing.s3) {
            Image(systemName: symbol).font(.system(size: 20))
                .foregroundStyle(destructive ? KccPalette.errorRed : KccTheme.driver)
                .frame(width: 40, height: 40)
                .background(KccTheme.subtle, in: RoundedRectangle(cornerRadius: KccRadius.sm))
                .accessibilityHidden(true)
            Text(title).font(.body)
                .foregroundStyle(destructive ? KccPalette.errorRed : KccTheme.text)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: external ? "arrow.up.right" : "chevron.right")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(KccTheme.secondary).accessibilityHidden(true)
        }
        .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
        .padding(.vertical, KccSpacing.s1)
        .contentShape(Rectangle())
    }
}

struct SettingsStateCard: View {
    let title: LocalizedStringKey
    let message: LocalizedStringKey
    let symbol: String
    var retryTitle: LocalizedStringKey? = nil
    var onRetry: (() -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            Image(systemName: symbol).font(.title).foregroundStyle(KccTheme.driver)
                .accessibilityHidden(true)
            Text(title).font(.title2.bold()).accessibilityAddTraits(.isHeader)
            Text(message).font(.body).foregroundStyle(KccTheme.secondary)
                .fixedSize(horizontal: false, vertical: true)
            if let retryTitle, let onRetry {
                Button(action: onRetry) {
                    Text(retryTitle).frame(minHeight: 48)
                }.kccPrimaryAction()
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .kccContentCard()
    }
}

/// Uses the parent's existing draft and independent save status; no auto-save.
struct PrivacyPreferenceCard: View {
    let title: LocalizedStringKey
    let label: LocalizedStringKey
    let explanations: [LocalizedStringKey]
    @Binding var value: Bool
    let status: PrivacySettingsSaveStatus
    let canEdit: Bool
    let saveIdentifier: String
    let onSave: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            Text(title).font(.title3.bold()).accessibilityAddTraits(.isHeader)
            ForEach(explanations.indices, id: \.self) { index in
                Text(explanations[index]).font(.body).foregroundStyle(KccTheme.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Toggle(isOn: $value) {
                Text(label).font(.body).fixedSize(horizontal: false, vertical: true)
            }
            .frame(minHeight: 48)
            .tint(KccTheme.driver)
            .disabled(!canEdit || status == .saving)
            Button(action: onSave) {
                HStack(spacing: KccSpacing.s2) {
                    if status == .saving { ProgressView().accessibilityHidden(true) }
                    Text("privacySettings.saveButton").font(.body.weight(.semibold))
                }
                .frame(maxWidth: .infinity, minHeight: 48)
            }
            .kccPrimaryAction()
            .disabled(!canEdit || status == .saving)
            .accessibilityValue(status == .saving ? Text("notifications.settingsSaving") : Text(""))
            .accessibilityIdentifier(saveIdentifier)
            switch status {
            case .saved:
                Label("privacySettings.saved", systemImage: "checkmark.circle")
                    .foregroundStyle(KccPalette.successGreen)
            case .failed:
                Text("privacySettings.error").foregroundStyle(KccPalette.errorRed)
            case .idle, .saving:
                EmptyView()
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .kccContentCard()
    }
}

struct NotificationPermissionCard: View {
    let state: PushPermissionState
    let requesting: Bool
    let onRequest: () -> Void
    let onOpenSettings: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            Text("notifications.settingsPushTitle").font(.title3.bold()).accessibilityAddTraits(.isHeader)
            Text("notifications.permissionRationale").font(.body).foregroundStyle(KccTheme.secondary)
                .fixedSize(horizontal: false, vertical: true)
            switch state {
            case .notDetermined:
                Button(action: onRequest) {
                    Text("notifications.permissionEnable").frame(minHeight: 48)
                }.kccPrimaryAction().disabled(requesting)
            case .denied:
                Button(action: onOpenSettings) {
                    Text("notifications.settingsOpenSystemSettings").frame(minHeight: 48)
                }.buttonStyle(.bordered)
            case .enabled:
                Label("notifications.permissionEnabled", systemImage: "checkmark.circle.fill")
                    .foregroundStyle(KccPalette.successGreen)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .kccContentCard()
    }
}

struct BlockedUserCard: View {
    let user: BlockedUser
    let working: Bool
    let onRequestUnblock: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            SocialMemberLabel(name: user.displayName, fallback: "blocking.unknownUser")
            if let blockedAt = user.blockedAt {
                Text(blockedAt, format: .dateTime.year().month().day())
                    .font(.caption).foregroundStyle(KccTheme.secondary)
            }
            Button(action: onRequestUnblock) {
                Text("blocking.unblock").frame(minHeight: 48)
            }
            .buttonStyle(.bordered)
            .disabled(working)
            .accessibilityIdentifier("blockedUsers.unblock.\(user.userId)")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .kccContentCard()
        .accessibilityElement(children: .contain)
    }
}
