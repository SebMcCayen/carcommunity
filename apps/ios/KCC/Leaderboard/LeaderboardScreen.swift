import SwiftUI

/// The social leaderboard — the iOS slice of Android's `LeaderboardScreen`.
///
/// A read-only view of the precomputed board: an All-time / This-month toggle
/// and a category picker at the top, then, for the chosen category, a podium of
/// the top three and a list down the rest. Everything shown — ranks, names,
/// avatars, ordering — is resolved server-side; this screen only formats each
/// raw value for its category (KP/CP, km, counts, days) and lays it out.
///
/// Where Android STACKS every category on one scroll, iOS offers a category
/// PICKER and shows one at a time (the coordinator owns the selection), so the
/// small phone screen shows a full podium rather than a cramped stack. The
/// `streak` category exists only on the all-time board — ``availableCategories``
/// enforces that, so the monthly picker simply carries four options, not five.
///
/// A dumb switch over ``LeaderboardUiState``: all decisions live in the pure
/// ``LeaderboardCoordinator``. The `coordinator` is nil in a config-less build
/// (no GoogleService-Info.plist → ``FirebaseLeaderboardRepository/createIfAvailable()``
/// returns nil); the screen then renders the placeholder state instead of
/// crashing — the same seam every Firebase-backed surface honors
/// (apps/ios/README.md, "Firebase configuration").
///
/// Reached from ``ShellRoute/leaderboard`` — wired by a later PR; this screen
/// is exported ready, wrapped by the shell in a `NavigationStack` that supplies
/// the Back affordance (like ``EventsScreen``).
struct LeaderboardScreen: View {
    /// Nil in a config-less build; the screen degrades to a placeholder.
    let coordinator: LeaderboardCoordinator?

    var body: some View {
        content
            .navigationTitle(Text("leaderboard.title"))
            .task { coordinator?.start() }
    }

    @ViewBuilder
    private var content: some View {
        if let coordinator {
            ScrollView {
                VStack(spacing: KccSpacing.s4) {
                    ScopeToggle(coordinator: coordinator)
                    CategoryPicker(coordinator: coordinator)
                    stateContent(coordinator)
                }
                .padding(KccSpacing.s4)
            }
            // A Firestore listener terminates on error, so a failed board can
            // only recover by re-subscribing — surfaced as idiomatic
            // pull-to-refresh (Android has no retry at all; its live listener
            // is re-attached by leaving and re-entering the screen).
            .refreshable { coordinator.reload() }
        } else {
            // Config-less build: the board is not wired here.
            messageState(title: "leaderboard.title", body: "leaderboard.categoryEmpty")
        }
    }

    @ViewBuilder
    private func stateContent(_ coordinator: LeaderboardCoordinator) -> some View {
        switch coordinator.state {
        case .loading:
            LeaderboardSkeleton()
        case .unavailable:
            InfoNoticeCard(text: "leaderboard.categoryEmpty")
        case .empty:
            InfoNoticeCard(text: "leaderboard.categoryEmpty")
        case .failed:
            // A soft notice, exactly like Android's Error state — no retry
            // button (a missing `leaderboard.retry` key aside, the live
            // listener has no per-tap re-attach); pull-to-refresh recovers.
            InfoNoticeCard(text: "leaderboard.error")
        case .loaded:
            if let board = coordinator.selectedCategoryBoard, !board.entries.isEmpty {
                CategorySection(board: board, coordinator: coordinator)
            } else {
                // The chosen category has no rows yet, though another does.
                InfoNoticeCard(text: "leaderboard.categoryEmpty")
            }
        }
    }

