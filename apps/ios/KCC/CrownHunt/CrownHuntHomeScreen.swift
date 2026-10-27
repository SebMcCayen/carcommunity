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
    var showsFullStatistics = false

    var body: some View {
        ScrollView {
            VStack(spacing: KccSpacing.s4) {
                content
                if !showsFullStatistics {
                    NavigationLink { CrownInstructionsScreen() } label: {
                        Label("crownHunt.instrButton", systemImage: "info.circle")
                            .frame(maxWidth: .infinity, minHeight: 48).contentShape(Rectangle())
                    }.buttonStyle(.bordered)
                    .accessibilityIdentifier("crown.instructions.open")
                }
            }
            .padding(KccSpacing.s4)
        }
        .kccReadingSurface(pageBackground: true)
        .accessibilityIdentifier(showsFullStatistics ? "crown.statistics" : "crown.overview")
        .navigationTitle(Text(showsFullStatistics ? LocalizedStringKey("crownHunt.statisticsTab") : LocalizedStringKey("crownHunt.screenTitle")))
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
                NavigationLink {
                    SeasonStandingsScreen(coordinator: coordinator)
                } label: {
                    SeasonStandingsView(board: .empty(seasonId: seasonId))
                }
                .buttonStyle(.plain)
            case .loaded(let data):
                if let personal = data.personal {
                    if showsFullStatistics { PersonalStatsCard(stats: personal) }
                    else { CrownSeasonSummary(stats: personal, seasonId: data.board.seasonId) }
                } else {
                    NoStatsYetCard()
                }
                NavigationLink {
                    SeasonStandingsScreen(coordinator: coordinator)
                } label: {
                    SeasonStandingsView(board: data.board)
                }
                .buttonStyle(.plain)
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
struct PersonalStatsCard: View {
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
struct NoStatsYetCard: View {
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
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let label: LocalizedStringKey
    let value: String

    var body: some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: KccSpacing.s1))
            : AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: KccSpacing.s3))
        layout {
            Text(label)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            if !dynamicTypeSize.isAccessibilitySize { Spacer(minLength: KccSpacing.s2) }
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

/// Season aggregates are earned points, never the spendable shop balance.
struct CrownSeasonSummary: View {
    let stats: CrownPersonalStats
    let seasonId: String

    var body: some View {
        CrownHuntCard {
            Label(seasonId, systemImage: "calendar").font(.headline)
            Text("crownHunt.seasonPoints").font(.subheadline).foregroundStyle(KccTheme.secondary)
            Text(CrownHuntStrings.kpValue(stats.seasonPoints))
                .font(.largeTitle.weight(.bold)).fixedSize(horizontal: false, vertical: true)
            StatRow(label: "crownHunt.statSeasonRank", value: stats.seasonRank.map(CrownHuntStrings.rankValue)
                ?? String(localized: "crownHunt.rankNone"))
            StatRow(label: "crownHunt.statCrowns", value: String(stats.seasonCrowns))
        }
    }
}
