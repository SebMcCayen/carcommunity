import SwiftUI

/// This season's ranked top scores, with the viewer's own row highlighted —
/// the iOS port of Android's `SeasonLeaderboardCard` + `LeaderboardRow`.
///
/// A PURE view over a ``CrownSeasonBoard`` (no coordinator/Firebase), so it is
/// reused both inline on the hub home and as its own season-standings screen,
/// and is trivially previewable.
struct SeasonStandingsView: View {
    let board: CrownSeasonBoard

    var body: some View {
        CrownHuntCard {
            Text("crownHunt.leaderboardTitle")
                .font(.headline)
            if board.rows.isEmpty {
                Text("crownHunt.leaderboardEmpty")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(board.rows) { row in
                    LeaderboardRowView(row: row)
                }
            }
        }
    }
}

/// One leaderboard line: "#rank  name … N CP", the viewer's own emphasized.
private struct LeaderboardRowView: View {
    let row: CrownLeaderboardRow

    var body: some View {
        HStack(spacing: KccSpacing.s2) {
            Text(CrownHuntStrings.rankValue(row.rank))
                .font(.subheadline)
                .fontWeight(row.isViewer ? .bold : .regular)
                .foregroundStyle(.secondary)
            Text(row.displayName)
                .font(.subheadline)
                .fontWeight(row.isViewer ? .bold : .regular)
                .foregroundStyle(row.isViewer ? KccPalette.crownGold : Color.primary)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(CrownHuntStrings.kpValue(row.points))
                .font(.subheadline)
                .fontWeight(row.isViewer ? .bold : .regular)
        }
        .padding(.vertical, KccSpacing.s1)
    }
}

/// The season standings as its own screen — a scrollable full board fed by a
/// ``CrownHuntStatsCoordinator`` (the same one-shot read the hub uses; the board
/// travels with the personal stats). Reached from the hub as a distinct
/// destination in a later wiring PR (the shell has a `ShellRoute.crownHunt`
/// case; this surface is exported ready for it).
struct SeasonStandingsScreen: View {
    let coordinator: CrownHuntStatsCoordinator?

    var body: some View {
        ScrollView {
            VStack(spacing: KccSpacing.s4) {
                content
            }
            .padding(KccSpacing.s4)
        }
        .navigationTitle(Text("crownHunt.leaderboardTitle"))
        .task { coordinator?.start() }
    }

    @ViewBuilder
    private var content: some View {
        if let coordinator {
            switch coordinator.state {
            case .loading:
                CrownHuntMessageState(title: "crownHunt.screenTitle", message: "crownHunt.loading")
            case .empty(let seasonId):
                SeasonStandingsView(board: .empty(seasonId: seasonId))
            case .loaded(let data):
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

#Preview("Season standings") {
    NavigationStack {
        ScrollView {
            SeasonStandingsView(
                board: CrownSeasonBoard(
                    seasonId: "2026-08",
                    rows: [
                        CrownLeaderboardRow(
                            rank: 1, uid: "a", displayName: "Sebbe",
                            points: 420, crownsCollected: 12, isViewer: false
                        ),
                        CrownLeaderboardRow(
                            rank: 2, uid: "b", displayName: "You",
                            points: 310, crownsCollected: 9, isViewer: true
                        ),
                    ],
                    viewerRank: 2
                )
            )
            .padding()
        }
    }
}
