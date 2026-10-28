import SwiftUI

enum DriveDeleteStatus: Equatable {
    case ready
    case deleting

    var catalogKey: String {
        switch self {
        case .ready: "savedDrives.deleteAction"
        case .deleting: "savedDrives.deletingProgress"
        }
    }

    var localizedKey: LocalizedStringKey {
        switch self {
        case .ready: "savedDrives.deleteAction"
        case .deleting: "savedDrives.deletingProgress"
        }
    }
}

struct DrivesPanel: View {
    @State private var coordinator: DriveHistoryCoordinator
    let sharingEnabled: Bool
    @State private var selectedDrive: SavedDrive?
    @State private var showingStats = false
    @State private var showingFilters = false
    @State private var pendingDelete: SavedDrive?

    init(sharingEnabled: Bool = true) {
        self.init(
            coordinator: DriveHistoryCoordinator(
                repository: FirebaseDriveHistoryRepository.createIfAvailable()
            ),
            sharingEnabled: sharingEnabled
        )
    }

    init(coordinator: DriveHistoryCoordinator, sharingEnabled: Bool = true) {
        _coordinator = State(initialValue: coordinator)
        self.sharingEnabled = sharingEnabled
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: KccSpacing.s4) {
                HStack(alignment: .top) {
                    Text("shell.moreDrives")
                        .font(.title2.weight(.semibold)).accessibilityAddTraits(.isHeader)
                    Spacer()
                    Button { showingStats = true } label: {
                        Image(systemName: "chart.bar").frame(minWidth: 48, minHeight: 48).contentShape(Rectangle())
                    }
                    .accessibilityLabel(Text("savedDrives.statsEntryAction"))
                    .accessibilityIdentifier("myDrives.stats")
                }
                historyContent
            }
            .padding(KccSpacing.s4)
        }
        .kccReadingSurface(pageBackground: true)
        .tint(KccTheme.driver)
        .task { await coordinator.load() }
        .sheet(item: $selectedDrive) { drive in
            SavedDriveDetailScreen(
                coordinator: coordinator,
                drive: drive,
                sharingEnabled: sharingEnabled
            ) {
                pendingDelete = drive
            }
        }
        .sheet(isPresented: $showingStats) { DriveStatsScreen(coordinator: coordinator) }
        .confirmationDialog(
            "savedDrives.deleteConfirmTitle",
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("savedDrives.deleteConfirmAction", role: .destructive) {
                guard let drive = pendingDelete else { return }
                pendingDelete = nil
                selectedDrive = nil
                Task { await coordinator.delete(drive) }
            }
            Button("savedDrives.deleteConfirmCancel", role: .cancel) { pendingDelete = nil }
        } message: { Text("savedDrives.deleteConfirmBody") }
    }

    @ViewBuilder private var historyContent: some View {
        switch coordinator.state {
        case .loading: ProgressView("savedDrives.loading")
        case .unavailable: SettingsStateCard(title: "shell.moreDrives", message: "savedDrives.error", symbol: "car")
        case .failed:
            SettingsStateCard(title: "shell.moreDrives", message: "savedDrives.error", symbol: "exclamationmark.circle",
                retryTitle: "savedDrives.retry", onRetry: { Task { await coordinator.load() } })
        case .loaded: loadedHistory
        }
    }

    @ViewBuilder private var loadedHistory: some View {
        if coordinator.hiddenDriveCount > 0 {
            Text(String(format: String(localized: "savedDrives.tierRestrictedBanner"), coordinator.hiddenDriveCount))
                .font(.subheadline).foregroundStyle(KccTheme.secondary)
        }
        Button { showingFilters.toggle() } label: {
            HStack {
                Label(showingFilters ? "savedDrives.filterToggleCollapse" : "savedDrives.filterToggleExpand",
                      systemImage: "line.3.horizontal.decrease.circle")
                Spacer()
                if coordinator.filters.activeFilterCount > 0 {
                    Text("\(coordinator.filters.activeFilterCount)").font(.footnote.bold())
                        .padding(KccSpacing.s2).background(KccTheme.driver, in: Capsule()).foregroundStyle(KccTheme.onDriver)
                }
            }.frame(minHeight: 48)
        }.buttonStyle(.plain)
        if showingFilters { filters }

        if coordinator.drives.isEmpty {
            Text("savedDrives.empty").foregroundStyle(.secondary)
        } else if coordinator.visibleDrives.isEmpty {
            Text("savedDrives.filterNoMatches").foregroundStyle(.secondary)
            Button { coordinator.filters = .init() } label: { Text("savedDrives.filterNoMatchesAction").frame(minHeight: 48) }.buttonStyle(.bordered)
        } else {
            ForEach(coordinator.visibleDrives) { drive in
                Button { selectedDrive = drive } label: {
                    DriveHistoryCard(drive: drive,
                        carImageURL: drive.carImagePath.flatMap { coordinator.imageURLs[$0] })
                }
                .buttonStyle(.plain)
                .contextMenu {
                    if sharingEnabled {
                        ShareLink(item: DriveShareText.summary(for: drive)) {
                            Label("savedDrives.shareAction", systemImage: "square.and.arrow.up")
                        }
                    }
                    Button(role: .destructive) { pendingDelete = drive } label: {
                        Label("savedDrives.deleteAction", systemImage: "trash")
                    }
                }
            }
        }
        // Paging belongs to the loaded history, not to the filtered result.
        // A page with zero local matches can still lead to a later matching
        // page, so keep Load more/retry reachable in that state.
        pagingControls
        if coordinator.deleteFailed { Text("savedDrives.deleteError").foregroundStyle(KccPalette.errorRed) }
    }

    private var filters: some View { DriveFilterControls(criteria: $coordinator.filters) }

    @ViewBuilder private var pagingControls: some View {
        if coordinator.loadingMore { ProgressView("savedDrives.loadingMore") }
        else if coordinator.loadMoreFailed {
            Text("savedDrives.loadMoreError").foregroundStyle(KccPalette.errorRed)
            Button { Task { await coordinator.loadMore() } } label: { Text("savedDrives.retryAction").frame(minHeight: 48) }.buttonStyle(.bordered)
        } else if coordinator.hasMore {
            Button { Task { await coordinator.loadMore() } } label: {
                Text("savedDrives.loadMore").frame(maxWidth: .infinity, minHeight: 48)
            }.buttonStyle(.bordered)
        }
    }
}

