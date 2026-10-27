import SwiftUI

/// Native iOS privacy controls matching Android's PartnerStats screen and
/// leaderboard-visibility section.
struct PrivacySettingsScreen: View {
    @Bindable var coordinator: PrivacySettingsCoordinator

    var body: some View {
        content
            .navigationTitle(Text("privacySettings.title"))
            .kccReadingSurface(pageBackground: true)
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
            ScrollView {
                SettingsStateCard(title: "privacySettings.title", message: "privacySettings.error", symbol: "lock.shield",
                    retryTitle: "privacySettings.retry", onRetry: coordinator.reload).padding(KccSpacing.s4)
            }
        case .loaded:
            settingsList
        }
    }

    private var unavailable: some View {
        ScrollView {
            SettingsStateCard(title: "privacySettings.title", message: "privacySettings.unavailable", symbol: "lock.shield")
                .padding(KccSpacing.s4)
        }
    }

    private var settingsList: some View {
        ScrollView {
            LazyVStack(spacing: KccSpacing.s4) {
                PrivacyPreferenceCard(title: "privacySettings.partnerStatsTitle", label: "privacySettings.partnerStatsBody",
                    explanations: ["privacySettings.partnerStatsExplainer", "privacySettings.partnerStatsNotice", "privacySettings.partnerStatsNote"],
                    value: Binding(
                        get: { coordinator.draft?.partnerStatsOptIn ?? true },
                        set: { coordinator.setPendingPartnerStatsOptIn($0) }),
                    status: coordinator.partnerSaveStatus, canEdit: coordinator.canEdit,
                    saveIdentifier: "privacySettings.partnerStats.save",
                    onSave: { Task { await coordinator.savePartnerStats() } })
                PrivacyPreferenceCard(title: "privacySettings.leaderboardTitle", label: "privacySettings.leaderboardBody",
                    explanations: ["privacySettings.leaderboardExplainer"],
                    value: Binding(
                        get: { coordinator.draft?.leaderboardShown ?? true },
                        set: { coordinator.setPendingLeaderboardShown($0) }),
                    status: coordinator.leaderboardSaveStatus, canEdit: coordinator.canEdit,
                    saveIdentifier: "privacySettings.leaderboard.save",
                    onSave: { Task { await coordinator.saveLeaderboardVisibility() } })
            }.padding(KccSpacing.s4)
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
