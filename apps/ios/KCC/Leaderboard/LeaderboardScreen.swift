import SwiftUI

/// Reads the existing server-ranked board; scope changes still own a single
/// subscription and category changes only choose which loaded rows to show.
struct LeaderboardScreen: View {
    let coordinator: LeaderboardCoordinator?

    var body: some View {
        Group {
            if let coordinator {
                VStack(spacing: 0) {
                    StatsReadingTabs(options: LeaderboardScope.allCases,
                        selection: Binding(get: { coordinator.scope }, set: { coordinator.select(scope: $0) }),
                        title: LeaderboardFormat.scopeTitleKey, identifier: "leaderboard.scopes")
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: KccSpacing.s4) {
                            LeaderboardCategoryPicker(coordinator: coordinator)
                            stateContent(coordinator)
                        }.padding(KccSpacing.s4)
                    }
                    .refreshable { coordinator.reload() }
                }
            } else {
                SocialReadingState(title: "leaderboard.title", message: "leaderboard.categoryEmpty", symbol: "chart.bar")
            }
        }
        .navigationTitle(Text("leaderboard.title"))
        .kccReadingSurface(pageBackground: true)
        .tint(KccTheme.driver)
        .task { coordinator?.start() }
    }

    @ViewBuilder
    private func stateContent(_ coordinator: LeaderboardCoordinator) -> some View {
        switch coordinator.state {
        case .loading:
            ProgressView().frame(maxWidth: .infinity).padding(KccSpacing.s4)
        case .unavailable, .empty:
            SettingsStateCard(title: "leaderboard.title", message: "leaderboard.categoryEmpty", symbol: "chart.bar")
        case .failed:
            SettingsStateCard(title: "leaderboard.title", message: "leaderboard.error", symbol: "exclamationmark.circle",
                retryTitle: "points.retry", onRetry: { coordinator.reload() })
        case .loaded:
            if let board = coordinator.selectedCategoryBoard, !board.entries.isEmpty {
                LeaderboardCategorySection(board: board, coordinator: coordinator)
            } else {
                SettingsStateCard(title: "leaderboard.title", message: "leaderboard.categoryEmpty", symbol: "chart.bar")
            }
        }
    }
}

struct LeaderboardCategoryPicker: View {
    let coordinator: LeaderboardCoordinator

    var body: some View {
        Menu {
            Picker("leaderboard.title", selection: Binding(
                get: { coordinator.selectedCategory }, set: { coordinator.select(category: $0) }
            )) {
                ForEach(coordinator.availableCategories, id: \.self) { category in
                    Text(LeaderboardFormat.categoryTitleKey(category)).tag(category)
                }
            }
        } label: {
            HStack(spacing: KccSpacing.s3) {
                Text(LeaderboardFormat.categoryTitleKey(coordinator.selectedCategory))
                    .font(.headline).fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.down").font(.system(size: 14, weight: .semibold)).accessibilityHidden(true)
            }
            .frame(minHeight: 48)
            .kccContentCard()
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("leaderboard.category")
    }
}

struct LeaderboardCategorySection: View {
    let board: LeaderboardCategoryBoard
    let coordinator: LeaderboardCoordinator

    var body: some View {
        let split = LeaderboardBoard.podiumSplit(board.entries)
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            Text(LeaderboardFormat.categoryTitleKey(board.category)).font(.title3.bold()).accessibilityAddTraits(.isHeader)
            // Preserve the published order, including ties. Large text stacks
            // the same tiles so names, ranks and values never need truncation.
            SocialFlow {
                ForEach(split.top) { entry in
                    LeaderboardPodiumTile(entry: entry, format: board.category.format, coordinator: coordinator)
                }
            }
            ForEach(split.rest) { entry in
                LeaderboardListRow(entry: entry, format: board.category.format, coordinator: coordinator)
            }
        }
    }
}

struct LeaderboardPodiumTile: View {
    let entry: LeaderboardEntry
    let format: LeaderboardValueFormat
    let coordinator: LeaderboardCoordinator

    var body: some View {
        VStack(spacing: KccSpacing.s2) {
            Text(verbatim: LeaderboardFormat.rank(entry.rank))
                .font(.headline).foregroundStyle(KccTheme.driver)
            LeaderboardAvatar(avatarPath: entry.avatarPath, size: entry.rank == 1 ? 48 : 40,
                ringColor: LeaderboardFormat.medalColor(entry.rank), coordinator: coordinator)
            Text(verbatim: entry.displayName).font(.body.weight(entry.isViewer ? .bold : .medium))
                .fixedSize(horizontal: false, vertical: true)
            if entry.isViewer {
                Text("leaderboard.you").font(.footnote).foregroundStyle(KccTheme.driver)
            }
            Text(verbatim: LeaderboardFormat.value(format, entry.value)).font(.headline)
                .fixedSize(horizontal: false, vertical: true)
        }
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
        .kccContentCard(border: entry.isViewer ? KccTheme.driver : KccTheme.border)
        .accessibilityElement(children: .combine)
    }
}

