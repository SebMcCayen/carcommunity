import Foundation
import Observation

/// Owns bounded nearby discovery plus the authorized per-uid live streams.
/// Blocked users are removed by `live-listNearby` in both directions; hidden,
/// stopped, suspended or newly denied users disappear through their per-uid
/// RTDB value/rules. No collection-level RTDB read exists in this type.
@MainActor
@Observable
final class NearbyLiveCoordinator {
    private static let retryDelay: Duration = .seconds(1)
    static let discoveryInterval: Duration = .seconds(20)

    private(set) var orderedUids: [String] = []
    private(set) var positions: [String: LiveMarker] = [:]
    private(set) var imageURLs: [String: URL] = [:]
    private(set) var lastRefreshFailed = false

    @ObservationIgnored private var repository: LiveLocationRepository?
    @ObservationIgnored private var currentUid: String?
    @ObservationIgnored private var excludedUids = Set<String>()
    @ObservationIgnored private var subscriptionKey = ""
    @ObservationIgnored private var generation = 0
    @ObservationIgnored nonisolated(unsafe) private var markerTasks: [Task<Void, Never>] = []
    @ObservationIgnored private var imageTasks: [String: Task<Void, Never>] = [:]
    @ObservationIgnored private var imageAttempts = Set<String>()
    @ObservationIgnored private let monotonicNow: () -> ContinuousClock.Instant
    @ObservationIgnored private var lastDiscoveryStartedAt: ContinuousClock.Instant?

    init(monotonicNow: @escaping () -> ContinuousClock.Instant = { .now }) {
        self.monotonicNow = monotonicNow
    }

    func activate(
        repository: LiveLocationRepository?,
        currentUid: String?,
        excludedUids: Set<String>
    ) {
        let previouslyAuthorizedRoster = orderedUids
        let crossesAuthorizationBoundary = self.currentUid != currentUid
            || !Self.sameRepository(self.repository, repository)
        if crossesAuthorizationBoundary {
            resetAuthorizationBoundary()
        }
        self.repository = repository
        self.currentUid = currentUid
        self.excludedUids = excludedUids
        guard repository != nil, currentUid != nil else { deactivate() ; return }
        // Re-apply exclusions immediately. Discovery refresh will reconcile the
        // listener roster; convoy members must never appear in both layers.
        reconcileSubscriptions(with: previouslyAuthorizedRoster)
    }

    /// A user or repository identity change invalidates every value obtained
    /// through the previous authorization context, even when discovery later
    /// returns the same uid roster. Clear before installing the new identity
    /// so cancelled streams cannot leave old markers visible during refresh.
    private func resetAuthorizationBoundary() {
        generation += 1
        cancelTasks()
        subscriptionKey = ""
        orderedUids = []
        positions = [:]
        imageURLs = [:]
        imageAttempts = []
        lastRefreshFailed = false
        lastDiscoveryStartedAt = nil
    }

    private static func sameRepository(
        _ lhs: LiveLocationRepository?,
        _ rhs: LiveLocationRepository?
    ) -> Bool {
        switch (lhs, rhs) {
        case (nil, nil): return true
        case let (lhs?, rhs?): return lhs === rhs
        default: return false
        }
    }

    func deactivate() {
        generation += 1
        cancelTasks()
        repository = nil
        currentUid = nil
        excludedUids = []
        subscriptionKey = ""
        orderedUids = []
        positions = [:]
        imageURLs = [:]
        imageAttempts = []
        lastRefreshFailed = false
    }

    /// A failed poll deliberately retains the last good roster. Cancellation
    /// simply abandons the obsolete response; it is not reported as a failure.
    /// Starts discovery only when the monotonic cadence permits it. Keeping
    /// this gate in the coordinator means SwiftUI task cancellation/recreation
    /// cannot cause camera or lifecycle changes to issue back-to-back calls.
    @discardableResult
    func poll(
        center: MapPoint,
        radiusMeters: Double,
        minimumInterval: Duration = NearbyLiveCoordinator.discoveryInterval
    ) async -> Bool {
        guard let repository else { return false }
        let startedAt = monotonicNow()
        if let lastDiscoveryStartedAt,
           startedAt - lastDiscoveryStartedAt < minimumInterval {
            return false
        }
        lastDiscoveryStartedAt = startedAt
        let requestedGeneration = generation
        do {
            let fetched = try await repository.listNearby(
                center: center,
                radiusMeters: NearbyLiveRadius.clamp(radiusMeters)
            )
            guard !Task.isCancelled, generation == requestedGeneration,
                  self.repository === repository else { return true }
            lastRefreshFailed = false
            apply(fetched)
        } catch is CancellationError {
            return true
        } catch {
            guard generation == requestedGeneration else { return true }
            lastRefreshFailed = true
        }
        return true
    }