struct DriveHistoryCard: View {
    let drive: SavedDrive
    let carImageURL: URL?
    var body: some View {
        HStack(alignment: .top, spacing: KccSpacing.s3) {
            VStack(alignment: .leading, spacing: KccSpacing.s3) {
                SocialFlow {
                    DriveRouteThumbnailView(encoded: drive.routeThumbnail).frame(width: 72, height: 56)
                    VStack(alignment: .leading, spacing: KccSpacing.s2) {
                        Text(verbatim: DriveDisplay.title(for: drive)).font(.headline).fixedSize(horizontal: false, vertical: true)
                        Text(verbatim: DriveDisplay.statsLine(for: drive)).font(.subheadline).foregroundStyle(KccTheme.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }
                if !drive.convoyMembers.isEmpty {
                    Label(DriveDisplay.convoyLine(for: drive), systemImage: "person.2").font(.subheadline)
                        .foregroundStyle(KccTheme.secondary).fixedSize(horizontal: false, vertical: true)
                }
                if drive.carImagePath != nil {
                    AsyncImage(url: carImageURL) { phase in
                        if let image = phase.image { image.resizable().scaledToFill() }
                        else { Image(systemName: "car").foregroundStyle(KccTheme.secondary) }
                    }.frame(width: 44, height: 44).clipShape(Circle()).accessibilityHidden(true)
                }
            }.frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: "chevron.right").font(.system(size: 14, weight: .semibold))
                .foregroundStyle(KccTheme.secondary).accessibilityHidden(true)
        }
        .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading).kccContentCard().contentShape(Rectangle())
    }
}

private struct DriveRouteThumbnailView: View {
    let encoded: String?
    var body: some View {
        let points = DriveRouteThumbnail.decode(encoded)
        Canvas { context, size in
            guard points.count >= 2 else { return }
            var path = Path()
            for (index, point) in points.enumerated() {
                let rendered = CGPoint(x: 6 + point.x * (size.width - 12), y: 6 + point.y * (size.height - 12))
                index == 0 ? path.move(to: rendered) : path.addLine(to: rendered)
            }
            context.stroke(path, with: .color(KccTheme.driver), style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
        }
        .background(KccTheme.subtle, in: RoundedRectangle(cornerRadius: KccRadius.sm))
        .overlay { if points.count < 2 { Image(systemName: "map").foregroundStyle(.secondary) } }
        .accessibilityLabel("savedDrives.routeThumbnailLabel")
    }
}

enum DriveDisplay {
    static func title(for drive: SavedDrive) -> String {
        if let title = drive.title?.trimmingCharacters(in: .whitespacesAndNewlines), !title.isEmpty { return title }
        if let date = drive.createdAt ?? drive.startedAt { return date.formatted(date: .abbreviated, time: .omitted) }
        return String(localized: "savedDrives.detailTitle")
    }
    static func statsLine(for drive: SavedDrive) -> String {
        [DriveFormatters.formatDistance(drive.distanceMeters),
         DriveFormatters.formatDuration(drive.durationSeconds),
         String(format: String(localized: "savedDrives.maxSpeedShort"), DriveFormatters.formatSpeed(drive.maxSpeedMetersPerSecond))]
            .joined(separator: " · ")
    }
    static func convoyLine(for drive: SavedDrive) -> String {
        String(format: String(localized: "savedDrives.convoyDroveWith"),
               ConvoyDriveMembers.joinedNames(drive.convoyMembers,
                   unknownLabel: String(localized: "savedDrives.convoyMemberUnknown")))
    }
}

enum DriveShareText {
    /// Statistics-only by design: exact coordinates, map snapshots, start/end
    /// areas and timestamps never cross the share sheet by default.
    static func summary(for drive: SavedDrive) -> String {
        String(format: String(localized: "savedDrives.shareSummary"),
               String(localized: "app.name"),
               DriveFormatters.formatDistance(drive.distanceMeters),
               DriveFormatters.formatDuration(drive.durationSeconds))
    }
}
