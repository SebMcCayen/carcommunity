import SwiftUI

struct DrivesPanel: View {
    @State private var coordinator: DriveHistoryCoordinator
    @State private var selectedDrive: SavedDrive?
    @State private var showingStats = false
    @State private var showingFilters = false
    @State private var pendingDelete: SavedDrive?

    init() {
        self.init(coordinator: DriveHistoryCoordinator(
            repository: FirebaseDriveHistoryRepository.createIfAvailable()
        ))
    }

    init(coordinator: DriveHistoryCoordinator) { _coordinator = State(initialValue: coordinator) }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: KccSpacing.s4) {
                HStack {
                    Text("savedDrives.screenTitle")
                        .font(.system(size: KccTypeScale.headingLg, weight: KccTypeScale.semibold))
                    Spacer()
                    Button { showingStats = true } label: {
                        Label("savedDrives.statsEntryAction", systemImage: "chart.bar")
                    }
                    .labelStyle(.iconOnly)
                    .frame(minWidth: 44, minHeight: 44)
                }
                historyContent
            }
            .padding(KccSpacing.s6)
        }
        .task { await coordinator.load() }
        .sheet(item: $selectedDrive) { drive in
            SavedDriveDetailScreen(coordinator: coordinator, drive: drive) {
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
        case .unavailable: Text("savedDrives.error").foregroundStyle(.secondary)
        case .failed:
            Text("savedDrives.error").foregroundStyle(KccPalette.errorRed)
            Button("savedDrives.retry") { Task { await coordinator.load() } }
                .buttonStyle(.bordered).frame(maxWidth: .infinity, minHeight: 44)
        case .loaded: loadedHistory
        }
    }

    @ViewBuilder private var loadedHistory: some View {
        if coordinator.hiddenDriveCount > 0 {
            Text(String(format: String(localized: "savedDrives.tierRestrictedBanner"), coordinator.hiddenDriveCount))
                .font(.system(size: KccTypeScale.bodySm)).foregroundStyle(.secondary)
        }
        Button { showingFilters.toggle() } label: {
            HStack {
                Label(showingFilters ? "savedDrives.filterToggleCollapse" : "savedDrives.filterToggleExpand",
                      systemImage: "line.3.horizontal.decrease.circle")
                Spacer()
                if coordinator.filters.activeFilterCount > 0 {
                    Text("\(coordinator.filters.activeFilterCount)").font(.caption.bold())
                        .padding(6).background(.tint, in: Circle()).foregroundStyle(.white)
                }
            }.frame(minHeight: 44)
        }.buttonStyle(.plain)
        if showingFilters { filters }

        if coordinator.drives.isEmpty {
            Text("savedDrives.empty").foregroundStyle(.secondary)
        } else if coordinator.visibleDrives.isEmpty {
            Text("savedDrives.filterNoMatches").foregroundStyle(.secondary)
            Button("savedDrives.filterNoMatchesAction") { coordinator.filters = .init() }
        } else {
            ForEach(coordinator.visibleDrives) { drive in
                Button { selectedDrive = drive } label: {
                    DriveHistoryCard(drive: drive,
                        carImageURL: drive.carImagePath.flatMap { coordinator.imageURLs[$0] })
                }
                .buttonStyle(.plain)
                .contextMenu {
                    ShareLink(item: DriveShareText.summary(for: drive)) {
                        Label("savedDrives.shareAction", systemImage: "square.and.arrow.up")
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

    private var filters: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            TextField("savedDrives.filterSearchLabel", text: $coordinator.filters.query)
                .textFieldStyle(.roundedBorder)
            Picker("savedDrives.filterPeriod", selection: $coordinator.filters.dateRange) {
                Text("savedDrives.filterAll").tag(DriveDateRange.all)
                Text("savedDrives.filterThisWeek").tag(DriveDateRange.thisWeek)
                Text("savedDrives.filterThisMonth").tag(DriveDateRange.thisMonth)
            }
            Picker("savedDrives.filterDistance", selection: $coordinator.filters.distanceBand) {
                Text("savedDrives.filterAll").tag(DriveDistanceBand.all)
                Text("savedDrives.filterUnder10").tag(DriveDistanceBand.under10)
                Text("savedDrives.filter10to50").tag(DriveDistanceBand.from10To50)
                Text("savedDrives.filterOver50").tag(DriveDistanceBand.over50)
            }
            Picker("savedDrives.filterSort", selection: $coordinator.filters.sort) {
                Text("savedDrives.sortNewest").tag(DriveSort.newest)
                Text("savedDrives.sortLongest").tag(DriveSort.longest)
                Text("savedDrives.sortFastest").tag(DriveSort.fastestAverage)
            }
            if coordinator.filters.activeFilterCount > 0 {
                Button("savedDrives.filterClear") { coordinator.filters = .init() }
            }
        }.pickerStyle(.menu)
    }

    @ViewBuilder private var pagingControls: some View {
        if coordinator.loadingMore { ProgressView("savedDrives.loadingMore") }
        else if coordinator.loadMoreFailed {
            Text("savedDrives.loadMoreError").foregroundStyle(KccPalette.errorRed)
            Button("savedDrives.retryAction") { Task { await coordinator.loadMore() } }
        } else if coordinator.hasMore {
            Button("savedDrives.loadMore") { Task { await coordinator.loadMore() } }
                .frame(maxWidth: .infinity, minHeight: 44)
        }
    }
}

struct DriveHistoryCard: View {
    let drive: SavedDrive
    let carImageURL: URL?
    var body: some View {
        HStack(spacing: KccSpacing.s3) {
            DriveRouteThumbnailView(encoded: drive.routeThumbnail).frame(width: 72, height: 56)
            VStack(alignment: .leading, spacing: KccSpacing.s1) {
                Text(DriveDisplay.title(for: drive))
                    .font(.system(size: KccTypeScale.titleMd, weight: KccTypeScale.medium))
                Text(DriveDisplay.statsLine(for: drive))
                    .font(.system(size: KccTypeScale.bodySm)).foregroundStyle(.secondary)
                if !drive.convoyMembers.isEmpty {
                    Label(DriveDisplay.convoyLine(for: drive), systemImage: "person.2")
                        .font(.system(size: KccTypeScale.bodySm)).foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 0)
            if drive.carImagePath != nil {
                AsyncImage(url: carImageURL) { image in image.resizable().scaledToFill() } placeholder: {
                    Image(systemName: "car").foregroundStyle(.secondary)
                }.frame(width: 44, height: 44).clipShape(Circle()).accessibilityHidden(true)
            }
            Image(systemName: "chevron.right").foregroundStyle(.secondary)
        }
        .padding(KccSpacing.s4).frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
        .background(Color(.secondarySystemBackground)).clipShape(RoundedRectangle(cornerRadius: KccRadius.md))
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
            context.stroke(path, with: .color(.accentColor), style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
        }
        .background(Color(.tertiarySystemBackground), in: RoundedRectangle(cornerRadius: KccRadius.sm))
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