struct LeaderboardListRow: View {
    let entry: LeaderboardEntry
    let format: LeaderboardValueFormat
    let coordinator: LeaderboardCoordinator

    var body: some View {
        SocialFlow {
            HStack(alignment: .top, spacing: KccSpacing.s3) {
                LeaderboardAvatar(avatarPath: entry.avatarPath, size: 40, ringColor: nil, coordinator: coordinator)
                VStack(alignment: .leading, spacing: KccSpacing.s1) {
                    Text(verbatim: LeaderboardFormat.rank(entry.rank)).font(.footnote).foregroundStyle(KccTheme.secondary)
                    Text(verbatim: entry.displayName).font(.body.weight(entry.isViewer ? .bold : .regular))
                        .fixedSize(horizontal: false, vertical: true)
                    if entry.isViewer {
                        Text("leaderboard.you").font(.footnote).foregroundStyle(KccTheme.driver)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            Text(verbatim: LeaderboardFormat.value(format, entry.value)).font(.headline)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .kccContentCard(border: entry.isViewer ? KccTheme.driver : KccTheme.border)
        .accessibilityElement(children: .combine)
    }
}

/// Keeps the original coordinator cache and cancellable per-path resolution.
private struct LeaderboardAvatar: View {
    let avatarPath: String?
    let size: CGFloat
    let ringColor: Color?
    let coordinator: LeaderboardCoordinator
    @State private var url: URL?

    var body: some View {
        AsyncImage(url: url) { phase in
            if let image = phase.image { image.resizable().scaledToFill() }
            else {
                Image(systemName: "person.fill").font(.system(size: size / 2))
                    .foregroundStyle(KccTheme.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(KccTheme.subtle)
            }
        }
        .frame(width: size, height: size).clipShape(Circle())
        .overlay { Circle().strokeBorder(ringColor ?? KccTheme.border, lineWidth: 2).allowsHitTesting(false) }
        .task(id: avatarPath) {
            url = nil
            guard let avatarPath else { return }
            let resolved = await coordinator.avatarURL(for: avatarPath)
            if !Task.isCancelled { url = resolved }
        }
        .accessibilityHidden(true)
    }
}

// MARK: - Formatting

/// The screen's localization + colour helpers, kept out of the views so the
/// contract keys and the medal palette live in one place — the iOS counterpart
/// of Android's `categoryTitleRes` / `formattedValue` / `medalColor`.
enum LeaderboardFormat {
    static func scopeTitleKey(_ scope: LeaderboardScope) -> LocalizedStringKey {
        switch scope {
        case .thisMonth: "leaderboard.scopeThisMonth"
        case .lastMonth: "leaderboard.scopeLastMonth"
        case .allTime: "leaderboard.scopeAllTime"
        }
    }

    /// The localized category header / picker label for `category`.
    static func categoryTitleKey(_ category: LeaderboardCategory) -> LocalizedStringKey {
        switch category {
        case .crownPoints: return "leaderboard.categoryCrownPoints"
        case .distance: return "leaderboard.categoryDistance"
        case .events: return "leaderboard.categoryEvents"
        case .convoys: return "leaderboard.categoryConvoys"
        case .waves: return "leaderboard.categoryWaves"
        case .streak: return "leaderboard.categoryStreak"
        }
    }

    /// "#N" (leaderboard.rank) with the row's rank.
    static func rank(_ rank: Int) -> String {
        String.localizedStringWithFormat(
            NSLocalizedString("leaderboard.rank", comment: "A ranked row's #N label"),
            Int64(rank)
        )
    }

    /// Formats a raw value via the pure transform + the localized unit
    /// template ("%lld km", "%lld CP", …).
    static func value(_ format: LeaderboardValueFormat, _ value: Double) -> String {
        let magnitude = LeaderboardBoard.displayValue(format, value: value)
        let key: String
        switch format {
        case .crownPoints: key = "leaderboard.valueCrownPoints"
        case .distanceKm: key = "leaderboard.valueDistance"
        case .count: key = "leaderboard.valueCount"
        case .waves: key = "leaderboard.valueWaves"
        case .days: key = "leaderboard.valueDays"
        }
        return String.localizedStringWithFormat(
            NSLocalizedString(key, comment: "A leaderboard value with its unit"),
            magnitude
        )
    }

    /// Gold / silver / bronze for ranks 1–3; the brand gold as a fallback —
    /// Android's `medalColor`. Bronze has no design token (the medal set is a
    /// semantic trio), so the third colour is defined here rather than
    /// inventing a palette entry.
    static func medalColor(_ rank: Int) -> Color {
        switch rank {
        case 1: return KccPalette.crownGold
        case 2: return KccPalette.silverGrey
        case 3: return Color(red: 205 / 255, green: 127 / 255, blue: 50 / 255)
        default: return KccPalette.crownGold
        }
    }
}

#Preview {
    NavigationStack {
        LeaderboardScreen(coordinator: nil)
    }
}
