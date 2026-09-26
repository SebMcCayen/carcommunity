import Foundation
import Observation

enum DriveHistoryUiState: Equatable, Sendable {
    case unavailable
    case loading
    case failed(code: KccFunctionsErrorCode?)
    case loaded
}

enum DriveStatsUiState: Equatable, Sendable {
    case idle
    case loading
    case failed
    case loaded(DriveStatsSnapshot)
}

enum DriveRouteUiState: Equatable, Sendable {
    case idle
    case loading
    case unavailable
    case ready([DriveRoutePoint])
}

@MainActor
@Observable
final class DriveHistoryCoordinator {
    static let pageSize = 25
    private let repository: DriveHistoryRepository?
    @ObservationIgnored private var nextCursorRideId: String?
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var attemptedImages: Set<String> = []

    private(set) var state: DriveHistoryUiState
    private(set) var drives: [SavedDrive] = []
    private(set) var tier: DriveSubscriptionTier = .unknown
    private(set) var hiddenDriveCount = 0
    private(set) var hasMore = false
    private(set) var loadingMore = false
    private(set) var loadMoreFailed = false
    private(set) var deletingRideId: String?
    private(set) var deleteFailed = false
    private(set) var imageURLs: [String: URL] = [:]
    private(set) var statsState: DriveStatsUiState = .idle
    private(set) var routeState: DriveRouteUiState = .idle
    var filters = DriveFilterCriteria()

    init(repository: DriveHistoryRepository?) {
        self.repository = repository
        state = repository == nil ? .unavailable : .loading
    }

    var visibleDrives: [SavedDrive] { DriveFilters.apply(drives, criteria: filters) }

    func load() async {
        guard let repository else { return }
        generation += 1
        let request = generation
        state = .loading
        nextCursorRideId = nil
        do {
            let page = try await repository.listHistory(cursorRideId: nil, pageSize: Self.pageSize)
            guard request == generation else { return }
            apply(page, replacing: true)
        } catch {
            guard request == generation, !Task.isCancelled else { return }
            state = .failed(code: (error as? DriveHistoryError).flatMap(Self.code))
        }
    }

    func loadMore() async {
        guard let repository, hasMore, !loadingMore, let cursor = nextCursorRideId else { return }
        let request = generation
        loadingMore = true
        loadMoreFailed = false
        defer { if request == generation { loadingMore = false } }
        do {
            let page = try await repository.listHistory(cursorRideId: cursor, pageSize: Self.pageSize)
            guard request == generation else { return }
            apply(page, replacing: false)
        } catch {
            guard request == generation, !Task.isCancelled else { return }
            loadMoreFailed = true
        }
    }

    func delete(_ drive: SavedDrive) async {
        guard let repository, deletingRideId == nil else { return }
        deletingRideId = drive.id
        deleteFailed = false
        defer { deletingRideId = nil }
        do {
            try await repository.deleteDrive(rideId: drive.id)
            await load()
            await loadStats()
        } catch {
            guard !Task.isCancelled else { return }
            deleteFailed = true
        }
    }

    func loadStats(calendar: Calendar = .current, now: Date = .now) async {
        guard let repository,
              let month = calendar.dateInterval(of: .month, for: now) else { return }
        statsState = .loading
        do {
            statsState = .loaded(try await repository.fetchStats(
                monthStart: month.start, monthEnd: month.end
            ))
        } catch {
            guard !Task.isCancelled else { return }
            statsState = .failed
        }
    }

    func loadRoute(for drive: SavedDrive) async {
        guard let repository else { routeState = .unavailable; return }
        routeState = .loading
        switch await repository.loadRoute(rideId: drive.id) {
        case .unavailable: routeState = .unavailable
        case .ready(let points): routeState = points.isEmpty ? .unavailable : .ready(points)
        }
    }

    func clearRoute() { routeState = .idle }

    private func apply(_ page: DriveHistoryPage, replacing: Bool) {
        tier = page.tier
        hiddenDriveCount = replacing ? page.hiddenDriveCount : hiddenDriveCount
        nextCursorRideId = page.nextCursorRideId
        hasMore = page.hasMore
        if replacing {
            drives = page.drives
        } else {
            var seen = Set(drives.map(\.id))
            drives += page.drives.filter { seen.insert($0.id).inserted }
        }
        state = .loaded
        resolveImages()
    }

    private func resolveImages() {
        guard let repository else { return }
        for path in drives.compactMap(\.carImagePath) where attemptedImages.insert(path).inserted {
            Task { [weak self] in
                guard let url = await repository.imageDownloadURL(for: path), let self else { return }
                self.imageURLs[path] = url
            }
        }
    }

    private static func code(_ error: DriveHistoryError) -> KccFunctionsErrorCode? {
        if case .callable(let code) = error { return code }
        return nil
    }
}
