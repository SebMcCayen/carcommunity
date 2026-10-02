import SwiftUI

/// Native iOS privacy controls matching Android's PartnerStats screen and
/// leaderboard-visibility section.
struct PrivacySettingsScreen: View {
    @Bindable var coordinator: PrivacySettingsCoordinator

    var body: some View {
        content
            .navigationTitle(Text("privacySettings.title"))
            .task { coordinator.start() }
            .onDisappear { coordinator.stop() }
    }

    @ViewBuilder
    private var content: some View {
        switch coordinator.state {
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .unavailable:
            unavailable
        case .failed:
            VStack(spacing: KccSpacing.s3) {
                Text("privacySettings.error")
                    .multilineTextAlignment(.center)
                Button("privacySettings.retry") { coordinator.reload() }
                    .buttonStyle(.borderedProminent)
            }
            .padding(KccSpacing.s6)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .loaded:
            settingsList
        }
    }

    private var unavailable: some View {
        VStack(spacing: KccSpacing.s2) {
            Text("privacySettings.unavailable")
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(KccSpacing.s4)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var settingsList: some View {
        List {
            partnerStatsSection
            leaderboardSection
        }
        .listStyle(.insetGrouped)
    }

    private var partnerStatsSection: some View {
        Section {
            Toggle(
                "privacySettings.partnerStatsBody",
                isOn: Binding(
                    get: { coordinator.draft?.partnerStatsOptIn ?? true },
                    set: { value in coordinator.setPendingPartnerStatsOptIn(value) }
                )
            )
            .disabled(!coordinator.canEdit || coordinator.partnerSaveStatus == .saving)

            Button {
                Task { await coordinator.savePartnerStats() }
            } label: {
                saveButtonLabel(status: coordinator.partnerSaveStatus)
            }
            .disabled(!coordinator.canEdit || coordinator.partnerSaveStatus == .saving)
            .accessibilityIdentifier("privacySettings.partnerStats.save")
        } header: {
            Text("privacySettings.partnerStatsTitle")
        } footer: {
            VStack(alignment: .leading, spacing: KccSpacing.s2) {
                Text("privacySettings.partnerStatsExplainer")
                Text("privacySettings.partnerStatsNotice")
                Text("privacySettings.partnerStatsNote")
                statusText(coordinator.partnerSaveStatus)
            }
        }
    }

    private var leaderboardSection: some View {
        Section {
            Toggle(
                "privacySettings.leaderboardBody",
                isOn: Binding(
                    get: { coordinator.draft?.leaderboardShown ?? true },
                    set: { value in coordinator.setPendingLeaderboardShown(value) }
                )
            )
            .disabled(!coordinator.canEdit || coordinator.leaderboardSaveStatus == .saving)

            Button {
                Task { await coordinator.saveLeaderboardVisibility() }
            } label: {
                saveButtonLabel(status: coordinator.leaderboardSaveStatus)
            }
            .disabled(!coordinator.canEdit || coordinator.leaderboardSaveStatus == .saving)
            .accessibilityIdentifier("privacySettings.leaderboard.save")
        } header: {
            Text("privacySettings.leaderboardTitle")
        } footer: {
            VStack(alignment: .leading, spacing: KccSpacing.s2) {
                Text("privacySettings.leaderboardExplainer")
                statusText(coordinator.leaderboardSaveStatus)
            }
        }
    }

    @ViewBuilder
    private func saveButtonLabel(status: PrivacySettingsSaveStatus) -> some View {
        if status == .saving {
            ProgressView().frame(maxWidth: .infinity)
        } else {
            Text("privacySettings.saveButton").frame(maxWidth: .infinity)
        }
    }

    @ViewBuilder
    private func statusText(_ status: PrivacySettingsSaveStatus) -> some View {
        switch status {
        case .saved:
            Text("privacySettings.saved").foregroundStyle(.green)
        case .failed:
            Text("privacySettings.error").foregroundStyle(KccPalette.errorRed)
        case .idle, .saving:
            EmptyView()
        }
    }
}

#Preview {
    NavigationStack {
        PrivacySettingsScreen(
            coordinator: PrivacySettingsCoordinator(repository: nil, uid: nil)
        )
    }
}