    private func messageState(title: LocalizedStringKey, body: LocalizedStringKey) -> some View {
        VStack(spacing: KccSpacing.s2) {
            Text(title)
                .font(.system(size: KccTypeScale.titleMd, weight: KccTypeScale.semibold))
            Text(body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(KccSpacing.s4)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Toggle + picker

/// The All-time / This-month scope switch, a two-option segmented control —
/// Android's two-tab `TabRow`.
private struct ScopeToggle: View {
    let coordinator: LeaderboardCoordinator

    var body: some View {
        Picker(
            "leaderboard.scopeAllTime",
            selection: Binding(
                get: { coordinator.scope },
                set: { coordinator.select(scope: $0) }
            )
        ) {
            Text("leaderboard.scopeAllTime").tag(LeaderboardScope.allTime)
            Text("leaderboard.scopeThisMonth").tag(LeaderboardScope.thisMonth)
        }
        .pickerStyle(.segmented)
        .labelsHidden()
    }
}

/// The category picker — a menu of the scope's categories (four or five), so a
/// short list of long Swedish labels ("Ledda konvojer") stays legible where a
/// segmented control would truncate.
private struct CategoryPicker: View {
    let coordinator: LeaderboardCoordinator

    var body: some View {
        Picker(
            "leaderboard.title",
            selection: Binding(
                get: { coordinator.selectedCategory },
                set: { coordinator.select(category: $0) }
            )
        ) {
            ForEach(coordinator.availableCategories, id: \.self) { category in
                Text(LeaderboardFormat.categoryTitleKey(category)).tag(category)
            }
        }
        .pickerStyle(.menu)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Category section

/// One category: a header, then the podium + the remaining ranked list.
private struct CategorySection: View {
    let board: LeaderboardCategoryBoard
    let coordinator: LeaderboardCoordinator

    var body: some View {
        let split = LeaderboardBoard.podiumSplit(board.entries)
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            Text(LeaderboardFormat.categoryTitleKey(board.category))
                .font(.system(size: KccTypeScale.titleMd, weight: KccTypeScale.semibold))
            Podium(top: split.top, format: board.category.format, coordinator: coordinator)
            if !split.rest.isEmpty {
                VStack(spacing: 0) {
                    ForEach(split.rest) { entry in
                        LeaderboardListRow(
                            entry: entry,
                            format: board.category.format,
                            coordinator: coordinator
                        )
                        if entry.id != split.rest.last?.id {
                            Divider()
                        }
                    }
                }
                .background(cardBackground)
                .clipShape(RoundedRectangle(cornerRadius: KccRadius.md))
            }
        }
    }
}

/// The top-three podium — rank order (1, 2, 3) with distinct medal colours; the
/// first-place tile takes a larger avatar. Fewer than three entries yield a
/// shorter row (padded so a lone winner does not stretch full-width).
private struct Podium: View {
    let top: [LeaderboardEntry]
    let format: LeaderboardValueFormat
    let coordinator: LeaderboardCoordinator

    var body: some View {
        HStack(alignment: .bottom, spacing: KccSpacing.s2) {
            ForEach(top) { entry in
                PodiumTile(entry: entry, format: format, coordinator: coordinator)
                    .frame(maxWidth: .infinity)
            }
            ForEach(0..<max(0, LeaderboardBoard.podiumSize - top.count), id: \.self) { _ in
                Color.clear.frame(maxWidth: .infinity)
            }
        }
    }
}

private struct PodiumTile: View {
    let entry: LeaderboardEntry
    let format: LeaderboardValueFormat
    let coordinator: LeaderboardCoordinator

    var body: some View {
        let medal = LeaderboardFormat.medalColor(entry.rank)
        VStack(spacing: KccSpacing.s1) {
            RankBadge(rank: entry.rank, color: medal)
            LeaderboardAvatar(
                avatarPath: entry.avatarPath,
                size: entry.rank == 1 ? KccSpacing.s12 : KccSpacing.s10,
                ringColor: medal,
                coordinator: coordinator
            )
            Text(entry.displayName)
                .font(.system(size: KccTypeScale.bodyMd, weight: entry.isViewer ? .bold : .medium))
                .lineLimit(1)
                .truncationMode(.tail)
                .multilineTextAlignment(.center)
            if entry.isViewer {
                Text("leaderboard.you")
                    .font(.system(size: KccTypeScale.caption))
                    .foregroundStyle(KccPalette.crownGold)
            }
            Text(LeaderboardFormat.value(format, entry.value))
                .font(.system(size: KccTypeScale.bodyMd, weight: .semibold))
                .foregroundStyle(KccPalette.crownGold)
                .multilineTextAlignment(.center)
        }
        .padding(.vertical, KccSpacing.s3)
        .padding(.horizontal, KccSpacing.s2)
        .frame(maxWidth: .infinity)
        .background(cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: KccRadius.md))
    }
}

/// A single ranked line (rank 4 downwards).
private struct LeaderboardListRow: View {
    let entry: LeaderboardEntry
    let format: LeaderboardValueFormat
    let coordinator: LeaderboardCoordinator

    var body: some View {
        HStack(spacing: KccSpacing.s3) {
            Text(LeaderboardFormat.rank(entry.rank))
                .font(.system(size: KccTypeScale.bodyMd, weight: .semibold))
                .foregroundStyle(.secondary)
                .frame(width: KccSpacing.s8, alignment: .leading)
            LeaderboardAvatar(
                avatarPath: entry.avatarPath,
                size: KccSpacing.s8,
                ringColor: nil,
                coordinator: coordinator
            )
            Text(entry.displayName)
                .font(.system(size: KccTypeScale.bodyMd, weight: entry.isViewer ? .bold : .regular))
                .lineLimit(1)
                .truncationMode(.tail)
                .frame(maxWidth: .infinity, alignment: .leading)
            if entry.isViewer {
                Text("leaderboard.you")
                    .font(.system(size: KccTypeScale.caption))
                    .foregroundStyle(KccPalette.crownGold)
            }
            Text(LeaderboardFormat.value(format, entry.value))
                .font(.system(size: KccTypeScale.bodyMd, weight: .semibold))
        }
        .padding(.horizontal, KccSpacing.s4)
        .padding(.vertical, KccSpacing.s3)
    }
}

// MARK: - Pieces

/// A round rank chip in the medal colour.
private struct RankBadge: View {
    let rank: Int
    let color: Color

    var body: some View {
        Text(String(rank))
            .font(.system(size: KccTypeScale.bodySm, weight: .bold))
            .foregroundStyle(.white)
            .frame(width: KccSpacing.s6, height: KccSpacing.s6)
            .background(color)
            .clipShape(Circle())
    }
}

/// A circular member avatar; `ringColor` draws a medal ring on the podium. The
/// download URL is resolved lazily through the coordinator (Storage path →
/// URL), so a config-less build or a member with no avatar simply shows the
/// person placeholder.
private struct LeaderboardAvatar: View {
    let avatarPath: String?
    let size: CGFloat
    let ringColor: Color?
    let coordinator: LeaderboardCoordinator

    @State private var url: URL?

    var body: some View {
        ZStack {
            Circle().fill(ringColor ?? KccPalette.silverGrey.opacity(0.3))
            Circle()
                .fill(KccPalette.silverGrey.opacity(0.3))
                .padding(ringColor != nil ? 2 : 0)
                .overlay {
                    if let url {
                        AsyncImage(url: url) { image in
                            image.resizable().scaledToFill()
                        } placeholder: {
                            placeholderIcon
                        }
                        .clipShape(Circle())
                        .padding(ringColor != nil ? 2 : 0)
                    } else {
                        placeholderIcon
                    }
                }
        }
        .frame(width: size, height: size)
        .task(id: avatarPath) {
            url = nil
            guard let avatarPath else { return }
            let resolved = await coordinator.avatarURL(for: avatarPath)
            if !Task.isCancelled { url = resolved }
        }
    }

    private var placeholderIcon: some View {
        Image(systemName: "person.fill")
            .font(.system(size: size / 2))
            .foregroundStyle(.secondary)
    }
}

/// A calm loading skeleton: a header bar, a podium row and a few list lines
/// drawn as neutral rounded blocks — Android's `LeaderboardSkeleton`.
private struct LeaderboardSkeleton: View {
    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            SkeletonBlock(width: KccSpacing.s12, height: KccSpacing.s5)
            HStack(spacing: KccSpacing.s2) {
                ForEach(0..<3, id: \.self) { _ in
                    SkeletonBlock(width: nil, height: KccSpacing.s12)
                        .frame(maxWidth: .infinity)
                }
            }
            ForEach(0..<3, id: \.self) { _ in
                SkeletonBlock(width: nil, height: KccSpacing.s8)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct SkeletonBlock: View {
    let width: CGFloat?
    let height: CGFloat

    var body: some View {
        RoundedRectangle(cornerRadius: KccRadius.sm)
            .fill(KccPalette.silverGrey.opacity(0.3))
            .frame(width: width, height: height)
            .frame(maxWidth: width == nil ? .infinity : nil, alignment: .leading)
    }
}

/// A soft, neutral notice for the empty / unavailable states: an info icon plus
/// muted text — Android's `InfoNoticeCard`.
private struct InfoNoticeCard: View {
    let text: LocalizedStringKey

    var body: some View {
        HStack(spacing: KccSpacing.s3) {
            Image(systemName: "info.circle")
                .foregroundStyle(.secondary)
            Text(text)
                .font(.system(size: KccTypeScale.bodyMd))
                .foregroundStyle(.secondary)
            Spacer(minLength: 0)
        }
        .padding(KccSpacing.s4)
        .frame(maxWidth: .infinity)
        .background(cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: KccRadius.md))
    }
}

/// The subtle card fill shared by the podium tiles, list card and notices —
/// the semantic surface token, tracking light/dark.
private var cardBackground: Color {
    Color(.secondarySystemBackground)
}

// MARK: - Formatting

/// The screen's localization + colour helpers, kept out of the views so the
/// contract keys and the medal palette live in one place — the iOS counterpart
/// of Android's `categoryTitleRes` / `formattedValue` / `medalColor`.
enum LeaderboardFormat {
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
