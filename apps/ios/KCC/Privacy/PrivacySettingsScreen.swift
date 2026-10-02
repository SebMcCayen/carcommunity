import SwiftUI

/// Native iOS privacy controls matching Android's PartnerStats screen and
/// leaderboard-visibility section.
struct PrivacySettingsScreen: View {
    @Bindable var coordinator: PrivacySettingsCoordinator

    var body: some View {
        content
            .navigationTitle(Text("privacySettings.title"))
            .task { coordinator.start() }
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
                    set: coordinator.setPendingPartnerStatsOptIn
                )
            )
            .disabled(!coordinator.canEdit || coordinator.partnerSaveStatus == .saving)

            saveButton(status: coordinator.partnerSaveStatus) {
                await coordinator.savePartnerStats()
            }
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
                    set: coordinator.setPendingLeaderboardShown
                )
            )
            .disabled(!coordinator.canEdit || coordinator.leaderboardSaveStatus == .saving)

            saveButton(status: coordinator.leaderboardSaveStatus) {
                await coordinator.saveLeaderboardVisibility()
            }
        } header: {
            Text("privacySettings.leaderboardTitle")
        } footer: {
            VStack(alignment: .leading, spacing: KccSpacing.s2) {
                Text("privacySettings.leaderboardExplainer")
                statusText(coordinator.leaderboardSaveStatus)
            }
        }
    }

    private func saveButton(
        status: PrivacySettingsSaveStatus,
        action: @escaping @MainActor () async -> Void
    ) -> some View {
        Button {
            Task { await action() }
        } label: {
            if status == .saving {
                ProgressView().frame(maxWidth: .infinity)
            } else {
                Text("privacySettings.saveButton").frame(maxWidth: .infinity)
            }
        }
        .disabled(!coordinator.canEdit || status == .saving)
        .accessibilityIdentifier("privacySettings.save")
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