    func visibleMarkers(at now: Date = Date()) -> [LiveMarker] {
        NearbyLivePresentation.markers(
            orderedUids: orderedUids,
            positions: positions,
            currentUid: currentUid,
            excludedUids: excludedUids,
            now: now
        )
    }

    private func apply(_ sessions: [NearbyLiveSession]) {
        var seen = Set<String>()
        let allowed = sessions.compactMap { session -> NearbyLiveSession? in
            guard seen.insert(session.uid).inserted,
                  session.uid != currentUid,
                  !excludedUids.contains(session.uid)
            else { return nil }
            return session
        }
        // Discovery coordinates only select a bounded listener roster. They
        // are never renderable: only a confirmed, non-nil authorized RTDB
        // value may enter `positions`.
        reconcileSubscriptions(with: Array(allowed.prefix(maximumNearbyLiveMarkers)).map(\.uid))
    }

    private func reconcileSubscriptions(with proposedUids: [String]) {
        let filtered = proposedUids.filter {
            !$0.isEmpty && $0 != currentUid && !excludedUids.contains($0)
        }
        let newKey = filtered.joined(separator: "|")
        if newKey == subscriptionKey {
            orderedUids = filtered
            positions = positions.filter { filtered.contains($0.key) }
            return
        }
        generation += 1
        cancelTasks()
        subscriptionKey = newKey
        orderedUids = filtered
        // A new authorization roster creates new reads. Do not carry a value
        // across that boundary: a replacement stream may be delayed, denied,
        // or never produce its first snapshot.
        positions = [:]
        imageURLs = [:]
        imageAttempts = []
        guard let repository else { return }
        let expectedKey = newKey
        for uid in filtered {
            markerTasks.append(Task { [weak self, repository] in
                await self?.observe(uid: uid, repository: repository, expectedKey: expectedKey)
            })
        }
    }

    private func observe(
        uid: String,
        repository: LiveLocationRepository,
        expectedKey: String
    ) async {
        while !Task.isCancelled, subscriptionKey == expectedKey {
            var retry = false
            for await event in repository.latestUpdateEvents(uid: uid) {
                guard !Task.isCancelled, subscriptionKey == expectedKey else { return }
                switch event {
                case .retry:
                    // A failed/denied read cannot keep its last coordinate on
                    // screen while authorization is being re-established.
                    positions.removeValue(forKey: uid)
                    retry = true
                case .value(let marker):
                    guard let marker else {
                        positions.removeValue(forKey: uid)
                        continue
                    }
                    positions[uid] = marker
                    if let path = marker.imagePath, !imageAttempts.contains(path) {
                        resolveImage(path, repository: repository, expectedKey: expectedKey)
                    }
                }
            }
            guard retry, !Task.isCancelled, subscriptionKey == expectedKey else { return }
            do { try await Task.sleep(for: Self.retryDelay) } catch { return }
        }
    }

    private func resolveImage(
        _ path: String,
        repository: LiveLocationRepository,
        expectedKey: String
    ) {
        imageAttempts.insert(path)
        imageTasks[path] = Task { [weak self, repository] in
            let url = await repository.imageDownloadURL(for: path)
            guard let self else { return }
            defer { self.imageTasks.removeValue(forKey: path) }
            guard !Task.isCancelled, self.subscriptionKey == expectedKey, let url else { return }
            self.imageURLs[path] = url
        }
    }

    private func cancelTasks() {
        markerTasks.forEach { $0.cancel() }
        markerTasks.removeAll()
        imageTasks.values.forEach { $0.cancel() }
        imageTasks.removeAll()
    }

    deinit {
        markerTasks.forEach { $0.cancel() }
        imageTasks.values.forEach { $0.cancel() }
    }
}

enum NearbyLiveRadius {
    static let minimum = 100.0
    static let maximum = 50_000.0

    static func clamp(_ value: Double) -> Double {
        guard value.isFinite else { return defaultNearbyLiveRadiusMeters }
        return min(max(value, minimum), maximum)
    }
}
