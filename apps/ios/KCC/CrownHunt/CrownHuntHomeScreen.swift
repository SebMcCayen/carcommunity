import SwiftUI

/// The Crown-Hunt hub HOME — the member's own points + stats and this season's
/// standings. The iOS port of Android's `CrownHuntHubContent`, restricted to
/// the MAP-INDEPENDENT read (crowns are collected on the map, a later slice).
///
/// A dumb switch over ``CrownHuntStatsUiState``: every decision lives in the
/// pure ``CrownHuntStatsCoordinator``. The coordinator is nil in a config-less
/// build (no GoogleService-Info.plist → ``FirebaseCrownHuntStatsRepository/createIfAvailable()``
/// returns nil); the screen then shows the unavailable placeholder rather than
/// crashing — the seam every Firebase-backed surface honors.
struct CrownHuntHomeScreen: View {
    let coordinator: CrownHuntStatsCoordinator?

    var body: some View {
        ScrollView {
            VStack(spacing: KccSpacing.s4) {
                content
            }
            .padding(KccSpacing.s4)
        }
        .navigationTitle(Text("crownHunt.screenTitle"))
        .task { coordinator?.start() }
    }

    @ViewBuilder
    private var content: some View {
        if let coordinator {
            switch coordinator.state {
            case .loading:
                CrownHuntMessageState(title: "crownHunt.screenTitle", message: "crownHunt.loading")
            case .empty(let seasonId):
                NoStatsYetCard()
                SeasonStandingsView(board: .empty(seasonId: seasonId))
            case .loaded(let data):
                if let personal = data.personal {
                    PersonalStatsCard(stats: personal)
                } else {
                    NoStatsYetCard()
                }
                SeasonStandingsView(board: data.board)
            case .unavailable:
                CrownHuntMessageState(
                    title: "crownHunt.screenTitle", message: "crownHunt.resultFeatureDisabled"
                )
            case .failed:
                CrownHuntMessageState(
                    title: "crownHunt.screenTitle",
                    message: "crownHunt.statsError",
                    retry: { coordinator.reload() }
                )
            }
        } else {
            CrownHuntMessageState(
                title: "crownHunt.screenTitle", message: "crownHunt.resultFeatureDisabled"
            )
        }
    }
}

/// The member's own Crown-Hunt statistics — Android's `PersonalStatsCard`.
private struct PersonalStatsCard: View {
    let stats: CrownPersonalStats

    var body: some View {
        CrownHuntCard {
            Text("crownHunt.myStatsTitle")
                .font(.headline)
            StatRow(label: "crownHunt.statCrowns", value: "\(stats.crownsCollected)")
            StatRow(label: "crownHunt.statPoints", value: CrownHuntStrings.kpValue(stats.points))
            StatRow(
                label: "crownHunt.statSeasonRank",
                value: stats.seasonRank.map { CrownHuntStrings.rankValue($0) }
                    ?? String(localized: "crownHunt.rankNone")
            )
            StatRow(label: "crownHunt.statStreak", value: "\(stats.streakCurrent)")
            if stats.seasonsWon > 0 {
                StatRow(label: "crownHunt.statSeasonsWon", value: "\(stats.seasonsWon)")
            }
            if let rarest = stats.rarest {
                StatRow(label: "crownHunt.statRarest", value: CrownHuntStrings.rarityName(rarest))
            }
            Text("crownHunt.statsServerNote")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }
}

/// The "collect your first crown" prompt — Android's `NoStatsYetCard`.
private struct NoStatsYetCard: View {
    var body: some View {
        CrownHuntCard {
            Text("crownHunt.myStatsTitle")
                .font(.headline)
            Text("crownHunt.noStatsYet")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
    }
}

/// One "label … value" line inside a stats card.
private struct StatRow: View {
    let label: LocalizedStringKey
    let value: String

    var body: some View {
        HStack {
            Text(label)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Spacer()
            Text(value)
                .font(.subheadline)
                .fontWeight(.semibold)
        }
    }
}

#Preview("Home – loaded") {
    NavigationStack {
        ScrollView {
            VStack(spacing: KccSpacing.s4) {
                SeasonStandingsView(
                    board: CrownSeasonBoard(seasonId: "2026-08", rows: [], viewerRank: nil)
                )
            }
            .padding()
        }
    }
}
